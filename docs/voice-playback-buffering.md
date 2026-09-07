# Kokoro playback queue

The previous implementation loaded a native engine for every phrase and ran two
four-thread synthesis jobs concurrently. A phone trace showed 19.2 seconds to
produce 11.1 seconds of audio and a 9.6-second wait at playback. That measurement
included engine loading; it did not establish Kokoro's steady-state speed.

The new path creates one engine per response, preloaded while listening, on a
dedicated native-owner thread. All generation calls and release run sequentially
on that thread. This removes repeated loading and overlapping native calls;
repeated-generation stability still needs verification on ARM64 Android.

Build 547 exposed a callback ABI incompatibility: Sherpa 1.13.7 looks up
`invoke([F)Ljava/lang/Integer;`, while Kotlin 2.3.0's default invokedynamic
lambda exposes `invoke(Object): Object`. A compiler probe reproduced the
missing typed method. The native crash on the first phrase is consistent with
this failure; Android process-exit diagnostics now provide additional evidence.

The output path uses `generateWithConfig` without a JNI callback. Each bounded
sentence/clause completes and enters a two-chunk PCM queue while playback
continues. The one native owner and engine reuse remain. Queue cancellation
unblocks the producer before release; native generation itself must return
before its engine can be freed. AudioTrack writes remain nonblocking and
cancellable, so already-playing audio stops without waiting for synthesis.

Text chunking targets a short opening (70 characters), then 180 characters when
synthesis is faster than playback or 120 when slower. Punctuation and whitespace
boundaries preserve words; final text is flushed. These are initial heuristics,
not a phone benchmark. A startup grace period adapts from 0 to 1200 ms based on the first chunk's
measured synthesis time versus audio duration, reserving 20% scheduling headroom.
Completed short answers do not wait for the full period.
The separate 120 ms artificial inter-phrase padding is removed. Voice speed is
unchanged.

Diagnostics separate `loadMs`, `synthesisMs`, `queueWaitMs`, callback latency,
and PCM occupancy. The session summary retains aggregate synthesis/audio times
even if per-phrase entries roll out of the diagnostics buffer. Queue waiting is
excluded from synthesis realtime factor. Values below 1 mean synthesis is ahead
of normal-speed playback; buffering cannot fix sustained values above 1.

Validation: unit tests cover token-split decimals, first clauses, complete text
preservation, final flushing, chunk adaptation, queue ordering and cancellation
while full. Android CI runs the unit suite and builds the signed APK. On-device
acceptance: request a long pirate story, listen across every sentence boundary,
end playback midway, then start another call. Compare session RTF and underruns
against build 543. One-versus-two-worker throughput and thermal behavior cannot
be established by desktop tests; the new default is one worker to remove the
observed competing loads. No claim of gap-free device playback is made yet.

Microphone readiness now requires 300 ms of captured PCM, retained for ASR,
before the UI receives Listening. The UI no longer sets Listening on the button
press or automatic rearm. ASR receives every frame from capture startup; VAD
still gates visible hypotheses and turn submission, preserving opening speech
that precedes VAD confirmation. The microphone buffer is one second and the
bounded handoff holds 6.4 seconds to absorb temporary ASR decoding stalls.
