#!/usr/bin/env python3
"""Fresh official inputs, bounded native prerequisites, then two fresh lanes."""
import argparse
import base64
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import unicodedata
from common import *
from bounded_exec import BUDGET, run
from build_probes import PACKAGE, TARGETS
from compare_native_pair import compare
from prepare_inputs import derive_pcm, extract_sections, encoder_cases, compare_encoder

REFERENCE = {'text': 'Roses are red, violets are blue.',
    'source_url': 'https://ai.google.dev/gemma/docs/capabilities/audio',
    'audio_url': WAV['url'], 'audio_sha256': WAV['sha256'],
    'type': 'Official documentation example output for this exact linked audio; not a new human listening annotation.'}


RESOURCE_PATTERN = r'\b(?:ENOMEM|std::bad_alloc|RESOURCE_EXHAUSTED)\b|(?:mmap|mapping|allocate|allocation).{0,100}(?:Cannot allocate memory|out of memory)'

def classify_process(process, result=None, diagnostic=''):
    """Only explicit resource evidence earns the resource classification."""
    if process.get('status') == 'resource_blocked': return 'resource_constrained'
    if process.get('stop_reason') in {'wall_timeout','rss_watchdog_limit','system_memory_reserve'}:
        return 'resource_constrained'
    if process.get('exit_code') == -signal.SIGXCPU: return 'resource_constrained'
    error = str((result or {}).get('error', ''))+'\n'+diagnostic
    if re.search(RESOURCE_PATTERN, error, re.I):
        return 'resource_constrained'
    if any(message in error for message in (
            'Native audio-embedding callback differs from pinned reference',
            'Full SDK Mel bytes differ', 'Decoded PCM differs bitwise')):
        return 'numerical_failure'
    if process.get('status') != 'completed': return 'native_execution_failure' 
    if result is not None and result.get('execution_passed') is not True: return 'native_execution_failure'
    return 'passed'


def checked_process(command, directory, results=False):
    process = run(command, directory)
    result = load(directory/'result.json') if results and (directory/'result.json').is_file() else None
    diagnostic = (directory/'stderr.log').read_text(errors='replace') if (directory/'stderr.log').exists() else ''
    kind = classify_process(process, result, diagnostic)
    # No stderr dump is exported; keep the exact error string if native code
    # produced a structured result, plus process metrics and bounded reason.
    process['classification'] = kind
    if diagnostic:
        process['stderr_sha256'] = sha(directory/'stderr.log')
        # Retain only the exact bounded allocation evidence, never a log dump.
        matched = re.search(RESOURCE_PATTERN, str((result or {}).get('error', ''))+'\n'+diagnostic, re.I)
        if matched: process['explicit_resource_diagnostic'] = matched.group(0)
    write(directory/'process.json', process)
    need(kind == 'passed', f'{directory.name}: {kind}', kind)
    if results: need(result is not None, 'Native success omitted its result receipt', 'evidence_failure')
    return process, result


def verify_build(build_dir):
    receipt = load(build_dir/'build-status.json')
    need(receipt.get('build_succeeded') is True and receipt.get('compilation_exited_before_quality') is True,
         'Host compilation has not completed successfully')
    need(receipt['recipe_manifest_sha256'] == verify_recipe_sources(), 'Hosted recipe changed after compile')
    sdk = Path(receipt['sdk'])
    need(digest(sdk_snapshot(sdk)) == receipt['source_snapshot_sha256'], 'SDK source changed after host compilation')
    for target in TARGETS: verify(sdk/'bazel-bin'/PACKAGE/target, receipt['binaries'][target])
    for path, pin in receipt['host_prebuilts'].items(): verify(sdk/path, pin)
    for path, pin in receipt.get('dynamic_libraries', {}).items(): verify(Path(path), pin)
    return receipt, sdk


def lane_request(mode, work, build, identity):
    projected = (work/'inputs/projected.f32le').read_bytes()
    audio = {'type': 'audio', 'blob': base64.b64encode(projected if mode == 'projected_null' else
                                                   (work/'inputs/matched.wav').read_bytes()).decode()}
    if mode == 'projected_null':
        audio['projected_audio'] = dict(schema_version=1, dtype='float32_le', projection='audio_adapter',
            end_marker='runtime', complete=True, pcm_samples=49221, token_count=77, embedding_width=1536,
            seal_token='public-complete-float-pcm-49221-v1', producer_sha256=PRODUCER)
    return dict(mode=mode, case='transcribe', sdk_commit=SDK_PIN, litert_workspace_pin=LITERT_PIN,
        bundle_sha256=BUNDLE['sha256'], producer_sha256=PRODUCER,
        model_path=str(work/'inputs/full.litertlm'), projected_tokens_path=str(work/'inputs/projected.f32le'),
        wav_path=str(work/'inputs/matched.wav'), manifest_sha256=digest(identity), pcm_sha256=PCM_SHA,
        projected_tokens_sha256=ROWS_SHA, native_binary_sha256=build['binaries']['native_conversation_quality_probe']['sha256'],
        native_source_snapshot_sha256=build['source_snapshot_sha256'], full_bundle_hash_reverified_before_launch=True,
        context_tokens=512, max_output_tokens=64, audio_embedding_tap=True,
        message={'role': 'user', 'content': [{'type': 'text', 'text': 'Transcribe the spoken words in this audio. Return only the transcription.'}, audio]})


def normalize(text):
    return ' '.join(''.join(' ' if unicodedata.category(c).startswith('P') else c
                            for c in text.casefold()).split())


def final_comparison(raw, projected):
    pair = compare(raw, projected)
    pair['semantic_reference'] = REFERENCE
    pair['documentation_example_match'] = all(normalize(r.get('text', '')) == normalize(REFERENCE['text'])
                                              for r in (raw, projected))
    pair['semantic_quality'] = 'single_public_documentation_example_match_only; not independently human-annotated'
    pair['passed'] = pair['native_pair_passed'] and pair['documentation_example_match']
    pair['classification'] = ('passed' if pair['passed'] else 'numerical_or_output_parity_failure'
                              if not pair['native_pair_passed'] else 'semantic_reference_mismatch')
    return pair


def run_gate(a):
    work = a.out.resolve()
    need(not work.exists(), 'Use a fresh quality run directory; stale receipts are forbidden')
    work.mkdir(parents=True)
    summary = {'schema_version': 1, 'classification': 'pending', 'passed': False,
               'android_full_model_proven': False, 'jni_full_model_proven': False,
               'budget': BUDGET, 'stage': 'verify_build', 'lanes': {},
               'scope': 'Hosted native C++ Conversation quality prerequisite only; no Android/JNI full-model quality or device latency claim.'}
    write(work/'summary.json', summary)
    start = time.monotonic()
    try:
        build, sdk = verify_build(a.build_dir)
        need(os.environ.get('GEMMA_QUALITY_COMPUTE_SLOT') == 'confirmed_by_owner', 'Post-compilation compute slot required')
        inputs = work/'inputs'; inputs.mkdir()
        summary['source_snapshot_sha256'] = build['source_snapshot_sha256']
        summary['reviewed_patch_sha256'] = build['reviewed_patch_sha256']
        summary['recipe_manifest_sha256'] = build['recipe_manifest_sha256']
        summary['stage'] = 'official_downloads'; write(work/'summary.json', summary)
        download(BUNDLE, inputs/'full.litertlm')
        download(WAV, inputs/'public.wav', seconds=90)
        identity = {'bundle': BUNDLE, 'public_wav': WAV, 'models': extract_sections(inputs/'full.litertlm', inputs),
                    'pcm_derivation': derive_pcm(inputs/'public.wav', inputs),
                    'source_snapshot_sha256': build['source_snapshot_sha256']}
        summary['stage'] = 'weightless_reassembly'; write(work/'summary.json', summary)
        java = Path(os.environ['JAVA_HOME'])/'bin/java'
        checked_process([java, '-Xms16m', '-Xmx128m', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1',
            '-XX:CompressedClassSpaceSize=32m', '-XX:MaxMetaspaceSize=128m', '-XX:ReservedCodeCacheSize=64m',
            '-Xss512k', HERE/'recipe/WeightlessEncoderRecipe.java', inputs/'full.litertlm',
            HERE/'recipe/source-copy-recipe.bin', HERE/'recipe/structural-literals.bin.gz', inputs/'stateful.tflite'], work/'reassembly')
        verify(inputs/'stateful.tflite', load(HERE/'model-structure.json')['stateful'])
        identity['models']['stateful'] = describe(inputs/'stateful.tflite')
        summary['stage'] = 'native_frontend'; write(work/'summary.json', summary)
        checked_process([sdk/'bazel-bin'/PACKAGE/'native_frontend_quality_probe', inputs/'matched.wav',
                         inputs/'pcm.f32le', inputs/'mel.f32le'], work/'frontend')
        frontend = json.loads((work/'frontend/stdout.log').read_text().splitlines()[-1])
        need(frontend.get('passed') is True and frontend.get('decode_pcm_bitwise') is True and
             frontend.get('encoded_and_pcm_mel_bitwise') is True, 'Native frontend parity failed', 'numerical_failure')
        need(sha(inputs/'mel.f32le') == MEL_SHA, 'Native full-SDK Mel hash mismatch', 'numerical_failure')
        frontend.update(mel_fixture_bitwise=True, pcm=describe(inputs/'pcm.f32le'),
                        mel=describe(inputs/'mel.f32le'), wav=describe(inputs/'matched.wav'),
                        binary=build['binaries']['native_frontend_quality_probe'])
        write(work/'frontend.json', frontend)
        stages = encoder_cases(inputs)
        summary['stage'] = 'native_encoder_oracle'; write(work/'summary.json', summary)
        (inputs/'actual').mkdir()
        for stage in ('stateful', 'static', 'adapter', 'eoa'):
            target = inputs/'actual'/stage; target.mkdir()
            checked_process([sdk/'bazel-bin'/PACKAGE/'pinned_encoder_probe', inputs/(stage+'.tflite'),
                             inputs/(stage+'.tsv'), inputs, target], work/('encoder-'+stage))
        oracle = compare_encoder(inputs, stages, work/'encoder-oracle.json')
        oracle['binary'] = build['binaries']['pinned_encoder_probe']
        write(work/'encoder-oracle.json', oracle)
        identity.update(pcm=describe(inputs/'pcm.f32le'), mel=describe(inputs/'mel.f32le'),
                        wav=describe(inputs/'matched.wav'), projected=describe(inputs/'projected.f32le'),
                        frontend_receipt_sha256=sha(work/'frontend.json'), oracle_receipt_sha256=sha(work/'encoder-oracle.json'))
        write(work/'input-identity.json', identity)
        # Each lane has a new OS process, engine, Conversation and lifecycle.
        # Neither receives history/state/output from the other lane.
        for mode in ('projected_null', 'raw'):
            summary['stage'] = mode; write(work/'summary.json', summary)
            verify_build(a.build_dir)
            verify(inputs/'full.litertlm', BUNDLE)  # Whole 2.59GB hash again, each lane.
            for name, file in [('pcm','pcm.f32le'), ('mel','mel.f32le'), ('wav','matched.wav'), ('projected','projected.f32le')]:
                verify(inputs/file, identity[name])
            request = work/(mode+'-request.json')
            write(request, lane_request(mode, work, build, identity))
            process, result = checked_process([sdk/'bazel-bin'/PACKAGE/'native_conversation_quality_probe',
                                               request, work/mode/'result.json'], work/mode, results=True)
            summary['lanes'][mode] = {'classification': 'passed', 'result_sha256': sha(work/mode/'result.json'),
                                     'process_sha256': sha(work/mode/'process.json')}
            write(work/'summary.json', summary)
        pair = final_comparison(load(work/'raw/result.json'), load(work/'projected_null/result.json'))
        write(work/'comparison.json', pair)
        summary.update(classification=pair['classification'], passed=pair['passed'], stage='finished')
        summary['semantic_reference_type'] = REFERENCE['type']
    except Exception as e:
        summary.update(classification=e.classification if isinstance(e, GateError) else 'orchestration_failure',
                       passed=False, error=str(e))
    summary['wall_seconds'] = time.monotonic()-start
    write(work/'summary.json', summary)
    return summary


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--build-dir', type=Path, required=True); p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    result = run_gate(a); print(json.dumps(result, indent=2))
    return 0 if result['passed'] else 3 if result['classification'] == 'resource_constrained' else 2
if __name__ == '__main__': sys.exit(main())
