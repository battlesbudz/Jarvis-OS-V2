#!/usr/bin/env python3
"""Native wake regression: pip install sherpa-onnx==1.13.7 numpy.
Pass extracted sherpa 2025-12-20 KWS and vits-piper-en_US-lessac-low dirs.
The TTS model generates test speech only; it is never included in the app.
Synthetic fixtures do not establish accuracy for a user's voice or phone routing.
"""
import argparse
from pathlib import Path
import tempfile
import numpy as np
import sherpa_onnx as so

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('kws', type=Path)
p.add_argument('tts', type=Path)
p.add_argument('--score', type=float, default=2.0)
p.add_argument('--threshold', type=float, default=.25)
p.add_argument('--paths', type=int, default=8)
a = p.parse_args()
tts = so.OfflineTts(so.OfflineTtsConfig(model=so.OfflineTtsModelConfig(
    vits=so.OfflineTtsVitsModelConfig(model=str(a.tts/'en_US-lessac-low.onnx'),
        tokens=str(a.tts/'tokens.txt'), data_dir=str(a.tts/'espeak-ng-data'), noise_scale=0.0, noise_scale_w=0.0), num_threads=2)))
assert tts.sample_rate == 16000
source = Path(__file__).resolve().parents[1]/'app/src/main/java/com/battlesbudz/jarvis/v2/voice/PassiveWakeListener.kt'
assert 'HH EY1 JH AA1 R V AH0 S @HEY_JARVIS' in source.read_text()
positives = ['Hey Jarvis.', 'Hey, Jarvis.', 'Hey Jarvis, what is my battery percentage?']
negatives = ['Hey Travis.', 'Hey Google.', 'Hey Charlie.', 'Hello there, how are you?',
             'Jarvis.', 'The weather is nice today.', 'Can you open Facebook?', 'Hey service.']
failures = []
with tempfile.TemporaryDirectory() as temporary:
    keywords = Path(temporary)/'keywords.txt'
    keywords.write_text('HH EY1 JH AA1 R V AH0 S @HEY_JARVIS\n')
    spot = so.KeywordSpotter(tokens=str(a.kws/'tokens.txt'),
        encoder=str(a.kws/'encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx'),
        decoder=str(a.kws/'decoder-epoch-13-avg-2-chunk-16-left-64.onnx'),
        joiner=str(a.kws/'joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx'),
        keywords_file=str(keywords), num_threads=1, keywords_score=a.score,
        keywords_threshold=a.threshold, num_trailing_blanks=2, max_active_paths=a.paths)
    rng = np.random.default_rng(42)
    total = 0
    for phrase in positives + negatives:
        for speed in [.85, 1.0, 1.15]:
            spoken = np.asarray(tts.generate(phrase, sid=0, speed=speed).samples, dtype=np.float32)
            for gain in [1.0, .15]:
                # Continuous ambient input before and after speech, in Android-sized chunks.
                audio = np.concatenate([np.zeros(16000), spoken*gain, np.zeros(32000)])
                audio = (audio + rng.normal(0, .002, len(audio))).astype(np.float32)
                stream = spot.create_stream()
                found = False
                for offset in range(0, len(audio), 1600):
                    stream.accept_waveform(16000, audio[offset:offset+1600])
                    while spot.is_ready(stream):
                        spot.decode_stream(stream)
                        if spot.get_result(stream):
                            found = True
                            spot.reset_stream(stream)
                total += 1
                if found != (phrase in positives):
                    failures.append((phrase, speed, gain, found))
    # An indefinitely armed stream must still detect after a long idle period.
    stream = spot.create_stream()
    for _ in range(600):
        stream.accept_waveform(16000, rng.normal(0, .002, 1600).astype(np.float32))
        while spot.is_ready(stream):
            spot.decode_stream(stream)
            assert not spot.get_result(stream), 'Ambient noise triggered a wake'
    speech = np.asarray(tts.generate('Hey Jarvis.', sid=0, speed=1).samples, dtype=np.float32)
    stream.accept_waveform(16000, np.concatenate([speech, np.zeros(32000)]).astype(np.float32))
    found = False
    while spot.is_ready(stream):
        spot.decode_stream(stream)
        found = found or bool(spot.get_result(stream))
    assert found, 'Wake failed after 60 seconds of ambient input'
print(f'{total-len(failures)}/{total} synthetic phrase/speed/volume cases passed; long-idle test passed')
for failure in failures:
    print('FAIL', failure)
# Require normal-volume wake cases and all negatives. Report quiet/noisy recall
# separately: acoustic detection cannot guarantee every stress case.
assert not [f for f in failures if f[3] or f[2] == 1.0], 'Normal-volume wake or false-positive regression'
