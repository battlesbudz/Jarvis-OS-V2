# Current settings and development diagnostics

Current on `audio-pr2` / PR #6, 16 September 2026.

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
Saved Voice Calls and their resume/history behavior are unchanged.

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
