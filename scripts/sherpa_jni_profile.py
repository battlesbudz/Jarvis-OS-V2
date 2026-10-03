"""Limit the pinned Sherpa JNI wrapper to the interfaces Jarvis actually uses.

This changes build reachability, not inference implementations or model formats.
OnlineStream is needed by SpeakerEmbeddingExtractor even though online ASR is not.
"""
from pathlib import Path
import re

JNI_SOURCES = (
    'common.cc', 'jni.cc', 'offline-recognizer.cc', 'offline-stream.cc',
    'online-stream.cc', 'speaker-embedding-extractor.cc', 'version.cc',
    'voice-activity-detector.cc',
)
UPSTREAM_SOURCES = set(JNI_SOURCES) | {
    'audio-tagging.cc', 'keyword-spotter.cc', 'offline-diacritization.cc',
    'offline-punctuation.cc', 'offline-speech-denoiser.cc',
    'online-speech-denoiser.cc', 'online-punctuation.cc', 'online-recognizer.cc',
    'speaker-embedding-manager.cc', 'speech-denoiser.cc',
    'spoken-language-identification.cc', 'wave-reader.cc', 'wave-writer.cc',
}
REQUIRED_CLASSES = ('OfflineRecognizer', 'OfflineStream', 'OnlineStream',
                    'OfflineTts', 'SpeakerEmbeddingExtractor', 'Vad')
REMOVED_CLASSES = ('AudioTagging', 'KeywordSpotter', 'OfflinePunctuation',
                   'OnlinePunctuation', 'OnlineRecognizer', 'OfflineSpeechDenoiser',
                   'OnlineSpeechDenoiser', 'SpeakerEmbeddingManager',
                   'SpokenLanguageIdentification')


def configure(source):
    path = Path(source) / 'sherpa-onnx/jni/CMakeLists.txt'
    text = path.read_text()
    match = re.search(r'set\(sources\s+(.*?)\n\)', text, re.S)
    if not match or set(match.group(1).split()) not in (UPSTREAM_SOURCES, set(JNI_SOURCES)):
        raise ValueError('Pinned Sherpa JNI source list changed; review the app profile')
    # TTS remains in the upstream conditional below this block.
    if 'offline-tts.cc' not in text:
        raise ValueError('Pinned Sherpa TTS wrapper is missing')
    for name in (*JNI_SOURCES, 'offline-tts.cc'):
        if not (path.parent / name).is_file():
            raise ValueError(f'Missing required JNI source: {name}')
    text = text[:match.start()] + 'set(sources\n  ' + '\n  '.join(JNI_SOURCES) + '\n)' + text[match.end():]
    path.write_text(text)
