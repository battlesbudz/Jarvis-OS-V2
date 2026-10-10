#!/usr/bin/env python3
"""Namespace the pinned Moonshine Android SDK's ORT; preserve all ELF offsets/alignment.

Sherpa and Moonshine need different versioned OrtGetApiBase symbols. Neither
pickFirst nor replacing Moonshine's runtime with Sherpa's is ABI-compatible.
Only the equal-length library name in .dynstr changes, including references
from DT_NEEDED, DT_SONAME and the GNU version tables. Code and symbols stay intact.
"""
import argparse
from pathlib import Path
import struct
import zipfile

OLD = b"libonnxruntime.so\0"
NEW = b"libms_ort_1232.so\0"


def namespace_runtime(data):
    assert len(OLD) == len(NEW)
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
            raise ValueError("Pinned SDK changed: expected exactly one runtime name in .dynstr")
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
        for name in ("libmoonshine-jni.so", "libmoonshine.so", "libonnxruntime.so"):
            data = namespace_runtime(archive.read("jni/arm64-v8a/" + name))
            target = NEW[:-1].decode() if name == "libonnxruntime.so" else name
            (native / target).write_bytes(data)
    # Remove output left by any previous extraction strategy.
    (native / "libonnxruntime.so").unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("aar")
    parser.add_argument("output")
    args = parser.parse_args()
    prepare(args.aar, args.output)
