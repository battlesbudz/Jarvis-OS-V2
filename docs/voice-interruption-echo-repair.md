# Build 709 false interruption repair

17 September 2026, `audio-pr2`, existing PR #6.

## Phone evidence

Call `e2bdb824-5d7c-4214-ae6c-dd9de339ceb4` successfully generated text-only
Gemma replies. Natural interruption then accepted `is brilliant.` and later
`tracks` from an otherwise echoed sentence. The resulting final transcripts
(`This guy is blue due to Rayleigh'` and a passage about the Earth's atmosphere)
were fed back as user messages. Both speaker checks reported MATCH around 0.85.
This is evidence of failed echo discrimination, not evidence that the owner spoke.
There were no keyword hits responsible for these interruptions.

The natural path already required a nonempty ASR result, but treating any unmatched
token as a real owner word was insufficient. An ASR substitution can look like a
word, and similarity to a learned speaker centroid alone did not reject Piper.

## Changes

- VAD and volume only nominate audio for recognition. They do not pause, duck,
  stop playback or hand off the floor. Empty results, punctuation and known sound
  captions (including cough, sneeze, wind, traffic and yelling) cannot authorize
  natural interruption.
- Compare each complete ASR clause to contiguous playback text using bounded
  edit-distance matching. Near-echo sentences do not yield isolated edge words.
  Apply the same check to final correction text before it reaches Gemma or tools.
- Retain up to three seconds of actual synthesized Piper samples in memory,
  resampled to 16 kHz. On an eligible word candidate, use the existing speaker
  extractor to compare candidate windows to both the learned owner and Piper.
  Cache playback embeddings until the reference changes; do not train on them.
- Every candidate window must meet the existing owner threshold (0.65) and exceed
  its playback similarity by at least 0.08. Missing playback embeddings when a
  reference exists fail closed. `PLAYBACK_ECHO` includes ambiguous owner/playback
  matches; it is a rejection policy, not proof of microphone ground truth.
- Preserve single-word eligibility, the 250 ms minimum probe, and both existing
  `stop` and `Hey Jarvis` keyword paths. No multiword command requirement or new
  silence/stability delay is introduced.
- Log owner and playback scores, the required margin, rejected speaker decisions,
  and the recognized words that actually authorized a natural stop.

No new models or dependencies are added. The playback reference is bounded,
in-memory only, and uses the already installed speaker embedding extractor.

## Verification and limits

111 focused JVM tests pass, including the reported `tracks`/Rayleigh transcripts,
sound captions with VAD true, playback rejection despite a high owner score,
preserved single-word interruptions, final echo exclusion and keyword behavior.
Android release assembly and packaging run in CI for the published replacement.

Phone acceptance remains required: leave Jarvis speaking without talking, then
try coughs/background noise, then separate short words such as No, Yes and I,
and finally Stop/Hey Jarvis. Capture the new score evidence if any false stop or
missed owner word occurs. The 0.08 owner/playback margin is a conservative initial
policy and has not yet been measured on the phone. It can reject an ambiguous
mixed owner/Piper fragment; it must not be represented as perfect speaker ID.
ASR can hallucinate words from noise, so recognition text is necessary but is not
by itself proof that a physical word was spoken. The code prohibits acoustic-only
stops; acoustic classification accuracy still requires device evidence.
