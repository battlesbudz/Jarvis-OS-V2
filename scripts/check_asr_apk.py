#!/usr/bin/env python3
"""Fail packaging if either recognizer would bind to the other SDK's ORT."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import zipfile


def check(apk):
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as temporary:
        names = archive.namelist()
        prefix = "lib/arm64-v8a/"
        required = ["libmoonshine.so", "libmoonshine-jni.so", "libms_ort_1232.so",
                    "libsherpa-onnx-jni.so", "libonnxruntime.so"]
        metadata = {}
        for name in required:
            assert names.count(prefix + name) == 1, f"Missing/duplicate {name}"
            path = Path(temporary) / name
            path.write_bytes(archive.read(prefix + name))
            metadata[name] = subprocess.check_output(["readelf", "-d", "-V", str(path)], text=True)
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
