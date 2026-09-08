# Story recall and TTS comparison

## User test

End any call, then tap **Voice: …**. Select Kokoro original, Kokoro INT8, or Piper Lessac for the next call. **Test selected voice** reads a fixed short reply, paragraph and story. **Test all three** runs the same samples sequentially with each engine without changing the selected call voice. **Stop benchmark** cancels playback and retains completed results; native synthesis may take a moment to retire before controls become available again.

First use downloads INT8 Kokoro (~103 MB) or Piper (~67 MB), with a pinned SHA-256 and byte count. Extracted model lengths and essential phonemizer files are checked. Bundles install in separate directories; original Kokoro remains available. Downloads and extraction are cancellable for the alternatives. The original model retains its existing installer. All models operate locally after installation.

Each benchmark sample creates a fresh native engine. All engines use the same 2–4 thread limit, non-callback JNI generation, fixed phrase boundaries, and normal 1.0 playback speed. Model loading and queue waits are reported separately from synthesis. Downloads, Gemma generation and microphone recognition are excluded. The benchmark does not unload Gemma weights already resident in memory; it prevents competing model operations. These are in-app synthesis/playback measurements, not laboratory isolated hardware timings. Try repeated runs in similar temperature/power conditions and listen to pronunciation and voice quality.

Ordinary calls record the selected engine under `source=voice-call`, using the existing adaptive phrase boundaries and at most 10% slower playback. Do not mix these with `source=benchmark-v1` when ranking models.

## Results

The last 40 TTS measurements survive calls and app restarts. **Copy diagnostics** includes them alongside ASR comparisons. Records include engine/model/speaker, sample ID, input SHA-256, model load, first-phrase synthesis latency, total synthesis, raw audio duration, RTF, playback pace, estimated supply gaps, queue wait, thread count, completion and error status. Terminal AudioTrack underruns are explicitly included in the underrun count and should not be interpreted alone as speech gaps.

Compare completed records with matching sample ID and text hash. RTF is synthesis time divided by raw audio duration; below one means synthesis kept up with normal speech. Slower playback never improves the reported RTF. Incomplete/error runs remain visible and must not be ranked as successful fast results. No model is automatically deleted or selected as a winner.

## Models

- Baseline: existing kokoro-en-v0_19, speaker 10.
- INT8: https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2 — same model family and speaker 10, 103248205 archive bytes; SHA-256 `c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd`.
- Piper: https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2 — Lessac medium English, speaker 0, 67230653 archive bytes; SHA-256 `9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e`.

Both alternatives use the already bundled Sherpa 1.13.7 runtime; no additional native library or Piper engine package is introduced. The archives retain their upstream license/model-card files. INT8 Kokoro's LICENSE and Piper's MODEL_CARD remain with the downloaded model, including the Lessac dataset attribution/license link. Sherpa model configuration documentation: https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/index.html

## Dialogue behavior

A direct story request gets a prompt instruction to tell it immediately, choosing unspecified details. A short acceptance of an unfulfilled story request carries a specific continuation instruction into both speculative and final generation. A completed story, a declined request, or an intervening new user task invalidates that continuation. This changes guidance and contextual routing; it is not an extra inference pass or a guarantee of model compliance.

References to a recent story's character name, explicit requests to repeat the previous reply, and questions about that story use conversation context instead of external factual lookup. Explicit search requests still take priority, and unrelated factual questions retain their grounding path. Saved histories supply the same context when resumed. Phone tool targets and permissions are still checked against the actual current request.

## Validation

Regression tests cover pending-story acceptance, repeated acceptance after an unhelpful response, negative/new-task controls, completed-story protection, story-name recall, explicit search and unrelated factual grounding. Comparison-store tests cover precision/model identity, persistence, retention, raw RTF and incomplete/error labels. Both exact model archives were extracted and generated nonempty audio through Sherpa 1.13.7 in a host smoke test. Host timings are not device benchmark results. Android CI compiles both variants, runs the full unit suite and checks native packaging/signing. Device acceptance: tell an open-ended story, ask its character's name, resume the history and ask again; run the TTS comparison and share diagnostics.
