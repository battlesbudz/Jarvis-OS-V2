#!/usr/bin/env python3
"""Verify Moonshine ASR and Sherpa TTS/VAD native packaging."""
import argparse
import hashlib
import re
from pathlib import Path
import subprocess
import tempfile
import zipfile


# Public Android platform libraries used by the bundled runtimes. The C++ STL
# is an app dependency, not a platform library, and must never be allowed here.
ANDROID_LIBRARIES = {
    'libandroid.so', 'libc.so', 'libdl.so', 'liblog.so', 'libm.so', 'libz.so',
    'libEGL.so', 'libGLESv2.so', 'libGLESv3.so', 'libOpenSLES.so',
    'libaaudio.so', 'libmediandk.so', 'libjnigraphics.so', 'libvulkan.so',
    'libnativewindow.so', 'libneuralnetworks.so',
}


def check_dependencies(metadata):
    for name, dynamic in metadata.items():
        needed = set(re.findall(r'\(NEEDED\).*?\[([^\]]+)\]', dynamic))
        missing = needed - metadata.keys() - ANDROID_LIBRARIES
        assert not missing, f'{name}: missing packaged native dependencies: {sorted(missing)}'


def check(apk):
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as temporary:
        names = archive.namelist()
        prefix = "lib/arm64-v8a/"
        required = ["libmoonshine.so", "libmoonshine-jni.so", "libms_ort_1232.so",
                    "libsherpa-onnx-jni.so", "libonnxruntime.so", "libmicrowakeword.so",
                    "libc++_shared.so"]
        for retired in ["libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so"]:
            assert prefix + retired not in names, f"Unused native wrapper packaged: {retired}"
        assert "assets/voice/silero_vad.onnx" in names, "Missing speech detector model"
        assert hashlib.sha256(archive.read("assets/microwakeword/hey_jarvis.tflite")).hexdigest() == "21a7976add39ee24ec96c63d96b7aaa18e24d1d9824b963e451da8feb4b78b77", "Wrong microWakeWord model"
        metadata = {}
        for name in required:
            assert names.count(prefix + name) == 1, f"Missing/duplicate {name}"
        for entry in names:
            if not entry.startswith(prefix) or not entry.endswith('.so'):
                continue
            name = entry.removeprefix(prefix)
            assert names.count(entry) == 1, f"Duplicate native library: {name}"
            path = Path(temporary) / name
            path.write_bytes(archive.read(prefix + name))
            metadata[name] = subprocess.check_output(["readelf", "-d", "-V", str(path)], text=True)
        check_dependencies(metadata)
        for name in ["libmoonshine.so", "libmoonshine-jni.so"]:
            assert "libms_ort_1232.so" in metadata[name], f"Missing Moonshine runtime dependency: {name}"
            assert "libonnxruntime.so" not in metadata[name], f"Moonshine still binds Sherpa's runtime: {name}"
        assert "Library soname: [libms_ort_1232.so]" in metadata["libms_ort_1232.so"]
        assert "VERS_1.23.2" in metadata["libms_ort_1232.so"]
        assert "Library soname: [libonnxruntime.so]" in metadata["libonnxruntime.so"]
        assert "VERS_1.27.1" in metadata["libonnxruntime.so"]
        assert "libms_ort_1232.so" not in metadata["libsherpa-onnx-jni.so"]
    print("ASR APK native dependency/version checks passed (separate matching ONNX runtimes).")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    check(parser.parse_args().apk)
