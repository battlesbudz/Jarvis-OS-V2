"""Failure cases for the consolidated CI gate; all fixtures are synthetic."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from verification.receipt import consolidate, junit_results, SCENARIOS
from verification.android import sha256, instrumentation_results


class ReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source, self.head = 'a' * 40, 'b' * 40
        self.url = 'https://github.com/owner/repo/actions/runs/1'
        self.needs = {key: {'result': 'success'} for key in ('build-release', 'verify-release')}
        self.scenarios = json.loads(SCENARIOS.read_text())
        self.junit = self.root / 'jarvis-release-unit-tests' / 'TEST-suite.xml'
        self.junit.parent.mkdir()
        self.junit.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="Suite" name="works"/></testsuite>')
        test = self.root / 'jarvis-verification-test-apk' / 'app-release-androidTest.apk'
        test.parent.mkdir()
        test.write_bytes(b'synthetic test APK')
        for api, apk in ((30, 'app-release'), (35, 'app-compact')):
            binary = self.root / 'jarvis-os-v2-release-apk' / f'{apk}.apk'
            binary.parent.mkdir(exist_ok=True)
            binary.write_bytes(f'synthetic {apk}'.encode())
            folder = self.root / f'jarvis-verification-api-{api}-{apk}'
            folder.mkdir()
            (folder / 'tests').mkdir()
            raw = ''.join(f"INSTRUMENTATION_STATUS: class={self.scenarios['class']}\nINSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: 0\n" for name in self.scenarios['tests']) + f"OK ({len(self.scenarios['tests'])} tests)\n"
            (folder / 'instrumentation.txt').write_text(raw)
            report = {'passed': True, 'source_commit': self.source, 'pr_head': self.head, 'run_url': self.url,
                      'errors': [], 'process_restart': 'passed', 'apk_sha256': sha256(binary), 'test_apk_sha256': sha256(test),
                      'device': {'api': str(api)}, 'instrumentation': instrumentation_results(raw, self.scenarios['tests'], self.scenarios['class'])}
            (folder / 'report.json').write_text(json.dumps(report))
            for name in ['first-launch', 'final', 'after-process-restart', *self.scenarios['tests']]:
                base = folder / 'tests' if name in self.scenarios['tests'] else folder
                (base / f'{name}.png').write_bytes(b'\x89PNG\r\n\x1a\nfixture')
                (base / f'{name}.xml').write_text(f'<hierarchy><node text="{self.scenarios["restart_model"]}"/></hierarchy>')
        self.folder = self.root / 'jarvis-verification-api-30-app-release'

    def check(self):
        return consolidate(self.root, self.source, self.head, self.url, self.needs)

    def test_complete_evidence_passes_without_granting_approval(self):
        receipt = self.check()
        self.assertTrue(receipt['passed'], receipt['errors'])
        self.assertEqual(2, len(receipt['variants']))
        self.assertFalse(receipt['release_approved'])

    def test_failed_upstream_rejected_even_with_good_artifacts(self):
        self.needs['verify-release']['result'] = 'failure'
        self.assertFalse(self.check()['passed'])

    def test_junit_failures_skips_empty_and_count_lies_rejected(self):
        for xml in ['<testsuite tests="0"/>', '<testsuite tests="2"><testcase classname="S" name="a"/></testsuite>',
                    '<testsuite tests="1"><testcase classname="S" name="a"><failure/></testcase></testsuite>',
                    '<testsuite tests="1"><testcase classname="S" name="a"><skipped/></testcase></testsuite>',
                    '<!DOCTYPE testsuite><testsuite tests="0"/>']:
            self.junit.write_text(xml)
            with self.subTest(xml=xml):
                self.assertFalse(self.check()['passed'])

    def test_apk_tampering_rejected(self):
        (self.root / 'jarvis-os-v2-release-apk' / 'app-release.apk').write_bytes(b'changed')
        self.assertFalse(self.check()['passed'])

    def test_stale_revision_wrong_run_or_api_rejected(self):
        path = self.folder / 'report.json'
        original = json.loads(path.read_text())
        for key, value in [('source_commit', self.head), ('pr_head', self.source), ('run_url', self.url + '9'), ('device', {'api': '35'})]:
            report = copy.deepcopy(original)
            report[key] = value
            path.write_text(json.dumps(report))
            with self.subTest(key=key):
                self.assertFalse(self.check()['passed'])

    def test_missing_evidence_and_failed_raw_test_rejected(self):
        (self.folder / 'final.png').unlink()
        self.assertFalse(self.check()['passed'])
        (self.folder / 'instrumentation.txt').write_text('OK (0 tests)')
        self.assertTrue(any('instrumentation' in e for e in self.check()['errors']))

    def test_restart_xml_must_contain_selected_model(self):
        (self.folder / 'after-process-restart.xml').write_text('<hierarchy/>')
        self.assertFalse(self.check()['passed'])

    def test_push_receipt_accepts_empty_pr_head(self):
        self.head = ''
        for path in self.root.glob('jarvis-verification-api-*/report.json'):
            report = json.loads(path.read_text())
            report['pr_head'] = ''
            path.write_text(json.dumps(report))
        self.assertTrue(self.check()['passed'])


if __name__ == '__main__':
    unittest.main()
