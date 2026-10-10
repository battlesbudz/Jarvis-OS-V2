#!/usr/bin/env python3
"""Assemble the ONNX Runtime 1.27.1 Java API + JNI bridge for the vision module.

The vision module (:phoneinference) needs the ORT Java API, but Microsoft never
published onnxruntime-android 1.27.1 to Maven Central. The APK already ships
Sherpa's libonnxruntime.so (1.27.1), so we need only:
  1. classes.jar -- the ai.onnxruntime.* Java API, compiled from the v1.27.1 source
  2. libonnxruntime4j_jni.so -- the JNI bridge, taken prebuilt from the same
     csukuangfj 1.27.1 release that Sherpa's runtime comes from (verified:
     DT_NEEDED libonnxruntime.so, requires OrtGetApiBase@VERS_1.27.1).

At runtime the bridge resolves Sherpa's libonnxruntime.so from the app's native
library directory -- this module packages NO private ORT copy.

This is the same pattern as the LiteRT streaming bridge: use the available
upstream artifacts instead of waiting for a Maven publication that will never come.

Pinned inputs:
  ORT source:      microsoft/onnxruntime tag v1.27.1
                   (https://github.com/microsoft/onnxruntime/archive/refs/tags/v1.27.1.tar.gz)
  ORT Android libs: csukuangfj/onnxruntime-libs v1.27.1
                   (same zip + sha256 that scripts/build_sherpa.py uses)

Outputs (under --output):
  classes.jar                          -- compiled ai.onnxruntime.* Java API (1.27.1)
  jni/arm64-v8a/libonnxruntime4j_jni.so -- prebuilt JNI bridge, DT_NEEDED libonnxruntime.so

Follows scripts/build_sherpa.py conventions: pinned downloads with sha256
where available, a stamp file to skip redundant rebuilds.
"""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ORT_VERSION = '1.27.1'
ORT_SOURCE = ('https://github.com/microsoft/onnxruntime/archive/refs/tags/v1.27.1.tar.gz', None)
# Same pinned zip + hash as scripts/build_sherpa.py (Sherpa's ORT 1.27.1).
ORT_ANDROID = ('https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.27.1/onnxruntime-android-1.27.1.zip',
               'defade26209f72cf4fa9769b18052c842833d6bef12924595d26f03b995548ca')

ABI = 'arm64-v8a'


def download(spec, directory):
    url, expected = spec
    dest = directory / url.rsplit('/', 1)[1]
    if dest.exists() and (expected is None or hashlib.sha256(dest.read_bytes()).hexdigest() == expected):
        return dest
    tmp = dest.with_suffix('.part')
    print(f'Downloading {url}', flush=True)
    urllib.request.urlretrieve(url, tmp)
    if expected is not None and hashlib.sha256(tmp.read_bytes()).hexdigest() != expected:
        raise RuntimeError(f'Checksum mismatch: {url}')
    tmp.replace(dest)
    return dest


def build(output):
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    # Stamp: rebuild when this script changes (mirrors build_sherpa.py).
    fingerprint = 'vision-ort-jni-v2-' + hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    stamp = output / 'build-stamp'
    if stamp.exists() and stamp.read_text() == ORT_VERSION + fingerprint:
        print('Vision ORT JNI up to date, skipping build.', flush=True)
        return
    shutil.rmtree(output, ignore_errors=True)
    output.mkdir(parents=True, exist_ok=True)

    work = output / 'work'
    work.mkdir(parents=True)

    # 1. ORT source tree: compile the Java API from the v1.27.1 sources.
    with tarfile.open(download(ORT_SOURCE, work)) as archive:
        # Only need the Java sources, not the full 272 MB tree.
        members = [m for m in archive.getmembers()
                   if '/java/src/main/java/' in m.name or m.name.endswith('VERSION_NUMBER')]
        archive.extractall(work, members=members, filter='data')
    src = work / f'onnxruntime-{ORT_VERSION}'
    version_file = src / 'VERSION_NUMBER'
    if not version_file.is_file() or version_file.read_text().strip() != ORT_VERSION:
        raise RuntimeError(f'Unexpected ORT source version in {version_file}')
    java_src = src / 'java/src/main/java'

    classes = work / 'classes'
    classes.mkdir()
    java_files = sorted(java_src.rglob('*.java'))
    if not java_files:
        raise RuntimeError('No Java sources found')
    javac = shutil.which('javac') or 'javac'
    print(f'Compiling {len(java_files)} Java sources', flush=True)
    subprocess.run([javac, '-d', str(classes), *[str(f) for f in java_files]], check=True)
    jar = shutil.which('jar') or 'jar'
    subprocess.run([jar, 'cf', str(output / 'classes.jar'), '-C', str(classes), '.'], check=True)

    # 2. Prebuilt JNI bridge from the csukuangfj 1.27.1 release (same release
    #    Sherpa's libonnxruntime.so comes from -- guaranteed ABI match).
    with zipfile.ZipFile(download(ORT_ANDROID, work)) as archive:
        jni_name = f'jni/{ABI}/libonnxruntime4j_jni.so'
        if jni_name not in archive.namelist():
            raise RuntimeError(f'Missing {jni_name} in csukuangfj zip')
        jni_out = output / 'jni' / ABI
        jni_out.mkdir(parents=True)
        with archive.open(jni_name) as src_file, open(jni_out / 'libonnxruntime4j_jni.so', 'wb') as dst:
            shutil.copyfileobj(src_file, dst)
    print('Extracted prebuilt libonnxruntime4j_jni.so', flush=True)

    # 3. Sanity: the bridge must DT_NEEDED libonnxruntime.so (resolved to
    #    Sherpa's copy at runtime), and must NOT bundle its own copy.
    if (output / 'jni' / ABI / 'libonnxruntime.so').exists():
        raise RuntimeError('Module must not package a private libonnxruntime.so')

    stamp.write_text(ORT_VERSION + fingerprint)
    print(f'Vision ORT JNI ready: {output}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.output)
