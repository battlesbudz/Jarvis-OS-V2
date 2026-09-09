# Voice profile comparison

Open **Voice and response speed** and select a profile: 2 or 4 threads,
1.0× or 0.9× playback, and a 40-, 60-, or 90-character opening or full-text
synthesis. **Compare Kokoro and Miro · this profile** runs the same short reply,
paragraph, and story through both voices, twice. The all-profiles button runs
all 16 combinations (192 text runs). Individual voice tests use the same inputs.

Each input delivers four characters every 32 ms after the model reports ready.
Loading and downloads are outside the text-to-audio timer; the first synthesis
after loading is included. Both voices receive identical text and input pacing.
Opening sizes are natural-boundary targets, not exact cuts through words.
Full-text mode waits for the input to end and calls non-callback synthesis once,
then plays the resulting complete audio. It may still contain pauses generated
by the model. There is no deliberate startup buffering in these profiles.
Playback at 0.9× stretches PCM with pitch held at 1.0; synthesis speed itself
does not change. These settings never alter live-call settings.

Voices alternate for each sample/profile; pass two reverses the complete order.
Before each run the controller waits up to three minutes for thermal status below
Android's severe level (3), then stops with an explanation if it remains hot.
Stop also works during cooling. Start/end thermal states remain in each record;
results that reach severe or higher are flagged, not treated as clean comparisons.

Every text run has its own result UUID, suite UUID, profile ID, sample ID, pass,
exact input text and input hash. The displayed result's **Copy this text run**
exports only that immutable result. It never attaches current voice selection,
old calls, other samples, or Gemma results. Gemma has a separate per-text-run copy.
Starting a test clears the visible list; finishing shows only newly recorded
results. Use **Browse saved text runs** to deliberately view older results.
Benchmark history retains 384 records separately from the 40 recent call records.

Compare completed runs of the same sample, profile, and pass, checking requested
versus actual playback speed and thermal flags first. Prioritize low estimated
supply gaps, then low first-text-to-playback time. Effective RTF multiplies raw
RTF by actual playback speed: below 1 means generation can keep up on average.
Full-text buffering may avoid supply gaps even when this value is above 1.
First-text-to-playback detects the playback head passing the first non-silent
PCM frame; it is not an external microphone measurement of a recognized word.
Underruns include startup/drain events, and supply gaps are estimates. Listen
to the whole output to judge unnatural pauses, pronunciation and voice quality.

Phone acceptance checks: compare 4-thread 60-character profiles at both speeds;
listen for unchanged pitch and slower cadence at 0.9×; run full-text on the story
and verify one phrase; stop during synthesis/cooling and restart; copy an older
result after changing voice/profile and confirm its original IDs/settings remain.
