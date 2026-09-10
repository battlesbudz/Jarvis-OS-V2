# Local voice implementation plan

Status: implementation started on `audio-pr2`. Phase 0 checkpointing and initial turn timelines are implemented. Source-PCM measurements and the early Paul isolation runner are now implemented; baseline measurements and phone acceptance remain pending.

Prepared: 2026-09-09. Repository: `battlesbudz/Jarvis-OS-V2`. Working branch: `audio-pr2`, existing [PR #6](https://github.com/battlesbudz/Jarvis-OS-V2/pull/6).

Audit baseline: [`eabf77dbc7d8589247558882bb97efdc9678dc7f`](https://github.com/battlesbudz/Jarvis-OS-V2/commit/eabf77dbc7d8589247558882bb97efdc9678dc7f). Recheck affected code against the branch head before implementing a phase. Findings below describe that baseline, not every future build.

## Outcome and scope

Make local conversation on the Samsung Galaxy Z Fold 6 feel continuous: retain opening words, recognize speech while it arrives, wait appropriately through hesitation, respond promptly, accept ordinary spoken corrections, and keep Paul's voice stable. Conversation memory must reflect delivered speech and completed actions.

The reference is current production voice-agent practice, with emphasis on local inference and primary sources from September 2025 through September 2026. There is no single formal industry standard or universal latency threshold for this experience.

Core constraints:

- Keep the core conversation, recognition, speech, and turn decisions on the phone. Telecom, SIP, phone numbers, and hosted call-center infrastructure are outside scope.
- Retain Gemma's audio understanding alongside ASR. A blank ASR result must not automatically discard confirmed intelligible speech. Sound-only input must not become an invented request. The 2026-09-10 text-first decision below permits selective audio verification; tandem capability does not require processing every recording twice.
- Keep Paul as the preferred voice for this tuning effort. Retain Kokoro as the existing comparison/fallback option. Model selection changes require measured evidence; this document does not switch the saved selection.
- Preserve wake activation, spoken goodbye/stop-listening controls, explicit Stop and Pause, background operation, saved calls, and microphone priority for other apps.
- Gap cues are optional, varied feedback about actual processing. They may recur during a genuine wait; there is no once-per-turn cap. A ready response preempts every cue, including the initial Ummm. Never delay a fast answer to insert filler (user correction, 2026-09-10).
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

## Context for future sessions — lessons from the Apple local-agent resources

Reviewed 2026-09-09 from the user's supplied links. These are architectural references and proposed experiments, not delivered Jarvis features or Fold 6 benchmarks. The video itself was not supplied or reviewed. Keep the phase status and evidence log authoritative; recheck branch head before implementing.

### What the resources establish

| Resource | Verified scope | How to use it here |
| --- | --- | --- |
| [Core AI](https://developer.apple.com/documentation/coreai) | Apple's runtime targets Apple silicon, with CPU/GPU/Neural Engine execution, specialization, caching, and profiling. | Borrow the separation of model preparation, loading, and inference measurements. Its runtime and `.aimodel` artifacts are not drop-in Android dependencies. |
| [Official Core AI Models](https://github.com/apple/coreai-models) | Apple-maintained export recipes, Python primitives, and Swift utilities; the reviewed integration requirements are iOS/macOS 27+ and Xcode 27+. | Study reproducible export and runtime contracts. An open model's original weights may have an Android-compatible path; that does not make its Apple export portable. |
| [Community Core AI Model Zoo](https://github.com/john-rocky/coreai-model-zoo) | Community conversions with recipes and model-specific source-parity evidence; verification strength and tested hardware vary by model. | Require the exact recipe, preprocessing, artifact, runtime patch, and device behind each result. Apply this method to Paul's upstream-versus-patched comparisons in Phase 6A. |
| [SpeechAnalyzer API](https://developer.apple.com/documentation/speech/speechanalyzer) and [Apple's explanation](https://developer.apple.com/videos/play/wwdc2025/277/) | Asynchronous audio input and result delivery; optional volatile results revise earlier text for an audio range before finalization. | Adapt the audio-range/revision contract to our Kotlin pipeline. This is a design reference, not a way to install Apple's recognizer on the Fold 6. |
| [Foundation Models](https://developer.apple.com/documentation/foundationmodels) | Sessions, structured output, and tools; current APIs also support multiple model providers. | Use session/context ownership as a reference. This framework is not itself a speech recognizer or TTS engine, and choosing it does not automatically guarantee local inference. Apple's system model is not an Android replacement for Gemma. |
| [Kokoro-82M Core AI model card](https://huggingface.co/mlboydaisuke/Kokoro-82M-CoreAI) | Non-autoregressive synthesis; its streaming wrapper returns a chunk per sentence. The card reports approximately 0.75 seconds per utterance on M4 Max using CPU execution. | This is an Apple conversion, not evidence of continuous text ingestion or a speed/quality improvement on our phone. It does not establish a fix for Paul. |

### Terminology future changes must preserve

“Streaming” must identify the layer: microphone PCM input, revisable ASR text, LLM token output, incremental PCM within one TTS submission, or playback of completed sentence chunks. Record both TTS submission count and PCM callback count. Neither callback delivery nor smooth playback proves that generation state persists across sentences.

ASR finalization is also distinct from conversational turn completion: finalizing one recognized audio range does not authorize answering while the user continues speaking. Keep generated text, rendered audio, and confirmed/intelligible spoken output distinct.

### Concrete experiments and acceptance evidence

| Work | Phase | Experiment and gate |
| --- | --- | --- |
| Prompt visible recognition | 0, 5 | Represent provisional and committed text with audio start/end positions and revision IDs. Replace revised ranges instead of appending duplicates; show provisional text promptly without a synchronous checkpoint. Measure capture-to-first-visible-text separately from decoder output and finalization. Test revisions, resumed speech, and stale results after Stop. |
| Independent capture and recognition | 1, 5 | Keep timestamped capture independent of heavy decoding and result/UI work with bounded queues and serialized native model access. Measure backlog, retained first words, and finalization wait under concurrent TTS; asynchronous scheduling alone is not proof that an offline recognizer has become natively streaming. |
| Safe early preparation | 4, 5 | Let stable, meaningful provisional text support bounded preparation; reconcile the final utterance before any tool dispatch. Test corrected names and changed action targets. Preserve Gemma audio fallback for real speech while rejecting sound-only hallucinated requests. |
| Model conversion parity | 6A | Compare the same text/reference/settings through upstream and patched paths; retain artifact hashes, export recipe, phonemization, state policy, sample rate, and actual source/converted audio metrics plus listening results. A numerical match is useful evidence, not a substitute for intelligibility. |
| Warm inference and realistic performance | 0, 1, 6B | Separate model load/specialization from warm inference; repeat with ASR, Gemma, and Paul sharing the phone. Record thermal status, route, memory, and first intelligible answer timing. Desktop or isolated TTS results cannot select a live-call default alone. |

These experiments refine the existing phases; they do not add an Apple dependency or require switching models. Retain Paul as the preferred speaker, Kokoro as a comparison, and the measured Moonshine/Whisper choice. Evaluate candidate Android acceleration/export changes separately only when profiling identifies a relevant bottleneck.

### What remains unproven

- The resources do not demonstrate owner-only voice recognition or reliable suppression of TV/radio speech in Jarvis. Evaluate speaker preference, false accepts, missed user speech, and echo handling separately; early background audio must not be treated as proof of the owner's identity.
- A natural demo does not establish interruption correctness, microphone handoff, long-call stability, or history consistent with delivered speech.
- The Core AI Kokoro card does not prove Apple's port is better than our Kokoro path, and its sentence chunks do not explain every Paul tonal change.
- “Free/open-source models” in a video description does not establish that every framework, model, or export shares the same license or platform support. Check the specific component before adopting it.

Resume work by reading the current phase/evidence tables, checking the selected phone profile and build, and choosing the earliest unmet measurement or correctness gate. Preserve existing implementation evidence and record rejected experiments with their reason so a future session does not restore a known regression.

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
| 0 | Baseline measurements and efficient checkpoints | Audited head verified | In progress — checkpointing and initial timeline implemented; device baseline pending |
| 6A | Early Paul source-audio and submission comparisons | 0 — comparable TTS baseline and required export fields | In progress — fixed-submission runner and source exports implemented; phone comparisons and listening pending |
| 1 | Call-scoped microphone and model ownership | 0 | Pending |
| 2 | Playback-aware history and cancellation | 1 | Pending |
| 3 | Natural interruptions and seamless follow-up capture | 1, 2 | Pending |
| 4 | Local turn completion and long-utterance segmentation | 1, 3 | Pending |
| 5 | Measured ASR policy and bounded speculative work | 0, 1, 4 | Pending |
| 6B | Integrated Paul synthesis and tuned audio supply | 6A, 0, 1, 2, 5 | Pending |
| 7 | Integrated phone acceptance and documentation reconciliation | 0–5, 6A, 6B | Pending |

Phase 6A runs early once its Phase 0 measurement prerequisites are available; it does not wait for natural interruptions, a new turn detector, or long-utterance support. Preserve the correctness dependencies among Phases 1–5. Repeat Paul tuning under concurrent recognition in Phase 6B before selecting integrated defaults.

### Phase 0 — Establish comparable measurements

Extend existing `AsrCaptureMetrics`, `TtsSessionMetrics`, `TurnLatency`, comparison stores, and in-app copy/export. Reuse existing clocks and IDs rather than adding a parallel metrics system.

- Record audio sample position/capture time, VAD speech end, provisional and committed ASR, endpoint decision, finalization completion, speculation seal, first usable reply text, first generated PCM, first non-silent rendered reply PCM, interruption candidate/confirmation, playback stop, and microphone readiness.
- In annotated listening cases, also record the first intelligible substantive answer word. Keep this distinct from first PCM and first non-silent playback: breathing, filler, or garble must not count as a successful meaningful response. Mark intelligibility timing unavailable when it has not been assessed.
- Associate generated-silence intervals and speech-active level measurements with PCM sample ranges and submission boundaries. Report generated pauses separately from playback starvation; report level differences separately from perceived speaker/timbre changes. Retain the measurement windows and method so phonetic-content differences are visible.
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

### Phase 6A — Isolate Paul's opening and source-audio inconsistencies early

Use the short-opening evidence below as an investigation baseline, not a quality pass. Run these comparisons after the relevant Phase 0 exports are available, independently of the wider conversation redesign.

1. Generate the exact same complete text as one submission and as the current two submissions. Make all text available upfront in both cases so text-arrival pacing does not confound the submission comparison.
2. Keep the two submissions and all other settings identical; change only reset-per-submission versus supported continuous acoustic state. Record actual native behavior and distinguish cached voice conditioning, LM state, decoder state, and RNG state. Do not carry completed text/EOS state forward without engine support.
3. Compare upstream behavior against the patched native callback implementation using the same compatible pinned model, reference, and generation settings. Verify complete PCM coverage, callback/returned-PCM parity where applicable, text coverage, and cancellation. Host parity is not a phone performance result.
4. Repeat candidate profiles under normal and thermally limited conditions, recording repetitions and separating those distributions. Then restore the original paced-text feed to measure realistic text-wait costs.

Hold reference, seed, temperature, flow steps, playback speed, and startup headroom fixed within each comparison. Begin with the uploaded profile's speed 1.0 and 200 ms headroom; this is an experimental baseline, not a new production default. A short run without underruns does not establish sustained-call reliability.

Required cases: standalone “I understand.”; the exact three-sentence short-opening-v2 text below with its current first-two-sentence grouping; a longer paragraph with early and late sentence comparisons; the bundled “Ummm” alone; and the opening followed by an actual answer. The TTS-only answer export excludes fillers, so it cannot validate the interjection.

For each case:

- Align suspected pauses and level changes with words and submission boundaries. Listen for intelligibility, omitted words, unintended pauses, and speaker identity changes; automated PCM checks alone cannot decide these.
- Preserve the grouped-opening regression protection until shorter submissions demonstrate complete spoken output. The earlier build 621 trace produced only 240 ms for “I understand.”; punctuation or a complete text sentence is not proof of usable speech.
- Do not automatically trim generated pauses or normalize each PCM chunk to conceal a suspected defect. Establish whether the pause is appropriate and whether level changes reproducibly follow state boundaries before selecting a correction.
- Do not increase buffers or alter normal speech speed to address silence already embedded in source PCM. Keep supply problems and synthesis problems separately attributed.

Acceptance: a documented comparison identifies which factors reproduce or improve the failure, includes listening/text-coverage evidence and known limitations, and preserves a rollback profile. Unresolved cause remains explicitly unknown. This milestone does not certify live ASR latency, the bundled opening, or long-call performance without their respective tests.

### Phase 6B — Stabilize Paul under integrated load and reduce meaningful-answer delay

- Keep the bundled opening and substantive answer timings separate. The opening must never overlap answer playback or contaminate answer decoder state.
- Carry forward Phase 6A's measured state policy and repeat the winning comparisons with concurrent recognition and sustained-call load. Recheck PCM/text coverage and cancellation after model-ownership or scheduling changes; do not assume the isolated winner remains best under contention.
- Do not equate stable speaker identity with preserving every decoder state. Keep the state policy that passes acoustic and non-truncation checks; update both code and documentation to describe it accurately.
- Reduce text wait by requesting concise natural opening sentences and testing release of short complete openings only after Phase 6A's intelligibility and non-truncation gates pass. Preserve first-two-sentence grouping when a shorter opening fails; do not trade missing words or garble for an earlier PCM timestamp. Do not split Paul input by arbitrary character counts or claim word-by-word text ingestion from PCM callback streaming. Any clause-level change requires the same listening checks as sentence-level synthesis.
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
| Last reference speech to first intelligible substantive answer word | Aim for p50 at or below 1.5 seconds and p95 at or below 2.5 seconds in annotated listening cases in the defined warm subset; report endpoint, ASR, model, and TTS contributions. Retain first PCM/non-silent rendering as separate operational metrics; do not substitute them when intelligibility is unassessed. |
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

### 2026-09-09 — Phase 0, first implementation increment

Implementation: [`5c67941`](https://github.com/battlesbudz/Jarvis-OS-V2/commit/5c67941ce22e2239b296e1fa5ae05f757d3aca0d), based on `933760a`. Android validation: [build 625](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34412236930): debug and release unit tests/assembly passed, along with native keyword validation, native packaging checks, callback ABI checks, release signature verification, and APK publication. [Signed APK](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.625/app-release.apk) / [release details](https://github.com/battlesbudz/Jarvis-OS-V2/releases/tag/audio-pr2-pr6-build.625). Rollback code baseline: `933760a`.

| Item | Implementation and evidence |
| --- | --- |
| Partial transcript checkpoints | `CoalescingVoiceCallStore` coalesces transient updates over a nominal 500 ms window on one background writer. Controller memory still updates immediately. Identical state updates no longer save again. |
| Boundary ordering | Completed transcript updates, call endings, and cleanup flushes submit the latest snapshot immediately. Revision checks reject stale queued writes and prevent deleted calls from being recreated by a pending timer. Microphone handoff schedules its flush on IO so persistence does not block the main callback. |
| Persistence observability | Runtime summaries expose cumulative progress updates, coalesced updates, writes, write duration, and failures. A failed background save remains available for a later progress/boundary retry; it does not spin. |
| Initial turn timeline | `VoiceTurnTrace` uses the existing turn ID and monotonic offsets for capture readiness/release, recognition finalization, preparation seal, audio fallback, reply dispatch/text/audio, confirmed interruption, stop request, and cleanup completion. Existing ASR comparison export includes available stages; diagnostic summaries also cover turns without comparison entries. |
| Focused automated validation | 25 Kotlin/JUnit tests passed, covering new checkpoint/trace tests and existing controller, coordinator, and latency tests. Includes burst coalescing, final-save ordering, stale timers, deletion, in-flight writes, failure retry, lifecycle flush, and late callbacks. This standalone run does not compile the full Android runtime; Android CI is a separate gate. |
| Independent review | Fixed wrapper property typing and moved handoff persistence off the main callback. Review found no further substantive stale-write or lock-order issues. |
| Phone validation | Pending. No latency improvement, acoustic stop time, cold/warm baseline, or route behavior is claimed from unit tests. |

Limitations and next work:

- The existing SharedPreferences backend still serializes the full saved-call collection per actual checkpoint. Its `apply()` submits disk persistence asynchronously: immediate checkpoints are not an fsync guarantee. Abrupt termination can lose the latest partial window plus scheduler/storage delay; 500 ms is a scheduling target, not a proven crash-loss bound.
- Timeline events are operational timestamps. Recognition finalization is not reference speech end; playback-stop request is not physical silence. Missing events remain unavailable, and first reply audio excludes acknowledgement/filler. Queue depth, acoustic/render-stop confirmation, model-load counts, and the remaining Phase 0 metrics still need implementation.
- Generated-versus-heard history, microphone ownership, and interruption policy are unchanged by this increment. Phases 1–7 remain pending.
- Complete the remaining Phase 0 instrumentation and collect the defined phone baseline before marking the phase complete. Then proceed to call-scoped microphone/model ownership and playback-aware history.

#### Phone smoke checks for this increment — not yet run

Use the same selected ASR, Gemma, Paul settings, and audio route for both builds. Keep raw audio export optional; **Copy diagnostics** supplies the new summaries without a desktop or ADB.

| ID | Action | Check and retain |
| --- | --- | --- |
| P0-SAVE | Ask a short question, let the answer finish, end the call, reopen it from Voice Calls. | Final transcript is present once; capture the checkpoint counters and available `Voice pipeline` stage offsets from Copy diagnostics. |
| P0-INTERRUPT | Interrupt a longer spoken answer with the currently supported stop command, then end and reopen the call. | Latest partial text is retained according to current history behavior; canceled timers must not replace the final saved record. This does not validate playback-aware history yet. |
| P0-HANDOFF | During an active call, start and finish a Messenger voice recording, return to Jarvis, then end the call. | Microphone handoff remains responsive; the saved call includes the latest text; note any checkpoint failure diagnostic. |
| P0-RESTART | After normally ending a call, close and reopen Jarvis. Separately try an abrupt process termination during a partial answer. | Completed history survives normal restart; record actual partial loss after abrupt termination instead of assuming a 500 ms bound. |

For each case record build number, phone/OS, route, selected models, pass/fail, and copied diagnostics. These smoke checks do not replace the 30-turn baseline or the full acceptance matrix.

### 2026-09-09 — Paul short-opening-v2 review and plan amendment

Source: user-supplied `jarvis-voice-short-opening-v2-758e3eda-20e9-4b9c-a30d-2937fbdbf4ea.zip`; run `d0a7e071-00e7-438e-a4b8-7bb9c833d355-12`. This is one exported TTS benchmark pass, not a live ASR/Gemma comparison. The export identifies `streaming-voice-state-v5` but does not identify an exact APK build or commit; do not assign it to build 625 or another build by inference. Audio is not added to the repository by this documentation change.

Input: “I understand. I can keep up with what you are saying and process your requests. I am ready when you are.”

| Evidence | Observation and limit |
| --- | --- |
| Profile | Paul, pinned `sherpa-onnx-pocket-tts-int8-2026-01-26`; two threads, speed 1.0, reset true, leading period false, buffer 200 ms. Pass 2; simulated text feed of four characters every 32 ms after model readiness. |
| Conditions | Thermal status 4 at start/end, `thermal_limited=true`. Keep separate from normal-condition latency baselines. |
| Startup and throughput | Model load 1,803 ms; first text to PCM 1,257 ms; first text to playback 1,757 ms; synthesis 4,657 ms for 6,400 ms source audio (RTF approximately 0.728). These are benchmark timings, not last-user-word-to-answer latency. |
| Delivery | Two text submissions, 18 PCM callbacks, no repeated text calls, zero underruns and zero reported supply gaps. The first submission groups the first two sentences; the second starts at frame 124,800 (5.2 seconds at 24 kHz). Delivery callbacks are not independent text regenerations. |
| Generated pause | Approximately 0.89–1.86 seconds is near-silent using 10 ms RMS windows below 0.001 of full scale. This approximately 0.97-second interval exists inside source PCM and inside the first submission, before the 5.2-second reset. Listening/word alignment is still needed to determine whether the pause is inappropriate. |
| Level variation | RMS over 1.9–4.9 seconds is approximately -22.63 dBFS; over 5.2–6.2 seconds approximately -15.49 dBFS, a roughly 7.1 dB difference. Different phonetic content confounds the comparison; this is not proof of changed speaker identity. No clipped PCM16 samples were found. |
| Scope | Source PCM excludes filler, playback gaps, time stretching, and microphone recording. This review used numerical inspection, not verified listening. Neither “Ummm” quality nor perceived tonal consistency is established. |

Decision: split Phase 6 into early isolation (6A) and integrated validation (6B), add intelligible-word timing and source-pause/level attribution, and retain short-opening regression checks. Warm-model reuse and ASR finalization work remain necessary for live latency, but cannot explain a pause embedded in a TTS-only export. No synthesis default or completed-phase status changes solely from this evidence.

### 2026-09-09 — Phase 0 source measurements and Phase 6A comparison runner

Based on the updated plan at `0d8c195`. Initial implementation: [`34bb525`](https://github.com/battlesbudz/Jarvis-OS-V2/commit/34bb525302c76d525f5570a02acfc46ff05b8d57); [`08a0ebd`](https://github.com/battlesbudz/Jarvis-OS-V2/commit/08a0ebd4e68749d34bf32247eefa2df0331d3e10) adds callback sample parity. Android validation: [build 630](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34415858421): debug and release unit tests/assembly, native keyword validation, ASR native packaging, Pocket callback ABI checks, release signature verification, and APK publication all passed. [Signed APK](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.630/app-release.apk) / [release details](https://github.com/battlesbudz/Jarvis-OS-V2/releases/tag/audio-pr2-pr6-build.630). Rollback application baseline: [build 625](https://github.com/battlesbudz/Jarvis-OS-V2/releases/tag/audio-pr2-pr6-build.625). No production voice policy or saved selection changes in this increment.

| Work | Delivered behavior |
| --- | --- |
| Controlled submissions | **Compare Paul source audio · 14 runs** in existing Voice and response speed diagnostics runs seven cases twice, reversing order on pass two. Standalone “I understand.”, the exact short-opening-v2 text, and the existing paragraph are covered. Compare one versus two fixed submissions with reset, then the identical two submissions with retained state. All text is supplied upfront after model readiness through the same native callback path; exact text coverage is checked before dispatch. |
| Fixed settings | Two threads, speed 1.0, 200 ms startup cushion, no leading period; pinned Paul reference, seed 42, temperature 0.7, five flow steps, and current native chunk settings. The state comparison changes decoder, RNG, and chunk-startup state together; it cannot identify decoder-only causality. Live first-two-sentence grouping remains protected. |
| Source-PCM attribution | Benchmark-only `pocket_source_pcm` events report per-submission source frame ranges, near-silence intervals, active-window RMS dBFS, and PCM16 rail counts. Windows continue across callback boundaries and restart at submission boundaries. Analysis uses floor(sampleRate / 100) samples per window (240 at 24 kHz), PCM16 divided by 32768, RMS below 0.001 for near-silence, and includes the last partial window. Speech-active windows are an energy proxy, not verified speech. |
| Bounded measurements | Only accumulators are retained; at most 128 near-silence intervals are emitted per submission, with omitted count and complete totals. PCM16 rail counts include both ±32767 and -32768; these are saturation proxies, not proof of float-domain clipping. Interrupted submissions are marked incomplete. Source measurements describe accepted callback PCM; the WAV remains bounded to 180 seconds and trimmed to playback-head progress. Match its exported start/end frames before aligning audio and measurements. |
| Reproducible exports | Existing per-run ZIP includes source analysis in its boundary log and immutable result metadata with planned submissions, input delivery, build version/code, CI checkout commit, device/Android SDK, actual model/reference/vocabulary hashes, generation settings, and native policy provenance. CI checkout SHA may be the PR merge commit; local builds report it unavailable. Hashes are collected once before timed runs, with cancellation checks. This pre-reads model files, so the suite is not a cold-filesystem benchmark; each case still creates a fresh native instance. |
| Callback/returned PCM parity | Benchmarks hash the exact float32 little-endian samples across callback boundaries and compare with the returned array, in addition to existing frame-count/rate checks. Same-length corruption, reordering, missing/duplicate frames, and late callbacks have fixtures. A mismatch fails the run and remains visible in its exported trace. Checks retain no duplicate utterance and add benchmark overhead included in synthesis timing. This verifies the current patched path only; upstream parity and intelligibility remain unassessed. |
| Honest quality fields | First intelligible word timing remains unavailable and intelligibility is explicitly not assessed. PCM energy, source silence, callback delivery, and non-silent playback are not automatically promoted to speech quality or word coverage. |
| Validation | 35 focused Kotlin/JUnit tests passed, covering source-window invariance, source offsets, truncated reports, saturation rails, unavailable intelligibility, paired-text consistency, immutable run metadata/profile isolation, artifact hashing and cancellation. Independent review completed; full Android validation passed in build 630 (linked above). Reviewer corrections: count symmetric PCM16 rails and describe the actual combined synthesis-state reset. |

Phone procedure: end the active call, select Paul in **Voice and response speed**, tap **Compare Paul source audio · 14 runs**, and use **Export this run’s audio + diagnostics** for the relevant cases. The comparison does not apply its experimental settings to calls. Stop remains available and completed records remain saved. Fourteen recordings fit within the existing 24-run cache; older recordings can expire after further benchmarks.

Compare `opening-one-reset` against `opening-grouped-reset`, then `opening-grouped-reset` against `opening-grouped-retained`, with matching pass and thermal conditions. Repeat the paragraph pairs and listen to `standalone-reset` for missing words. Record whether each word is intelligible, suspected pause locations, and perceived voice changes; numerical levels alone are not a verdict. Keep thermally limited results separate. The existing paced profile test restores text-arrival pacing for follow-up comparisons; its natural grouping must be read from its trace rather than assumed identical to fixed groups.

Still pending: phone listening/text-coverage results, normal versus thermally limited comparisons, first intelligible answer annotations, upstream-versus-patched parity, bundled Ummm and opening-plus-answer evidence, and integrated ASR/Gemma load. No root cause or winning state policy is selected by this implementation. Phase 6A and Phase 0 remain open; broader Phase 0 metrics and Phases 1–5/6B/7 remain on the roadmap.

### 2026-09-10 — Live repetition repair and gap-cue correction

Evidence: user-supplied build 630 live-call trace, turn `aa50ba9f-29cb-4928-835c-e1a08e44ad29`. The initial model pass took 5,338 ms (first raw token 4,232 ms); repetition repair then took approximately 29,058 ms and withheld its output until completion. Endpoint-to-first-accepted-text was 35,680 ms. Ten cached “One second.” cues played before the first Paul answer submission. Only one answer submission appears before the user's explicit Stop, and no first answer PCM is logged. This trace does not establish repeated Paul synthesis or explain all profile-dependent sound differences.

User clarification overrides the old always-initial-cue policy: use varied cues during real gaps, allow recurring feedback, and prioritize a ready answer over every filler. The initially proposed once-per-turn cap was rejected and is not implemented.

Implemented in `f7508a0` (streamed repair and gap-only cues) and `8784965` (new-stage cue priority). [Android build 633](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34420702803) passed debug and release unit tests, assembly, and signed release publication. [Install signed build 633](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.633/app-release.apk); its release targets `878496508b477f3f4284e06b9f4e2a5b90bee91e`. Phone validation remains pending:

- `VoiceRepetitionRepair` streams repetition-checked sentences from the read-only retry. It stops at two accepted sentences, 1,600 input characters, or a 10-second generation budget. Tools are disabled and the retry never enters action dispatch. Timeout/Stop discards unfinished text; parent cancellation propagates. The existing native cancellation/terminal-callback cleanup remains mandatory and can add its existing wait beyond the generation budget; this is not a strict ten-second wall-time promise. If nothing usable is accepted, the existing short failure response remains available rather than starting another repair.
- `DelayedAcknowledgement` waits an initial 700 ms gap, then spaces further cues by 3,500 ms after cue playback. Ready answer PCM cancels and joins any active cue, including Ummm, or any pending gap wait. It never waits for filler cache preparation. The initial cue is no longer compulsory.
- Recurring cues rotate “One second.”, “One moment please.”, and “Just a second.” Available stage-specific cues correspond to recorded processing stages: recognizing the recorded utterance, generating an answer, or preparing answer audio. Generic phrases make no fabricated lookup/action/success claims. Adjacent duplicate cached clips are avoided; unavailable clips are skipped or replaced by another available neutral clip.
- Missing clips are prepared and cached on the existing serialized native owner only while no answer text has arrived. Ready token processing wins over queued optional preparation. Optional synthesis uses the stable Java callback and stops at a native PCM callback boundary when answer text arrives; partial filler PCM is discarded and never cached. Paul filler preparation is restricted to before the first answer submission; the answer then initializes its own session. Native code owns one stream, so this restriction must remain to avoid replacing active answer state. Native work is not instantly preemptible before its next callback; playback priority is enforced once answer PCM is ready. Diagnostics report the observed stage and chosen cue. There is no new cloud or telecom dependency.
- Focused Kotlin/JUnit validation: 26 tests passed for fast-answer skipping, cancellation of an active initial cue, missing-cache behavior, recurring variations, stage changes, streamed repair, two-sentence/character limits, timeout suffix removal, Stop propagation, final-only replies, and existing repetition guards. Independent review found and corrected uncancellable optional filler synthesis and control syntax after prose in repair output; full debug and release Android CI passed in build 633.

Phone acceptance pending: repeat a short question (no unnecessary initial filler), the story request and a follow-up that triggers repetition repair (checked text must become available before a long repair completes), a sustained generation gap (varied cues), Stop during a cue/repair, and another-app microphone handoff. Compare timing and quality separately. The original 14-run Paul source-audio comparison remains available; this live-control fix does not select a winning Paul profile or mark Phase 6A complete.

### 2026-09-10 — Preferred Paul profile and measured latency priorities

User listening evidence: build 630 profile `threads-4-native-stream-speed-1.0-reset-true-period-true-buffer-200` was the best of the tested profiles, with a better Ummm and no reported garble in that sample, but remaining voice variation. Preserve this as the user's comparison baseline: four threads, native streaming, speed 1.0, fresh decoder per sentence group, leading period enabled, 200 ms base cushion. This is a user preference, not an objective quality winner or a newly changed global default.

Evidence: text run `06292b4f-418e-4de3-a401-e55410947b3c` and live call `a231baea-138c-4d79-88d3-a6b82cb36eb4`. These are build 630 measurements, before the gap-cue/repair correction above.

| Observed cost | Evidence | Interpretation |
| --- | --- | --- |
| Final recognition to first accepted answer text | 5,805 ms and 4,996 ms in the two complete latest turns | Largest measured post-recognition delay; first raw token alone takes roughly 3.8–4.7 seconds, followed by about 0.9 seconds to checked sentence publication. Prepared and native TTFT use different origins and must not be added. |
| Accepted text to first PCM | 812 ms and 811 ms | Paul startup is material but smaller than answer generation. |
| First PCM to confirmed playback | Approximately 766 ms and 553 ms from TTS summaries | Includes 454 ms and 402 ms of explicit cushion plus playback setup. |
| Final recognition to first answer playback | 7,405 ms and 6,381 ms from monotonic stage offsets | Compare with speech-end-to-playback of 9,583 ms and 7,345 ms; additional recognition/endpoint work occurs before finalization. Do not add model load or cumulative checkpoint totals again. |
| Sustained synthesis | Standalone 28,838 ms for 29,360 ms audio (RTF 0.982); latest short live reply 3,861 ms for 2,960 ms audio (RTF 1.304) | The standalone run has little throughput margin; live short-utterance startup and contention can matter. These are different workloads, not a controlled slowdown measurement. Standalone thermal status was 4 throughout and flagged limited; live thermal status is absent. |
| Audible opening | First two live PCM chunks total 640 ms, with peaks approximately 0.0085 and 0.0053; the next chunk peaks at 0.513 | Playback confirmation is not first intelligible speech. Measure leading quiet audio before claiming perceptual latency; low peak alone does not prove silence or justify trimming speech. |

Source-confirmed causes and next experiments (not yet implemented):

1. **Measure and reduce repeated Gemma work while preserving the ASR/Gemma tandem.** `JarvisRuntime` sends audio plus text to `generateAudio` even after successful Moonshine recognition; `gemmaTranscriptionFallback=false` only excludes the separate fallback transcription pass. Speculation resets the native conversation and sends updated audio/history again. This trace starts drafts at 18, 50, 73, and 129 characters; the final draft begins only about 392 ms before final recognition. Instrument audio duration, time in queue, conversation reset, inference startup, and draft reuse/discard costs. Benchmark a more selective stability policy and shared-prefix reuse only where native APIs support it. Prompt character counts exclude the audio workload. Do not silently replace the required tandem with transcript-only inference.
2. **Make selected versus effective buffering explicit before reducing headroom.** `SherpaKokoroVoiceOutput` applies `PaulPlaybackBuffer.target(base)` in live calls but bypasses adaptation in benchmarks. The shared adaptive buffer adds 100 ms after observed starvation and removes 50 ms after a clean turn, with a 600 ms target cap. Thus the selected 200 ms base became 500/450/400/350 ms in these live turns. Export base, adaptive increment, effective target, and adaptation reason together; scope adaptation by relevant profile/route. Benchmark PCM-supply-aware release against the timer. The 2,000 ms AudioTrack capacity is not a compulsory two-second delay. A single drain-only underrun is not evidence of audible mid-answer starvation.
3. **Measure first speech and preserve voice quality.** Add a conservative source-speech onset metric alongside first PCM and playback confirmation, and compare period-on/off using matched text and thermal conditions. Do not blindly strip the first 640 ms, lower synthesis steps, increase playback speed, remove the leading period, or alter decoder policy based on this trace. Thread-count and thermal comparisons need matched sustained live workloads, not only isolated text runs.
4. **Reduce repeated model setup only with safe ownership.** Paul reloads for each live turn (about 2.1 seconds here), mostly overlapping microphone capture. Reusing a model across turns may reduce CPU/memory churn, but is not an automatic two-second reduction in post-speech latency. Retain per-submission decoder isolation and mandatory cancellation cleanup; benchmark contention and memory before adopting session reuse.
5. **Track response correctness separately from speed.** The reply “What changed in the latest follow-up?” closely echoes wording in `VoiceResponsePolicy`; this suggests instruction leakage, not proven acoustic corruption. The claimed “Help us location” is unsupported by any action result. Evaluate prompt simplification and conversational acknowledgements against repeat/replay, correction, story, and ambiguous-transcript cases. Do not interpret this call's ASR transcript as verified intended user speech.

Acceptance still pending: repeat the preferred profile under cooler and sustained conditions, compare the same questions before/after the current build, and record speech-end-to-first-intelligible-answer, streamed repair latency, mid-answer gaps, voice consistency, and response relevance. No speedup figure is claimed from source inspection alone.

### 2026-09-10 — Subsecond Gemma target and spoken tool progress

Status: approved direction added to the plan; not implemented or benchmarked by this documentation update. Supplements Phases 0, 1, 5, and 6B. Preserve the current Paul comparison baseline. The user's earlier approximately 0.53-second Gemma first-token benchmark is a reference to reproduce, not proof that the complete voice workload already meets that latency.

**Target:** under 1,000 ms from a ready text request to Gemma's first nonempty answer token in a warm, ordinary conversational turn. Record queue wait and native generation TTFT separately, and report median/p95 with prompt size, audio duration, model/runtime identity, thermal state, and concurrent workloads. Tool progress or filler does not count as the first answer token. Continue measuring speech-end-to-first-intelligible-answer independently: recognition, sentence commitment, synthesis, quiet PCM, and playback can add latency after inference begins. Cold starts and long-context/tool turns must be reported separately rather than hidden in the target.

Implementation sequence:

1. Reproduce the earlier text-only benchmark inside Jarvis with matched model/backend/settings and a warm engine. Confirm the benchmark provenance before comparing numbers.
2. Add costs incrementally: representative conversation history, audio input, active ASR/TTS/interruption listeners, then speculative revisions. Attribute time to request queueing, context preparation/reset, audio processing where observable, first token, checked sentence, first PCM, and first speech. Do not label the whole five seconds as text decoding or infer unsupported native substage timings.
3. Make reliable ASR text the normal fast input to response generation. Use Gemma audio understanding selectively for blank, unstable, ambiguous, or conflicting recognition and speech/sound requests requiring audio. Establish evidence-based uncertainty signals; do not assume Moonshine provides calibrated confidence. Retain the recording until the turn is resolved. This explicitly permits a text-first path while preserving the required Gemma/ASR tandem and empty-transcript fallback.
4. Reduce cancelled drafts, repeated audio/history processing, and unnecessary conversation resets. Keep expensive model state warm under a single safe native owner; reuse context only where the pinned runtime supports it. Do not create two concurrent Gemma instances by default. If verification changes the request, invalidate stale drafts and queued speech; never dispatch a device action from an unvalidated speculative transcript. Already delivered speech cannot be silently retracted.
5. Benchmark against build 630/633 with matched questions, ordinary context, cooler starts and sustained calls. Record observed gains and accuracy regressions before marking the target achieved.

**Natural tool progress is a required part of the voice design.** Tool execution should run asynchronously while short, grounded progress text is delivered to the same speech pipeline. This can overlap lookup I/O with speech without requiring two Gemma engines. Do not assume the pinned Gemma API continues decoding after a structured tool call: finish that generation at its supported boundary, dispatch the tool, emit progress from actual runtime events, and resume grounded answer generation when results arrive. Optional model-written wording must be bounded, read-only, and must not delay the lookup or the final answer; concise event-based wording is the dependable baseline.

| Runtime evidence | Permitted speech | Release condition |
| --- | --- | --- |
| A lookup is selected and about to be dispatched | “Let me check that.” | A genuine wait exists; omit if the answer is already ready. |
| Wikipedia lookup actually starts | “I'm checking Wikipedia now.” | Matching tool-start event for the current request. Never say this for a local-only lookup. |
| A local knowledge-base lookup actually starts | “I'm checking my saved information.” | Matching local lookup-start event. Do not imply network activity. |
| A result is received and usable | “It looks like…” followed by supported findings | Relevant result is available and the spoken claim is grounded in it; do not invent findings while waiting. |
| Lookup fails or finds nothing useful | A concise truthful failure/no-result explanation | Actual failure/no-result event; no false completion claim. |

All examples are optional wording, not an obligatory script. Coalesce redundant events, vary wording during genuine waits, and allow recurring useful updates rather than a once-per-turn cap. A ready answer takes priority over pending progress and can cancel current cue playback. Use one serialized speech queue so progress and answer audio never overlap or duplicate. Bind every event, tool result, and speech item to the turn/request revision; Stop, interruption, or a corrected request must cancel or discard obsolete work. Record spoken progress separately from delivered answer content and confirmed tool outcomes so it does not pollute answer memory or latency measurements. Existing final-intent/action authorization rules remain authoritative; routine progress must not introduce extra user confirmation prompts.

Acceptance cases: instant result (no unnecessary preamble), slow local lookup, slow Wikipedia lookup, multiple tool steps, timeout/failure, result arriving during progress speech, Stop and correction during lookup, and stale results after a new turn. Verify natural continuity, truthful source/action wording, no premature factual claims, no progress/answer overlap, and no duplicate tool execution. Preserve phone-only diagnostics and the offline conversational path; Wikipedia remains an explicitly network-dependent tool.

### 2026-09-10 — Warm Gemma input-cost comparison implemented

Implemented in `f784e86` and validated by [Android build 636](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34424197963): a new **Compare Gemma text vs audio** action in the existing voice diagnostics dialog. It reuses the existing Gemma benchmark lifecycle and exclusive model-operation guard. Finish a spoken turn and end/disarm the call before starting. The three modes are a short text request, the request with captured conversation context, and the exact same contextual prompt plus the captured audio. Three passes rotate the first/middle/last position of every mode. The same GPU Gemma engine stays loaded; each measured case gets a fresh conversation, with tools unavailable and no executor or speech playback. Text and audio warmups are outside measured requests. Live routing and the applied Paul profile are unchanged.

The latest eligible spoken input is retained as one process-local sample, bounded to 1 MiB audio, 4,000 transcript characters and 32,000 contextual prompt characters; invalid/oversized samples are rejected rather than truncated into mismatched text/audio. The sample is replaced on the next eligible turn and cleared when voice diagnostics start a new session or the process exits. Audio is not written to preferences or exported. Benchmark results persist generated output, prompt/audio hashes, input sizes/duration, source turn and capture time, runtime/model/build provenance, model loading, conversation reset, thermal start/end, process memory, raw-text TTFT, filtered first answer text, checked sentence time, and SDK submission/first-message-callback timings. The copy controls export the selected result.

The target flag uses filtered first answer text under 1,000 ms, and is unavailable if there is no accepted answer, a tool call, or detected repetition. Raw SDK text and first native callback are explicitly separate: callbacks can contain control/tool output. SDK submission time is not labeled audio-encoder time. Unobservable encoder internals and live request queueing remain unavailable. The short-text case is a reproducible in-app baseline, not a claim to reproduce the exact earlier OGAM prompt/configuration. Three passes are exploratory; 30+ comparable phone turns are still required for the plan's preliminary p50/p95 gate.

Stop propagates through native cancellation and cleanup. Each measured generation has a 30-second budget plus the existing native cleanup wait; an inference failure is saved and ends this comparison rather than reusing that native instance. Model initialization/warmup errors are also saved. Existing acceleration comparisons remain available. Live inference diagnostics now record actual text/audio/image input mode, SDK submission duration, and first native callback without adding an inference pass.

Validation: four focused Kotlin/JUnit tests passed for sample bounds, independent ownership of captured bytes, exact text/audio pairing, and rotation of case order. ECG-guided source review checked native ownership, cancellation, input pairing, metric scope, model-operation guards and UI integration. Full debug and release unit tests, assembly, native packaging and signing checks passed in build 636. [Install the signed APK](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.636/app-release.apk); the release targets `f784e86c7b0b9888680920be9c9c57cee91b0202`. No subsecond result, overall speedup, or phone quality acceptance is claimed yet.

Next: run the new comparison on the Fold 6 to distinguish contextual prompt and audio costs; then implement the approved selective audio/text-first and speculation policy with those measurements. Concurrent spoken tool progress remains planned, not implemented by this increment. Pipecat is a coordination reference; the production implementation remains Kotlin with Gemma-native tool requests and existing tool executors. Source references verified in this session: [Pipecat's actual task and intermediate-result implementation](https://github.com/pipecat-ai/pipecat/blob/main/src/pipecat/services/llm_service.py), [its function-calling examples including speech from a handler](https://docs.pipecat.ai/pipecat/learn/function-calling), and [Google's Kotlin manual tool-calling and streaming examples](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md#manual-tool-calling). These establish reusable coordination patterns and runtime building blocks, not a verified complete Gemma E2B/Android/Paul implementation or a Fold 6 performance result. Inspect version compatibility before adopting an API from current upstream into the app's pinned LiteRT-LM 0.12.0.

### 2026-09-10 — Compact voice prompts and shorter answer openings

Implemented in `81ccd60` and `239a00b`; [Android build 639](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34428025214) passed. Phone verification is pending. Build 636 matched comparison `24e24c97-bafd-4c22-9dbe-0dbc49108869`, repeat 3, showed first answer text at 367 ms for 143-character SHORT_TEXT, 2,880 ms for 6,370-character CONTEXT_TEXT, and 3,499 ms for the same contextual prompt plus 7.4 seconds of audio. Checked text arrived at 2,144 / 4,084 / 5,283 ms respectively. All three cases reported thermal status 4 with ASR/TTS idle. The observed context difference was 2,513 ms and the added-audio difference 619 ms; these single-pass differences are diagnostic evidence, not guaranteed additive savings. Subsecond warm short-text generation is demonstrated; full-context voice has not met that target.

The production voice path now builds one compact policy instead of combining the generic chat instructions with another long voice policy. Live, speculative, captured comparison, factual retry and repetition-repair prompts use the same builder. Voice instructions are included in prompt budgeting and reported prompt length, rather than appended after those calculations. For the same empty-history dog request, the actual builder produces 998 characters versus 2,976 previously (1,978 fewer, approximately 66%). This fixture measures prompt overhead, not the user's full 6,370-character captured prompt or a measured latency gain. Historical live promptChars omitted the appended voice policy, so cross-build length comparisons must account for that change.

Compact context headings and exact duplicate topic-anchor removal reduce additional input without lowering the existing recent-history, summary or context budgets. An older topic outside the recent window, or details absent from the truncated recent entry, remain included. Recall, pending story acceptance, current corrections and verified tool evidence remain represented. The voice policy asks for a complete 4–8-word answer opening, then requested detail, and starts stories directly. This targets the measured delay between first token and checked sentence while retaining sentence-based repetition checks and Paul's natural sentence input. Model compliance and audible improvement require phone testing; no arbitrary mid-sentence release was introduced.

Validation: eight focused Kotlin/JUnit tests passed for context preservation, exact duplicate removal, long/older topic retention including duplicates beyond the context cap, seed omission, recall/story handling, verified results, and separation from ordinary text-chat policy. Source review checked all voice prompt call sites and removed external voice-policy appends. Android debug and release unit tests, assembly, interruption keyword verification, ASR native packaging, Pocket callback ABI and release signing checks passed in build 639. [Install the signed APK](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.639/app-release.apk); its release targets `239a00be91e3bc075b46798fa666b4560cc85ccd`. Audio routing, native conversation ownership/reset behavior, history budgets, gap-only answer-prioritized cues and the Paul profile are unchanged. This patch adds no inference pass. Selective audio routing, safe context reuse and asynchronous spoken tool progress remain separate planned work.

Phone acceptance: repeat the same dog question and captured text/audio comparison; compare first answer, checked sentence and first intelligible speech separately. Also check a correction, a recalled detail, a multi-turn story, a verified phone action and a lookup. Do not mark the subsecond full-context target or voice-quality gate complete until these are measured.

### 2026-09-10 — Early grounded sentences and inter-sentence gap cues

Implemented in `58024b6` and `2a75584`; [Android build 642](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/34431698546) passed. Phone acceptance is pending. Build 639 call `c0740b6f-efae-4499-a01d-810989068665`, rock-and-roll turn `b2f0e918-cfd7-4b53-be84-173fdbf2b388`, exposed a full-answer hold on factual/reference responses. Although the lookup succeeded, the first checked text was released only after the 8,315 ms generation finished. The input contained 7,754 prompt characters and 380,844 audio bytes; native first token took 5,349 ms. Endpoint to first checked text was 13,352 ms, and reported speech-end to playback was 18,064 ms. A short answer alone cannot remove input processing, lookup or endpoint delays. The logs do not expose every substage needed to attribute all of that time.

With reference evidence already supplied and no current action intent, voice answers now publish complete sentences through the existing protocol/repetition checks as generation continues. Each sentence also passes the existing knowledge-gap phrase filter. Unverified local-factual drafts still require their existing verification/fallback path; tool results retain their execution gates. Once sentences have been delivered, a later knowledge-gap fragment is suppressed without replaying or replacing that delivered answer. If all draft sentences are rejected, the final safe fallback can still be delivered. This is evidence-conditioned streaming with the existing phrase checks, not a new semantic fact verifier or a guarantee against hallucination.

Pocket now releases a complete short opening even below four words, instead of joining it to the next sentence. Subsequent short sentences keep their grouping policy. Gemma remains one generation stream; Paul starts the opening while subsequent text is generated. No second Gemma pass, extra model instance or arbitrary word/token-based TTS submission was added.

A boundary marker on the bounded native PCM queue lets the single playback writer distinguish a completed sentence group from temporary starvation inside a sentence. After previous answer PCM has actually drained, 600 ms of genuine waiting can trigger cached `Ummm...`; recurring gaps may cue again after 3,500 ms. Native synthesis and Gemma continue while the cache plays. The writer cancels and joins the cue before writing newly available answer PCM. A ready chunk, channel completion, Stop, pause/interruption or producer failure takes priority; cues never mix with answer playback, never require native synthesis, and are excluded from answer text, PCM trace, captions and answer-frame counts. Cue playback time is excluded from the supply-gap estimate. Existing underrun counters can still include an empty track at a sentence boundary/drain, so they are not a count of audible defects. Explicit `sentence_gap_filler` / `sentence_gap_filler_finished` events identify these waits. PCM completion is published before native release so model cleanup cannot trigger a trailing cue. Benchmarks remain filler-free.

Initial gap cues now prefer the cached requested `Ummm...` before later stage/variation cues. The 700 ms initial gap threshold and answer priority remain. In the supplied log, `Ummm...` started late, shortly before first answer PCM, without a speech-frame-rendered confirmation; audibility cannot be inferred from the playback-start event alone. The new preference does not force an um ahead of an already-ready answer.

Limitations: inter-sentence cues cover real pauses; they do not insert words into mid-sentence underruns or manufacture pauses to finish a filler. The supplied Paul run synthesized 7,040 ms of audio in 8,317 ms with 248 ms estimated supply gaps. Sustained synthesis slower than playback still needs performance/headroom work. The full-context first-token target, acoustic clarity and continuity remain phone acceptance gates.

Validation: 32 focused Kotlin/JUnit tests cover gap timing after drain, immediate-ready and completed streams, no mid-sentence cues, repeated waits, cancellation/producer failure cleanup, short-opening release, sentence filtering/fallback, repetition handling and initial recorded-um preference. Android debug/release unit tests and assembly, interruption keyword models, ASR native packaging, Pocket callback ABI and release signing checks passed in build 642. [Install the signed APK](https://github.com/battlesbudz/Jarvis-OS-V2/releases/download/audio-pr2-pr6-build.642/app-release.apk); the published release targets `2a7558404c3b37799c9bbff9a63d1f86689fa6e9`. Phone checks: short answer without filler, longer answer with a deliberate genuine sentence gap, the same reference-backed question, Stop/interruption during an um, and no duplicate or clipped words when the next PCM arrives. Inspect first checked text and first answer playback independently from filler events.

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

