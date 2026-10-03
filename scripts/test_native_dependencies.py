"""Regression checks for APK native dependency validation."""
import unittest

from check_asr_apk import check_dependencies


def needed(*libraries):
    return '\n'.join(f' 0x0000000000000001 (NEEDED) Shared library: [{name}]'
                     for name in libraries)


class NativeDependencyTests(unittest.TestCase):
    def test_missing_cpp_runtime_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, r'libsherpa-onnx-jni.*libc\+\+_shared'):
            check_dependencies({'libsherpa-onnx-jni.so': needed('libc++_shared.so', 'liblog.so')})

    def test_bundled_cpp_runtime_and_android_dependencies_pass(self):
        check_dependencies({
            'libsherpa-onnx-jni.so': needed('libc++_shared.so', 'liblog.so'),
            'libc++_shared.so': needed('libc.so', 'libm.so', 'libdl.so'),
        })

    def test_missing_transitive_dependency_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'libhelper.so'):
            check_dependencies({
                'libsherpa-onnx-jni.so': needed('libc++_shared.so'),
                'libc++_shared.so': needed('libhelper.so'),
            })

    def test_private_platform_library_is_not_assumed_available(self):
        with self.assertRaisesRegex(AssertionError, 'libprivate.so'):
            check_dependencies({'libvoice.so': needed('libprivate.so')})


if __name__ == '__main__':
    unittest.main()
