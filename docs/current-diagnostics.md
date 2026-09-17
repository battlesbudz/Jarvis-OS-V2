# Current settings and development diagnostics

## Phase D acoustic evidence

With the call and wake listening stopped, open **Voice Call settings → Development diagnostics →
Echo test — Phase D**. Run **Jarvis alone** while completely silent, **Your voice alone**, then
**Both voices**. For the latter two say slowly “No. Yes. I. Stop. Hey Jarvis.” Follow the on-screen
listening instruction; preparing/downloading models is not part of the capture. A test deliberately
does not interrupt Piper. Keep volume and phone position constant. Save one ZIP, then repeat
with headphones and save another. Run in the foreground; Stop/backgrounding cancels and releases
capture/playback. Native model operations finish cleanup before the busy state is released.

Each ZIP scenario includes `report.txt`, `timeline.csv`, `microphone.wav`, optional
`piper-reference.wav`, and available `decoder-input.wav`. Reports include build/commit/device,
route IDs, volume, AEC availability/control/implementation, capture statistics, transcript and
keyword scores/hits every 100 ms of replay. The timeline keeps microphone read frame ranges,
playback head observations and hardware timestamps on the same monotonic clock. Empty hardware
fields mean unavailable; read delivery time is not acoustic onset. Audio is bounded in memory
(12 seconds of capture maximum, 1,200 timing rows); truncation is reported. Save is explicit and
uses Android's document picker. No automatic disk recording or upload is added to normal calls.

Limitations: microphone PCM is **after Android processing**; pre-effect PCM is unavailable.
AEC enabled is not measured effectiveness. Piper reference is exactly the submitted eight-second
clip at 22,050 Hz / 1.0x, with observed played positions, not acoustic loudspeaker output. The
full synthesis text can extend past that clip. Recognition uses the selected adapter on the
whole captured clip **after** recording without VAD filtering; keyword replay also runs afterward.
These results isolate acoustics and model responses, not live CPU scheduling, automatic stopping,
or owner verification. A quiet transcript is not alone proof of effective echo cancellation;
compare the recorded audio as well. This is D1 evidence, not a production route change or D3 AEC.


Current on `audio-pr2` / PR #6, 17 September 2026.

## False interruption investigation (build 709 follow-up)

`speaker_interruption` now includes `playbackScores`, `playbackReference` and
`minimumOwnerMargin`, alongside owner `scores`. A high owner score alone is not
proof that the user spoke. `PLAYBACK_ECHO` rejects candidates insufficiently
distinct from actual Piper audio; missing required embeddings are uncertain.
`barge_evidence` retains `near_echo` fragments and final speaker rejection reasons.
Successful natural stops log `recognizedWords` and `acousticOnly=false`.
Sound or VAD alone must never stop output. See [repair and phone acceptance](voice-interruption-echo-repair.md).

The build 710 follow-up adds `referenceSource=playback_head`, `startFrame`,
`endFrame`, `capturePositionAgeMs` and `referenceAudioMs`. These identify the
actual played samples selected for the candidate's capture timestamp. An
`unavailable:*` reference fails closed; passage-opening samples are no longer
reused for interruptions occurring later in a long answer.

## Everyday settings

- AI model: Gemma 4 E2B or E4B, using the existing verified installer and model readiness check.
- Speech recognition: Moonshine Small Streaming or Whisper base.en.
- Voice: Piper Northern English Male medium. There is no voice/profile selector.
- Default assistant and keyboard microphone-handoff setup.
- Reset learned speaker preference when recognition is favoring the wrong speaker.

Piper uses four synthesis threads, native sample-rate playback (1x), complete
sentences targeting 320 characters, and a 640-character generation cap. Native
whole-passage synthesis and natural pauses remain enabled. Old saved thread,
playback-speed and opening profiles are ignored. The experimental 160-character
opening is no longer applied to calls. The accepted 320-character policy remains
the baseline; this cleanup makes no new phone latency or quality claim.

## Retained development checks

Open **Voice settings → Development diagnostics**:

| Check | Purpose |
| --- | --- |
| Record 8 seconds / play recording | Inspect the actual microphone path for distortion or missing speech |
| Record and test recognition | Compare microphone PCM, actual decoder input and the selected recognizer's transcript; copy its report |
| Test wake word | Isolate wake recognition without running Gemma |
| Copy diagnostics | Export the current call's timing, recognition, speech delivery, interruption, model/build and process-exit evidence |
| Save latest reply audio | Listen to generated Piper audio when investigating gaps or voice quality |

Recording tests are disabled during calls or other microphone work. Model/ASR
changes and starting a call are disabled while a microphone diagnostic is busy.
The selected-model readiness check remains in setup because it verifies whether
the installed model can initialize and answer before calls become available.

Automatic call diagnostics retain useful timing, playback starvation, cancellation,
thermal/scheduling and crash evidence. Thread counts and thermal state may still
appear as measured context in developer logs; they are not user tuning controls.
Saved Voice Calls remain available; only explicit Resume imports their transcript. New calls no longer import recent calls automatically.

## Removed routes

Removed the 144-run voice-profile matrix, manual thread/pace/opening controls,
profile apply/restore, synthetic P1 setup/P2 load packs, benchmark WAV export,
Gemma acceleration and text/audio comparison runners, duplicate two-engine ASR
room comparison, and the unused chat screen's direct 25-second Gemma audio and
simulated-tool tests. Their controllers, replay-only synthesis branches and
dedicated fixtures/tests are removed from the app. Production Gemma audio input,
tools, native scheduling and recognition are retained.

Old benchmark preferences are not executed or migrated into current call tuning.
Existing archived records are left on disk with their original identities; there
is no benchmark browser or replay route. Historical documents remain evidence,
not instructions for the current UI.

## Acceptance still needed

On a phone, upgrade with an old slow/profile selection saved, verify ordinary
Piper playback, exercise E2B/E4B and both recognizers, check stop/correction/goodbye,
and inspect/export a real call. Confirm diagnostics release the microphone on
cancel or leaving settings. E4B memory/latency, acoustic performance and sustained
call interruption acceptance remain open in [the current pipeline](voice-pipeline-current.md).

## Exact model input and call boundaries (build 705 follow-up)

Copy diagnostics retains the latest 24 actual Gemma text-prompt submissions separately
from timing chatter, without the former 1,200/1,500-character truncation. This includes
speculative drafts, final inference, transcription fallback and text retries. Each entry
has turn/submission IDs, model ID, audio/text mode and byte count, native-session/prior-
submission counters, tool-enable state, and the exact submitted text. A consumed draft
has already been recorded at its actual submission; it is not another inference.
These are app-submitted prompts, not a dump of the SDK's internal chat template.

History provenance identifies the current call or explicitly resumed call, total and
selected entry counts, and oldest/newest selected timestamps. The exact prompt shows
which content survived the newest-eight-entry, 300-character user/450-character assistant,
3,000-character context budget, plus any summary and resolved subject. New call IDs clear
summary and subject state. An unrelated current request clears a stale resolved subject.
Prompt snapshots clear at the next diagnostic session and remain app-private until copied.

`audio_text` means Gemma was sent both audio and text; it does **not** count corrections
of Moonshine. `audioCorrectionCount=not_observable` states this explicitly. Recognition
text evidence separately retains ASR/resolved text and the transcription-fallback flag.

## Short interruptions and speaker preference

Stop and Hey Jarvis keyword paths remain. Natural interruption no longer requires a
command, question or minimum word count. A brief word can take the floor, including
No, Yes or I; a lone floor-taking word returns to listening without asking Gemma to
answer it. Echo-only clauses are removed from the captured correction.

The first natural probe can start from 250 ms of buffered PCM after 200 ms from the
VAD candidate onset. There is no three-second speech prerequisite and no extra lexical
stability wait on the speaker-verified path. Buffer age, ASR, speaker embedding and
playback-stop processing still add latency. `speechOnsetToStopRequestMs` measures the
VAD-candidate-to-stop-request interval, not the acoustic first phoneme or final audible
frame. Device measurement is still required.

Before natural interruption stops playback, the same PCM clip used by ASR must positively
match the learned speaker preference (cosine >= 0.65 in an available embedding window).
A missing profile, insufficient embedding or uncertain score leaves playback running;
keywords remain available. Speaker extraction runs off the capture loop and is joined
before releasing its native owner. Logs report MATCH/DIFFERENT/UNCERTAIN, scores, clip
length and compute time. This is a learned voice preference, not verified owner identity.
The existing preference needs consistent speech over at least three distinct activations;
its training-duration rules are not a per-interruption waiting period. Reliable matching
of a 270 ms word amid speaker echo remains a Fold acceptance test, not a guaranteed result.

## Incremental text input (17 September follow-up)

Ordinary voice answers log `mode=incremental_text` (or `voice_text` for a final-only
text path), `audioBytes=0`, and `retainedAudioBytes` separately. Keeping a recording
for recovery does not mean it was submitted to Gemma. Explicit empty-ASR recovery
still records its own audio submission and fallback decision.

The retained per-turn `input_finalized` record reports `reuse`, input chunks,
retained characters, full prompt length and `listeningPrefillMs`.
`input_final_prefill` reports remaining characters and `finalPrefillMs`.
Recent `asr_partial` events now include the actual hypothesis (up to 900 characters,
with an explicit truncation flag), rather than character counts alone.
The full assembled final prompt remains in the independent exact-prompt retention.
It is the concatenated logical text input; Gemma turn delimiters are specified in
[the adapter contract](voice-incremental-input.md). These records cannot prove
what the speaker physically said without comparing microphone audio.

`input_revision` means Moonshine/Whisper revised text already processed by Gemma;
the input is rebuilt once at final validation, never restarted after every new word.
`thermal_emergency` applies only at Android status 5/6; ordinary throttling no
longer disables input preparation. `asr_backlog` can temporarily defer optional
prefill while recognition catches up. No cooldown is carried from failed drafts.

Decode TTFT starts after final explicit prefill. Use `finalPrefillMs` and the
end-to-end pipeline timestamps as well; a reduced decode TTFT alone is not proof
of reduced user-visible latency. See the [phone checks](voice-incremental-input.md).

Build 708 repair adds `input_context_prefilled`: fixed context was processed,
possibly with `chunks=0` because no user words were yet stable. `reuse=true`
can therefore mean context-only reuse; use `committedChars` to distinguish it.
`Voice incremental fallback` records a one-time final-text retry before any
output, without audio submission or unloading the engine. The streaming TTFT now
includes its small final turn-boundary prefill; logical remaining prompt prefill
is still reported separately. ASR ellipses alone no longer produce
`explicit_hesitation`; spoken hesitation words still can.
