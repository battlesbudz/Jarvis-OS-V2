# Kokoro playback queue

The previous implementation loaded a native engine for every phrase and ran two
four-thread synthesis jobs concurrently. A phone trace showed 19.2 seconds to
produce 11.1 seconds of audio and a 9.6-second wait at playback. That measurement
included engine loading; it did not establish Kokoro's steady-state speed.

The new path creates one engine per response, preloaded while listening, on a
dedicated native-owner thread. All generation calls and release run sequentially
on that thread. This removes repeated loading and overlapping native calls;
repeated-generation stability still needs verification on ARM64 Android.

Sherpa's Kokoro callback emits completed internal sentence chunks, not words.
The callback copies audio into a two-chunk queue. Playback consumes that queue
while the engine prepares following chunks. Backpressure blocks only the native
producer when the queue is full, limiting PCM accumulation. Queue cancellation
unblocks that callback before native release. AudioTrack writes handle partial
writes and poll nonblocking so cancellation remains responsive.

Text chunking targets a short opening (70 characters), then 180 characters when
synthesis is faster than playback or 120 when slower. Punctuation and whitespace
boundaries preserve words; final text is flushed. These are initial heuristics,
not a phone benchmark. A startup grace period of at most 700 ms gives longer
answers some headroom; completed short answers do not wait for the full period.
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
