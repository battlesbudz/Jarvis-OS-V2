#!/usr/bin/env python3
"""Add exact SDK/model-prerequisite provenance to an already checked release receipt."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import zipfile
from package_android_aar import sha256
from producer_identity import workflow_identity
from verify_packaged_samplers import verify as verify_packaged_samplers
from verify_packaged_encoder_assets import bind_report as bind_encoder_assets

ROOT = Path(__file__).resolve().parents[2]
QUALITY_FILES = {'build/build-status.json', 'build/source-snapshot.json', 'build/diagnostic-status.json',
    'run/summary.json', 'run/input-identity.json', 'run/frontend.json', 'run/encoder-oracle.json',
    'run/comparison.json', 'run/reassembly/process.json', 'run/frontend/process.json',
    *[f'run/encoder-{s}/process.json' for s in ('stateful','static','adapter','eoa')],
    *[f'run/{lane}/{name}.json' for lane in ('raw','projected_null') for name in ('process','result')]}


def load(path):
    return json.loads(path.read_text())


def consumer_evidence(consumer):
    """Accept only a flat bundle or the workflow's retained input subdirectory.

    The manifest stays at the artifact root in both layouts. Never search for a
    receipt independently of its provenance, or follow links outside the bundle.
    """
    def checked(path, directory=False):
        if (any(part.is_symlink() for part in (path, *path.parents))
                or path.absolute() != path.resolve()
                or not (path.is_dir() if directory else path.is_file())):
            raise ValueError('Missing or unsafe SDK consumer evidence path: ' + str(path))
        return path

    checked(consumer, directory=True)
    manifest = checked(consumer/'streaming-sdk-manifest.json')
    entries = {p.name: p for p in consumer.iterdir() if p != manifest}
    if 'streaming-sdk-input' in entries:
        if set(entries) != {'streaming-sdk-input'}:
            raise ValueError('Ambiguous SDK consumer evidence layout')
        folder = checked(entries['streaming-sdk-input'], directory=True)
        entries = {p.name: p for p in folder.iterdir()}
    provenance = [p for name, p in entries.items() if name.endswith('.provenance.json')]
    if (len(provenance) != 1 or set(entries) != {provenance[0].name, 'source-receipt.json'}
            or '\\' in provenance[0].name):
        raise ValueError('Missing, ambiguous or unsupported SDK consumer evidence layout')
    return checked(provenance[0]), checked(entries['source-receipt.json']), manifest


def quality_receipt(folder, identity, patch_sha, expected_source_receipt=None):
    index = load(folder/'EVIDENCE-INDEX.json')
    if index.get('ci') != {k:identity[k] for k in ('GITHUB_SHA','GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT')}:
        raise ValueError('Quality receipts do not belong to this run/attempt/source')
    if index.get('audio_weights_activations_uploaded') is not False:
        raise ValueError('Weightless quality export contract missing')
    files = index['files']
    if set(files) != QUALITY_FILES:
        raise ValueError('Missing or unexpected quality evidence')
    for relative, pin in files.items():
        p=folder/relative
        if p.is_symlink() or not p.is_file() or not p.resolve().is_relative_to(folder.resolve()):
            raise ValueError('Unsafe quality receipt path')
        if p.stat().st_size > 4*1024**2 or {'bytes':p.stat().st_size,'sha256':sha256(p)} != pin:
            raise ValueError('Quality receipt bytes changed: '+relative)
    summary=index['summary']
    if summary != load(folder/'run/summary.json') or any(summary.get(k)!=v for k,v in {
            'passed':True,'classification':'passed','stage':'finished','reviewed_patch_sha256':patch_sha,
            'android_full_model_proven':False,'jni_full_model_proven':False}.items()):
        raise ValueError('Actual full-model quality prerequisite did not explicitly pass')
    build=load(folder/'build/build-status.json')
    sys.path.insert(0, str(ROOT/'scripts/streaming-quality/encoder-replay'))
    from diagnostic_status import read as read_diagnostic_status
    diagnostic=read_diagnostic_status(folder/'build/diagnostic-status.json')
    expected_diagnostic={'repository':identity['GITHUB_REPOSITORY'],
        'run_id':int(identity['GITHUB_RUN_ID']), 'run_attempt':int(identity['GITHUB_RUN_ATTEMPT']),
        'source_commit':identity['GITHUB_SHA']}
    if (diagnostic.get('context') is None or any(diagnostic['context'].get(k)!=v
            for k,v in expected_diagnostic.items()) or diagnostic['state']!='complete'
            or diagnostic['cleanup']['verified'] is not True):
        raise ValueError('Diagnostic cleanup status is not complete for this exact quality producer')
    if expected_source_receipt is not None and build.get('android_source_receipt_sha256') != expected_source_receipt:
        raise ValueError('Quality evidence used a different SDK source receipt')
    if (build.get('build_succeeded') is not True or build.get('compilation_exited_before_quality') is not True
            or build.get('reviewed_patch_sha256') != patch_sha
            or build.get('source_snapshot_sha256') != summary.get('source_snapshot_sha256')):
        raise ValueError('Quality build/source proof disagrees with actual inference')
    snapshot=load(folder/'build/source-snapshot.json')
    digest=hashlib.sha256(json.dumps(snapshot,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    if digest != build['source_snapshot_sha256']:
        raise ValueError('Quality source snapshot changed')
    comparison=load(folder/'run/comparison.json')
    if any(comparison.get(k) is not True for k in ('passed','native_pair_passed','documentation_example_match')):
        raise ValueError('Raw/projected parity or documented semantic reference failed')
    for lane in ('raw','projected_null'):
        process=load(folder/f'run/{lane}/process.json')
        if any(process.get(k)!=v for k,v in {'classification':'passed','status':'completed','exit_code':0}.items()):
            raise ValueError('Native inference did not complete successfully: '+lane)
    return {'passed':True,'evidence_index_sha256':sha256(folder/'EVIDENCE-INDEX.json'),
            'summary':summary,'files':files}


def bind(inputs, out, quality, expected_aar, expected_provenance, identity,
         quality_identity, expected_source_receipt, current_identity):
    for value in (expected_aar,expected_provenance,expected_source_receipt):
        if not re.fullmatch('[0-9a-f]{64}',value or ''):
            raise ValueError('Exact SDK producer digests required')
    provenance_path, source_receipt, consumer_manifest = consumer_evidence(inputs/'jarvis-streaming-sdk-consumer')
    if sha256(provenance_path)!=expected_provenance:
        raise ValueError('Missing, ambiguous or changed SDK consumer provenance')
    provenance=load(provenance_path)
    if not isinstance(provenance,dict) or not isinstance(provenance.get('source'),dict):
        raise ValueError('SDK consumer provenance and source must be objects')
    source=provenance['source']
    if not source_receipt.is_file() or sha256(source_receipt)!=expected_source_receipt or load(source_receipt)!=source:
        raise ValueError('SDK source receipt does not match exact producer/AAR provenance')
    if any(identity.get(k)!=quality_identity.get(k) for k in ('GITHUB_RUN_ID','GITHUB_SHA','GITHUB_REPOSITORY')):
        raise ValueError('SDK and quality producers do not share the same run/source/repository')
    reviewed=load(ROOT/'third_party/litert-lm-0.16.0/reviewed-source.json')
    if (provenance.get('aar_sha256')!=expected_aar or source.get('workflow_identity')!=identity
            or source.get('reviewed_patch_sha256')!=reviewed['patch_sha256']
            or source.get('reviewed_source_sha256')!=sha256(ROOT/'third_party/litert-lm-0.16.0/reviewed-source.json')):
        raise ValueError('SDK provenance is not the exact reviewed producer result')
    packaged_samplers = []
    for name in ('app-release.apk','app-compact.apk'):
        packaged_samplers.append(verify_packaged_samplers(
            inputs/'jarvis-os-v2-release-apk'/name, provenance_path, expected_provenance))
    # The retained report travelled with these exact APK bytes. Independently
    # reopen both packages and recheck source pins, bounded gzip and notices.
    if any(current_identity.get(k) != identity.get(k)
           for k in ('GITHUB_RUN_ID', 'GITHUB_SHA', 'GITHUB_REPOSITORY')):
        raise ValueError('APK consumer and SDK do not share the same run/source/repository')
    packaged_encoder = bind_encoder_assets(inputs/'jarvis-os-v2-release-apk', current_identity)
    quality_name=f"jarvis-streaming-quality-evidence-{quality_identity['GITHUB_RUN_ID']}-{quality_identity['GITHUB_RUN_ATTEMPT']}"
    if quality.name!=quality_name:
        raise ValueError('Quality artifact directory differs from retained producer name')
    quality_result=quality_receipt(quality,quality_identity,reviewed['patch_sha256'],expected_source_receipt)
    retained=out/'streaming-sdk';retained.mkdir()
    shutil.copyfile(provenance_path,retained/'sdk.provenance.json')
    shutil.copyfile(source_receipt,retained/'source-receipt.json')
    shutil.copyfile(consumer_manifest,retained/'artifact-selection.json')
    destination=out/'streaming-quality';destination.mkdir()
    for relative in ['EVIDENCE-INDEX.json',*sorted(QUALITY_FILES)]:
        target=destination/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(quality/relative,target)
    return {'passed':True,'aar_sha256':expected_aar,'provenance_sha256':expected_provenance,
            'reviewed_patch_sha256':reviewed['patch_sha256'],'workflow_identity':identity,
            'quality_artifact_name':quality_name, 'producer_attempt':identity['GITHUB_RUN_ATTEMPT'],
            'quality_workflow_identity':quality_identity, 'quality_producer_attempt':quality_identity['GITHUB_RUN_ATTEMPT'],
            'source_receipt_sha256':expected_source_receipt,
            'packaged_sampler_identity': packaged_samplers,
            'packaged_encoder_assets': packaged_encoder,
            'host_native_quality':quality_result,
            'scope':'Same-run SDK package and host native full-model prerequisite; Android/JNI full-model and physical audio remain unverified.'}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('inputs','out','quality-dir'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--producer-attempt',required=True)
    p.add_argument('--quality-producer-attempt',required=True)
    p.add_argument('--expected-source-receipt-sha256',required=True)
    p.add_argument('--expected-aar-sha256',required=True);p.add_argument('--expected-provenance-sha256',required=True)
    a=p.parse_args();receipt_path=a.out/'receipt.json';receipt=load(receipt_path)
    try:
        identity=workflow_identity(os.environ,a.producer_attempt)
        quality_identity=workflow_identity(os.environ,a.quality_producer_attempt)
        if receipt.get('passed') is not True:raise ValueError('Existing release checks did not pass')
        receipt['streaming_sdk']=bind(a.inputs,a.out,a.quality_dir,a.expected_aar_sha256,a.expected_provenance_sha256,identity,
            quality_identity,a.expected_source_receipt_sha256,workflow_identity(os.environ))
        note='\n\nReviewed SDK provenance, exact packaged encoder assets and bounded host full-model quality prerequisite: PASS. Android/JNI full-model inference and physical audio remain unverified.\n'
    except (ValueError,OSError,KeyError,TypeError,zipfile.BadZipFile) as error:
        receipt['passed']=False;receipt.setdefault('errors',[]).append('Streaming SDK evidence: '+str(error))
        note='\n\nStreaming SDK evidence: FAIL: '+str(error)+'\n'
    receipt_path.write_text(json.dumps(receipt,indent=2)+'\n')
    summary=a.out/'summary.md'
    text=summary.read_text()
    if not receipt['passed']:text=text.replace('# Jarvis verification: PASS','# Jarvis verification: FAIL',1)
    summary.write_text(text+note)
    if os.getenv('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as f:f.write(note)
    return 0 if receipt['passed'] else 1


if __name__=='__main__':sys.exit(main())
