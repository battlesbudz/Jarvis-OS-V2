# Pocket appendability probe (B1)

Host-only experiment. **Not linked into Android, not a new live-call policy.**
The existing `paulReset=false` experiment preserves Mimi decoder/RNG state but
re-copies the voice-prompt LM state for each submission. This probe instead inserts
text into the **currently generating LM cache**, during one `GenerateStreaming`
invocation. Mimi, RNG, current latent, and LM cache are retained at insertion.

## What is controlled

- Phone-matching January INT8 ONNX files and Paul reference; `run.py` checks every
  hash in `model-hashes.json` before invoking native code.
- Sherpa source `917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e`, repository production
  streaming patch plus a disposable host-only injection from `prepare.py`.
- ORT 1.27.1, CPU two threads, seed 42, temperature 0.7, five flow steps,
  first three/subsequent five latent frames per callback, no leading period.
- Source WAVs at 24 kHz, natural 1.0x, no AudioTrack, fillers or time stretching.
- One fixed prefix, `Good evening, sir.`, and suffix,
  ` Your next appointment begins in twenty minutes. There is time for a cup of tea.`
- Prefix-only and full-text controls, suffix before any audio latent (step 0),
  suffix after 5/15/30 generated latents, and word-by-word suffix beginning at
  step 0/5. Word delivery is one whitespace-delimited word per latent step.
  A latent represents 80 ms of generated audio, **not** wall-clock arrival time.
- Full-text control repeated after all cases to check state isolation.

The suffix is stored in the harness, but **not passed to the LM** until its scheduled
step. This is a deterministic arrival simulation, not a concurrent append API.
There is no forced suppression of EOS: a case may finish before its scheduled
append. Logs explicitly record actual insertions, EOS, and incomplete delivery.
Generation is capped at 400 latents (32 seconds). Pending PCM is decoded normally.
Callback PCM must be finite and exactly match returned PCM, once each.

Tokenization is performed on each incoming addition, so word-by-word cases also
exercise incremental word tokenization. This probe does not claim that token IDs
are identical to tokenizing the full string in one call.

## Reproduction (developer; no phone/ADB required)

Use a **new disposable host directory** and an empty output directory. Model files
are downloaded with the existing pinned model installer/archive; do not modify
ONNX graphs. Host timings cannot establish Fold6 performance.

```sh
python scripts/build_sherpa.py --output /tmp/pocket-append-host
python experiments/pocket-append/prepare.py /tmp/pocket-append-host
# Reconfigure using the existing builder's pinned ORT environment.
python scripts/build_sherpa.py --output /tmp/pocket-append-host
cmake --build /tmp/pocket-append-host/cmake --target jarvis-pocket-append-check --parallel 2
python experiments/pocket-append/run.py \
  /tmp/pocket-append-host/cmake/bin/jarvis-pocket-append-check \
  MODEL_DIR PAUL_WAV /tmp/pocket-append-results
```

`prepare.py` rejects Android build directories. It changes the extracted host
source only; `native/sherpa/pocket-streaming.patch` is untouched. Delete the
experimental host build before using that directory for any production build.

## Source findings

The archive identifies KevinAHM's exporter but does not record its exact export
commit. Its January source is therefore historical evidence, **not a proven
byte-for-byte export recipe**. The actual experiment uses the verified phone files.

- [January FlowLM exporter](https://github.com/KevinAHM/pocket-tts-onnx-export/blob/29ec97e4ed71ffd95e0e8f394638c1e2cbe825c9/scripts/export_flow_lm.py)
  makes the caches and position updates explicit and concatenates text embeddings
  before latent inputs for each invocation.
- [January generation orchestration](https://github.com/KevinAHM/pocket-tts-onnx-export/blob/29ec97e4ed71ffd95e0e8f394638c1e2cbe825c9/pocket_tts/models/tts_model.py)
  prefills text before autoregressive generation, then feeds successive audio
  latents. Its `generate_audio_stream` accepts a complete text string.
- [September training row assembly](https://github.com/kyutai-labs/pocket-tts/blob/8d4be1987ef1531fbbf3300d972fdbdc68c1799b/training/modules/conditioner.py)
  places voice/text ahead of target audio. This later training code is supporting
  architectural context, **not proof of the January checkpoint's training data**.

A valid ONNX invocation establishes mechanical feasibility only. Word completeness,
voice stability and naturalness must be assessed separately. A failure of this
particular schedule does not prove that every possible incremental architecture is
impossible or that retraining is the only remaining option.

## Recorded outcome

See [RESULTS.md](RESULTS.md) for the nine-case result and limitations. Machine
records are under `results/`. Source WAV identities are recorded in the manifest;
audio is delivered separately rather than committed as binaries.
