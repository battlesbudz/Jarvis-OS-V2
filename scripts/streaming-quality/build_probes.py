#!/usr/bin/env python3
"""Build hosted CPU probes only after the serial Android SDK compilation exits."""
import argparse
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
from common import *

PACKAGE = 'experimental/hosted_audio_quality_20261006'
TARGETS = ['native_frontend_quality_probe', 'pinned_encoder_probe', 'native_conversation_quality_probe']
BAZEL_SHA = 'ac6249d1192aea9feaf49dfee2ab50c38cee2454b00cf29bbec985a11795c025'


def checked_overlay(text):
    old = 'android_ndk_repository(name = "androidndk")'
    if text.count(old) != 1:
        raise ValueError('NDK declaration changed; re-review API30 overlay')
    return text.replace(old, 'android_ndk_repository(name = "androidndk", api_level = 30)')

def checked_owner_overlay(text):
    """Keep the tested Linux owner topology, statically link Android dependencies."""
    marker = '    name = "libnative_audio_owner_jni.so",'
    prefix, separator, suffix = text.partition(marker)
    normal, separator2, test = suffix.partition('# Deliberately separate test library:')
    if not separator or not separator2 or normal.count('    linkstatic = False,') != 1:
        raise ValueError('Owner normal target changed; re-review Android linkstatic overlay')
    normal = normal.replace('    linkstatic = False,', '''    linkstatic = select({
        "@platforms//os:android": True,
        "//conditions:default": False,
    }),''')
    return prefix + separator + normal + separator2 + test

def build(a):
    package_identity = verify_recipe_sources()
    sdk = a.sdk.resolve(); out = a.out.resolve()
    need(not out.exists(), 'Use a fresh hosted quality build directory')
    out.mkdir(parents=True)
    status = {'classification': 'build_pending', 'build_succeeded': False, 'inference_run': False}
    write(out/'build-status.json', status)
    try:
        android = load(a.android_receipt)
        need(android['sdk_commit'] == SDK_PIN and android['litert_commit'] == LITERT_PIN,
             'Android build source pin mismatch')
        need(sha(a.patch) == android['reviewed_patch_sha256'], 'Reviewed patch digest differs from compiled Android receipt')
        need(sha(sdk/'WORKSPACE') == android['workspace_after_api30_sha256'], 'Android API30 workspace overlay changed')
        # Reconstruct the expected source tree in a temporary worktree so extra
        # tracked/untracked modifications cannot hide behind the patch digest.
        expected = out/'expected-sdk'
        subprocess.run(['git', '-C', str(sdk), 'worktree', 'add', '--detach', str(expected), SDK_PIN],
                       check=True, env=dict(os.environ, GIT_LFS_SKIP_SMUDGE='1'))
        subprocess.run(['git', '-C', str(expected), 'apply', str(a.patch.resolve())], check=True)
        overlays = {'WORKSPACE': checked_overlay,
                    'experimental/native_audio_owner_jni_20261006/BUILD': checked_owner_overlay}
        need(set(android['build_overlays']) == set(overlays), 'Unreviewed Android source overlay')
        for relative, apply in overlays.items():
            path = expected/relative
            need(sha(path) == android['build_overlays'][relative]['before_sha256'], 'Overlay source changed')
            path.write_text(apply(path.read_text()))
            need(sha(path) == android['build_overlays'][relative]['after_sha256'], 'Overlay result differs')
        need(sdk_snapshot(sdk) == sdk_snapshot(expected), 'SDK source tree differs from the reviewed Android patch')
        subprocess.run(['git', '-C', str(sdk), 'worktree', 'remove', '--force', str(expected)], check=True)
        installed = sdk/PACKAGE
        need(not installed.exists(), 'Hosted probe package already exists; stale build refused')
        shutil.copytree(HERE/'native', installed)
        pins = load(HERE/'host-prebuilts.json')
        for pin in pins:
            pointer = subprocess.check_output(['git', '-C', str(sdk), 'show', SDK_PIN+':'+pin['path']], text=True)
            need(f'oid sha256:{pin["sha256"]}\nsize {pin["bytes"]}\n' in pointer, 'Host prebuilt Git-LFS pointer changed')
            path = sdk/pin['path']
            with path.open('rb') as stream: prefix = stream.read(42)
            if prefix.startswith(b'version https://git-lfs.github.com/spec/v1'):
                path.unlink()  # Replace only the confirmed tiny upstream pointer.
            download(pin, path)
        before = sdk_snapshot(sdk)
        write(out/'source-snapshot.json', before)
        need(sha(a.bazel) == BAZEL_SHA, 'Bazel executable identity changed')
        java_home = Path(os.environ['JAVA_HOME'])
        version = subprocess.check_output([str(java_home/'bin/java'), '-version'], stderr=subprocess.STDOUT, text=True)
        need('version "21' in version, 'JDK21 required')
        cc = shutil.which('clang'); cxx = shutil.which('clang++')
        need(bool(cc and cxx), 'Host clang/clang++ required')
        command = [str(a.bazel.resolve()), '--batch', f'--output_user_root={a.bazel_root.resolve()}',
            f'--server_javabase={java_home}', '--host_jvm_args=-Xmx2048m', 'build', '-c', 'opt',
            '--config=linux', '--jobs=1', '--local_resources=cpu=1', '--local_resources=memory=6144',
            '--remote_executor=', '--remote_cache=', '--noremote_upload_local_results',
            f'--repo_env=CC={cc}', f'--repo_env=CXX={cxx}', *[f'//{PACKAGE}:{t}' for t in TARGETS]]
        # Build has its own bounded job; no inference or model download runs
        # while this compiler subprocess is alive. Batch mode exits its JVM.
        with (out/'compile.log').open('w') as log:
            subprocess.run(command, cwd=sdk, check=True, stdout=log, stderr=subprocess.STDOUT, timeout=2700)
        need(sdk_snapshot(sdk) == before, 'SDK source changed during host compilation')
        binaries = {t: describe(sdk/'bazel-bin'/PACKAGE/t) for t in TARGETS}
        dynamic = {}
        for target in TARGETS:
            listing = subprocess.check_output(['ldd', str(sdk/'bazel-bin'/PACKAGE/target)], text=True)
            need('not found' not in listing, 'Host runtime library is missing')
            for line in listing.splitlines():
                match = re.search(r'(?:=>\s+)?(/\S+)\s+\(', line)
                if match:
                    path = Path(match[1]).resolve(); dynamic[str(path)] = describe(path)
        status.update(dynamic_libraries=dynamic, classification='build_passed', build_succeeded=True,
            sdk=str(sdk), package=PACKAGE, targets=TARGETS, binaries=binaries,
            source_snapshot_sha256=digest(before), recipe_manifest_sha256=package_identity,
            android_source_receipt_sha256=sha(a.android_receipt), reviewed_patch_sha256=sha(a.patch),
            bazel_sha256=BAZEL_SHA, host_prebuilts={p['path']: {k:p[k] for k in ('bytes','sha256')} for p in pins},
            compile_log_sha256=sha(out/'compile.log'), java=version.strip(),
            host_compiler=subprocess.check_output([cc, '--version'], text=True).strip(),
            compilation_exited_before_quality=True)
    except Exception as e:
        status.update(classification=e.classification if isinstance(e, GateError) else 'build_failure', error=str(e))
        write(out/'build-status.json', status)
        raise
    write(out/'build-status.json', status)
    return status


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('sdk','android-receipt','patch','bazel','bazel-root','out'): p.add_argument('--'+name, type=Path, required=True)
    a = p.parse_args()
    try: print(json.dumps(build(a), indent=2))
    except Exception as e: print(str(e), file=sys.stderr); return 2
    return 0
if __name__ == '__main__': sys.exit(main())
