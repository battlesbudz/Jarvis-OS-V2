# Story recall and TTS comparison

## User test

End any call, then tap **Voice: …**. Select Kokoro original or Piper Miro High (British) for the next call. **Test selected voice** reads a fixed short reply, paragraph and story. **Test all voices** runs the same samples sequentially with each engine without changing the selected call voice. **Stop benchmark** cancels playback and retains completed results; native synthesis may take a moment to retire before controls become available again.

First use downloads Miro (~67 MB), with a pinned SHA-256 and byte count. Extracted model lengths and essential phonemizer files are checked. Bundles install in separate directories; original Kokoro remains available. Downloads and extraction are cancellable for the alternatives. The original model retains its existing installer. All models operate locally after installation.

Each benchmark sample creates a fresh native engine. All engines use the same 2–4 thread limit, non-callback JNI generation, fixed phrase boundaries, and normal 1.0 playback speed. Model loading and queue waits are reported separately from synthesis. Downloads, Gemma generation and microphone recognition are excluded. The benchmark does not unload Gemma weights already resident in memory; it prevents competing model operations. These are in-app synthesis/playback measurements, not laboratory isolated hardware timings. Try repeated runs in similar temperature/power conditions and listen to pronunciation and voice quality.

Ordinary calls record the selected engine under `source=voice-call`, using the existing adaptive phrase boundaries and at most 10% slower playback. Do not mix these with `source=benchmark-v1` when ranking models.

## Results

The last 40 TTS measurements survive calls and app restarts. **Copy diagnostics** includes them alongside ASR comparisons. Records include engine/model/speaker, sample ID, input SHA-256, model load, first-phrase synthesis latency, total synthesis, raw audio duration, RTF, playback pace, estimated supply gaps, queue wait, thread count, completion and error status. Terminal AudioTrack underruns are explicitly included in the underrun count and should not be interpreted alone as speech gaps.

Compare completed records with matching sample ID and text hash. RTF is synthesis time divided by raw audio duration; below one means synthesis kept up with normal speech. Slower playback never improves the reported RTF. Incomplete/error runs remain visible and must not be ranked as successful fast results. No model is automatically deleted or selected as a winner.

## Model evaluation history (retired options are no longer selectable)

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
Archive bytes: 67220121; SHA-256: `a48d4017da0f77668b27bed63fe6e04dd64c6397e1fadad4f460efb0ef7c9012`; extracted ONNX bytes: 63201430. Model card and dataset attribution remain in the installed bundle. Existing saved selection and historical results keep their IDs. Test all voices now includes only Kokoro original and Miro; Test selected voice runs just the chosen one.

## Ryan High and INT8 retirement

Ryan High is a higher-capacity Piper male US English voice, trained from scratch at the high tier, rather than another medium-tier accent swap. It is a quality candidate, not a measured perceptual match for Kokoro or a British voice. Listen to it on the phone and compare speed under the same benchmark before choosing it for calls. No playback pitch tricks or new native runtimes are added.

- Model card: https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/ryan/high/MODEL_CARD
- Samples and integration: https://k2-fsa.github.io/sherpa/onnx/tts/all/English/vits-piper-en_US-ryan-high.html
- Archive: https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ryan-high.tar.bz2
- Archive bytes: 115630708; SHA-256: `6a71edf4d308b9cb2eaeadc8d1f3c6bf96120ecb7fe52c29a2b6e139c59760ed`; ONNX bytes: 120786923; expanded bundle: 138783768 bytes (within the existing 200 MB extraction cap).
- Upstream model card lists the RyanSpeech dataset as CC BY-NC-SA 4.0. Preserve this attribution; commercial distribution needs a licensing assessment rather than assuming the runtime's license covers the voice data.

Kokoro INT8 is removed from selection and Test all voices. A saved INT8 selection resolves to original Kokoro. Historical INT8 results retain their own name and model ID, marked retired, so previous measurements are not mislabeled as original Kokoro. The next voice model preparation removes only the retired INT8 model directory and its installer staging/partial files, under the existing model-operation gate. Other models and all saved measurements remain available.

Validation adds a regression test for retired selection fallback and historical identity, alongside the existing persistence/retention test now exercising Ryan High. Ryan's exact pinned bundle generated nonempty 22,050 Hz speech using Sherpa 1.13.7 on the host; this checks runtime compatibility, not phone latency or subjective voice quality.

## Miro High (British)

Miro is an additional British English male voice from TigreGotico Lda / OpenVoiceOS, distributed as `vits-piper-en_GB-miro-high` by Sherpa. Its first-use download is about 67 MB. It uses the existing VITS configuration and is selectable for calls and both benchmark modes; no existing voice preference is changed. Its stable diagnostic ID is `piper_miro_high`. The High name follows the upstream package, not a claim of perceptual equivalence to Kokoro or a measured phone speed.

- Model source and attribution: https://huggingface.co/OpenVoiceOS/pipertts_en-GB_miro
- Samples/configuration: https://k2-fsa.github.io/sherpa/onnx/tts/all/English/vits-piper-en_GB-miro-high.html
- Archive: https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-miro-high.tar.bz2
- Archive bytes: 67194499; SHA-256: `c42907615e1a95bb69a85674f9d7979eeb2352c40fe86a0ae84d5479149c03d1`; ONNX bytes: 63511174; expanded bundle: 81511809 bytes.
- Preserve the included README and license information. The pinned Sherpa bundle README states CC BY-NC-SA 4.0; the current upstream model card states CC BY-NC-ND 4.0. Both restrict commercial use. The app identifies the creator and non-commercial restriction when selected; commercial release needs permission from the rights holder. The model is downloaded unchanged, with no retraining or voice conversion.

Validation: the exact pinned archive generated nonempty 22,050 Hz audio through Sherpa 1.13.7 on the host. Android CI covers compilation, unit tests, native packaging and signing. Phone acceptance is selecting Miro, running Test selected voice, copying diagnostics, and comparing pronunciation and long-response gaps against Alan and Kokoro.

## Short-reply playback and exact recall

AudioTrack's default streaming startup threshold equals its buffer capacity. The two-second buffer could therefore prevent shorter battery/action replies from ever starting. Android 12+ now uses a startup threshold of at most 100 ms, bounded by the first available phrase. The two-second capacity remains for scheduling headroom. Older Android uses end-of-stream silence only when necessary to reach its threshold. Silence padding is excluded from speech duration and synthesis metrics.

Playback-start callbacks now require observed playback-head movement. Completion requires consumption of all speech frames; a drain timeout is an error, not a successful session. Nonblocking writes also have a no-progress timeout. Diagnostics retain `playback_confirmed`, `played_frames`, `speech_frames`, and `output_route` in the TTS comparison records. These indicate Android consumption, not a guarantee of audible sound at the physical speaker. Runtime phrase logs include PCM RMS and peak to distinguish very quiet/model audio from routing problems. Old records show these new fields as unavailable.

Simple repeat requests such as “What did you say?” and “Sorry, can you say that one more time?” return the latest visible assistant entry, including verified tool outcomes. Any prepared draft is discarded before returning that answer. No tool is rerun. Compound requests and questions about specific earlier content remain normal model turns.

Six focused tests cover short-clip startup thresholds, no-progress drain timeout, full consumption, cancellation, latest-tool-result recall, and compound-request exclusions. Kokoro's occasional garble remains unconfirmed: some reported calls use speed 1.0, so time stretching is not established as the cause. No speculative pitch or voice-generation changes are included.

Android behavior reference: https://developer.android.com/reference/android/media/AudioTrack#getStartThresholdInFrames()

## Final voice shortlist

Only Kokoro original and Piper Miro High remain selectable or benchmarked. A current Kokoro or Miro preference is preserved; a retired Lessac, Alan, Ryan or INT8 preference falls back to Kokoro. Old benchmark records retain each original model's identity with a retired label. The next voice-model preparation deletes only the retired downloads and their installer staging/partial files, reclaiming storage without touching Kokoro, Miro, or saved transcripts/results. Earlier sections document the evaluation history, not additional available choices.
