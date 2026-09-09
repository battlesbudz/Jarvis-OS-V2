# Pocket TTS — Paul

Pocket is an optional voice in Voice Call → Voice settings → Voice. Select **Pocket TTS — Paul**, then start a Voice Call or run the voice comparison. Kokoro remains the default and existing Miro selections are preserved. Custom voice creation is not part of this change.

## Download and identity

The first use downloads approximately 99 MB (98,336,520-byte INT8 archive plus 717,182-byte Paul reference) outside the APK. Allow approximately 310 MB free during installation for archive plus extracted models. After installation, synthesis and Paul's voice conditioning work offline. Each download has a pinned SHA-256, expected size, temporary staging, cancellation checks and atomic completion. Demo reference voices are not installed.

Model: `sherpa-onnx-pocket-tts-int8-2026-01-26`, seven ONNX/token files. Paul is the official Kyutai label for `vctk/p259_023_enhanced.wav`, speaker p259, pinned at revision `a0de156151266cf8eb27ac8f27312f7aff2ef7b8`. His 32 kHz recording is loaded once per TTS session and resampled by Sherpa. Native reference embedding caching is limited to one voice. See `PocketVoiceSpec.kt` for manifests and checksums.

## Playback

Sherpa 1.13.7 already contains Pocket. Default configuration is CPU, two threads, five generation steps, temperature 0.7, decoder chunks of 15 latent frames, reference limit 15 seconds. Benchmark thread overrides remain available. The packaged native implementation generates a sentence's latents before decoding in chunks; this is not a claim of 200 ms startup on Android.

Confirmed answer audio uses `generateWithConfigAndCallback` through a concrete, kept Java callback with JNI descriptor `([F)Ljava/lang/Integer;`. Exceptions are retained and thrown only after native returns. Each callback is enqueued once through the bounded PCM queue; the full returned utterance is checked but never played a second time. Cancellation unblocks the queue before joining/releasing the sole native owner. Pocket uses normal playback speed and no artificial startup buffer. Its silence scale is 1 so the returned and callback PCM agree. Existing Kokoro/Miro generation stays on the non-callback path.

Prepared openings and cached fillers use full-utterance generation. Pocket's initial cue is **“Um, one second.”**: isolated “Um.” produced a pathological 40-second clip in native testing. Filler generation is capped at 50 latent frames (four seconds), with seed 42, and oversized PCM is rejected before caching or playing. A cache failure unblocks the answer. A fresh installation still needs model/reference loading and first filler generation before a cache exists; no sub-three-second cold-start guarantee is made. Existing interruption keywords, live input transcription and speech PCM export remain available.

Caption timing is estimated, not word-aligned: for incremental Pocket audio, phrase text is attached to its first PCM chunk. Diagnostics add first-callback latency, chunk/frame counts, PCM peak and completed synthesis timing excluding queue wait. The exported WAV contains accepted synthesized PCM; Android time stretching, speaker acoustics and playback gaps are not embedded.

## Verification

Test the exact archive and Paul reference with `scripts/check_pocket_tts.py MODEL_DIR PAUL_WAV` after installing `sherpa-onnx==1.13.7`, numpy and soundfile. This synthesizes fillers and a sentence, checks finite PCM and callback/full-output agreement, and tests cancellation. Host timing does not establish Fold 6 performance. Unit tests check voice selection/configuration, the callback ABI and exception containment, and acknowledgement ordering. APK packaging checks validate that the kept callback method survives release shrinking.

Sources and attribution: [Pocket TTS](https://github.com/kyutai-labs/pocket-tts), [Sherpa model package](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models), [ONNX conversion](https://huggingface.co/KevinAHM/pocket-tts-onnx), [Paul reference](https://huggingface.co/kyutai/tts-voices/blob/main/vctk/p259_023_enhanced.wav). CC BY 4.0 license and detailed attribution are bundled in `assets/licenses/pocket-tts-*`. The downloaded archive's stale non-commercial README conflicts with its shipped license/current upstream card; both original archive files are preserved, rather than silently relabeling them.
