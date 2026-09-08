# Voice microphone interruption behavior

The foreground VoiceCallService starts/stops JarvisRuntime voice execution and owns the recording monitor. JarvisRuntime uses application context; Activity destruction does not cancel voice jobs or close their models. The Activity handles permissions, user-started foreground-service launch, and UI callbacks. Conversation generation uses the same shared runtime/model-operation lock.

The service watches AudioManager recording callbacks and polls every 500 ms while active. External recorder counts exclude Jarvis's recorder. Communication mode, microphone mute, the app's dictation request, and recorder silencing also yield priority. Availability must remain clear for 750 ms before resumption. The old keyboard accessibility service and window reservations are removed.

During capture, interruption completes the capture with MicrophoneBusyException and discards an unfinished utterance. It does not close the VoiceCallRecord. The next capture waits for availability before its inactivity timer starts, preserves context, and signals readiness with COMMAND_READY. Passive listening resumes passive detection and signals actual detector readiness with WAKE_LISTENING.

During generation/playback, the current operation remains alive rather than being rerun. AudioTrack pauses without flushing; PCM queues are bounded and playback resumes from the existing consumption position. Playback deadlines exclude interruption time. Output remains paused through an external recording. Manual pause/stop still require explicit resume/start; external recorder completion does not override them.

Diagnostics retain recorder route/silencing changes, external/own recorder counts, interruption/resumption with current call and phase, and empty-ASR recovery results. Audio remains in memory. The recognition failure producing empty streaming and batch results remains a separate investigation.

Phone acceptance checks:
- Open Messenger with its keyboard visible without recording: Jarvis must continue normally.
- From passive mode, record a Messenger voice message; after recording stops, wait for the wake cue without opening Jarvis or the notification shade.
- Repeat during an active call: the same call resumes command listening without another wake phrase.
- Interrupt a long spoken reply for over 20 seconds: playback resumes remaining audio; no tool action is executed twice.
- Test keyboard dictation, repeated interruptions, screen-off recovery, and Activity recreation.
- Explicit Pause/Stop during interruption must not automatically resume afterward.

A keyboard that rejects microphone use before opening its own recorder might emit no recording event. Use the existing notification Pause mic / Resume mic controls in that case. Keyboard visibility is never used as a substitute for ownership.
