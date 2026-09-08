# Story recall and TTS comparison

## User test

End any call, then tap **Voice: …**. Select Kokoro original, Piper Lessac (US), Piper Alan (British), or Piper Ryan High (US) for the next call. **Test selected voice** reads a fixed short reply, paragraph and story. **Test all voices** runs the same samples sequentially with each engine without changing the selected call voice. **Stop benchmark** cancels playback and retains completed results; native synthesis may take a moment to retire before controls become available again.

First use downloads Ryan High (~116 MB) or Lessac/Alan (~67 MB each), with a pinned SHA-256 and byte count. Extracted model lengths and essential phonemizer files are checked. Bundles install in separate directories; original Kokoro remains available. Downloads and extraction are cancellable for the alternatives. The original model retains its existing installer. All models operate locally after installation.

Each benchmark sample creates a fresh native engine. All engines use the same 2–4 thread limit, non-callback JNI generation, fixed phrase boundaries, and normal 1.0 playback speed. Model loading and queue waits are reported separately from synthesis. Downloads, Gemma generation and microphone recognition are excluded. The benchmark does not unload Gemma weights already resident in memory; it prevents competing model operations. These are in-app synthesis/playback measurements, not laboratory isolated hardware timings. Try repeated runs in similar temperature/power conditions and listen to pronunciation and voice quality.

Ordinary calls record the selected engine under `source=voice-call`, using the existing adaptive phrase boundaries and at most 10% slower playback. Do not mix these with `source=benchmark-v1` when ranking models.

## Results

The last 40 TTS measurements survive calls and app restarts. **Copy diagnostics** includes them alongside ASR comparisons. Records include engine/model/speaker, sample ID, input SHA-256, model load, first-phrase synthesis latency, total synthesis, raw audio duration, RTF, playback pace, estimated supply gaps, queue wait, thread count, completion and error status. Terminal AudioTrack underruns are explicitly included in the underrun count and should not be interpreted alone as speech gaps.

Compare completed records with matching sample ID and text hash. RTF is synthesis time divided by raw audio duration; below one means synthesis kept up with normal speech. Slower playback never improves the reported RTF. Incomplete/error runs remain visible and must not be ranked as successful fast results. No model is automatically deleted or selected as a winner.

## Models

- Baseline: existing kokoro-en-v0_19, speaker 10.
- Piper: https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2 — Lessac medium English, speaker 0, 67230653 archive bytes; SHA-256 `9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e`.

All Piper alternatives use the already bundled Sherpa 1.13.7 runtime; no additional native library or Piper engine package is introduced. The archives retain their upstream license/model-card files. Piper's MODEL_CARD remains with each downloaded model, including dataset attribution and license links. Sherpa model configuration documentation: https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/index.html

## Dialogue behavior

A direct story request gets a prompt instruction to tell it immediately, choosing unspecified details. A short acceptance of an unfulfilled story request carries a specific continuation instruction into both speculative and final generation. A completed story, a declined request, or an intervening new user task invalidates that continuation. This changes guidance and contextual routing; it is not an extra inference pass or a guarantee of model compliance.

References to a recent story's character name, explicit requests to repeat the previous reply, and questions about that story use conversation context instead of external factual lookup. Explicit search requests still take priority, and unrelated factual questions retain their grounding path. Saved histories supply the same context when resumed. Phone tool targets and permissions are still checked against the actual current request.

## Validation

Regression tests cover pending-story acceptance, repeated acceptance after an unhelpful response, negative/new-task controls, completed-story protection, story-name recall, explicit search and unrelated factual grounding. Comparison-store tests cover precision/model identity, persistence, retention, raw RTF and incomplete/error labels. The installed alternatives have been extracted and generated nonempty audio through Sherpa 1.13.7 in host smoke tests. Host timings are not device benchmark results. Android CI compiles both variants, runs the full unit suite and checks native packaging/signing. Device acceptance: tell an open-ended story, ask its character's name, resume the history and ask again; run the TTS comparison and share diagnostics.

## British voice and direct copying

The benchmark dialog now has a persistent **Copy diagnostics** action beside Done. It copies the current stored results (including earlier runs) and the current status directly to the Android clipboard, with a copied confirmation. It remains available during a benchmark; running samples appear only once their session closes. No new call is required to export benchmark results.

Piper Alan medium is an additional British English option, keeping Lessac and original Kokoro available. Alan uses the same VITS path and default medium-model inference configuration; it is a different voice/accent, not a guaranteed fidelity upgrade. Its model card says it was fine-tuned from Lessac medium. Model card: https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_GB/alan/medium/MODEL_CARD

Archive: https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-alan-medium.tar.bz2
Archive bytes: 67220121; SHA-256: `a48d4017da0f77668b27bed63fe6e04dd64c6397e1fadad4f460efb0ef7c9012`; extracted ONNX bytes: 63201430. Model card and dataset attribution remain in the installed bundle. Existing saved selection and historical results keep their IDs. Test all voices includes all four choices; Test selected voice runs just the chosen one.

## Ryan High and INT8 retirement

Ryan High is a higher-capacity Piper male US English voice, trained from scratch at the high tier, rather than another medium-tier accent swap. It is a quality candidate, not a measured perceptual match for Kokoro or a British voice. Listen to it on the phone and compare speed under the same benchmark before choosing it for calls. No playback pitch tricks or new native runtimes are added.

- Model card: https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/ryan/high/MODEL_CARD
- Samples and integration: https://k2-fsa.github.io/sherpa/onnx/tts/all/English/vits-piper-en_US-ryan-high.html
- Archive: https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ryan-high.tar.bz2
- Archive bytes: 115630708; SHA-256: `6a71edf4d308b9cb2eaeadc8d1f3c6bf96120ecb7fe52c29a2b6e139c59760ed`; ONNX bytes: 120786923; expanded bundle: 138783768 bytes (within the existing 200 MB extraction cap).
- Upstream model card lists the RyanSpeech dataset as CC BY-NC-SA 4.0. Preserve this attribution; commercial distribution needs a licensing assessment rather than assuming the runtime's license covers the voice data.

Kokoro INT8 is removed from selection and Test all voices. A saved INT8 selection resolves to original Kokoro. Historical INT8 results retain their own name and model ID, marked retired, so previous measurements are not mislabeled as original Kokoro. The next voice model preparation removes only the retired INT8 model directory and its installer staging/partial files, under the existing model-operation gate. Other models and all saved measurements remain available.

Validation adds a regression test for retired selection fallback and historical identity, alongside the existing persistence/retention test now exercising Ryan High. Ryan's exact pinned bundle generated nonempty 22,050 Hz speech using Sherpa 1.13.7 on the host; this checks runtime compatibility, not phone latency or subjective voice quality.
