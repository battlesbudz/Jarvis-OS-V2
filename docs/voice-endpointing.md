# Voice turn endpointing

Audio PR2 uses bundled Silero VAD through Sherpa-ONNX 1.13.7. RMS and peak are diagnostics only: the previous fixed energy threshold could classify continuous room noise as speech and prevent a turn from ending.

- Input: 16 kHz mono signed PCM16, framed into 512 samples (32 ms).
- Speech: three consecutive frames with probability at least 0.5 (96 ms).
- End: 1.2 seconds since the last chunk containing confirmed speech. Model classification latency is additional to this pause.
- First-turn no-speech window: six seconds. An established call can wait indefinitely for the next spoken turn.
- Idle audio: bounded 600 ms pre-roll, preserving speech onset without accumulating an entire idle call.
- Active audio: bounded 25 seconds; a continuously positive detector is segmented at that limit. Raw microphone audio stays in memory.
- No detected speech: skip Gemma, regardless of how loud the noise is.
- The model runs on one CPU thread off the UI thread. The microphone reader owns recorder release; capture waits for it before allowing a subsequent turn to acquire the mic.

## Bundled model

`app/src/main/assets/voice/silero_vad.onnx` is the k2-fsa 16 kHz Silero VAD v4 export:

- Source: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
- Size: 643854 bytes
- SHA-256: `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`
- Model license: MIT; included next to the model as `SILERO_LICENSE.txt`.
- Integration API: https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/sherpa-onnx/kotlin-api/Vad.kt

The APK contains the model, so calls require no extra setup or network download.

## Validation

The focused Kotlin suite covers PCM normalization and odd chunk boundaries, speech confirmation, rejection of isolated positive frames, natural pauses and resumed speech, loud nonspeech decisions, initial silence, long idle pre-roll, the 25-second cap, detector failure, and cancellation/release.

A host ONNX Runtime check of the bundled model used 15 seconds each of seeded white noise (PCM RMS approximately 3300) and fan-like noise, then the first 10 seconds of the public Silero speech test recording followed by five seconds of loud white noise. Both standalone noise cases produced zero confirmed speech frames. The mixed clip produced 187 confirmed speech frames; its final confirmed frame ended at 10.368 seconds, giving a 1.2-second endpoint at 11.568 seconds. Audio chunk scheduling can add up to a chunk interval on-device.

Speech fixture source (not bundled): https://github.com/snakers4/silero-vad/blob/master/tests/data/test.wav

Host fixtures do not reproduce the user's room. Phone acceptance: ask a question, stop speaking while normal background sound remains, hear the reply, then ask a second question after automatic rearming. Copy diagnostics if the turn remains stuck; `capture_level` now includes `vad=silero` and its speech probability.
