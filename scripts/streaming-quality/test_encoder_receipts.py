"""Synthetic scalar/tensor fixtures exercise failure evidence, not model quality."""
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock

import prepare_inputs
from common import GateError, LITERT_PIN
from export_evidence import validate_json


class EncoderReceiptTest(unittest.TestCase):
    def fixture(self, root):
        stages = {name: [] for name in ('stateful', 'static', 'adapter', 'eoa')}
        def case(stage, name, values):
            directory = root / 'actual' / stage
            directory.mkdir(parents=True, exist_ok=True)
            specs = []
            for label, (dtype, data) in values.items():
                (directory / f'pinned-encoder-{name}.out.{label}.bin').write_bytes(data)
                specs.append({'label': label, 'dtype': dtype, 'bytes': len(data)})
            stages[stage].append({'id': name, 'outputs': specs})
        for index, count in enumerate([12] * 6 + [5]):
            case('stateful', f'{index:03}', {
                'soft_tokens': ('float32', bytes(12 * 1536 * 4)),
                'token_count': ('int32', struct.pack('<i', count)),
                'token_mask': ('bool', b'\1' * count + b'\0' * (12 - count))})
        case('stateful', 'eoa', {'eoa_embedding': ('float32', bytes(1536 * 4))})
        case('static', '000', {'mask': ('bool', b'\1' * 77 + b'\0' * 127)})
        case('adapter', '000', {'soft_tokens': ('float32', bytes(204 * 1536 * 4))})
        case('eoa', '000', {'eoa_embedding': ('float32', bytes(1536 * 4))})
        for stage, cases in stages.items():
            (root / 'actual' / stage / 'pinned-encoder-run.tsv').write_text(
                f'PIN\t{LITERT_PIN}\nCPU_THREADS\t1\nCOMPLETE\t{len(cases)}\n')
        return stages

    def test_fixed_hash_failure_retains_positive_pair_checks_and_actual_hash(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); stages = self.fixture(root); receipt = root / 'oracle.json'
            with self.assertRaisesRegex(GateError, 'Complete pinned projected-row hash mismatch'):
                prepare_inputs.compare_encoder(root, stages, receipt)
            result = json.loads(receipt.read_text())
            self.assertFalse(result['passed'])
            self.assertTrue(result['post_adapter_valid_rows_bitwise'])
            self.assertTrue(result['eoa_bitwise'])
            self.assertFalse(result['complete_reference_hash_match'])
            self.assertEqual(hashlib.sha256(bytes(473088)).hexdigest(), result['projected_rows']['sha256'])
            self.assertEqual('numerical_failure', result['classification'])
            validate_json(result)
            self.assertFalse(any(key in result for key in ('blob', 'token_values', 'activation_data')))

    def test_same_run_row_failure_does_not_claim_reference_check_ran(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); stages = self.fixture(root); receipt = root / 'oracle.json'
            row = root / 'actual' / 'adapter' / 'pinned-encoder-000.out.soft_tokens.bin'
            row.write_bytes(struct.pack('<f', 1.0) + row.read_bytes()[4:])
            with self.assertRaisesRegex(GateError, 'Streamed/static post-adapter rows differ'):
                prepare_inputs.compare_encoder(root, stages, receipt)
            result = json.loads(receipt.read_text())
            self.assertFalse(result['post_adapter_valid_rows_bitwise'])
            self.assertIsNone(result['complete_reference_hash_match'])
            self.assertNotIn('projected_rows', result)

    def test_success_requires_every_original_check_and_preserves_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); stages = self.fixture(root); receipt = root / 'oracle.json'
            # Only this synthetic test supplies its own known zero-fixture reference.
            with mock.patch.object(prepare_inputs, 'ROWS_SHA', hashlib.sha256(bytes(473088)).hexdigest()):
                result = prepare_inputs.compare_encoder(root, stages, receipt)
            self.assertTrue(result['passed'])
            self.assertTrue(result['complete_reference_hash_match'])
            self.assertEqual(result, json.loads(receipt.read_text()))
            validate_json(result)


if __name__ == '__main__':
    unittest.main()
