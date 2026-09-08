# Passive Jarvis sessions

Start Jarvis session in the visible app and allow microphone/notification permissions. The APK bundles the hash-verified 52 KB microWakeWord Hey Jarvis v2 model; no wake model download is needed. Moonshine remains the call transcriber; the retired Zipformer ASR selector is not restored.

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

Passive diagnostics include microphone RMS, peak, and maximum detection score over each complete one-second window without storing audio or transcription. Cancellation and Activity destruction are recorded as call events. Preparation is labeled Preparing rather than Thinking; the full current state is displayed below the controls. A wake match alone does not prove command capture or app launch succeeded.

## microWakeWord

The native frontend and streaming TFLite Micro engine follow Home Assistant Android's implementation. See [provenance and license notices](microwakeword-notices.md). The previous Sherpa keyword models are removed from app storage; Sherpa remains for Kokoro/Miro and Silero. Wake audio is PCM16 mono at 16 kHz and is never transcribed or saved before detection. The UI reports microphone warm-up before the detector's initial suppression period finishes, then Waiting for Hey Jarvis.

Use **Test wake word** with the session stopped for a 30-second microphone-only test. It needs no Gemma, ASR, or TTS inference. After warm-up, say Hey Jarvis. A detection beeps and displays a passed result; otherwise Copy diagnostics includes actual input RMS/peak and model scores. Stop, leaving the voice screen, timeout, or errors releases the test microphone. Keep this short diagnostic test visible; the ordinary Jarvis session remains the background listening mode.

The new detector does not resolve the Android battery limitation or make microphone sharing simultaneous. Validate Home, screen locking, repeated wake after goodbye, microphone yielding, and notification stop on the actual phone.

## Native validation

Build the same frontend and engine on a host with `cmake -S app/src/main/cpp/microwakeword -B /tmp/mww-build` followed by `cmake --build /tmp/mww-build -j4`. Run `microwakeword_test <model.tflite> <16-kHz-mono-PCM16-file>`. Use at least four seconds of ambient audio before the first phrase for the initial warm-up. Android uses the same C++ files through JNI.

Initial generated speech checks recognized all three positive variants (Hey Jarvis, Hey comma Jarvis, and Hey Jarvis followed by a battery question). Ordinary greeting, Hey Google, and Jarvis without Hey were rejected. The synthetic Hey Travis clip falsely triggered (peak averaged score 0.978824); similar-name rejection and real-phone sensitivity remain device acceptance checks. No threshold changes were made to hide that limitation. These results do not establish phone microphone routing or real-world accuracy.
