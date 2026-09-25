#!/usr/bin/env python3
"""Consolidate same-run GitHub artifacts; never approve a release or fetch credentials."""
import argparse
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET

try:
    from .android import instrumentation_results, sha256, SCENARIOS
except ImportError:
    from android import instrumentation_results, sha256, SCENARIOS


def xml_file(path):
    data = path.read_bytes()
    if len(data) > 8 * 1024 * 1024 or b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper():
        raise ValueError('Unsafe or oversized XML')
    return ET.fromstring(data)


def junit_results(folder):
    files = sorted(folder.rglob('TEST-*.xml'))
    if not files:
        raise ValueError('Missing JVM test XML')
    names = set()
    for path in files:
        suite = xml_file(path)
        cases = suite.findall('testcase')
        if suite.tag != 'testsuite' or int(suite.get('tests', '-1')) != len(cases):
            raise ValueError('Invalid JVM suite/count')
        if any(int(suite.get(key, '0')) != 0 for key in ('failures', 'errors', 'skipped')):
            raise ValueError('JVM suite reports failures, errors or skips')
        for case in cases:
            key = (case.get('classname'), case.get('name'))
            if not all(key) or key in names or any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError('Failed, skipped, unnamed or duplicate JVM test')
            names.add(key)
    if not names:
        raise ValueError('No JVM tests executed')
    return {'passed': True, 'tests': len(names), 'suites': len(files)}


def consolidate(root, source_commit, pr_head, run_url, needs):
    scenarios = json.loads(SCENARIOS.read_text())
    result = {'schema': 1, 'passed': False, 'source_commit': source_commit,
              'pr_head': pr_head, 'run_url': run_url, 'errors': [], 'variants': [],
              'release_approved': False, 'coverage': 'release JVM + UI + Android actions; no model inference',
              'not_covered': scenarios['not_covered'],
              'provenance': 'Same-run GitHub Actions artifacts; APK bytes rehashed. Not an external attestation.'}
    errors = result['errors']
    if not re.fullmatch('[0-9a-f]{40}', source_commit) or (pr_head and not re.fullmatch('[0-9a-f]{40}', pr_head)):
        errors.append('Invalid source revision')
    for job in ('build-release', 'verify-release'):
        if needs.get(job, {}).get('result') != 'success':
            errors.append(f'Required job did not succeed: {job}')
    try:
        result['jvm'] = junit_results(root / 'jarvis-release-unit-tests')
    except (OSError, ValueError, ET.ParseError) as error:
        errors.append(f'JVM evidence: {error}')
    for api, apk in ((30, 'app-release'), (35, 'app-compact')):
        try:
            folder = root / f'jarvis-verification-api-{api}-{apk}'
            report = json.loads((folder / 'report.json').read_text())
            expected = {'passed': True, 'source_commit': source_commit, 'pr_head': pr_head,
                        'run_url': run_url, 'errors': [], 'process_restart': 'passed',
                        'apk_sha256': sha256(root / 'jarvis-os-v2-release-apk' / f'{apk}.apk'),
                        'test_apk_sha256': sha256(root / 'jarvis-verification-test-apk' / 'app-release-androidTest.apk')}
            for key, value in expected.items():
                if report.get(key) != value:
                    raise ValueError(f'Mismatched {key}')
            if report.get('device', {}).get('api') != str(api):
                raise ValueError('Wrong emulator API')
            raw = instrumentation_results((folder / 'instrumentation.txt').read_text(), scenarios['tests'], scenarios['class'])
            if not raw['passed'] or raw != report.get('instrumentation'):
                raise ValueError('Raw instrumentation is incomplete or disagrees with report')
            for name in ['first-launch', 'final', 'after-process-restart', *scenarios['tests']]:
                base = folder / 'tests' if name in scenarios['tests'] else folder
                pngs, xmls = list(base.rglob(f'{name}.png')), list(base.rglob(f'{name}.xml'))
                if len(pngs) != 1 or len(xmls) != 1 or not pngs[0].read_bytes().startswith(b'\x89PNG\r\n\x1a\n'):
                    raise ValueError(f'Missing/ambiguous screenshot or hierarchy: {name}')
                tree = xml_file(xmls[0])
                if tree.tag != 'hierarchy':
                    raise ValueError('Invalid UI hierarchy')
                if name == 'after-process-restart' and scenarios['restart_model'] not in [n.get('text') for n in tree.iter('node')]:
                    raise ValueError('Selection did not persist across process restart')
            result['variants'].append({'api': api, 'apk': f'{apk}.apk', 'apk_sha256': expected['apk_sha256'],
                                       'test_apk_sha256': expected['test_apk_sha256'], 'tests': len(raw['tests']), 'passed': True})
        except (OSError, ValueError, KeyError, TypeError, AttributeError, ET.ParseError) as error:
            errors.append(f'API {api} {apk}: {error}')
    result['passed'] = not errors
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    receipt = consolidate(args.inputs, os.environ['GITHUB_SHA'], os.getenv('PR_HEAD', ''),
                          f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
                          json.loads(os.environ['VERIFICATION_NEEDS']))
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    summary = (f"# Jarvis verification: {'PASS' if receipt['passed'] else 'FAIL'}\n\n"
               f"Tested commit: `{receipt['source_commit']}`\n\nPR head: `{receipt['pr_head'] or 'push build'}`\n\n"
               f"JVM tests: {receipt.get('jvm', {}).get('tests', 0)}. Verified APK variants: {len(receipt['variants'])}.\n\n"
               + '\n'.join(f'- {error}' for error in receipt['errors']) + '\n\nNot verified:\n'
               + '\n'.join(f'- {item}' for item in receipt['not_covered'])
               + '\n\nScreenshots checked for presence/structure only. Final user sign-off is still required.\n')
    (args.out / 'summary.md').write_text(summary)
    if os.getenv('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
            stream.write(summary)
    print(json.dumps(receipt, indent=2))
    return 0 if receipt['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
