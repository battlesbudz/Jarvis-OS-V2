# Voice repair: bounded commits and phone test protocol

Prepared: 12 September 2026. Branch: `audio-pr2`. Source baseline: `93a144c` (build 673 investigation). Status: **documentation published; all implementation steps below are planned, not completed**.

Companion: [research proposal](voice-repair-proposal-2026-09-12.md). Parent roadmap: [local voice implementation plan](local-voice-implementation-plan.md). Continue in the existing Audio PR2 PR; no merge without Justin's explicit approval. This document defines future code work; publishing it does not claim that work is implemented or tested.

## 1. What Justin should do on every test build

The developer must send one signed APK and a short **build test card**. Justin follows that card, not every test in this document. Routine builds get a small screening pack; larger acceptance packs run only at the named milestones. A docs-only build is not a voice fix and requires no phone testing.

1. Install the specified signed APK over the existing installation. Do not uninstall, clear app data, or download arbitrary models. If installation fails, report it and stop.
2. Open Jarvis and verify the displayed build number matches the card. Copy diagnostics if it does not.
3. End any active call. Keep learned speaker preference, permissions, and accessibility configuration unchanged unless the card explicitly tests one of them.
4. Set up the same room, phone position, route, and volume described below. Let the phone return to its normal idle temperature for a cool test. Warm tests have a separate script.
5. Run only the pack listed on the card, in its specified order. With the planned harness, select its named pack and let it set the temporary profile. Do not touch tuning sliders between trials.
6. Mark each trial Pass, Fail, or Invalid. A wrong phrase/timing or external interruption makes a trial Invalid; a Jarvis miss, timeout, crash, or garble is Fail. Keep failures even if a later retry succeeds.
7. Save/export the result before another test can overwrite it. Then attach the report and requested source WAV here. If the new harness is not available, use the existing buttons listed in section 3 and the manual template in section 11.
8. Stop after the assigned pack. More random questions will not improve the controlled comparison. Normal use is welcome afterward, but label its evidence `exploratory` separately.

### Fixed physical setup

- Device: Samsung Galaxy Z Fold 6. Default route: built-in phone speaker and microphone; disconnect Bluetooth/headphones for this baseline. Fold the phone closed, put it on the same table stand, microphone unobstructed, and sit approximately 50 cm away.
- Use the same quiet room and normal speaking voice. Do not whisper, shout, or move the phone unless a named route/noise test requires it. Keep case and orientation the same.
- Baseline playback volume: nearest available setting to 50% on the active playback stream. The harness must record stream type, current step, and maximum step; reuse that exact step. Before the harness exists, take a volume-panel screenshot and note the approximate setting.
- Keep Battery Saver off. Start unplugged with at least 40% charge. Record battery and thermal status; pause an invalid cool-baseline run if it starts already thermally throttled. Never hide a warm-test slowdown by cooling the phone mid-pack.
- Download required models beforehand, then disable Wi-Fi/mobile data for the controlled offline pack. Do not change networking during a trial. Connected messaging/microphone-handoff tests are a separately labeled exception.
- Silence notifications for the pack if convenient, but leave the same device configuration across comparisons. Do not run other benchmarks, videos, or calls. Do not reset learned voice preference to rescue a failed test.
- Restore the user's previous settings after the pack. The harness must snapshot and restore the saved voice/profile; interrupted harness runs must recover the snapshot on next launch.

### Profile contracts

| Profile | Fixed values | Purpose |
| --- | --- | --- |
| `B673-reference-v1` | Paul; Moonshine; native streaming; 4 threads; speed 0.9; fresh decoder; leading period on; selected cushion 200 ms; seed 42, temperature 0.7, 5 generation steps | Reproduce the reported saved profile. Actual effective startup buffering must also be logged; a UI cushion is not the full buffer policy. |
| `Paul-source-control-v1` | Existing 14-run source-audio suite: 2 threads, 1.0 speed, 200 ms benchmark cushion, fixed suite variations | Isolated voice quality; leaves saved call settings intact. |
| `candidate-<commit>-v1` | Explicit manifest on each card, including every difference from the reference | Exactly one experimental change per A/B pair, unless the card identifies an inseparable implementation change. |

The reference profile is a reproduction setting, not a recommendation to keep it forever. `Restore call defaults` is **not** a substitute: defaults can change between builds. Voice quality comparisons use 1.0x playback for both A and B unless speed itself is the variable. Do not infer a synthesis speedup from slowing playback.

## 2. Delivery rules and ordering

Each numbered item below is one bounded, compilable commit, pushed separately after its required checks. Do not mix model changes, routing, and interruption thresholds in the same commit. Mark experimental paths disabled by default until their comparison passes. Keep a known accepted profile addressable. A revert is a forward commit on audio-pr2; never rewrite the branch or require installing an older APK over a newer version code.

Every code push must pass affected deterministic tests and the repository's Android debug/release, native packaging, callback ABI, and signing gates before it is presented as an installable test build. Lightweight docs/link validation suffices for this documentation publication. The existing PR workflow may still run for a docs-only push; its APK is not a behavioral milestone.

The developer, not Justin, owns build logs, race tests, callback ordering checks, branch state, source analysis, and packaging. Justin owns the scripted physical speech and listening judgments. No ADB or desktop is required on Justin's side.

Dependency order: A1 → A2 → A3; then B1/B2/B3, C1/C2/C3, and D1/D2/D3 use A's evidence. D1's measurements should be collected early, even while B/C are investigated. D3 is conditional. E1 requires usable D acoustic results; E2 requires E1 and C recognition readiness; F1 integrates all accepted paths. F2 closes acceptance and synchronizes documentation.

## 3. What exists now versus what must be added

Verified against the source baseline:

| Available now | Exact label or phrase | Use |
| --- | --- | --- |
| Voice settings dialog | `Voice settings` (settings icon), `Copy diagnostics`, `Save latest reply audio` | Current runtime evidence. The latest reply WAV may be overwritten by later speech. |
| Voice comparison dialog | `Voice and response speed` | Open the existing voice/speed dialog. The build card must include a screenshot if navigation changes. |
| Paul source suite | `Compare Paul source audio · 14 runs` | Already fixed and repeats cases; do not run the broad all-profiles suite. |
| Per-run audio export | `Export this run’s audio + diagnostics` | Save the particular comparison run rather than an unrelated latest-call WAV. |
| Normal call test | Say `Start the interruption test` | Fixed Mira/river passage through the ordinary playback/interruption path. It is not a guaranteed two-minute story. |
| Wake test | `Test wake word` | Passive keyword diagnostic; does not prove keywords work over Paul. |
| Recognition comparison | `Compare both on one 8-second recording`, `Copy recognition results` | Optional diagnostic when specifically assigned; do not change the selected ASR. |

**Not available yet:** named test packs, locked manifests, countdown/visual speech cues, automatic run labels, per-trial grading, complete timed story capture, and one bundled test report. A1–A3 must implement these. Do not tell Justin to press a planned button before a build actually contains it.

Until A1–A3 ship, use only the small current-build baseline in section 6. The new pack entry should sit inside the existing diagnostics facilities, not expand the normal voice conversation UI into a tuning console.

## 4. Commit-by-commit implementation plan

All paths below refer to `app/src/main/java/com/battlesbudz/jarvis/v2/voice/` unless qualified. Each gate is evidence required before enabling/promoting the change; a diagnostic commit may be useful even when it records a failing baseline.

### Phase A — fixed inputs, reproducible evidence

**A1 — `feat(voice-tests): add versioned test packs and reversible profile snapshots`**

- Add a small test-session manifest/store and entry point in the existing diagnostics UI. Register the fixed scripts and profiles in this document, assign pack/case/repetition IDs, and record build SHA, model/reference hashes, profile, selected ASR, route, volume, battery, and thermal state.
- Snapshot saved settings before a test, apply a temporary manifest, disable conflicting controls while running, restore on completion/cancel/relaunch. Do not change production inference behavior.
- Developer checks: cancellation/relaunch restoration, stale-run rejection, missing-model failure, correct active-call exclusion, and one case owning audio at a time.
- Justin: `P1-setup`, roughly 3–5 minutes. Start and cancel one pack; verify normal settings return. Gate: a report identifies the exact build and settings, and cancel leaves normal calls usable.
- Rollback: hide pack entry; leave current production pipeline unchanged.

**A2 — `feat(voice-tests): add fixed source and concurrent-load comparisons`**

- Reuse `TtsBenchmarkController`, `TtsBenchmarkSamples`, `VoiceInterruptionTest`, and `BenchmarkProvenance`; extend rather than duplicate. Implement the ordered load cases in P2 and fixed long narration in section 10.
- Add in-app microphone trial capture for deterministic replay in memory and visual prompts for user speech. Keep production call recording policy unchanged. Fixed text must use ordinary TTS/output paths; only bypass Gemma for isolation cases. Label live-Gemma trials separately.
- Developer checks: same text across profiles, declared intentional fixture repeats only, no competing native model owners, cancellation and model release after every condition. Validate audio callbacks are delivered exactly once.
- Justin: P2 screening. Gate: cases run without manual tuning and no result is silently replaced by the next case.
- Rollback: disable load cases while preserving A1.

**A3 — `feat(voice-tests): preserve timed run evidence and export one report`**

- Extend `SpeechAudioTrace`, `PocketStreamDiagnostics`, and interruption timing. Separate native work, load/acquire, finalization, release, callback intervals, queued audio and actual playback-head progress. Include requested/effective startup policies.
- Bundle manifest, trial outcomes, diagnostics and selected source WAVs with stable IDs. Add bounded diagnostic source-audio capture long enough for the assigned story, separate from the normal latest-30-second ring. No automatic disk recording of microphone audio; explicit user export only if a later diagnostic needs it.
- Include `not_measured` for physical onset/stop latency without calibrated timestamps. A visual cue is a prompt, not the time the user actually started speaking. AudioTrack-head estimates are not laboratory acoustic measurements.
- Developer checks: run association across later calls, sample offsets, export after cancellation, source/played duration separation, size limits, no raw mic in default exports.
- Justin: P1 export check plus P2. Gate: another session can reproduce the test from one report. **Milestone A: baseline captured; no fix claimed.**

### Phase B — Paul's generated voice

**B1 — `test(paul): add matching-runtime preprocessing and state controls`**

- In `PocketSpeechPolicy`, `PocketTextStream`, benchmark profiles, and `native/sherpa/pocket-streaming.patch`, expose diagnostic-only comparisons for added leading punctuation, token-aware sentence grouping, and decoder/RNG state. Reuse existing 14-run suite controls where valid.
- Identify the source revision/config matching the January ONNX export before porting modern upstream behavior. Fix voice-reference conversion or conditioning only if parity checks identify a discrepancy, in a separate follow-up if required. Do not globally retain completed text/EOS state.
- Compare full-text input with grouped input while recording any internal splitting. Keep reference, sample text and other variables constant. At most three candidate comparisons per pack; select from evidence, not a full grid.
- Developer checks: legal token bounds, punctuation/numbers/quotes, no repeated submissions, no truncation, same-run collected PCM equals concatenated callbacks. Upstream-vs-ONNX quality is not byte-equivalence.
- Justin: P3, A/B labels blinded and order alternated. Gate: the defect is narrowed to a reproducible variable or explicitly remains unexplained.
- Rollback: all candidates remain diagnostic only.

**B2 — `fix(paul): apply the selected voice-consistency correction`**

- Implement only the winning B1 change and update profile version. If B1 finds no winner, do not invent this commit; issue a measured decision report and remain on the reference.
- Preserve first-PCM latency, native callback order, final words and voice-reference identity. Do not combine a model upgrade or new voice with this correction.
- Developer checks: affected native/text tests and short/long fixtures, repeated turns and fillers isolated from answer state.
- Justin: P3 confirmation on all three passages. Gate: accepted voice stability and no skipped/repeated words. Residual model-level drift is reported, not marked fixed.
- Rollback: restore prior policy through a forward commit.

**B3 — `docs(paul): record accepted profile and unresolved quality limits`**

- Record exact candidate, listening scores, runtime/model versions, and source WAV run IDs. Retire diagnostic variants only after their useful evidence is retained; keep one baseline comparator.
- Developer: verify the documented profile equals the effective code manifest. Justin: no extra testing if B2 evidence is complete. **Milestone B: quality accepted, or explicit backend/reference decision required.**

### Phase C — sustainable playback and recognition

**C1 — `fix(voice): separate recognition readiness from decode and freshness budgets`**

- Update `BoundedInterruptionRecognizer`, `InterruptionTiming`, and `VoiceModelSession` to measure acquisition separately while preserving a bounded overall lifetime, audio age checks, native cancellation joins and exclusive access.
- Schedule bounded model renewal away from active candidates when safe; retain the existing SDK stream ceiling. Consume a completed fresh result before rejecting it because of later queue pressure. Do not widen deadlines until observed work justifies a value.
- Developer checks: slow acquisition then successful fresh decode, truly stale input rejection, failed acquisition, model rotation, late result/cancel race, no close-during-native-call. Verify no missed keyword service during renewal.
- Justin: P4 screening, including early and late interruption points. Gate: diagnostics distinguish loading from decode and no longer show repeated unexplained recognition loss.
- Rollback: old scheduling profile; maintain timing instrumentation.

**C2 — `perf(voice): reserve bounded compute for interruption work`**

- Update `DuplexPlaybackBudget`, `NaturalBargeInAudioInput`, and relevant scheduler hooks. Reserve work for admitted candidates and explicit stop verification; overload recovery is a named state rather than indefinite retries. Keep keywords/capture serviced.
- Benchmark 2/3/4 TTS threads under actual candidate load, adding a 3-thread diagnostic profile only here if necessary. Never treat thread count alone as guaranteed priority. Keep optional Gemma speculation behind committed audio/input work.
- Evaluate a candidate-lived recognition stream only if repeated setup is measured as dominant. Do not reintroduce unlimited concurrent ASR. Preserve Moonshine's external speech gate.
- Developer checks: renewable quota, short/long saturation, genuine stop not blocked by natural budget, bounded memory and no native overlap.
- Justin: P2 comparisons and P4 screening. Gate: lower combined-load starvation with recognition still completing, not just faster isolated TTS.
- Rollback: prior admitted-work policy and thread profile.

**C3 — `perf(paul): apply measured audio-production and buffering changes`**

- Use `SherpaKokoroVoiceOutput`, `PaulPlaybackBuffer`, and native measurements to select a bounded startup cushion. Keep PCM writer work short; move avoidable diagnostic I/O away from time-critical delivery where measured.
- If profiling justifies it, make bounded latent-producer/decoder concurrency its own separately reviewed `C3a` commit, then promote in `C3b`. Include queue limits, ordering and cooperative cancellation. If it offers no benefit, omit it and document why.
- Validate rate 1.0 and 0.9 separately, preserving stable pitch/rate per answer. Do not silently slow below 0.9 or replace Paul. If production remains slower than playback, document the resource/backend decision rather than increasing buffers indefinitely.
- Developer checks: callback integrity, no lost final frames, correct playback speed accounting, startup/short answer, starvation recovery and confirmed cancellation under full buffers.
- Justin: P2 extended plus P3 regression for any native synthesis change. Gate: three ≥120-second story runs without observed mid-answer starvation or audible stutter; report generation/load headroom. **Milestone C: throughput accepted.**

### Phase D — acoustic input during output

**D1 — `feat(voice-tests): add route-specific echo and keyword evidence`**

- Instrument `AndroidAudioInput`, `MicroInterruptionKeywords`, and `ReplyVoiceCapture` for actual AEC control/state, route, sample rate, capture timing, keyword score curves and assistant-only versus overlapping input. V2 already requests AEC; this is effectiveness testing.
- Add P5 speaker/headphone comparisons. Where hardware-preprocessed raw audio is unavailable, label that limitation rather than claim before/after AEC access.
- Developer checks: input format/quantization against model contract, capture replay order, effect lifecycle and disconnect. Do not lower thresholds to compensate for an unknown signal problem.
- Justin: P5. Gate: classify acoustic failure versus keyword preprocessing/model failure versus recognition scheduling.
- Rollback: disable diagnostic taps; no production route change.

**D2 — `fix(audio): select the verified duplex route with explicit ownership`**

- Only if D1 supports it, test a communication route and route-specific effects. Update `AndroidAudioInput`, `MicrophoneInterruptionMonitor` and `VoiceAudioSession` so Jarvis's own communication mode does not trigger self-suspension.
- Restore prior audio mode/route on end/failure and still yield to actual outside recording/calls. Avoid treating every keyboard/input screen as microphone use. Preserve quiet speech and screen-off passive operation.
- Developer checks: own/external ownership, effect unavailable, Bluetooth changes, route restoration, permission loss, external dictation and active-call recovery.
- Justin: P5 plus P6 handoff screening. Gate: overlap recognition improves with no own-mode deadlock or handoff regression.
- Rollback: previous route policy, not a blanket disabling of external microphone protection.

**D3 — conditional `feat(audio): evaluate local software echo cancellation`**

- Only if measured platform AEC is insufficient. Pin a suitable WebRTC APM revision/dependencies, verify packaging obligations, and implement capture plus rendered-reference processing with bounded delay alignment and resource use.
- Begin at controlled 1.0x; 0.9x requires an accurate rendered reference or owned time-stretch output. Do not feed original un-stretched PCM as though it were the played signal. Compare hardware versus software processing independently.
- Developer checks: aligned artificial echo tests, real double-talk preservation, resampling/frame sizes, route/delay changes, teardown, APK size and CPU/memory cost.
- Justin: P5 and P2 extended. Gate: better overlapping command recognition without destroying Justin's speech or causing playback starvation. Otherwise remove/disable candidate and document the limitation. **Milestone D: acoustic route accepted, conditional work resolved.**

### Phase E — recoverable barge-in

**E1 — `feat(voice): add reversible interruption candidate playback state`**

- Extend the existing coordinator/output model; introduce bounded candidate pause/duck distinct from permanent stop. Preserve first user words and retain bounded unplayed PCM/playback position. Brief false triggers resume retained audio without restarting synthesis.
- Candidate onset must use the verified acoustic path; minimum duration/energy alone is not proof of external speech. Keep this experimental until noise tests pass.
- Developer checks: pause/resume position, queue backpressure, no double-played callbacks, resume/cancel races, ongoing user speech prevents inappropriate resume, bounded timeout and exact reason codes.
- Justin: P4 natural screening and P5 noise screening. Gate: a real correction receives an audible response and false candidates recover without repeated words.
- Rollback: previous confirmed-only interruption behavior.

**E2 — `fix(voice): prioritize confirmed commands and flush stale output`**

- Keep confident Hey Jarvis independent of the natural recognizer. On a possible stop, pause provisionally and use its reserved non-echo verification path. Confirmed stop means active listening; goodbye means end call and passive wake.
- Flush output promptly before waiting for JNI cleanup; cancel workers cooperatively, reject late frames by answer/session generation, and preserve delivered-history accuracy. Finalized recognition remains required before any new tool action.
- Developer checks: quoted/negated/assistant-spoken stop/goodbye, genuine human overlap, cleanup lag, stale callbacks, active microphone ownership, no new answer or filler after goodbye, stopped content absent from delivered history.
- Justin: P4 full command acceptance plus P6. Gate: command semantics, response latency and no false cancellation pass. **Milestone E: interruption behavior accepted.**

### Phase F — integrated acceptance and closure

**F1 — `test(voice): lock integrated acceptance pack and regression fixtures`**

- Assemble P7 from accepted profiles; keep isolated fixtures separate from live-Gemma tests. Freeze model/runtime versions, route, and samples. Include warm, background, screen-off, first-word, long-turn and microphone-handoff cases.
- Developer: full CI, native/package/signing verification, regression tests for identified failures and installable APK metadata. Do not change thresholds in this test-only commit.
- Justin: P7 in two manageable sessions. Gate: all mandatory rows in section 9 pass; report failures without averaging them away.

**F2 — `docs(voice): publish measured acceptance and remaining issues`**

- Update this ledger and parent roadmap with commits, build numbers, reports, actual results, known limitations and accepted profile. Remove obsolete normal-flow settings only as separately reviewed code work if necessary; do not hide failing diagnostic evidence.
- Justin: no additional pack if F1 evidence is complete. State accepted/failing/not tested for each route and warm condition. Device acceptance is distinct from CI success. Merge remains a separate explicit approval.

## 5. Build test card — required developer handoff

Send this with every requested install:

```text
Build: <number>   APK: <permanent signed link>
Commit: <SHA>   Milestone/commit ID: <C1, for example>
Changed: <one sentence>
Run only: <P4 screening; trials H1, S1, N1>
Expected time: <rough duration>
Profile: <named manifest; list differences>
Phone setup: <speaker/cool/50 cm/exact volume step>
New controls available: <exact labels; screenshot if necessary>
Expected improvement: <observable result>
Known unresolved: <specific failures this build does not fix>
Send back: <report and exact WAV run IDs>
Stop condition: <crash, stuck mic, repeated unwanted cancellation, etc.>
```

Code pushes can happen separately, but do not ask Justin to install every intermediate commit. A milestone card names the precise cumulative SHA tested. If CI creates several APKs, send one selected build and retire ambiguous links. Do not identify a docs-only APK as an improvement.

## 6. P0 — current-build baseline (available before harness)

Use once if fresh baseline evidence is needed; existing build 673 evidence may satisfy it. Approximate effort: 5–10 minutes plus exports. Do not repeatedly reproduce known failures without a diagnostic purpose.

1. Keep or deliberately set `B673-reference-v1` in `Voice and response speed`. Check the label under `Voice calls`, not merely selected benchmark chips. Use `Apply to voice calls` only for this explicit reproduction; record the previous profile first. Do not tap `Restore call defaults`.
2. Start a call with Hey Jarvis. Say exactly: **Start the interruption test.** Let the Mira story finish without speaking. Immediately open Voice settings, `Copy diagnostics`, then `Save latest reply audio`, before starting another reply. Label `P0-story-1`.
3. Start the same test again. After hearing **The lantern glowed beside the river**, say **Hey Jarvis** once. Wait for listening, then say **What is two plus two?** Expected: story stops and answer is four. Record whether the first words were captured.
4. Repeat the story; at the same sentence say **Stop** once. Expected: silence and active listening, not ended call. After two seconds say **What is two plus two?**
5. Repeat the story; at the same sentence say **Actually, tell me what two plus two is.** Expected: story stops and answer is four.
6. End with **Goodbye Jarvis**. Expected: return to passive listening. If it fails, use the UI stop/end control and record a failure. Copy evidence after each trial; later results may displace earlier events.
7. If assigned, end all calls and run **Compare Paul source audio · 14 runs** once. Leave its fixed settings untouched. Export the specified cases using **Export this run’s audio + diagnostics**. Do not use **Compare all profiles · all voices**.

Manual P0 timing is approximate. Do not claim a 500 ms pass based on subjective reaction time. The structured harness introduced in A replaces this manual pack.

## 7. Structured test packs (require named harness support)

### P1 — setup and export, 3–5 minutes

1. Snapshot/screenshot your current voice and call profile.
2. Select the assigned named pack and press its displayed start control. Confirm the manifest matches the card.
3. Cancel during the first passage. Verify the test stops and the previous call profile is restored.
4. Restart, complete one trial, mark its outcome and export its report.
5. Make one short normal call, then reopen the report. Verify the report still belongs to the completed trial and has not become the latest call's data.
6. Pass only if cancellation, restoration, normal use, and durable run association all work.

### P2 — locate playback pressure

Screening: roughly 8–12 minutes; extended pack requires at least three uninterrupted two-minute passages and can be split across sessions.

1. Apply the card's manifest through the pack. Sit still and do not speak except at visual cues. The first run is labeled cold; later runs are warm, never silently averaged together.
2. Run condition A: the same previously generated source PCM plays with live synthesis off and microphone processing off. This checks the playback path.
3. Run B: Paul generates and plays the same fixed text with recognition off.
4. Run C: Paul plus the live keyword/VAD path. Remain silent first, then read the prompted speech sample when requested.
5. Run D: Paul plus bounded Moonshine candidate recognition using the fixed microphone sample/cues. Use **Actually, tell me what two plus two is.** The isolation runner must label whether confirmation is suppressed solely for measuring concurrent load; this is not a production interruption pass.
6. Run E: the normal call pipeline's fixed interruption story. This validates actual stop behavior. A separate live-Gemma question checks normal dispatch; it is not used for identical-waveform comparison.
7. For each case mark audible gaps, clicks/crackle, garbling, repetition and accent changes separately. Play that case's source WAV afterward and give separate source-vs-live ratings.
8. In A/B comparisons the harness alternates order. Do not manually change threads, speed or buffer. Only the card's parameter differs.
9. Extended pass: three complete story trials of at least 120 seconds of playback each, at the proposed production profile, without observed starvation or audible stutter. Let them complete. Keep interruption acceptance in a separate run so intentional stops are not counted as underruns.

### P3 — voice consistency listening

Screening: one A/B pair for a passage, about 3–5 minutes. Confirmation: three passages with two runs per candidate, split if tiring.

1. Use headphones only if the card says `source-listening`; hold that listening route constant across A and B. This does not qualify the phone speaker's duplex performance.
2. Listen to labeled Clip A then Clip B at 1.0x. Both contain identical text/reference and differ by one listed experimental variable. The developer reveals candidate labels only after your ratings.
3. Grade each clip: accent/identity drift 0 none, 1 slight, 2 obvious, 3 distracting; garbling 0–3; repeated/missing words count; naturalness 1 poor to 5 good. Note approximate audio timestamp and words around any issue.
4. Repeat with order reversed. Judge narration separately from quoted dialogue; deliberate expression is not automatically an accent change.
5. Confirmation covers the short opening, paragraph and story fixtures. Pass: no skipped/repeated words, no drift above 1, no garbling above 1, naturalness at least 4, and Justin explicitly accepts the voice. These are proposed acceptance criteria, not claims about current Paul quality.
6. If neither candidate improves the issue, report `neither`. Do not adjust sliders to find a third setting mid-pack.

### P4 — interruption commands and corrections

Screening: H1, S1, N1 and G1 once each, about 5 minutes. Full acceptance: 20 trials per command class, completed in blocks of five; schedule across multiple sessions. A failure stays in its original block. Reruns are new IDs.

The harness plays a fixed story and gives a **visual-only** cue at early/middle/late positions (2, 15 and 45 seconds after actual playback starts, where the passage is long enough). A sound cue could contaminate the microphone. Justin speaks within about one second of the cue. Before the harness exists, use P0's sentence cue. Latency is measured from actual detected speech/keyword timing, not the visual cue.

| Class | Say once at cue | Expected behavior | Follow-up |
| --- | --- | --- | --- |
| H | Hey Jarvis | Stop story, remain listening | When ready, say `What is two plus two?`; expected four. |
| S | Stop | Stop story, keep active call | Wait two seconds, say `What is two plus two?`; expected four. |
| N | Actually, tell me what two plus two is. | Yield, retain whole correction, answer four | Do not repeat if it misses; mark Fail. |
| G | Goodbye / Goodbye Jarvis (alternate each trial) | End active call, return to passive wake | Wait three seconds, say `Hey Jarvis`; new call must activate. |

For each class's 20 trials: 7 early, 6 middle, 7 late. Use ordinary speech at the fixed baseline volume/distance. Record success, first words retained, false resume, unexpected call ending, and answer correctness. Do not say the trigger repeatedly until it works. If nothing happens within three seconds after your phrase ends, record the miss; use UI end after five seconds if needed, rather than listening to the whole remaining story. Distinguish functional success from the stricter latency target.

An actual tool action is not needed to prove barge-in. Arithmetic has a deterministic expected answer and avoids changing your phone or accounts. Separate tool regression checks remain developer-owned unless specifically assigned.

### P5 — acoustic conditions and false triggers

Screening: about 5–8 minutes; complete noise acceptance is a separate ten-minute run.

1. Built-in speaker at the baseline volume: say `Hey Jarvis` five times in the idle wake test, then run five overlapping story trials using the assigned H/S/N class. Do not compare idle recognition with a different microphone position.
2. If requested, repeat at a fixed 75% speaker setting, restoring baseline afterward. Label separately; do not pool with 50% results.
3. If you have headphones, repeat the identical five trials with them. Record whether microphone input is phone, wired, or Bluetooth. If none are available, mark that route Not tested; it does not block evaluating the default phone route.
4. Assistant-only test: remain silent while the fixed passage plays. It deliberately includes the words Sir, stop, and goodbye in quotation/reporting contexts. Jarvis must not cancel itself.
5. At visual prompts during a separate noise test, make one quiet throat-clear, rustle a sleeve, then say `mm-hmm`. Space these by at least ten seconds. Do not shout, clap beside the mic, or improvise noises. Record provisional pauses/ducks separately from permanent cancellations.
6. Full noise gate: ten minutes of the assigned assistant-only/controlled-noise playback with zero confirmed false cancellation; target at most one provisional duck/pause. Any false pause must resume retained speech without duplicated words.
7. Export diagnostics. Source WAV alone cannot tell us what the microphone heard. No microphone audio is exported unless the card explicitly requests a user-initiated diagnostic export.

### P6 — microphone handoff and background behavior

Screening: 5–10 minutes. Do each once; repeat three times only at final acceptance.

1. Passive state: leave Jarvis armed, open a private unsent draft in an app with keyboard dictation. Dictate `This is a microphone handoff test.` Stop dictation. Do not reopen Jarvis. After five seconds say `Hey Jarvis`. Expected: passive wake recovered. Do not send the draft.
2. Active state: start a Jarvis call, then use the same dictation flow. Stop external recording. Expected: return to the prior active call state under the existing resume contract, without duplicate actions or replaying an entire answer. Speak `What is two plus two?` after the listening cue/state.
3. Ordinary keyboard: open an input field and type without activating dictation. Expected: typing alone does not suspend Jarvis.
4. Background: start the fixed story, go Home, then give the assigned interrupt command. Expected: same behavior as foreground.
5. Screen off: end the call with goodbye, lock the phone, wait ten seconds, then say `Hey Jarvis`. Ask the arithmetic question, then `Goodbye Jarvis`. Expected: wake, answer, and return to passive without opening the app.
6. Confirm notification/call state agrees with actual behavior. Report how long recovery took; the five-second check is an initial acceptance target, not proof an app necessarily released its mic at the instant dictation UI disappeared.
7. For eyeVue/Bluetooth, use a separate card and report; phone-route success must not be described as glasses acceptance.

### P7 — final integrated acceptance

Run only after B/C/D/E gates have an accepted candidate. Two sessions are recommended.

- Session 1, cool phone: P1 short check; P3 accepted-profile spot check; P2 extended; P4 full blocks spread over breaks. Use the complete 20-trial counts for final rates, not just screening passes.
- Session 2, sustained use: use the fixed dialogue/story loop for ten minutes without changing settings, then immediately run P4 screening and one ≥120-second uninterrupted P2 story. Record thermal state and battery. No deliberate cooling between warmup and test.
- Run P5 full ten-minute false-trigger pack and P6 lifecycle tests. Separate these from the warm timing pack if the overall session becomes tiring.
- Long-turn script: read the supplied script below with indicated pauses; expect no reply during hesitation and no first-word loss. Run one ≥30-second and one ≥60-second case with explicit visual pacing from the harness, not rushed reading. Ask `What was my final instruction?` afterward; expect the final instruction retained. Mark any truncated/ambiguous recognition as a failure, not a successful fallback answer.
- Live-Gemma checks: `What is two plus two?`, `Explain why leaves look green in three sentences.`, and `Tell me a short story about a lantern beside a river.` These check ordinary dispatch and responsiveness, not identical generated content.
- Stop when the assigned sheet is complete. Do not add broad optional regression tests unless a result identifies a concrete risk.

## 8. Triage after a failed trial

| Observation | Evidence to send | Next developer action |
| --- | --- | --- |
| Accent shifts in source WAV | P3 clip ID, timestamp, words, rating | B preprocessing/reference/runtime comparison; do not tweak playback buffer. |
| Source sounds clean; live has gaps | P2 condition, source export, queue/starvation report | C load/callback/playback analysis. |
| Keyword works idle but not over speaker | P5 route/volume, score curves, effect state | D acoustic analysis before threshold changes. |
| Recognition repeatedly loads/expires | P4 ID plus acquire/decode/freshness timing | C1/C2 scheduling review. |
| Noise causes a stop or repeated words on resume | P5 event/time and playback ledger | E1 candidate/resume review; preserve the failed fixture. |
| Goodbye stops speech but leaves active call | G trial and final state | E2 command-routing review. |
| Dictation ends but Jarvis never recovers | P6 state/notification and diagnostics | D2 ownership/rearm review. |
| Crash or microphone stuck | Last trial/build, screenshot if available, diagnostics after reopening | Stop pack, repair lifecycle first. Mark later cases Not run. |

Do not change models, thresholds, decoder settings, or speaker preference after a failure unless the next card explicitly assigns a controlled comparison.

## 9. Final acceptance gates

| Area | Required evidence |
| --- | --- |
| Voice quality | P3 three-passage acceptance; no skipped/repeated words; accepted identity/naturalness. |
| Playback | Three ≥120-second uninterrupted runs without measured mid-answer starvation or audible stutter; separately one warm run. Count actual starvation, not every end-of-drain underrun counter increment. |
| Keywords | At least 19/20 each for H, S and G at the baseline speaker route. Report classes separately. |
| Natural corrections | At least 18/20 N trials with first words retained and correct response. |
| Response latency | Proposed p95 provisional yield ≤500 ms from speech onset; explicit stop ≤500 ms from keyword completion. Report method/uncertainty. Without calibrated event timing mark Not measured; subjective success alone cannot pass a numeric gate. |
| False interruption | Ten-minute P5 with zero confirmed false cancellations and at most one provisional pause/duck; resume must not repeat speech. |
| Recognition/response | Report actual last-word-to-first-answer, recognition-final-to-audio, and text-to-PCM separately; fillers excluded. Investigate >10% median or >250 ms p95 regression against comparable baseline, rather than silently accept it. |
| Lifecycle | P6 passes three repetitions at final acceptance; test each supported route independently. |
| Developer gates | CI debug/release tests/build, keyword-model checks, ASR packaging, Pocket callback ABI, release signature and upgrade identity. |

Use nearest-rank p95 on available timing trials and disclose sample count; 20 trials provide an initial engineering acceptance sample, not universal reliability. Warm screening must not be presented as a full warm 20-trial reliability result. One failing mandatory area keeps the overall milestone open; optional unavailable routes are explicitly Not tested.

## 10. Fixed speech fixtures

Existing fixtures remain versioned: `short-opening-v2`, `paragraph-v1`, `story-v1`, and the current Mira interruption passage in `VoiceInterruptionTest`. Do not rename/change them without a new fixture version.

### `long-narration-v1` (planned)

Use this exact text for timed output, including quotation marks. The harness may repeat the entire fixture only at a labeled cycle boundary until at least 120 seconds of playback have accumulated, then finish that cycle. Intentional fixture-cycle repetition is excluded from accidental word-repeat scoring. The manifest records cycle count and text hash.

> The lantern glowed beside the river. Mira followed a narrow path through the trees, carrying a map her grandfather had drawn. Across the water, an old clock tower stood above a village of blue rooftops. Its bell had been silent for many years.
>
> At the bridge she met a gardener with a basket of apples. He pointed toward a little house beside the tower and told her that the keeper had left a letter there. Mira thanked him and crossed the bridge slowly. Beneath her feet, the river carried fallen leaves toward the sea.
>
> Inside the house, a copper kettle rested on a cold stove. There were three cups on the table, a blue coat beside the door, and a folded letter underneath a stone. The letter described a silver key hidden in the garden. It also contained a curious sentence: the word stop had once been painted on the tower gate. Mira read the sentence quietly and continued searching.
>
> A small brown dog appeared at the window. It watched her for a moment, then ran toward an apple tree. Beneath the tree was a wooden box. The key fitted the box perfectly, but inside there was only another map. This one showed the clock tower from above, with a narrow staircase drawn around its outer wall.
>
> The keeper's final note quoted his old teacher: “Goodbye is sometimes the beginning of another journey.” Mira put the note in her pocket. She climbed the staircase with the dog waiting below. When she reached the top, the village looked peaceful in the evening light. She turned the handle beside the clock, and the bell rang once. Somewhere along the river, the gardener looked up and smiled.

### Long input script (planned pacing fixture)

The harness displays sentences with visual pacing to create 30- and 60-second versions. It must display pause countdowns but not play audible prompts into the microphone. Justin reads naturally and observes indicated silences; automatic pacing must not require unnatural drawn-out words.

> I am planning a small room with two tables and a chair. [pause 2 seconds] The first table should go beside the window. I am still thinking about the second one. [pause 3 seconds] Put that one beside the opposite wall. The chair should be close to the door. [pause 2 seconds] Actually, change the chair position so it is between the tables. I have one more detail to add. [pause 3 seconds] There should be a clear path through the middle of the room. Please do not take any actions. My final instruction is to summarize the room arrangement in one sentence.

For the 60-second version, add before the final two sentences:

> The room gets bright in the morning and darker in the afternoon. I would like to keep the window easy to reach. [pause 3 seconds] There may eventually be a bookshelf beside the chair, but that is only an idea for later. [pause 2 seconds] The important thing today is the arrangement of the two tables and the chair.

Record actual speech duration. If reading finishes short of the target, classify that trial as the shorter duration rather than claiming a 60-second test passed. Add a new versioned longer fixture if needed; do not ask Justin to improvise extra instructions.

## 11. Results to send back

The harness should generate most fields. Until available, copy this template:

```text
Build / commit:
Test card / pack:
Profile (or screenshot):
Route / volume step / phone position:
Battery / cool or warm / thermal status if shown:
Trial ID / early, middle or late:
Exact words I said:
Pass / Fail / Invalid / Not run:
What Jarvis did:
Did it retain my first words?:
Did the story stop, resume, repeat, or end the call?:
Source WAV: accent drift 0–3 / garbling 0–3 / naturalness 1–5:
Live playback: gap / crackle / repeated or missing words:
Approximate audio timestamp / nearby words:
Timing: measured report value, or subjective only:
Diagnostics attached:
Source WAV run ID attached:
External disturbance or deviation from setup:
```

## 12. Implementation ledger

Update this table after each actual delivery. `Planned` is deliberately not `implemented`.

| ID | State | Commit / APK | Developer evidence | Justin pack / result |
| --- | --- | --- | --- | --- |
| A1 | Planned | — | — | P1 — |
| A2 | Planned | — | — | P2 screen — |
| A3 | Planned | — | — | P1/P2 — |
| B1 | Planned | — | — | P3 compare — |
| B2 | Conditional on B1 winner | — | — | P3 confirm — |
| B3 | Planned | — | — | Reuse B2 evidence |
| C1 | Planned | — | — | P4 screen — |
| C2 | Planned | — | — | P2/P4 screen — |
| C3 | Evidence-dependent; split C3a/C3b if needed | — | — | P2 extended/P3 — |
| D1 | Planned; measure early | — | — | P5 — |
| D2 | Conditional on route evidence | — | — | P5/P6 — |
| D3 | Conditional on insufficient platform AEC | — | — | P5/P2 — |
| E1 | Planned | — | — | P4/P5 screen — |
| E2 | Planned | — | — | P4 full/P6 — |
| F1 | Planned | — | — | P7 — |
| F2 | Planned | — | — | Reuse F1 evidence |

This work refines the parent roadmap's interruption, source-quality, integrated-load and phone-acceptance phases. It does not roll back completed external Moonshine gating, long-turn handling, microphone ownership, speculative-work limits, or tool-finalization safeguards. Recheck the current implementation before each commit; preserve unrelated changes. Publish measured failures as clearly as successful results.
