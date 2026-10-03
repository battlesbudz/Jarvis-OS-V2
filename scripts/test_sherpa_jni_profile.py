import tempfile
import unittest
from pathlib import Path
from sherpa_jni_profile import configure, JNI_SOURCES, UPSTREAM_SOURCES


class SherpaProfileTest(unittest.TestCase):
    def test_retain_speaker_stream_and_tts_but_drop_unused_wrappers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jni = root / 'sherpa-onnx/jni'
            jni.mkdir(parents=True)
            for name in (*UPSTREAM_SOURCES, 'offline-tts.cc'):
                (jni / name).touch()
            path = jni / 'CMakeLists.txt'
            path.write_text('set(sources\n  ' + '\n  '.join(sorted(UPSTREAM_SOURCES)) + '\n)\nif(SHERPA_ONNX_ENABLE_TTS)\nlist(APPEND sources offline-tts.cc)\nendif()\n')
            configure(root)
            result = path.read_text()
            self.assertIn('online-stream.cc', result)
            self.assertIn('offline-tts.cc', result)
            self.assertNotIn('online-recognizer.cc', result)
            self.assertNotIn('keyword-spotter.cc', result)
            configure(root)
            self.assertEqual(result, path.read_text())
            path.write_text(result.replace('common.cc', 'unknown.cc'))
            with self.assertRaises(ValueError):
                configure(root)
