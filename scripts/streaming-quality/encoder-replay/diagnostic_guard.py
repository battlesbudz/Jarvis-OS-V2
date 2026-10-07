#!/usr/bin/env python3
"""Own the complete optional diagnostic process tree before model admission.

The latch is written before any child starts. A cancelled or killed supervisor
cannot accidentally authorize the next workload: only its checked empty-tree
receipt clears the latch. Capture failure itself remains optional after cleanup.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from build_probes import run_compile
from capsule_writer import digest, file_identity, need
from hosted_capture import github_context

LATCH = 'diagnostic-cleanup.json'
REPORT = 'diagnostic-supervisor/report.private.json'


def save(path, value):
    path = Path(path)
    fd, temporary = tempfile.mkstemp(prefix='.'+path.name, dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, sort_keys=True, allow_nan=False)
            stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def read(path):
    path = Path(path)
    need(path.is_file() and not path.is_symlink() and path.stat().st_size <= 1024**2,
         'Missing or unsafe diagnostic cleanup evidence')
    return json.loads(path.read_text())


def clean_report(report):
    if report.get('started') is False:
        return report.get('classification') == 'build_resource_blocked' or report.get('cleanup_verified') is True
    return (report.get('started') is True and report.get('cleanup_verified') is True
            and report.get('surviving_process_count') == 0)


def verify_cleanup(build_dir, context):
    build_dir = Path(build_dir)
    latch = read(build_dir/LATCH)
    need(latch.get('schema_version') == 1 and latch.get('context') == context,
         'Diagnostic cleanup belongs to a different run/source/attempt')
    need(latch.get('state') == 'complete' and latch.get('cleanup_verified') is True,
         'Diagnostic process cleanup is still pending or uncertain')
    report_path = build_dir/REPORT
    report = read(report_path)
    need(file_identity(report_path)['sha256'] == latch.get('report_sha256'),
         'Diagnostic cleanup receipt changed')
    need(report.get('resource_profile') == 'diagnostic' and clean_report(report),
         'Diagnostic process tree was not positively cleared')
    return report


def supervise(command, build_dir, context, *, wall_seconds=180, cleanup_seconds=5):
    build_dir = Path(build_dir)
    need(build_dir.is_dir() and not (build_dir/LATCH).exists(), 'Fresh diagnostic cleanup latch required')
    save(build_dir/LATCH, {'schema_version': 1, 'context': context,
                         'state': 'pending', 'cleanup_verified': False})
    out = build_dir/'diagnostic-supervisor'
    out.mkdir()
    # Any exception before a complete supervisor receipt leaves the latch
    # pending. This includes SIGKILL and failures while inspecting /proc.
    report = run_compile(command, build_dir, out, wall_seconds=wall_seconds,
                         cleanup_seconds=cleanup_seconds, resource_profile='diagnostic')
    save(build_dir/REPORT, report)
    safe = clean_report(report)
    save(build_dir/LATCH, {'schema_version': 1, 'context': context,
                         'state': 'complete' if safe else 'uncertain', 'cleanup_verified': safe,
                         'report_sha256': file_identity(build_dir/REPORT)['sha256']})
    return report


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--child', action='store_true', help=argparse.SUPPRESS)
    for name in ('build-dir', 'guard', 'policy', 'out', 'repository-cache'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--expected-policy-sha256', required=True)
    args = parser.parse_args(argv)
    if args.child:
        # Both tests and the collector (including ldd/aquery) are descendants
        # of the outer supervisor. None of these children owns the latch.
        tests = subprocess.run([sys.executable, '-m', 'unittest', 'discover',
                                '-s', str(HERE), '-p', 'test_*.py', '-v'])
        if tests.returncode:
            return tests.returncode
        forwarded = [value for value in argv if value != '--child']
        return subprocess.run([sys.executable, str(HERE/'hosted_capture.py'), *forwarded]).returncode
    context = github_context()
    forwarded = []
    for name in ('build-dir', 'guard', 'policy', 'out', 'repository-cache'):
        forwarded += ['--'+name, str(getattr(args, name.replace('-', '_')).resolve())]
    forwarded += ['--expected-policy-sha256', args.expected_policy_sha256]
    report = supervise([sys.executable, str(Path(__file__).resolve()), '--child', *forwarded],
                       args.build_dir, context)
    print(json.dumps({'diagnostic_passed': report['passed'],
                      'cleanup_verified': clean_report(report),
                      'classification': report['classification']}))
    return 0 if report['passed'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
