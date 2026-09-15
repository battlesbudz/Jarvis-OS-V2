# Paul appendability result — 15 September 2026

**Decision: direct mid-generation text insertion is mechanically possible, but the
implemented schedule fails the content gate. Do not promote it into live calls.**
This is a B1 investigation result, not B2 acceptance, and not a claim that all
incremental TTS architectures are impossible.

The user rejected build 686's retained Mimi/RNG profile as robotic despite zero
observed starvation in all six runs of suite
`eb990ac7-92a7-480c-b285-6c1d7405c23a`. That profile still re-copies the voice prompt
into a fresh speech LM context per text submission. The host probe here instead
keeps the current LM cache and inserts new text while that same generation loop
is running. It retains the preceding latent, Mimi decoder, RNG, and EOS handling.

## Results

The intended full text was:

> Good evening, sir. Your next appointment begins in twenty minutes. There is time for a cup of tea.

Every actual insertion succeeded without ONNX shape errors. Each callback stream
was finite and exactly equalled returned audio. The comparison used the exact
model/reference hashes from the user's phone reports. All nine cases ran to
completion; two completed only because of the diagnostic safety ceiling.

| Case | Actual insertion | Audio | EOS result | Whisper base.en content screen |
| --- | --- | ---: | --- | --- |
| Prefix only | None | 1.04 s | Step 10 | `Good evening sir.` |
| Whole text | All upfront | 5.76 s | Step 69 | Entire intended text |
| Suffix at step 0 | Before first latent | 5.60 s | Step 67 | Entire intended text |
| Suffix at step 5 | Before EOS, after 400 ms of generated audio | 1.04 s | Step 10 | `It could even do so.` |
| Suffix at step 15 | Not reached | 1.04 s | Step 10 | `Good evening sir.` |
| Suffix at step 30 | Not reached | 1.04 s | Step 10 | `Good evening sir.` |
| Words beginning at step 0 | All 15 additions delivered, steps 0–14 | 32.00 s | No EOS; capped at 400 latents | Fragmentary/unrelated transcript |
| Words beginning at step 5 | All 15 additions delivered, steps 5–19 | 32.00 s | No EOS; capped at 400 latents | `good evening You` |
| Whole text repeated | All upfront | 5.76 s | Step 69 | Entire intended text |

Whisper normalizes “twenty” to “20.” Complete automatic transcripts, model identity
and decode settings are retained in `results/transcripts.json`. These are
**automatic content screens, not human listening judgments**. No accent or
naturalness pass is claimed, even for the controls.

The full-text repeat is byte-identical in PCM to the first full-text control.
The two late/unapplied suffix cases are byte-identical to the prefix-only control.
The first callback before step-5 insertion is byte-identical to the corresponding
prefix-only callback. The step-5 suffix was inserted while `prior_eos=-1`, so this
failure cannot be dismissed as merely feeding text after the earlier utterance
had already signalled EOS. See `results/native.txt` and `results/manifest.json`.

## Interpretation and limits

This provides direct evidence against **this simple append-to-current-cache
implementation**. Keeping the audio continuous and accepting input tensors did
not preserve correct speech. Forcing EOS off would not by itself establish word
completeness or voice consistency; the word cases already failed to find EOS.

The normal source protocol conditions on text before autoregressive audio; the
probe interleaves later text with that audio context. The result is consistent
with a conditioning/protocol mismatch, but does not isolate the exact learned
failure mechanism. No claim is made that a different cache policy, conditioning
strategy or trained model could never support incremental text.

This is one short, fixed fixture and two word arrival schedules. Tokenization
happens separately for additions. Host CPU timings are not Fold6 latency results.
The historical exporter commit is identified in README, but exact export-source
parity is not proven; the actual ONNX/reference files are hash-matched instead.

The usable control remains **one complete text submission with streamed audio
output**. That avoids application-created sentence restarts without waiting for
all audio to be synthesized before playback. It still requires the complete text
before that generation begins, and does not guarantee no within-generation accent
variation. The earlier user-preferred runs 5/10/13 are supporting listening evidence
for that direction, not proof of a general cure.

## Changes and validation

- Added only `experiments/pocket-append/` and the B1 status/documentation update.
- Compiled the host prototype against pinned Sherpa 1.13.7 + ORT 1.27.1.
- Ran nine cases, exact callback checks, baseline repeat and causal prefix checks.
- Ran Whisper base.en content screening with fixed decode settings.
- Python entry points compile; repository whitespace check passes.
- Production patch, Android/JNI build wiring, live settings and model weights are
  unchanged. No APK or repeat phone suite is required for this investigation.
