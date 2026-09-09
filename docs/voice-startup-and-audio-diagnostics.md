# Slow replies and speech diagnostics

Build 602 call `252373d8-89cf-4e91-928a-0a99ddab78eb` took 19,748 ms from endpoint to accepted answer text. Gemma's initial first token was 5,155 ms; the repetition guard then blocked four sentences and made a second generation. Opening synthesis took 3,806 ms and startup buffering another 1,202 ms. Speech-end to actual playback was 26,355 ms. The end of that reply had a 366 ms estimated audio supply gap. Those events explain silence and a playback interruption, but cannot establish whether the generated voice itself was garbled. An underrun reported during terminal drain alone is not proof of an audible internal gap.

This change builds on 603, which replaces full background ASR during replies with the bundled Hey Jarvis and stop keyword models. The supplied 602 logs do not test those changes.

## Acknowledgment

Voice calls prepare one neutral filler clip (`Uh, one moment.`, `Um, let me think.`, or `One moment.`) using the selected voice and the existing single native TTS owner while listening. Up to twelve engine/model/speaker/phrase combinations are cached in memory for the lifetime of the process. First use has a synthesis cost; there is no extra native model or concurrent synthesis worker.

Only a confirmed, accepted user turn requests the cue. After a 700 ms grace period it may play once, provided the answer PCM has not arrived. Build 604 incorrectly expired the cue after 1,500 ms of cache waiting, even though native model loading could take 3,702 ms on a correction turn. Cached PCM is now made available before native model loading, and a cache miss waits until preparation finishes, the answer wins, or the turn is cancelled. This is not a guaranteed 700 ms audible response on a cold start. A fast answer cancels the pending cue. An already-playing cue finishes before answer playback, and its remaining playback time counts toward the answer's startup headroom. Cancellation and microphone handoff stop the cue. The cue is not conversation history and cannot execute a tool. Neutral phrases rotate; `Checking that.` is requested only immediately before an actual reference lookup. Each phrase is cached independently. Only the selected neutral phrase is prewarmed on a listening turn; the full library is not synthesized before answering. An already-confirmed lookup can prepare its selected phrase instead. Logs identify request, cache wait/hit, skipped cue, and actual cue playback.

Acknowledgment logs are separate from answer text, PCM, and playback latency. Existing answer metrics do not count the cue as an answer. A neutral cue signals that processing continues; it does not claim that a lookup or action succeeded.

The repetition guard still checks sentences before speaking them. It now requests a repair only when filtering leaves no answer, avoiding extra generation after usable novel text. Entirely repeated answers still require repair; `repairMs` measures that cost. This does not promise sub-500 ms model responses or fix Kokoro's sustained synthesis throughput.

## Save latest reply audio

The Voice Call screen has **Save latest reply audio**, next to **Copy diagnostics**. After a reply finishes or is interrupted, the app retains up to 30 seconds of the latest reply's PCM in private cache. It records samples successfully accepted by AudioTrack, trims unplayed queued audio using the playback head, and replaces the previous file. Empty turns do not replace the previous recording. Export snapshots the file before opening Android's save chooser.

This WAV contains the generated answer PCM, not microphone audio. It excludes the acknowledgment, playback starvation gaps, Android playback time stretching, acoustic echo, speaker distortion, and Bluetooth processing. A clean WAV with distorted live playback points toward a later playback stage; it does not rule out the reported problem. The diagnostic event identifies the turn, frame range, sample rate and those limitations. Attach the WAV and copied diagnostics from the same reply for investigation.

PCM logs now include non-finite sample count, clipped sample count, and leading/trailing quiet durations alongside RMS and peak. These numbers are useful evidence of invalid synthesis or excessive quiet boundaries; they do not by themselves identify intelligibility or garbling.

## Validation

Host tests cover turn confirmation, skipping a cue for a fast answer, late cache availability, one-shot playback, orderly handoff to answer playback, cancellation, bounded WAV retention, playback-head trimming, replacing the previous WAV, and repair only for an empty filtered answer. Android CI additionally compiles and tests the application, verifies native keyword models, verifies packaged ASR libraries, and builds the signed release APK. Device playback quality and perceived latency still require testing the resulting APK.

## Build 604 follow-up diagnosis

Call `6d2c7b4b-29db-4db9-86f9-3d7014fb0e04` shows a story with Gemma first token at 4,167 ms and estimated decoding at 10.16 tokens/s. Kokoro repeatedly takes 1.58–1.91 seconds of synthesis per second of PCM, including after Gemma finishes. Increasing the startup buffer cannot indefinitely compensate for sustained slower-than-playback synthesis. A bigger voice model is not automatically faster; compare actual device first-PCM latency and sustained synthesis speed before choosing a replacement.

The last reply's 6,481 ms endpoint-to-text includes an unnecessary lookup for `the person you were talking about`; this exact dialogue reference now stays local when conversation history exists. Explicit Wikipedia requests still route to lookup. Opening synthesis adds 3,876 ms and buffering adds 1,201 ms. The attached WAV has exactly 99,690 frames at 24 kHz (4.15375 s), matching the final two-phrase lookup-failure reply rather than the story. It contains zero clipped samples; that is not a perceptual quality assessment.

After the `stop` keyword, Moonshine emits partials and a 54-character final transcript. ReplyVoiceCapture previously omitted the callback that forwards those partials to the waveform caption. The callback is now connected with an active-output/session guard. Recognition summaries distinguish normal versus post-keyword capture and whether missing Moonshine text requires the Gemma transcription fallback.

A separately retained, bounded summary section preserves acknowledgment, input, model, lookup and TTS timings so a long story's underrun events do not erase the evidence needed to diagnose its startup.
