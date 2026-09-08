# Passive Jarvis sessions

Start Jarvis session in the visible app and allow microphone/notification permissions. The first use downloads a pinned, hash-verified dedicated keyword model (about 33 MB archive; about 5.5 MB installed). Moonshine remains the call transcriber; the retired Zipformer ASR selector is not restored.

The microphone feeds only the local keyword spotter while the UI/notification says “Waiting for Hey Jarvis”. No transcript, WAV, or Gemma request is created from passive audio. Say “Hey Jarvis”, wait for the short readiness beep and “Voice Call is listening — speak now”, then speak. The existing call pipeline uses Moonshine and in-memory WAV audio for Gemma, and speaks with the selected Kokoro/Miro voice. Goodbye or 20 seconds without recognized speech ends the saved call and returns to passive listening. Stop Jarvis session or the notification's Stop session action releases the microphone and stops the foreground service.

The session continues after Home and screen locking. Android's microphone privacy indicator appears while capture is active; the ongoing notification distinguishes preparation, passive listening, active listening, speech, and microphone sharing pauses. Swiping away the task, destroying its runtime Activity, force-stop, or process death ends this session; it does not silently restart microphone capture. Activity recreation resilience remains separate work.

Jarvis uses non-private VOICE_RECOGNITION capture. It checks active recordings and Android's silenced state, releases capture when another client appears, discards interrupted call audio, and waits for microphone availability before returning to passive listening. Reasoning/playback is interrupted if another app begins recording. It cannot hear the wake phrase while yielded. Device behavior must be verified with keyboard dictation, voice messages, phone calls and microphone privacy controls.

## Assistant setup

Use Set Jarvis as default assistant, open Digital assistant app in Android Settings, and select Jarvis. The button remains available during a session and reports whether the assistant role is held when Settings returns. Devices without the voice-input settings screen fall back to Default apps settings. Jarvis declares a VoiceInteractionService, session service and a real local recognition service. Validated app-launch commands can use the selected, system-bound assistant service. If it is not active, the existing notification fallback remains. Launch acknowledgement says “Opening”, not “Opened”; Android/OEM behavior still needs device validation. No accessibility or overlay permission is requested, and this does not grant privileged hardware hotword or concurrent recording access.

## Phone acceptance checks

1. Start session, press Home, speak unrelated sentences. No call is created and no answer is spoken.
2. Say Hey Jarvis, wait for the readiness beep, ask the battery percentage. Hear a reply without reopening Jarvis. Continue a second turn normally.
3. Say goodbye. Say unrelated speech, then Hey Jarvis again. Only the second wake starts a new saved call.
4. Use keyboard dictation and a voice-message recorder while passive, while Jarvis is listening, and while it speaks. The other app must receive audio; Jarvis must yield, discard partial input and return to passive mode after release.
5. Stop the session from the notification. The Jarvis mic indicator must disappear and Hey Jarvis must do nothing.
6. Select Jarvis as default assistant, start a session, press Home, wake Jarvis and ask Open Facebook. Verify Facebook visibly opens; a spoken launch acknowledgement alone is not proof.
7. Copy diagnostics. The report contains only the most recent call, retaining action records separately from audio events.

Passive diagnostics include microphone RMS and peak measured over each complete three-second window without storing audio or transcription. Cancellation and Activity destruction are recorded as call events. Preparation is labeled Preparing rather than Thinking; the full current state is displayed below the controls. A wake match alone does not prove command capture or app launch succeeded.

## Wake detector regression (build after 577)

The 2024 English BPE keyword model missed synthesized “Hey Jarvis” at the shipped settings. Replace it with the [2025 pronunciation-based Sherpa keyword model](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html), using its en.phone entries HEY and JARVIS, int8 encoder/joiner, float decoder, score 2.0, threshold 0.25, eight search paths and two trailing blanks. The download and installed files are hash-verified. The previous keyword model is removed after successful installation. This is still keyword spotting only; Moonshine starts after a match.

Reproduce the native acoustic check with `python scripts/check_wake_word.py <extracted-2025-kws-directory> <extracted-vits-piper-en_US-lessac-low-directory>` using sherpa-onnx 1.13.7 and numpy. Piper is a desktop test-fixture generator only; it is not restored to the app. With deterministic synthesis, three positive phrases and eight negative phrases, three speeds, two volumes, and added ambient noise: 65/66 cases matched expectation. All normal-volume wake cases and all 48 negative cases passed. One fast, quiet, paused phrase was missed. A separate 60-second ambient-input test had no false wake and detected the subsequent phrase. Quiet/noisy recall is reported separately from the normal-volume/false-positive regression gate. These synthetic tests do not establish accuracy for a particular person, microphone, or acoustic environment.
