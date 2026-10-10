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

Pinned inputs:
  ORT Java sources: microsoft/onnxruntime tag v1.27.1, java/src/main/java
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
import urllib.request
import zipfile

ORT_VERSION = '1.27.1'
ORT_RAW_BASE = 'https://raw.githubusercontent.com/microsoft/onnxruntime/v1.27.1/java/src/main/java/ai/onnxruntime'
# Same pinned zip + hash as scripts/build_sherpa.py (Sherpa's ORT 1.27.1).
ORT_ANDROID = ('https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.27.1/onnxruntime-android-1.27.1.zip',
               'defade26209f72cf4fa9769b18052c842833d6bef12924595d26f03b995548ca')

# The Java sources in ai.onnxruntime (from the v1.27.1 tree).
JAVA_SOURCES = [
    'MapInfo.java', 'NodeInfo.java', 'OnnxJavaType.java', 'OnnxMap.java',
    'OnnxModelMetadata.java', 'OnnxRuntime.java', 'OnnxSequence.java',
    'OnnxSparseTensor.java', 'OnnxTensor.java', 'OnnxTensorLike.java',
    'OnnxValue.java', 'OrtAllocator.java', 'OrtEnvironment.java',
    'OrtEpDevice.java', 'OrtException.java', 'OrtFlags.java',
    'OrtHardwareDevice.java', 'OrtLoggingLevel.java', 'OrtLoraAdapter.java',
    'OrtModelCompilationOptions.java', 'OrtProvider.java', 'OrtProviderOptions.java',
    'OrtSession.java', 'OrtTrainingSession.java', 'OrtUtil.java',
    'SequenceInfo.java', 'TensorInfo.java', 'ValueInfo.java', 'package-info.java',
    'providers/CoreMLFlags.java', 'providers/NNAPIFlags.java',
    'providers/OrtCUDAProviderOptions.java', 'providers/OrtTensorRTProviderOptions.java',
    'providers/StringConfigProviderOptions.java', 'providers/package-info.java',
]

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
    fingerprint = 'vision-ort-jni-v3-' + hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    stamp = output / 'build-stamp'
    if stamp.exists() and stamp.read_text() == ORT_VERSION + fingerprint:
        print('Vision ORT JNI up to date, skipping build.', flush=True)
        return
    shutil.rmtree(output, ignore_errors=True)
    output.mkdir(parents=True, exist_ok=True)

    work = output / 'work'
    work.mkdir(parents=True)

    # 1. Fetch the Java API sources individually (fast, no 272 MB tarball).
    java_dir = work / 'java/ai/onnxruntime'
    java_dir.mkdir(parents=True)
    (java_dir / 'providers').mkdir(exist_ok=True)
    print(f'Fetching {len(JAVA_SOURCES)} Java sources from v{ORT_VERSION}', flush=True)
    for name in JAVA_SOURCES:
        url = f'{ORT_RAW_BASE}/{name}'
        dest = java_dir / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(url, dest)
        if dest.stat().st_size == 0:
            raise RuntimeError(f'Empty download: {url}')

    # 2. Compile the Java API.
    classes = work / 'classes'
    classes.mkdir()
    java_files = sorted(java_dir.rglob('*.java'))
    print(f'Compiling {len(java_files)} Java sources', flush=True)
    javac = shutil.which('javac')
    if not javac:
        raise RuntimeError('javac not found on PATH; JDK required')
    subprocess.run([javac, '-d', str(classes), *[str(f) for f in java_files]],
                   check=True, capture_output=True, text=True)
    jar = shutil.which('jar')
    if not jar:
        raise RuntimeError('jar not found on PATH; JDK required')
    subprocess.run([jar, 'cf', str(output / 'classes.jar'), '-C', str(classes), '.'],
                   check=True, capture_output=True, text=True)
    print('classes.jar built', flush=True)

    # 3. Prebuilt JNI bridge from the csukuangfj 1.27.1 release (same release
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

    # 4. Sanity: the module must NOT package a private libonnxruntime.so.
    if (output / 'jni' / ABI / 'libonnxruntime.so').exists():
        raise RuntimeError('Module must not package a private libonnxruntime.so')

    stamp.write_text(ORT_VERSION + fingerprint)
    print(f'Vision ORT JNI ready: {output}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        build(args.output)
    except subprocess.CalledProcessError as e:
        print(f'Command failed: {" ".join(e.cmd)}', flush=True)
        print(f'stdout: {e.stdout}', flush=True)
        print(f'stderr: {e.stderr}', flush=True)
        raise
