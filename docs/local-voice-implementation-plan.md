# Local voice implementation plan

Status: proposed implementation; no application changes are delivered by this document.

Prepared: 2026-09-09. Repository: `battlesbudz/Jarvis-OS-V2`. Working branch: `audio-pr2`, existing [PR #6](https://github.com/battlesbudz/Jarvis-OS-V2/pull/6).

Audit baseline: [`eabf77dbc7d8589247558882bb97efdc9678dc7f`](https://github.com/battlesbudz/Jarvis-OS-V2/commit/eabf77dbc7d8589247558882bb97efdc9678dc7f). Recheck affected code against the branch head before implementing a phase. Findings below describe that baseline, not every future build.

## Outcome and scope

Make local conversation on the Samsung Galaxy Z Fold 6 feel continuous: retain opening words, recognize speech while it arrives, wait appropriately through hesitation, respond promptly, accept ordinary spoken corrections, and keep Paul's voice stable. Conversation memory must reflect delivered speech and completed actions.

The reference is current production voice-agent practice, with emphasis on local inference and primary sources from September 2025 through September 2026. There is no single formal industry standard or universal latency threshold for this experience.

Core constraints:

- Keep the core conversation, recognition, speech, and turn decisions on the phone. Telecom, SIP, phone numbers, and hosted call-center infrastructure are outside scope.
- Retain Gemma's audio understanding alongside ASR. A blank ASR result must not automatically discard confirmed intelligible speech. Sound-only input must not become an invented request.
- Keep Paul as the preferred voice for this tuning effort. Retain Kokoro as the existing comparison/fallback option. Model selection changes require measured evidence; this document does not switch the saved selection.
- Preserve wake activation, spoken goodbye/stop-listening controls, explicit Stop and Pause, background operation, saved calls, and microphone priority for other apps.
- Preserve EYE VUE routing and phone fallback. Test each supported route separately.
- All user acceptance and diagnostics must work on the phone without ADB or a computer. CI and developer host checks supplement those tests.
- Do not expose an expanded model-tuning workflow in the normal conversation UI. Put technical comparisons in the existing diagnostics facilities.
- Continue in `audio-pr2` and its existing PR. Deliver test APKs with the existing permanent signing identity and increasing version code. Merge only with explicit user approval, using squash merge.

## Baseline findings

Paths in the following table are relative to `app/src/main/java/com/battlesbudz/jarvis/v2/` unless another root is shown.

| ID | Confirmed behavior at the audited commit | Main implementation location |
| --- | --- | --- |
| V1 | Normal capture stops before speculative sealing and possible Gemma transcription. A new microphone is opened for reply listening afterward. | `JarvisRuntime.kt`, `voice/AudioTurnCapture.kt`, `voice/ReplyVoiceCapture.kt` |
| V2 | Reply audio is discarded before a Hey Jarvis/stop match. The same keyword gate remains during the 500 ms post-reply grace period. | `voice/KeywordBargeInAudioInput.kt`, `voice/ReplyInterruption.kt` |
| V3 | Generated reply text is checkpointed and can be marked complete before playback finishes. Current-call context includes incomplete generated entries without delivery metadata. | `voice/VoiceTurnCoordinator.kt`, `voice/VoiceSessionController.kt`, `JarvisRuntime.kt` |
| V4 | Endpointing uses text rules: 350 ms for stable complete-looking text, 1,100 ms while settling, 1,500 ms for uncertainty, and 3,000 ms for hesitation/no text. Whisper overrides the no-text target to 900 ms. | `voice/AdaptiveTurnEnd.kt`, `voice/AudioTurnCapture.kt`, `voice/AsyncWhisperSession.kt` |
| V5 | Capture constructs and closes ASR engines; Moonshine loads model files in its constructor. Each output session constructs and releases native TTS. | `voice/MoonshineStreamingTranscriber.kt`, `voice/AudioTurnCapture.kt`, `voice/SherpaKokoroVoiceOutput.kt` |
| V6 | Changed eligible ASR partials invalidate speculative work. Replacement drafts reset Gemma conversation state and resubmit accumulated audio. Sealing may join cancelled work. | `voice/VoicePreparation.kt`, `JarvisRuntime.kt` |
| V7 | Paul emits native PCM callbacks, but text submissions wait for sentence boundaries. Decoder reset defaults to true per submission; startup headroom starts at 200 ms and can increase to 600 ms. | `voice/PocketTextStream.kt`, `voice/PocketSpeechPolicy.kt`, `voice/PaulPlaybackBuffer.kt`, `voice/SherpaKokoroVoiceOutput.kt` |
| V8 | A 25-second PCM capacity triggers turn completion even if speech continues. | `voice/AudioTurnCapture.kt` |
| V9 | Token-driven state and transcript updates repeatedly serialize the stored call collection. | `voice/VoiceTurnCoordinator.kt`, `voice/VoiceSessionController.kt`, `voice/VoiceCallStore.kt` |
| V10 | Whisper runs repeated growing-window batch decodes. Stable partials depend on agreement between results; finalization queues behind native work. | `voice/WhisperTranscriber.kt`, `voice/AsyncWhisperSession.kt` |

Existing strengths to preserve include native PCM callback delivery, bounded audio queues, final-transcript tool validation, speculative invalidation on resumed speech, microphone handoff, and separate filler/reply timing. The bundled Paul opening already avoids regenerating the initial interjection.

Historical documentation contains superseded experiments. In particular, `pocket-tts-paul.md` describes continuous decoder state and no startup wait, while the audited defaults differ. `voice-duplex-and-quiet-speech.md` already marks its continuous-ASR experiment as superseded. Do not use either experiment as proof of current behavior or simply restore it: the previous continuous-ASR approach exceeded the phone's processing budget.

## Intended architecture

Use small, independently testable owners. The following names describe proposed responsibilities; reuse suitable existing abstractions rather than creating duplicate layers.

| Proposed component | Responsibility | Boundary |
| --- | --- | --- |
| `VoiceAudioSession` | Own one active-call microphone, timestamp frames, retain bounded pre-roll, and distribute ordered input to VAD/ASR/turn handling. | End-of-turn changes consumers; it does not release the microphone. Stop, Pause, handoff, route failure, and call end can release it. |
| `VoiceModelSession` | Own warm recognizers, TTS weights, and Paul voice conditioning with serialized native access. | Utterance state is separate from model lifetime; reset acoustic/generation state deliberately. |
| `VoiceTurnStateMachine` | Coordinate listening, candidate end, processing, speaking, interruption, and external suspension. | Speech-start remains observable during processing and speaking; UI state does not determine microphone ownership. |
| `TurnEndDetector` | Combine VAD, transcript/acoustic completion evidence, and bounded pause policy. | Candidate decisions carry a revision and are invalidated by resumed speech. |
| `SpeechDeliveryLedger` | Map generated text spans to PCM ranges and confirmed playback progress. | Caption estimates are not proof that particular words were heard. |
| `VoiceWorkScheduler` | Budget speculative and optional work around capture, ASR backlog, and audio supply. | No concurrent native calls to the same model; no speculative action execution. |

Every asynchronous event must carry the applicable call ID, turn ID, and generation/revision ID. Late callbacks from a stopped or superseded generation cannot change state, play audio, update history, or dispatch actions.

PCM delivery must remain bounded and observable. Keep microphone reads independent of heavy model work. Do not use a dropping broadcast buffer as an accidental speech-loss policy; count overflows and recover explicitly. Evaluate smaller hardware chunks only if measurements show a benefit, preserving the VAD model's required framing.

## Delivery sequence

Each phase is a reviewable change set on the existing branch. Check a phase complete only after attaching its implementation commit and validation evidence. A phase number is not a request to create another PR.

| Phase | Deliverable | Depends on | Status |
| --- | --- | --- | --- |
| 0 | Baseline measurements and efficient checkpoints | Audited head verified | Pending |
| 1 | Call-scoped microphone and model ownership | 0 | Pending |
| 2 | Playback-aware history and cancellation | 1 | Pending |
| 3 | Natural interruptions and seamless follow-up capture | 1, 2 | Pending |
| 4 | Local turn completion and long-utterance segmentation | 1, 3 | Pending |
| 5 | Measured ASR policy and bounded speculative work | 0, 1, 4 | Pending |
| 6 | Stable Paul synthesis and tuned audio supply | 0, 1, 2, 5 | Pending |
| 7 | Integrated phone acceptance and documentation reconciliation | 0–6 | Pending |

### Phase 0 — Establish comparable measurements

Extend existing `AsrCaptureMetrics`, `TtsSessionMetrics`, `TurnLatency`, comparison stores, and in-app copy/export. Reuse existing clocks and IDs rather than adding a parallel metrics system.

- Record audio sample position/capture time, VAD speech end, provisional and committed ASR, endpoint decision, finalization completion, speculation seal, first usable reply text, first generated PCM, first non-silent rendered reply PCM, interruption candidate/confirmation, playback stop, and microphone readiness.
- Record stage intervals on a monotonic clock. Show overlaps; do not sum concurrent durations as if they were serial.
- Count model loads, recognizer resets, discarded/accepted drafts, cancellation wait, input backlog, output queued duration, underruns, and unexpected mic reopen events.
- Separate acknowledgement audio from substantive answer audio. Android playback-head progress is a rendering proxy; report route and volume, and do not label it an acoustic measurement at the listener's ear.
- Record build, commit, model/profile, audio route, prompt/case ID, context size, and thermal status. Partition cold, warm, and sustained-session results; report tool turns separately.
- Keep live transcript updates in memory. Debounce checkpoints outside token delivery and immediately flush accepted turns, completed tools, interruption, Pause/End, and lifecycle boundaries. Serialize checkpoint ownership; cancellation must not permit an old snapshot to overwrite a newer one. Preserve existing saved records and document the bounded loss window for an abrupt process kill.

Primary files: `voice/VoiceCallStore.kt`, `voice/VoiceSessionController.kt`, `voice/VoiceTurnCoordinator.kt`, `voice/AsrCaptureMetrics.kt`, `voice/TtsComparisonStore.kt`, `diagnostics/TurnLatency.kt`, `JarvisRuntime.kt`.

Acceptance: fixtures prove ordered checkpoint updates, final flushes, backwards-compatible reads, and correct metric attribution. A baseline phone session exports one trace per turn, with filler and answer timings separate. Profile token-path persistence with small and large saved histories; record costs without running an unbounded synthetic stress suite.

### Phase 1 — Keep capture and models alive for the active call

- Extract active-call audio ownership from the per-turn path in `JarvisRuntime`. Convert `AudioTurnCapture` into an utterance consumer that can finalize without stopping the call microphone.
- Reuse the same audio session through endpoint finalization, speculative sealing, acknowledgement, Gemma fallback, reply generation, playback, and follow-up listening. Preserve bounded onset audio at every transition.
- Keep recognition and TTS weights warm within a bounded call session. Give each engine one native owner and cancellation-safe release. Separate fresh utterance state from resident weights; do not carry a completed TTS text/EOS state into the next utterance without engine support.
- Verify the Moonshine SDK's stream-reset lifecycle against its pinned version. If an SDK cannot safely reuse a recognizer, retain microphone coverage while replacing only that recognizer and measure the cost.
- Preserve `MicrophoneHandoff` and `MicrophoneInterruptionMonitor`: release promptly for external microphone use, restore the previous passive/active mode when available, and never poll by repeatedly acquiring the microphone. Keep handoff quiet timing distinct from conversational end-of-turn timing.
- Keep Pause and Stop authoritative. Backgrounding and keyboard visibility alone must not change microphone ownership. Existing foreground-service eligibility and route handling remain required.

Primary files: `JarvisRuntime.kt`, `voice/AndroidAudioInput.kt`, `voice/AudioTurnCapture.kt`, `voice/ReplyVoiceCapture.kt`, `voice/VoiceCallService.kt`, `voice/MoonshineStreamingTranscriber.kt`, `voice/WhisperTranscriber.kt`, `voice/SherpaKokoroVoiceOutput.kt`, microphone handoff classes. Proposed files: `voice/VoiceAudioSession.kt`, `voice/VoiceModelSession.kt`.

Acceptance: deterministic tests cover finalization without recorder release, ordered pre-roll delivery, route/handoff recovery, Stop during load, and native release exactly once. On the phone, speak during acknowledgement/finalization and immediately after a reply: retain the beginning without a new ready cycle. After warmup, normal turns do not reload ASR/TTS weights unless a logged recovery or memory policy requires it. Memory remains bounded over a sustained call.

### Phase 2 — Reconcile history with playback

- Store generated text, delivered spans, playback state, and interruption separately. Add backwards-compatible record metadata; older records have unknown delivery precision and must remain readable.
- Associate text spans with output sample ranges before writing PCM. Use completed span boundaries for conservative delivery tracking. If the engine lacks word timestamps, label partial-span delivery as estimated and do not treat the caption's intentional visual lead as spoken content.
- On interruption, snapshot playback progress before flushing the track, cancel generation, reject stale queued audio, and reconcile history before constructing the next prompt. Retain fully delivered spans plus explicit partial/interrupted metadata; exclude the unplayed remainder from conversational context.
- Persist completed action outcomes independently of interrupted speech. Never replay an action because its verbal acknowledgement was cut off. Cancel only work that has not completed and whose execution contract supports cancellation.
- Serialize state changes so generation completion, playback completion, cancellation, and End cannot race into duplicate or incorrectly completed transcript entries.

Primary files: `voice/VoiceTurnCoordinator.kt`, `voice/VoiceSessionController.kt`, `voice/VoiceSession.kt`, `voice/VoiceCallStore.kt`, `voice/SpokenCaptionTimeline.kt`, `voice/SherpaKokoroVoiceOutput.kt`, `voice/ReplyInterruption.kt`, `JarvisRuntime.kt`. Proposed file: `voice/SpeechDeliveryLedger.kt`.

Acceptance: interrupt before first PCM, mid-span, between spans, and after generation completes but before playback drains. Next-turn prompts exclude the unplayed remainder; delivered tool results remain available; actions execute once. Saved-call resume and old records remain usable. Tests include late callbacks and output-route failure.

### Phase 3 — Enable natural interruptions safely within the phone budget

- Use persistent VAD/ASR and echo-aware evidence to detect ordinary corrections such as “actually, use the other one.” Preserve explicit stop controls and the trained keyword path as a temporary fallback.
- Combine acoustic evidence with recognized content and speaker preference where reliable. A brief backchannel should normally let Jarvis continue; a confirmed request should stop it. Do not add a mandatory pause/probe on every noise candidate.
- Validate Android AEC on phone speaker, supported Bluetooth routes, and EYE VUE. An enabled flag is not proof of cancellation quality. If software AEC is needed, provide the actual playback reference, compatible sample rates, and delay alignment; benchmark its CPU cost before enabling it.
- Do not blindly switch to communication mode: distinguish Jarvis-owned mode from external calls so microphone-priority logic does not yield to itself.
- Preserve correction pre-roll before confirmation. A user must not need to wait for a keyword and then repeat the request.
- On natural reply completion, immediately transition the existing capture into ordinary follow-up listening. Remove the keyword-only 500 ms gap. Account for keyword detector warmup when retaining that fallback.
- Keep input capture and output delivery responsive while optional work is throttled. An unavailable/overloaded classifier must not silently drop a command or pretend the listener is fully ready.

Primary files: `voice/ReplyVoiceCapture.kt`, `voice/KeywordBargeInAudioInput.kt`, `voice/ReplyInterruption.kt`, `voice/BargeInGate.kt`, `voice/AndroidAudioInput.kt`, `voice/SpeakerPreferenceGuard.kt`, `voice/MicroInterruptionKeywords.kt`, `JarvisRuntime.kt`. Proposed file: `voice/VoiceTurnStateMachine.kt` if existing coordination cannot express these transitions cleanly.

Acceptance: quiet-room corrections work during preparation and playback without a keyword; immediate follow-up retains its opening words. Echo, fan noise, clothing noise, and “mm-hmm” do not repeatedly stop a valid reply. Test the user repeating words Jarvis just said, low-volume speech, another speaker, and Jarvis saying “stop.” Verify no duplicated correction, action, or audio after cancellation. Do not restore the old continuous-ASR experiment without passing backlog and sustained-playback checks.

### Phase 4 — Improve turn completion and support speech beyond 25 seconds

- Introduce a `TurnEndDetector` interface. Retain the current adaptive policy as a bounded fallback and test baseline.
- Evaluate Smart Turn's local quantized model first, using its required 16 kHz audio window and preprocessing. Confirm exact model artifact, license, checksum, ONNX operators, native-runtime compatibility, memory, and Fold 6 inference cost before integration. Use existing download/setup conventions.
- Run completion inference at candidate pauses, not on every microphone frame. Discard results if more speech arrives or the revision changes. Use confidence and pause history to choose a bounded wait; keep conservative hesitation handling.
- Separate end-of-turn, post-handoff quiet time, and call inactivity. Preserve the existing 20-second inactivity semantics; processing and playback must not consume the follow-up listening allowance.
- Replace the 25-second submit-on-capacity behavior with bounded ASR segments, accumulated committed text, and indexed recent audio windows. Segment boundaries are not permission to answer or execute tools. Keep unstable overlap separate to avoid duplicate words.
- Keep Gemma audio-aware for long turns using the audio/text inputs supported by the pinned runtime. Do not concatenate unlimited WAV data. If the runtime cannot preserve enough audio evidence for an ambiguous portion, request clarification instead of dispatching a partial or guessed command.

Primary files: `voice/AdaptiveTurnEnd.kt`, `voice/AudioTurnCapture.kt`, `voice/RollingAudioBuffer.kt`, `voice/StreamingTranscriber.kt`, `voice/VoiceTranscriptResolver.kt`, `voice/VoiceCallPolicy.kt`, `voice/FinalVoiceToolGuard.kt`. Proposed files: `voice/TurnEndDetector.kt`, `voice/LocalTurnEndDetector.kt`, `voice/UtteranceAccumulator.kt`.

Acceptance: test incomplete questions, punctuation revisions, numbers, “I mean…”, a 2–3 second thinking pause, and resumed speech during inference. Test 30- and 60-second spoken requests with corrections near segment boundaries. No automatic answer at the old capacity boundary, no duplicate/lost committed text, and no partial action execution. The new detector must improve the latency/premature-cutoff tradeoff on the same recordings; otherwise retain the fallback.

### Phase 5 — Choose ASR and speculation policy from device evidence

- Compare current Moonshine streaming with Whisper using the same spoken cases, including onset retention, quiet speech, names, corrections, and long utterances. Report accuracy alongside first partial, finalization, and total reply latency.
- Treat Moonshine as the primary low-latency candidate. Keep Whisper as a measured option or selective recovery path; do not claim its repeated-window decoding is native streaming or change the user's selection silently.
- Expose provisional versus committed recognition internally. For Whisper, make background queue wait and final decode visible; reuse safe results when possible without pretending unprocessed tail audio was recognized. Preserve single-owner native cancellation.
- Start speculative Gemma work on stable meaningful prefixes/candidate pauses with sufficient confidence. Keep at most one active draft; use revision IDs and the existing final-transcript/action guards.
- Use observed draft reuse, cancellation wait, ASR backlog, queued output duration, and thermal status to throttle optional speculation. Do not repeatedly re-encode the full growing utterance when the useful draft rate is poor. Explore supported context reuse only with bounded memory and correct invalidation.
- Preserve the audio fallback when ASR is blank, while avoiding redundant transcription passes when a usable result exists. Partial or draft text cannot authorize a tool.

Primary files: `voice/AsrEngine.kt`, `voice/AsyncWhisperSession.kt`, `voice/WhisperTranscriber.kt`, `voice/MoonshineStreamingTranscriber.kt`, `voice/VoicePreparation.kt`, `voice/VoiceTranscriptResolver.kt`, `voice/FinalVoiceToolGuard.kt`, `ai/LiteRtLmEngine.kt`, `JarvisRuntime.kt`. Proposed file: `voice/VoiceWorkScheduler.kt`.

Acceptance: controlled speculation on/off comparisons show useful latency benefit without worsened recognition or audio starvation. Test a changing action target, blank ASR with real speech, sound-only audio, failed native cancellation, and thermal slowdown. Finalization cannot wait indefinitely for an obsolete draft. Verify correct call memory after any native conversation reset.

### Phase 6 — Stabilize Paul and reduce meaningful-answer delay

- Keep the bundled opening and substantive answer timings separate. The opening must never overlap answer playback or contaminate answer decoder state.
- Run a controlled parity matrix with the pinned model and Paul reference: upstream behavior, patched native callbacks, reset per submission, and continuous acoustic state. Keep text, seed, temperature, flow steps, reference, and input pacing fixed when testing state behavior. Verify callback/returned-PCM equality, complete text coverage, and cancellation.
- Do not equate stable speaker identity with preserving every decoder state. Keep the state policy that passes acoustic and non-truncation checks; update both code and documentation to describe it accurately.
- Reduce text wait by requesting concise natural opening sentences and testing release of short complete openings. Do not split Paul input by arbitrary character counts or claim word-by-word text ingestion from PCM callback streaming. Any clause-level change requires the same listening checks as sentence-level synthesis.
- Tune startup headroom using measured supply. Distinguish AudioTrack capacity from the actual playback start threshold. Never infer a fixed two-second startup delay from a two-second-capacity buffer.
- Keep bounded PCM backpressure and one native owner. If synthesis remains slower than playback, reduce competing optional work; increasing the initial buffer only postpones sustained starvation. Keep normal speech speed unless an explicit comparison setting is selected.
- Evaluate newer Pocket exports only as a separate compatibility/quality experiment. A new upstream release is not automatically interchangeable with the pinned ONNX files or custom patch.

Primary files: `voice/PocketTextStream.kt`, `voice/PocketSpeechPolicy.kt`, `voice/PocketVoiceSpec.kt`, `voice/PaulPlaybackBuffer.kt`, `voice/SherpaKokoroVoiceOutput.kt`, `voice/VoiceResponsePolicy.kt`, `voice/PaulOpeningAudio.kt`, `voice/SpokenCaptionTimeline.kt`, `native/sherpa/pocket-streaming.patch`, `scripts/check_pocket_stream.cpp`.

Acceptance: the same multi-sentence cases retain a recognizable Paul voice without garbled starts, missing/repeated text, or recurring boundary glitches. Verify clean interruption/restart and a long answer while recognition remains active. Record listening results and sustained synthesis throughput on the Fold 6; host speed alone cannot select the default.

### Phase 7 — Complete integrated acceptance and reconcile documentation

- Run the matrix below on a signed update APK, including background and external-microphone transitions. Fix regressions on the existing branch and attach build-specific evidence.
- Review native ownership, coroutine cancellation, event revision guards, tool execution, and stored-history migration together. Re-run only checks affected by a fix or required by CI.
- Update `voice-session.md`, `voice-endpointing.md`, `voice-keyword-interruption.md`, `voice-duplex-and-quiet-speech.md`, `streaming-voice-preparation.md`, `pocket-tts-paul.md`, `voice-playback-buffering.md`, and `per-reply-latency.md` where the implementation changes their claims. Mark historical reports as historical rather than rewriting their measurements.
- Remove superseded production paths once their replacement is accepted and a rollback checkpoint exists. Retain only useful regression fixtures and explicit fallback behavior.
- Record implementation commits, passing checks, phone results, remaining limitations, and the selected default policies in this document. A checklist entry without evidence remains pending.

Acceptance: all correctness gates pass, performance results are reported honestly, the existing PR is ready for review, and the user has a working APK link. Publication of this plan does not authorize merging the PR.

## Measurement goals and acceptance matrix

These are initial engineering goals for short, warm, local, non-tool turns on the Fold 6. They are not industry guarantees, current measurements, or promises for long reasoning/tool calls. Phase 0 establishes the baseline; if a goal is infeasible, record the measured limiting stage rather than hiding delay with an acknowledgement or weakening recognition.

| Measurement | Initial goal or gate |
| --- | --- |
| Last reference speech to first non-silent substantive reply | Aim for p50 at or below 1.5 seconds and p95 at or below 2.5 seconds in the defined warm subset; report endpoint, ASR, model, and TTS contributions. |
| Confirmed interruption to playback pause/flush | Aim for p95 at or below 150 ms at the app/rendering layer; report Bluetooth/route tail separately. |
| User interruption onset to rendering stop | Aim for p95 at or below 700 ms for clear corrections; separately report confirmation delay and false interruptions. |
| Missing opening words / premature endpointing | No failures in the deterministic transition fixtures; list every phone-case failure rather than averaging it away. |
| Playback supply | No recurring underruns or audible starvation in the agreed sustained test; exclude intentional stop and final drain from starvation counts. |
| Paul quality | No garbled starts, omitted/repeated phrases, or unacceptable voice changes in the agreed listening cases. Automated PCM checks alone cannot establish this. |
| Action/history correctness | No duplicate actions, stale-generation execution, or unplayed reply remainder represented as delivered speech. |
| Resources | Bounded input/output/history work, no per-turn model reload without a reason, no progressive memory growth or crash in a 15-minute session. Log thermal effects. |

Use deterministic prerecorded inputs for reproducibility and live speaking for interaction quality. Reference speech-end/onset timestamps must be manually annotated or otherwise independently derived for benchmark clips; a VAD's own timestamp is an operational metric, not ground truth. Collect at least 30 comparable warm turns for preliminary p50/p95 and report the sample count; do not present the tail estimate as a fleet-level guarantee. Use a smaller targeted subset for route and cold-start checks, then repeat only failures or changed paths.

| Case group | Phone scenarios | Required evidence |
| --- | --- | --- |
| Start and follow-up | Cold start, warm call, immediate response after Jarvis, speech during acknowledgement/finalization | Retained first words; mic timeline and model-load counts |
| Pause handling | Finished question, incomplete question, hesitation, 2–3 second thinking pause, revised ending | Endpoint evidence and premature-cutoff count |
| Interruption | Ordinary correction during generation and playback; explicit stop; interruption before first audio and near final drain | Confirmation/stop times, retained correction, playback-aware history |
| Noise and focus | Fan, clothing noise, background speech, quiet voice, backchannels, Jarvis echoing a keyword | False/missed interruption counts and transcript accuracy |
| Long turns and tools | 30/60-second speech, long answer, battery/app request, action target correction | No capacity-triggered action, bounded resources, action completion recorded once |
| Lifecycle and routing | Home/lock screen, Messenger voice recording and return, Pause/Resume, Stop during load/recovery, supported Bluetooth/EYE VUE and phone fallback | Correct mic ownership/restored mode, no restart after deliberate Stop |
| Sustained quality | 15-minute mixed session; same Paul cases with and without concurrent recognition | Audio export when explicitly requested, supply gaps, thermal status, memory, listening notes |
| Persistence | Interrupt, end, reopen saved call, read older records, recover from abrupt termination | Correct migration, delivery metadata, documented checkpoint loss bound |

Raw microphone audio remains bounded in memory for normal use. Benchmark recording/export is an explicit diagnostic action using existing app-private/export facilities; retain no new routine raw-audio history and upload nothing automatically.

## Rollout and recovery

- Preserve a known working signed APK and commit before replacing a major audio path. Keep rollback controls internal to diagnostics/build configuration while evaluating changes.
- Isolate ownership, history migration, turn detection, speculation, and TTS policy changes into reviewable commits. Deploy correctness prerequisites together when partially enabling them would corrupt history or permit stale actions.
- Keep current endpointing and keyword interruption as temporary explicit fallback modes until replacements pass their route-specific tests. Report fallback use in diagnostics; do not present it as natural duplex.
- On backlog or native failure, preserve completed actions and accepted history, cancel the affected generation, and recover the smallest failed component. Never silently discard the start of a command or replay a partly executed action.
- If local completion inference, ASR, and TTS cannot run within the phone's budget, first reduce optional speculative work and classifier cadence. Do not introduce a cloud dependency or another large model as an implicit fallback.

## Evidence and decisions log

For each completed phase, append: phase, commit, APK build/link, device/route, test case IDs, sample count, metrics before/after, acoustic observations, known failures, chosen default, and rollback commit. Keep absolute timing results tied to their build and conditions.

Open evidence-dependent decisions:

1. Smart Turn artifact/runtime suitability and whether it improves real turn completion on the Fold 6.
2. Effective AEC and natural-interruption policy per supported audio route.
3. Moonshine versus Whisper accuracy/latency tradeoff for the user's speech and room.
4. Safe model/session reuse supported by the pinned native APIs.
5. Paul's decoder-state policy, short-opening release, and minimum reliable startup headroom.
6. Speculative reuse threshold and sustained resource budget.

These are implementation experiments with defined gates, not blockers to beginning Phase 0 or requests for the user to choose internal algorithms.

## Primary references

Publication dates below identify recent research. Continuously updated documentation is labeled separately. Research results and laptop/cloud timings are not Fold 6 benchmarks.

| Source | Date/type | Use in this plan |
| --- | --- | --- |
| [Moonshine v2](https://arxiv.org/abs/2602.12241) | 12 February 2026, research | Streaming recognition designed for edge-device latency constraints. |
| [LTS-VoiceAgent](https://arxiv.org/abs/2601.19952) | 26 January 2026, research | Semantic triggering and incremental reasoning as alternatives to wasteful speculative restarts. |
| [FastTurn](https://arxiv.org/abs/2604.01897) | 2 April 2026; revised 13 July 2026, research | Combining acoustic and streaming semantic evidence for turn decisions. |
| [X2Streaming-TTS](https://arxiv.org/abs/2608.18661) | 19 August 2026, research | Incremental text commitment and acoustic continuity; research reference, not an approved Android dependency. |
| [Smart Turn](https://github.com/pipecat-ai/smart-turn) | Current project documentation reviewed 9 September 2026 | Local audio completion candidate; quantized model and input-window contract. |
| [Pocket TTS](https://github.com/kyutai-labs/pocket-tts) | Current upstream documentation reviewed 9 September 2026 | CPU synthesis, streaming output, and retaining model/voice state. |
| [LiveKit turn handling](https://docs.livekit.io/agents/build/turns/) | Current production documentation reviewed 9 September 2026 | Turn completion, interruption handling, and backchannels as behavioral references. No LiveKit/telecom dependency is required. |

