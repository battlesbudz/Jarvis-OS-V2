#!/usr/bin/env python3
"""Namespace the vision module's private ONNX Runtime copy.

The app already ships sherpa's libonnxruntime.so (1.27.1). The vision module
needs ORT 1.22.0's Java API, whose JNI bridge expects versioned OrtGetApiBase
symbols that sherpa's runtime does not provide. Same situation as Moonshine:
neither pickFirst nor swapping runtimes is ABI-compatible (see
prepare_moonshine_sdk.py).

This script extracts the pinned onnxruntime-android AAR, renames its
libonnxruntime.so to libvi_ort_1220.so via an equal-length .dynstr patch
(ELF offsets and alignment preserved), rewrites the JNI bridge's DT_NEEDED
to match, and writes classes.jar plus the namespaced libs to the output dir.
Code and symbols are untouched; only the library name changes.
"""
import argparse
from pathlib import Path
import struct
import zipfile

OLD = b"libonnxruntime.so\0"
NEW = b"libvi_ort_1220.so\0"
assert len(OLD) == len(NEW), "replacement name must be equal length"


def namespace_runtime(data):
    if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("Expected an ARM64 little-endian ELF")
    table = struct.unpack_from("<Q", data, 40)[0]
    size, count, names_index = struct.unpack_from("<HHH", data, 58)

    def section(index):
        return struct.unpack_from("<IIQQQQIIQQ", data, table + size * index)

    names = section(names_index)
    strings = data[names[4]:names[4] + names[5]]
    for i in range(count):
        entry = section(i)
        name = strings[entry[0]:].split(b"\0", 1)[0]
        if name != b".dynstr":
            continue
        start, length = entry[4:6]
        dynamic_strings = data[start:start + length]
        if dynamic_strings.count(OLD) != 1:
            raise ValueError("Pinned AAR changed: expected exactly one runtime name in .dynstr")
        patched = data[:start] + dynamic_strings.replace(OLD, NEW) + data[start + length:]
        assert len(patched) == len(data)
        return patched
    raise ValueError("Missing ELF dynamic string table")


def prepare(aar, output):
    output = Path(output)
    native = output / "jni/arm64-v8a"
    native.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(aar) as archive:
        (output / "classes.jar").write_bytes(archive.read("classes.jar"))
        for name in ("libonnxruntime.so", "libonnxruntime4j_jni.so"):
            data = namespace_runtime(archive.read("jni/arm64-v8a/" + name))
            target = NEW[:-1].decode() if name == "libonnxruntime.so" else name
            (native / target).write_bytes(data)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("aar")
    parser.add_argument("output")
    args = parser.parse_args()
    prepare(args.aar, args.output)
