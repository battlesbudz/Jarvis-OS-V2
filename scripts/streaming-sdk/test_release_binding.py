import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from bind_release_receipt import quality_receipt, QUALITY_FILES, bind, ROOT
from package_android_aar import sha256
from producer_identity import workflow_identity


class QualityEvidenceBinding(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)/'jarvis-streaming-quality-evidence-123-1';self.root.mkdir()
        self.identity={'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'1','GITHUB_SHA':'a'*40,'GITHUB_REPOSITORY':'owner/repo'}
        self.patch='b'*64
        snapshot={'files':{'source.cc':'c'*64}}
        digest=hashlib.sha256(json.dumps(snapshot,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        summary={'passed':True,'classification':'passed','stage':'finished','reviewed_patch_sha256':self.patch,
            'source_snapshot_sha256':digest,'android_full_model_proven':False,'jni_full_model_proven':False}
        self.data={name:{} for name in QUALITY_FILES}
        self.data.update({'build/source-snapshot.json':snapshot,'run/summary.json':summary,
            'build/build-status.json':{'build_succeeded':True,'compilation_exited_before_quality':True,
                'reviewed_patch_sha256':self.patch,'source_snapshot_sha256':digest},
            'run/comparison.json':{'passed':True,'native_pair_passed':True,'documentation_example_match':True}})
        for lane in ('raw','projected_null'):self.data[f'run/{lane}/process.json']={'classification':'passed','status':'completed','exit_code':0}
        self.write()

    def write(self):
        files={}
        for name,value in self.data.items():
            p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(value))
            files[name]={'bytes':p.stat().st_size,'sha256':sha256(p)}
        self.index={'summary':self.data['run/summary.json'],'files':files,'audio_weights_activations_uploaded':False,
            'ci':{k:self.identity[k] for k in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','GITHUB_SHA')}}
        (self.root/'EVIDENCE-INDEX.json').write_text(json.dumps(self.index))

    def test_exact_successful_receipt_passes(self):
        self.assertTrue(quality_receipt(self.root,self.identity,self.patch)['passed'])

    def test_missing_receipt_fails(self):
        del self.data['run/frontend.json'];self.write()
        with self.assertRaisesRegex(ValueError,'Missing'):quality_receipt(self.root,self.identity,self.patch)

    def test_changed_receipt_bytes_fail(self):
        (self.root/'run/frontend.json').write_text('{"tampered":true}')
        with self.assertRaisesRegex(ValueError,'bytes changed'):quality_receipt(self.root,self.identity,self.patch)

    def test_resource_failure_never_counts_as_pass(self):
        for mode in ['resource_constrained','not_run','model_failure']:
            self.data['run/summary.json']['classification']=mode;self.write()
            with self.subTest(mode=mode),self.assertRaisesRegex(ValueError,'did not explicitly pass'):
                quality_receipt(self.root,self.identity,self.patch)

    def test_failed_native_lane_or_semantic_reference_rejected(self):
        self.data['run/raw/process.json']['exit_code']=1;self.write()
        with self.assertRaisesRegex(ValueError,'inference did not complete'):quality_receipt(self.root,self.identity,self.patch)
        self.data['run/raw/process.json']['exit_code']=0;self.data['run/comparison.json']['documentation_example_match']=False;self.write()
        with self.assertRaisesRegex(ValueError,'semantic reference'):quality_receipt(self.root,self.identity,self.patch)

    def test_foreign_run_or_unsupported_android_claim_rejected(self):
        self.identity['GITHUB_RUN_ID']='456'
        with self.assertRaisesRegex(ValueError,'run/attempt'):quality_receipt(self.root,self.identity,self.patch)
        self.identity['GITHUB_RUN_ID']='123';self.data['run/summary.json']['android_full_model_proven']=True;self.write()
        with self.assertRaisesRegex(ValueError,'did not explicitly pass'):quality_receipt(self.root,self.identity,self.patch)

    def test_snapshot_drift_rejected(self):
        self.data['build/source-snapshot.json']['extra']='changed';self.write()
        with self.assertRaisesRegex(ValueError,'snapshot changed'):quality_receipt(self.root,self.identity,self.patch)


    def test_both_apks_must_embed_exact_producer_provenance(self):
        inputs=self.root/'inputs';out=self.root/'out';out.mkdir()
        consumer=inputs/'jarvis-streaming-sdk-consumer';consumer.mkdir(parents=True)
        manifest=ROOT/'third_party/litert-lm-0.16.0/reviewed-source.json'
        reviewed=json.loads(manifest.read_text());self.patch=reviewed['patch_sha256']
        self.data['run/summary.json']['reviewed_patch_sha256']=self.patch
        self.data['build/build-status.json']['reviewed_patch_sha256']=self.patch
        self.write()
        p={'aar_sha256':'d'*64,'source':{'workflow_identity':self.identity,
            'reviewed_patch_sha256':self.patch,'reviewed_source_sha256':sha256(manifest)}}
        provenance=consumer/'sdk.provenance.json';provenance.write_text(json.dumps(p))
        (consumer/'streaming-sdk-manifest.json').write_text('{}')
        apks=inputs/'jarvis-os-v2-release-apk';apks.mkdir()
        for name in ('app-release.apk','app-compact.apk'):
            with zipfile.ZipFile(apks/name,'w') as z:
                z.writestr('assets/litert-lm-source-provenance.json',json.dumps({'source':p['source']}))
        consumer=dict(self.identity,GITHUB_RUN_ATTEMPT='2')
        result=bind(inputs,out,self.root,'d'*64,sha256(provenance),workflow_identity(consumer,'1'))
        self.assertTrue(result['passed'])
        self.assertEqual('1',result['producer_attempt'])
        self.assertEqual('jarvis-streaming-quality-evidence-123-1',result['quality_artifact_name'])
        self.assertTrue((out/'streaming-quality/EVIDENCE-INDEX.json').is_file())
        with zipfile.ZipFile(apks/'app-compact.apk','w') as z:
            z.writestr('assets/litert-lm-source-provenance.json','{}')
        with self.assertRaisesRegex(ValueError,'APK does not embed'):
            bind(inputs,out,self.root,'d'*64,sha256(provenance),self.identity)

    def test_gradle_and_workflow_remain_fail_closed(self):
        gradle=(ROOT/'app/build.gradle.kts').read_text()
        self.assertNotIn('implementation("com.google.ai.edge.litertlm:litertlm-android',gradle)
        self.assertIn('throw GradleException',gradle)
        self.assertIn('ndkVersion = "27.2.12479018"',gradle)
        for dependency in ['gson:2.13.2','kotlin-reflect:2.3.21','kotlinx-coroutines-android:1.9.0']:
            self.assertIn(dependency,gradle)
        workflow=(ROOT/'.github/workflows/android.yml').read_text()
        producer=workflow.split('  build-streaming-sdk:',1)[1].split('  build-release:',1)[0]
        self.assertNotIn('continue-on-error',producer)
        self.assertIn('< /dev/null',producer)
        release=workflow.split('  build-release:',1)[1].split('  prepare-upgrade-baseline:',1)[0]
        self.assertEqual(2,release.count('--require-digests --check-workflow --producer-attempt'))
        self.assertEqual(2,release.count('-PlitertLmBridgeSha256="$STREAMING_AAR_SHA256"'))
        self.assertIn('PRODUCER_ARTIFACT_ID',release)
        self.assertIn('scripts/streaming-sdk/producer_identity.py',release)


if __name__=='__main__':unittest.main()
