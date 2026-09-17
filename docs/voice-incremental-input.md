# Incremental voice input — 17 September 2026

Implemented on `audio-pr2`, existing PR #6. Device acceptance is still pending.
Build 705/707 logs do not measure this implementation.

## Current behavior

Moonshine/Whisper publishes growing transcript hypotheses while the microphone
continues recording. `IncrementalVoiceInput` receives text, holds the newest
unfinished word, and processes complete words shared by consecutive hypotheses.
Its worker appends only the new suffix to one LiteRT-LM native Session using
`runPrefill`. The engine/weights stay loaded. Speech resumption and added words
do not cancel a generated answer or clear this input state. Partial callbacks no
longer construct a WAV snapshot of the whole utterance.

At the endpoint, queued input processing finishes. Final recognition, routing,
history/subject handling and reference lookup produce the authoritative prompt.
If that prompt starts with the already processed text, the session receives only
the remaining text and final reference/instruction material, then decodes an answer.
No microphone recording is included in ordinary answer generation. Empty-ASR
recovery can still explicitly ask Gemma to transcribe complete audio before using
the recovered text. Final app commands use the existing guarded action path.

ASR can revise earlier words despite the speaker saying each word only once.
If a revision changes committed text, we stop adding to that session and rebuild
once from the final prompt. We also rebuild if final context compaction changes
the prefix. We never append a correction after wrong text and pretend the cache
is equivalent. The conflated queue keeps the newest hypothesis under load; it
does not promise a separate model call per word. Prefill is bounded to partials
within the existing 12,000-character user-message budget.

This is **incremental input processing**, not answer generation before the
question is finished. It moves reusable model input work into listening time.
It does not guarantee an answer is mostly decoded after eight of nine words,
repair ASR accuracy by itself, or make lookup/TTS/end-of-speech delays disappear.
Lookups and phone actions are still decided from the final request. There is no
provisional tool execution. Existing Stop/Hey Jarvis and owner-matched natural
interruptions remain available.

`VoicePreparation` and its obsolete draft/restart tests and scheduler cooldowns
are removed. `IncrementalVoiceInput` tests cover append reuse, final-only decode,
revisions, failed prefill recovery and cancellation ownership instead.

## Thermal and native ownership policy

- Thermal statuses 0–4 allow listening-time input processing. Status 5
  (emergency) and 6 (shutdown) defer new optional prefill chunks. Android's own
  hardware protections are unchanged.
- Capture backlog above 200 ms defers optional prefill so recognition can catch
  up. A later hypothesis can resume work; final text is still processed. There
  is no fixed two-second start interval or 15/30-second draft penalty.
- Prefill runs off the capture worker. Cancellation joins blocking native work
  before releasing its session; decode cancellation waits for its native terminal
  callback before freeing resources. Only one input worker owns the session.
- The idle Conversation cache is released before incremental Session creation.
  Conversation resets recreate lazily for tools, recovery or chat, avoiding an
  extra idle KV cache beside incremental voice input.
- A Session is scoped to one utterance. Existing bounded app history is included
  in the next input prefix. New calls remain isolated; explicit Resume restores
  the selected saved conversation. This does not change Moonshine's existing
  between-command model rotation or Whisper's batch recognition implementation.

## Pinned SDK/model contract

The installed `litertlm-android:0.12.0` exposes repeated blocking `Session.runPrefill`
and `generateContentStream(emptyList(), callback)` to decode the accumulated input.
This was checked against [Session.kt at v0.12.0](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.12.0/kotlin/java/com/google/ai/edge/litertlm/Session.kt).

Both catalog-pinned model headers were inspected by HTTP range reads: E2B revision
`6e5c4f1`, E4B revision `1fc8912676889ed3aeec478c92c1e239bed08928`.
Their metadata contains a Jinja template and thought-channel delimiters, but
**no legacy Session prompt affixes** (protobuf field 3). Session adds BOS itself.
`LiteRtVoicePrefillSession` supplies `<|turn>user\n` once, appends the logical
prompt chunks, then `<turn|>\n<|turn>model\n` before decoding. This matches their
no-tools, non-thinking template. Recheck these facts on any SDK/model update;
do not duplicate delimiters if a future artifact adds Session affixes.
See the SDK's [session formatting implementation](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.12.0/runtime/core/session_utils.cc)
and [metadata schema](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.12.0/runtime/proto/llm_metadata.proto).

Raw Session callbacks differ from Conversation callbacks. `GemmaSessionText`
removes thought-channel content and turn/control markers even when split across
callbacks; none is spoken or saved as the reply. Output is bounded at 32,000
raw characters. Text input has no tool definitions, and decoding only starts
after final action validation.

## Evidence and remaining phone checks

The new diagnostics distinguish actual submitted audio bytes from retained
recordings, preserve the full final logical prompt, and retain per-turn reuse,
chunk counts, listening prefill time and final prefill time. Recent ASR partials
include bounded hypothesis text. See [diagnostic fields](current-diagnostics.md).
Decode TTFT excludes completed prefill; compare end-to-end timestamps as well.

Automated regression coverage checks one-session append behavior, zero early
decodes, final reference suffixes, changed-history rebuilds, ASR revisions,
native ownership on cancellation, failed initial prefill cleanup, non-emergency
thermal admission, exact live/final prompt prefixes and split thought markers.
The adapter is compiled against the actual 0.12.0 Android SDK classes. These
checks do not execute a Gemma model on the phone.

Phone acceptance must compare E2B/E4B and both ASR engines using the same spoken
requests: a short question, two sentences with a pause, a corrected recognition,
an explicit app command, a factual request requiring lookup, and Stop/Hey Jarvis/
owner single-word interruptions. Verify one answer, no draft speech or hidden
channel text, correct current-call history, no duplicate action, and clean
cancel/resume. Under sustained calls, compare speech-end-to-answer playback,
recognition backlog, `reuse`, and thermal status. Measure actual ASR accuracy
against recorded speech; partial text alone is not ground truth. Emergency
admission is unit-tested, not induced by overheating a phone.
