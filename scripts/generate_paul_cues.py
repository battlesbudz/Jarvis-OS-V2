#!/usr/bin/env python3
"""Generate pinned neutral cues offline; never run during APK build or a phone call.
Requires sherpa-onnx==1.13.7, numpy, soundfile. Model/reference hashes match PocketVoiceSpec.
"""
import argparse
import hashlib
from pathlib import Path
import wave
import numpy as np
import soundfile as sf
import sherpa_onnx

parser = argparse.ArgumentParser()
parser.add_argument('model_dir', type=Path)
parser.add_argument('reference', type=Path)
parser.add_argument('output', type=Path)
a = parser.parse_args()
assert hashlib.sha256(a.reference.read_bytes()).hexdigest() == '7aba504fe0b3b16478b69eb27ce6007e3cb42b0c1915b5f1c6a6024ae37d679b'
p = a.model_dir
model = sherpa_onnx.OfflineTtsPocketModelConfig(
    lm_flow=str(p/'lm_flow.int8.onnx'), lm_main=str(p/'lm_main.int8.onnx'),
    encoder=str(p/'encoder.onnx'), decoder=str(p/'decoder.int8.onnx'),
    text_conditioner=str(p/'text_conditioner.onnx'), vocab_json=str(p/'vocab.json'),
    token_scores_json=str(p/'token_scores.json'))
tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
    model=sherpa_onnx.OfflineTtsModelConfig(pocket=model, num_threads=2, provider='cpu')))
reference, rate = sf.read(a.reference, dtype='float32')
gen = sherpa_onnx.GenerationConfig()
gen.reference_audio = reference
gen.reference_sample_rate = rate
gen.silence_scale = 1.0
gen.num_steps = 5
gen.extra = {'temperature': '0.7', 'chunk_size': '15', 'max_reference_audio_len': '15',
             'seed': '42', 'min_char_in_sentence': '240', 'max_char_in_sentence': '240'}
a.output.mkdir(parents=True, exist_ok=True)
for name, text in [('paul-one-moment-v2.wav', 'One moment please, sir.'),
                   ('paul-bear-with-me-v2.wav', 'Bear with me, sir.')]:
    audio = tts.generate('. ' + text, gen)
    pcm = np.asarray(audio.samples, dtype=np.float64)
    assert audio.sample_rate == 24000 and np.isfinite(pcm).all()
    # Keep original pacing; trim only edge silence, then add gentle silent edges.
    audible = np.flatnonzero(np.abs(pcm) >= 64 / 32767)
    assert len(audible)
    pcm = pcm[max(0, audible[0]-480):min(len(pcm), audible[-1]+1201)].copy()
    gain = min(0.10 / np.sqrt(np.mean(pcm*pcm)), 0.90 / np.max(np.abs(pcm)))
    pcm *= gain
    fade = min(240, len(pcm)//2)
    pcm[:fade] *= np.linspace(0, 1, fade)
    pcm[-fade:] *= np.linspace(1, 0, fade)
    pcm = np.concatenate([np.zeros(480), pcm, np.zeros(960)])
    pcm = (np.clip(pcm, -1, 1) * 32767).astype('<i2')
    assert 24000 <= len(pcm) <= 24000*4 and max(abs(pcm.astype(np.int32))) < 32767
    target = a.output/name
    with wave.open(str(target), 'wb') as out:
        out.setnchannels(1); out.setsampwidth(2); out.setframerate(24000); out.writeframes(pcm.tobytes())
    print(name, repr(text), 'frames', len(pcm), 'sha256', hashlib.sha256(target.read_bytes()).hexdigest(), flush=True)
