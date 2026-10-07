"""Synthetic data and injected process boundaries only; no models or native probes."""
from contextlib import ExitStack
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
from unittest.mock import patch

import common
from common import GateError, load, sha, write
import diagnostic_pair as diagnostic
import export_evidence
import prepare_inputs
import run_quality
from test_quality import result

SYNTHETIC_ROWS_SHA = hashlib.sha256(bytes(473088)).hexdigest()


def fixtures(inputs):
    inputs.mkdir(exist_ok=True)
    if not (inputs/'mel.f32le').exists():
        (inputs/'mel.f32le').write_bytes(b''.join(struct.pack('<f', i+1)*128 for i in range(307)))
    with patch.object(prepare_inputs, 'MEL_SHA', sha(inputs/'mel.f32le')):
        stages = prepare_inputs.encoder_cases(inputs)
    payloads = {}
    for stage, cases in stages.items():
        payloads[stage] = {}
        for case in cases:
            for spec in case['outputs']:
                label = spec['label']; value = bytes(spec['bytes'])
                if stage == 'stateful' and case['id'] != 'eoa':
                    i = int(case['id']); count = [12,12,12,12,12,12,5][i]
                    end = [12,24,36,48,60,72,77][i]
                    if label == 'token_count': value = struct.pack('<i', count)
                    elif label == 'token_mask': value = b'\1'*count+b'\0'*(12-count)
                    elif label == 'next_history_tokens': value = struct.pack('<i', min(end,24))
                    elif label == 'next_mel':
                        valid = 19 if i == 6 else 48
                        value = (inputs/f'chunk-{i}.bin').read_bytes()[(valid-4)*128*4:valid*128*4]
                    elif label.startswith('next_layer_'):
                        layer = int(label[-2:])
                        value = b''.join(struct.pack('<f', token+1+1000*layer)*1024
                                         for token in range(max(0,end-24),end))
                        value += bytes((24-min(end,24))*1024*4)
                elif stage == 'static' and label == 'mask': value = b'\1'*77+b'\0'*127
                payloads[stage][(case['id'],label)] = value
    return stages, payloads


def emit(inputs, stage, cases, payloads):
    target = inputs/'actual'/stage; target.mkdir(parents=True, exist_ok=True)
    lines = [f'PIN\t{common.LITERT_PIN}', 'CPU_THREADS\t1']
    for case in cases:
        # Runtime may reorder the complete output name set.
        for spec in reversed(case['outputs']):
            name = f'pinned-encoder-{case["id"]}.out.{spec["label"]}.bin'
            data = payloads[(case['id'],spec['label'])]
            (target/name).write_bytes(data)
            lines.append(f'OUTPUT\t{case["id"]}\t{spec["label"]}\t{len(data)}\t{name}')
        lines.append(f'CASE\t{case["id"]}\t{case["signature"]}\t0.1')
    lines.append(f'COMPLETE\t{len(cases)}')
    (target/'pinned-encoder-run.tsv').write_text('\n'.join(lines)+'\n')


def completed():
    return dict(classification='passed', status='completed', exit_code=0,
                execution_started=True, cleanup_verified=True)


class StateValidityTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.inputs = Path(self.tmp.name)
        self.stages, payloads = fixtures(self.inputs)
        for stage, cases in self.stages.items(): emit(self.inputs,stage,cases,payloads[stage])

    def test_all_outputs_and_transitions_pass_without_cache_equivalence_claim(self):
        checks = diagnostic.validate_state_outputs(self.inputs,self.stages)
        self.assertTrue(checks['all_emitted_outputs_valid'])
        self.assertTrue(checks['state_transition_invariants_valid'])
        self.assertFalse(checks['cache_state_reference_equivalence_proven'])
        self.assertEqual(checks['emitted_output_count'],131)

    def test_invalid_outputs_fail_before_any_model_boundary(self):
        cases = [('000','next_history_tokens',struct.pack('<i',24)),
                 ('006','next_mel',bytes(2048)),
                 ('001','next_layer_11',struct.pack('<f',9999)),
                 ('000','encoder_features',struct.pack('<f',float('nan'))),
                 ('006','next_layer_03',struct.pack('<f',float('inf'))),
                 ('000','soft_tokens',b'')]
        for case,label,prefix in cases:
            with self.subTest(case=case,label=label):
                path=self.inputs/'actual/stateful'/f'pinned-encoder-{case}.out.{label}.bin'
                original=path.read_bytes()
                path.write_bytes(prefix+original[len(prefix):] if prefix else b'')
                with self.assertRaises(GateError): diagnostic.validate_state_outputs(self.inputs,self.stages)
                path.write_bytes(original)

    def test_positive_zero_padding_is_exact_for_the_pinned_select_graph(self):
        path=self.inputs/'actual/stateful/pinned-encoder-000.out.next_layer_00.bin'
        data=bytearray(path.read_bytes()); data[12*1024*4:12*1024*4+4]=struct.pack('<f',-0.0)
        path.write_bytes(data)
        with self.assertRaisesRegex(GateError,'history transition'): diagnostic.validate_state_outputs(self.inputs,self.stages)

    def test_manifest_chain_and_receipt_inventory_are_required(self):
        for path in [self.inputs/'stateful.tsv',self.inputs/'actual/stateful/pinned-encoder-run.tsv']:
            original=path.read_text(); path.write_text(original+'UNEXPECTED\n')
            with self.assertRaises(GateError): diagnostic.validate_state_outputs(self.inputs,self.stages)
            path.write_text(original)
        altered=copy.deepcopy(self.stages); altered['stateful'][1]['state']='RESET'
        with self.assertRaises(GateError): diagnostic.validate_state_outputs(self.inputs,altered)


class ContinuationTests(unittest.TestCase):
    def exercise(self, mutate=None, *, enabled=True, ordinary_error=None, lane_error=None, payload_mutate=None, lane_mutate=None):
        temporary=tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        root=Path(temporary.name); out=root/'run'; calls=[]; captured={}
        build={'source_snapshot_sha256':'a'*64,'reviewed_patch_sha256':'b'*64,
               'recipe_manifest_sha256':'c'*64,
               'binaries':{t:{'bytes':1,'sha256':'d'*64} for t in run_quality.TARGETS}}
        def session(work):
            captured['session']=diagnostic.DiagnosticSession(work); return captured['session']
        def download(pin,path,**kwargs): path.write_bytes(b'public-source')
        def derive(path,inputs):
            (inputs/'pcm.f32le').write_bytes(b'pcm'); (inputs/'matched.wav').write_bytes(b'wav'); return {}
        def cases(inputs):
            stages,payloads=fixtures(inputs)
            if payload_mutate: payload_mutate(payloads)
            captured.update(stages=stages,payloads=payloads); return stages
        def process(command,directory,results=False,*,cleanup_root=None):
            calls.append(directory.name); directory.mkdir(); value=completed()
            write(directory/'process.json',value)
            if directory.name=='reassembly': (out/'inputs/stateful.tflite').write_bytes(b'model')
            elif directory.name=='frontend':
                (out/'inputs/mel.f32le').write_bytes(bytes(307*128*4))
                (directory/'stdout.log').write_text(json.dumps(dict(passed=True,decode_pcm_bitwise=True,encoded_and_pcm_mel_bitwise=True)))
            elif directory.name.startswith('encoder-'):
                stage=directory.name[8:]; emit(out/'inputs',stage,captured['stages'][stage],captured['payloads'][stage])
            return value,None
        original=prepare_inputs.compare_encoder
        def oracle(inputs,stages,path,**kwargs):
            if ordinary_error: raise ordinary_error
            try: return original(inputs,stages,path,**kwargs)
            except diagnostic.HistoricalReferenceMismatch as failure:
                captured['original_receipt']=path.read_bytes()
                captured['failure']=failure
                if mutate: mutate(out,failure)
                raise
        def lane(binary,request,directory,*,binary_identity,cleanup_root):
            calls.append(directory.name)
            if lane_error: raise GateError(lane_error,'private diagnostic error /do-not-export')
            self.assertEqual(cleanup_root,root/'build')
            directory.mkdir(); requested=load(request)
            self.assertEqual(requested['projected_tokens_sha256'],SYNTHETIC_ROWS_SHA)
            self.assertNotEqual(requested['projected_tokens_sha256'],common.ROWS_SHA)
            value=result(directory.name)
            for key in list(value):
                if key in requested and key != 'audio_embedding_tap': value[key]=requested[key]
            if lane_mutate: lane_mutate(value)
            write(directory/'result.json',value); write(directory/'process.json',completed())
            return completed(),value
        with ExitStack() as stack:
            for owner,name,value in [(run_quality,'verify_build',lambda p:(build,root/'sdk')),
                    (run_quality,'DiagnosticSession',session),
                    (run_quality,'download',download),(run_quality,'extract_sections',lambda *a:{}),
                    (run_quality,'derive_pcm',derive),(run_quality,'checked_process',process),
                    (run_quality,'checked_full_e2b',lane),(run_quality,'verify',lambda *a:None),
                    (run_quality,'encoder_cases',cases),(run_quality,'compare_encoder',oracle)]:
                stack.enter_context(patch.object(owner,name,side_effect=value))
            for owner in (prepare_inputs,diagnostic):
                stack.enter_context(patch.object(owner,'KNOWN_ROWS_SHA',SYNTHETIC_ROWS_SHA))
            stack.enter_context(patch.object(run_quality,'KNOWN_ROWS_SHA',SYNTHETIC_ROWS_SHA))
            stack.enter_context(patch.object(run_quality,'sha',side_effect=lambda p:
                common.MEL_SHA if Path(p).name=='mel.f32le' else sha(p)))
            stack.enter_context(patch.dict(os.environ,JAVA_HOME=str(root),GEMMA_QUALITY_COMPUTE_SLOT='confirmed_by_owner'))
            args=type('Args',(),dict(out=out,build_dir=root/'build',diagnostic_on_known_reference_mismatch=enabled))()
            summary=run_quality.run_gate(args)
        return out,calls,captured,summary

    def test_successful_diagnostic_preserves_failed_oracle_summary_and_exit(self):
        out,calls,captured,summary=self.exercise()
        self.assertEqual(calls[-2:],['projected_null','raw'])
        self.assertFalse(summary['passed']); self.assertEqual(summary['classification'],'numerical_failure')
        self.assertEqual(summary['stage'],'native_encoder_oracle'); self.assertEqual(summary['lanes'],{})
        self.assertEqual(summary['error'],'Complete pinned projected-row hash mismatch')
        self.assertEqual((out/'encoder-oracle.json').read_bytes(),captured['original_receipt'])
        receipt=load(out/'diagnostic-pair.json')
        self.assertEqual(receipt['state'],'completed'); self.assertTrue(receipt['native_pair_passed'])
        self.assertTrue(receipt['documentation_example_match']); self.assertFalse(receipt['strict_gate_passed'])
        self.assertFalse(receipt['cache_state_reference_equivalence_proven'])
        self.assertFalse((out/'comparison.json').exists())
        with patch.object(run_quality,'run_gate',return_value=summary),patch('sys.argv',['run_quality.py','--build-dir','build','--out','run']),patch('builtins.print'):
            self.assertEqual(run_quality.main(),2)

    def test_disabled_option_keeps_original_stop(self):
        out,calls,_,summary=self.exercise(enabled=False)
        self.assertNotIn('projected_null',calls); self.assertFalse((out/'diagnostic-pair.json').exists())
        self.assertEqual(summary['classification'],'numerical_failure')

    def test_other_failure_classes_never_authorize_diagnostics(self):
        for kind in ('numerical_failure','identity_failure','evidence_failure','resource_constrained','model_cleanup_failure'):
            with self.subTest(kind=kind):
                out,calls,_,summary=self.exercise(ordinary_error=GateError(kind,'Complete pinned projected-row hash mismatch'))
                self.assertNotIn('projected_null',calls); self.assertEqual(summary['classification'],kind)
                self.assertFalse((out/'diagnostic-pair.json').exists())

    def test_real_row_eoa_count_mask_and_unknown_reference_errors_never_continue(self):
        def row(values):
            data=values['adapter'][('000','soft_tokens')]
            values['adapter'][('000','soft_tokens')]=struct.pack('<f',1.0)+data[4:]
        def eoa(values): values['eoa'][('000','eoa_embedding')]=struct.pack('<f',1.0)+bytes(6140)
        def count(values): values['stateful'][('006','token_count')]=struct.pack('<i',6)
        def mask(values): values['stateful'][('006','token_mask')]=bytes(12)
        def unknown(values):
            row(values)
            data=values['stateful'][('000','soft_tokens')]
            values['stateful'][('000','soft_tokens')]=struct.pack('<f',1.0)+data[4:]
        for mutate in (row,eoa,count,mask,unknown):
            with self.subTest(mutate=mutate.__name__):
                out,calls,_,summary=self.exercise(payload_mutate=mutate)
                self.assertNotIn('projected_null',calls)
                self.assertEqual(summary['classification'],'numerical_failure')
                self.assertFalse((out/'diagnostic-pair.json').exists())

    def test_copied_edited_or_foreign_receipts_never_launch(self):
        def copied(out,error):
            path=out/'encoder-oracle.json'; duplicate=out/'copy.json'; shutil.copyfile(path,duplicate); duplicate.replace(path)
        def edited(out,error):
            path=out/'encoder-oracle.json'; path.write_bytes(path.read_bytes()+b' ')
        def stale(out,error): error.ticket=object()
        def changed_process(out,error): write(out/'encoder-eoa/process.json',dict(completed(),cleanup_verified=False))
        def changed_input(out,error): (out/'inputs/chunk-6.bin').write_bytes(bytes(48*128*4))
        for mutate in (copied,edited,stale,changed_process,changed_input):
            with self.subTest(mutate=mutate.__name__):
                out,calls,_,summary=self.exercise(mutate)
                self.assertNotIn('projected_null',calls); self.assertFalse(summary['passed'])
                self.assertEqual(load(out/'diagnostic-pair.json')['state'],'blocked')

    def test_nonfinite_uncompared_output_blocks_diagnostic_lanes(self):
        def changed(out,error):
            path=out/'inputs/actual/stateful/pinned-encoder-000.out.encoder_features.bin'
            path.write_bytes(struct.pack('<f',float('nan'))+path.read_bytes()[4:])
        out,calls,_,_=self.exercise(changed)
        self.assertNotIn('projected_null',calls)
        self.assertEqual(load(out/'diagnostic-pair.json')['failure_class'],'evidence_failure')

    def test_fresh_invalid_state_is_numerical_and_never_launches(self):
        def nonfinite(values):
            key=('000','encoder_features'); data=values['stateful'][key]
            values['stateful'][key]=struct.pack('<f',float('nan'))+data[4:]
        def history(values): values['stateful'][('006','next_history_tokens')]=struct.pack('<i',23)
        for mutate in (nonfinite,history):
            out,calls,_,summary=self.exercise(payload_mutate=mutate)
            self.assertNotIn('projected_null',calls)
            self.assertEqual(load(out/'diagnostic-pair.json')['failure_class'],'numerical_failure')
            self.assertEqual(summary['classification'],'numerical_failure')

    def test_uncertain_cleanup_latch_blocks_first_diagnostic_lane(self):
        def uncertain(out,error):
            build=out.parent/'build'; build.mkdir()
            write(build/'model-process-cleanup.json',{'schema_version':1,'cleanup_verified':False})
        out,calls,_,_=self.exercise(uncertain)
        self.assertNotIn('projected_null',calls)
        self.assertEqual(load(out/'diagnostic-pair.json')['failure_class'],'model_cleanup_failure')

    def test_lane_failure_stops_raw_and_preserves_original_exit_class(self):
        for kind in ('resource_constrained','model_cleanup_failure','identity_failure','numerical_failure'):
            out,calls,_,summary=self.exercise(lane_error=kind)
            self.assertNotIn('raw',calls); self.assertEqual(summary['classification'],'numerical_failure')
            value=load(out/'diagnostic-pair.json'); self.assertEqual(value['failure_class'],kind)
            self.assertNotIn('do-not-export',json.dumps(value))

    def test_receipt_alone_and_reused_ticket_cannot_authorize(self):
        out,_,captured,_=self.exercise()
        with self.assertRaises(GateError): captured['session'].authorize(captured['failure'])
        with self.assertRaises(GateError): diagnostic.DiagnosticSession(out).authorize(captured['failure'])
        with self.assertRaises(GateError): diagnostic.DiagnosticSession(out).authorize(load(out/'encoder-oracle.json'))

    def test_first_lane_wrong_identity_tap_or_cleanup_stops_before_raw(self):
        def wrong_hash(value): value['projected_tokens_sha256']=common.ROWS_SHA
        def wrong_tap(value): value['audio_embedding_tap']['bitwise_equal']=False
        def wrong_cleanup(value): value['checked_drain_delete']=False
        for mutate in (wrong_hash,wrong_tap,wrong_cleanup):
            out,calls,_,summary=self.exercise(lane_mutate=mutate)
            self.assertNotIn('raw',calls)
            self.assertFalse(summary['passed'])
            self.assertEqual(load(out/'diagnostic-pair.json')['state'],'blocked')

    def test_export_keeps_fixed_metadata_and_excludes_all_diagnostic_payloads(self):
        out,_,_,_=self.exercise()
        (out/'diagnostic-pair/private.log').write_text('/private/NEVER-EXPORT')
        with patch.object(diagnostic,'KNOWN_ROWS_SHA',SYNTHETIC_ROWS_SHA):
            export_evidence.export(out.parent/'build',out,out.parent/'export')
        exported=out.parent/'export'
        content=''.join(p.read_text() for p in exported.rglob('*.json'))
        self.assertNotIn('NEVER-EXPORT',content); self.assertNotIn('"blob"',content)
        names=load(exported/'EVIDENCE-INDEX.json')['files']
        self.assertIn('run/diagnostic-pair.json',names)
        self.assertFalse(any(name.startswith('run/diagnostic-pair/') for name in names))
        self.assertFalse(load(exported/'EVIDENCE-INDEX.json')['summary']['passed'])
        receipt=load(out/'diagnostic-pair.json'); receipt['private_path']='/private/NEVER-EXPORT'
        write(out/'diagnostic-pair.json',receipt)
        with patch.object(diagnostic,'KNOWN_ROWS_SHA',SYNTHETIC_ROWS_SHA),self.assertRaises(GateError):
            export_evidence.export(out.parent/'build',out,out.parent/'bad-export')
        self.assertNotIn('NEVER-EXPORT',(out.parent/'bad-export/EVIDENCE-INDEX.json').read_text())

    def test_public_schema_rejects_false_green_and_nested_private_data(self):
        out,_,_,_=self.exercise(); value=load(out/'diagnostic-pair.json')
        for key,bad in [('strict_gate_passed',True),('strict_exit_code',0),
                        ('cache_state_reference_equivalence_proven',True),('failure_class','/private/path'),
                        ('lanes',{'raw':{'text':'private generated response'}})]:
            changed=copy.deepcopy(value); changed[key]=bad
            with patch.object(diagnostic,'KNOWN_ROWS_SHA',SYNTHETIC_ROWS_SHA),self.assertRaises(GateError):
                diagnostic.public_receipt(changed)

    def test_reader_and_export_reject_impossible_progress_claims(self):
        out,_,_,_=self.exercise(); good=load(out/'diagnostic-pair.json')
        mutations=[{'lanes':{}},{'checks':{}},{'input_identity_sha256':None},
                   {'checks':dict(good['checks'],emitted_output_count=130)},
                   {'native_pair_passed':False,'failure_class':None},
                   {'documentation_example_match':False,'failure_class':'numerical_or_output_parity_failure'},
                   {'state':'running'}, {'state':'blocked','failure_class':'evidence_failure'},
                   {'state':'running','native_pair_passed':None,'documentation_example_match':None,'checks':{}},
                   {'state':'running','native_pair_passed':None,'documentation_example_match':None,'failure_class':'evidence_failure'},
                   {'state':'blocked','native_pair_passed':None,'documentation_example_match':None,'failure_class':None},
                   {'lanes':{'raw':good['lanes']['raw']}}]
        for index, changes in enumerate(mutations):
            with self.subTest(changes=changes):
                write(out/'diagnostic-pair.json',dict(good,**changes))
                with patch.object(diagnostic,'KNOWN_ROWS_SHA',SYNTHETIC_ROWS_SHA):
                    with self.assertRaises(GateError): diagnostic.read_public(out/'diagnostic-pair.json')
                    with self.assertRaises(GateError):
                        export_evidence.export(out.parent/'build',out,out.parent/f'impossible-{index}')
                self.assertFalse((out.parent/f'impossible-{index}/run/diagnostic-pair.json').exists())

    def test_running_blocked_and_completed_failure_schemas_remain_honest(self):
        out,_,_,_=self.exercise(); good=load(out/'diagnostic-pair.json')
        values=[dict(good,state='running',lanes={},native_pair_passed=None,documentation_example_match=None),
                dict(good,state='blocked',failure_class='evidence_failure',lanes={},checks={},
                     strict_oracle_sha256=None,prerequisite_binding_sha256=None,input_identity_sha256=None,
                     native_pair_passed=None,documentation_example_match=None),
                dict(good,native_pair_passed=False,failure_class='numerical_or_output_parity_failure'),
                dict(good,documentation_example_match=False,failure_class='semantic_reference_mismatch')]
        for value in values:
            with patch.object(diagnostic,'KNOWN_ROWS_SHA',SYNTHETIC_ROWS_SHA):
                self.assertEqual(diagnostic.public_receipt(value),value)


if __name__ == '__main__': unittest.main()
