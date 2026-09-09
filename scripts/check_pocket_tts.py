#!/usr/bin/env python3
"""Native Pocket smoke test. pip install sherpa-onnx==1.13.7 numpy soundfile"""
import argparse
import hashlib
from pathlib import Path
import time
import numpy as np
import soundfile as sf
import sherpa_onnx

parser = argparse.ArgumentParser()
parser.add_argument('model_dir', type=Path)
parser.add_argument('paul_wav', type=Path)
args = parser.parse_args()
assert hashlib.sha256(args.paul_wav.read_bytes()).hexdigest() == '7aba504fe0b3b16478b69eb27ce6007e3cb42b0c1915b5f1c6a6024ae37d679b'
p = args.model_dir
model = sherpa_onnx.OfflineTtsPocketModelConfig(
    lm_flow=str(p/'lm_flow.int8.onnx'), lm_main=str(p/'lm_main.int8.onnx'),
    encoder=str(p/'encoder.onnx'), decoder=str(p/'decoder.int8.onnx'),
    text_conditioner=str(p/'text_conditioner.onnx'), vocab_json=str(p/'vocab.json'),
    token_scores_json=str(p/'token_scores.json'))
tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
    model=sherpa_onnx.OfflineTtsModelConfig(pocket=model, num_threads=2, provider='cpu')))
reference, rate = sf.read(args.paul_wav, dtype='float32')
gen = sherpa_onnx.GenerationConfig()
gen.reference_audio = reference
gen.reference_sample_rate = rate
gen.silence_scale = 1.0
gen.num_steps = 5
base_extra = {'temperature': '0.7', 'chunk_size': '15', 'max_reference_audio_len': '15'}
for text in ['Um, one second.', 'One second.',
             'Hello, I am Jarvis. This is the Paul voice speaking locally on your device.']:
    gen.extra = dict(base_extra, **({'max_frames': '50', 'seed': '42'} if len(text) < 20 else {}))
    chunks = []
    started = time.monotonic()
    def callback(samples, *unused):
        if not chunks:
            print(f'{text!r}: first callback {time.monotonic() - started:.3f}s (host only)', flush=True)
        chunks.append(np.array(samples))
        return 1
    audio = tts.generate(text, gen, callback=callback)
    pcm = np.asarray(audio.samples)
    assert len(pcm) and np.isfinite(pcm).all()
    np.testing.assert_array_equal(np.concatenate(chunks), pcm)
    if len(text) < 20:
        assert len(pcm) <= audio.sample_rate * 4
    print(f'{len(chunks)} chunks, {len(pcm)/audio.sample_rate:.2f}s audio, no duplicate/missing PCM', flush=True)

gen.extra = base_extra
calls = []
def cancel(samples, *unused):
    calls.append(len(samples))
    return 0
tts.generate('This is a cancellation test with enough words for several chunks of audio.', gen, callback=cancel)
assert len(calls) == 1, f'Native ignored callback cancellation: {calls}'
print('Paul native synthesis, filler budget, PCM continuity and callback cancellation passed.')
