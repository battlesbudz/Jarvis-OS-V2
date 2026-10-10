#!/usr/bin/env python3
"""Build the ONNX Runtime 1.27.1 Java JNI bridge for the vision module.

The vision module (:phoneinference) needs the ORT Java API, but Microsoft never
published onnxruntime-android 1.27.1 to Maven Central. The APK already ships
Sherpa's libonnxruntime.so (1.27.1), so we build only the thin JNI bridge
(libonnxruntime4j_jni.so) from the v1.27.1 source tree and link it against
Sherpa's runtime. At runtime the bridge resolves libonnxruntime.so from the
app's native library directory -- this module packages NO private ORT copy.

This is the same pattern as the LiteRT streaming bridge: build the missing
piece from source instead of waiting for an upstream artifact.

Pinned inputs:
  ORT source:      microsoft/onnxruntime tag v1.27.1
                   (https://github.com/microsoft/onnxruntime/archive/refs/tags/v1.27.1.tar.gz)
  ORT Android libs: csukuangfj/onnxruntime-libs v1.27.1
                   (same zip + sha256 that scripts/build_sherpa.py uses;
                    provides the C API headers and the link-time libonnxruntime.so)

Outputs (under --output):
  classes.jar                          -- compiled ai.onnxruntime.* Java API (1.27.1)
  jni/arm64-v8a/libonnxruntime4j_jni.so -- JNI bridge, DT_NEEDED libonnxruntime.so

The bridge is linked against the csukuangfj 1.27.1 libonnxruntime.so so the
linker records the correct versioned symbol references (OrtGetApiBase@VERS_1.27.1),
matching what Sherpa packages. The .so is built with 16 KB page alignment to
satisfy the native 16 KB compatibility check.

Follows scripts/build_sherpa.py conventions: pinned downloads with sha256
where available, a stamp file to skip redundant rebuilds, NDK via --android-ndk.
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
ANDROID_PLATFORM = 29  # match scripts/build_sherpa.py


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


def find_jdk_include():
    """Locate the JDK include dir containing jni.h."""
    import os
    candidates = []
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        candidates.append(Path(java_home) / 'include')
    javac = shutil.which('javac')
    if javac:
        # <jdk>/bin/javac -> <jdk>/include
        candidates.append(Path(javac).resolve().parents[1] / 'include')
    for base in candidates:
        if (base / 'jni.h').is_file():
            return base
    raise RuntimeError('Could not find jni.h; set JAVA_HOME to a JDK')


def build(output, ndk):
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    ndk = Path(ndk)
    tc = ndk / 'build/cmake/android.toolchain.cmake'
    if not tc.is_file():
        raise RuntimeError(f'Android NDK not found at {ndk} (expected {tc})')

    # Stamp: rebuild when this script changes (mirrors build_sherpa.py).
    fingerprint = 'vision-ort-jni-v1-' + hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    stamp = output / 'build-stamp'
    if stamp.exists() and stamp.read_text() == ORT_VERSION + fingerprint:
        print('Vision ORT JNI up to date, skipping build.', flush=True)
        return
    shutil.rmtree(output, ignore_errors=True)
    output.mkdir(parents=True, exist_ok=True)

    work = output / 'work'
    work.mkdir(parents=True)

    # 1. ORT source tree (Java API sources + JNI C sources + C API headers).
    with tarfile.open(download(ORT_SOURCE, work)) as archive:
        archive.extractall(work, filter='data')
    src = work / f'onnxruntime-{ORT_VERSION}'
    version_file = src / 'VERSION_NUMBER'
    if not version_file.is_file() or version_file.read_text().strip() != ORT_VERSION:
        raise RuntimeError(f'Unexpected ORT source version in {version_file}')
    java_src = src / 'java/src/main/java'
    jni_src = src / 'java/src/main/native'
    ort_include = src / 'include'
    if not java_src.is_dir() or not jni_src.is_dir() or not (ort_include / 'onnxruntime').is_dir():
        raise RuntimeError('ORT source tree layout unexpected; pinned tag may have moved')

    # 2. csukuangfj Android libs (C API headers + link-time libonnxruntime.so).
    with zipfile.ZipFile(download(ORT_ANDROID, work)) as archive:
        archive.extractall(work / 'ort-android')
    ort_android = work / 'ort-android'
    ort_lib = ort_android / 'jni' / ABI / 'libonnxruntime.so'
    if not ort_lib.is_file():
        raise RuntimeError(f'Missing {ort_lib} in csukuangfj zip')

    # 3. Compile the Java API and generate JNI headers.
    classes = work / 'classes'
    classes.mkdir()
    jni_headers = work / 'jni-headers'
    jni_headers.mkdir()
    java_files = sorted(java_src.rglob('*.java'))
    if not java_files:
        raise RuntimeError('No Java sources found')
    javac = shutil.which('javac') or 'javac'
    print(f'Compiling {len(java_files)} Java sources', flush=True)
    subprocess.run([javac, '-d', str(classes), '-h', str(jni_headers),
                    *[str(f) for f in java_files]], check=True)
    jar = shutil.which('jar') or 'jar'
    subprocess.run([jar, 'cf', str(output / 'classes.jar'), '-C', str(classes), '.'], check=True)

    # 4. Minimal onnxruntime_config.h (generated by CMake in ORT's own build;
    #    the JNI only needs it to exist; ORT_VERSION is informational here).
    gen_include = work / 'generated-include'
    gen_include.mkdir()
    (gen_include / 'onnxruntime_config.h').write_text(
        '#pragma once\n#define ORT_VERSION "1.27.1"\n')

    # 5. Compile the JNI C sources with the NDK.
    clang = (ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
             / f'aarch64-linux-android{ANDROID_PLATFORM}-clang')
    if not clang.is_file():
        raise RuntimeError(f'Missing NDK clang: {clang}')
    jdk_include = find_jdk_include()
    c_files = sorted(jni_src.glob('*.c'))
    if not c_files:
        raise RuntimeError('No JNI C sources found')
    obj_dir = work / 'obj'
    obj_dir.mkdir()
    print(f'Compiling {len(c_files)} JNI sources for {ABI}', flush=True)
    for c in c_files:
        o = obj_dir / (c.stem + '.o')
        subprocess.run([
            str(clang),
            '-fPIC', '-O2',
            f'-I{ort_include}', f'-I{jni_headers}', f'-I{jdk_include}',
            f'-I{jdk_include}/linux', f'-I{jni_src}', f'-I{gen_include}',
            '-c', str(c), '-o', str(o),
        ], check=True)

    # 6. Link the JNI bridge against Sherpa's 1.27.1 runtime so the linker
    #    records the correct versioned symbols (OrtGetApiBase@VERS_1.27.1).
    #    libonnxruntime.so itself is NOT packaged here; Sherpa ships it.
    jni_out = output / 'jni' / ABI
    jni_out.mkdir(parents=True)
    subprocess.run([
        str(clang),
        '-shared', '-o', str(jni_out / 'libonnxruntime4j_jni.so'),
        *[str(o) for o in sorted(obj_dir.glob('*.o'))],
        f'-L{ort_lib.parent}', '-lonnxruntime',
        '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384',
    ], check=True)

    # 7. Sanity: the bridge must need libonnxruntime.so, not bundle it.
    readelf = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'
    needed = subprocess.run([str(readelf), '-d', str(jni_out / 'libonnxruntime4j_jni.so')],
                            capture_output=True, text=True, check=True).stdout
    if 'libonnxruntime.so' not in needed:
        raise RuntimeError('JNI bridge does not DT_NEEDED libonnxruntime.so')
    print('DT_NEEDED check passed.', flush=True)

    stamp.write_text(ORT_VERSION + fingerprint)
    print(f'Vision ORT JNI ready: {output}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--android-ndk', type=Path, required=True)
    args = parser.parse_args()
    build(args.output, args.android_ndk)
