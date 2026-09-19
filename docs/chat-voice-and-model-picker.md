# Shared chat, voice and model selection

Chat and Voice call are two modes of one conversation surface, with one app-private conversation history. The application runtime owns generation and persistence, so folding/rotating the phone or backgrounding the Activity does not make UI callbacks the sole copy of a message.

- The shared header has a Chat / Voice call segmented control and one Settings button. Settings contains the provider-grouped model picker, phone guidance, voice input options and diagnostics in either mode.
- Type in Chat or start the existing hands-free session in Voice call. Starting Voice still waits for Hey Jarvis.
- Voice animates an opaque orb/call-control surface over the still-mounted chat. The draft and list state remain alive beneath it. The covered transcript is hidden visually and from accessibility semantics; switching into Voice clears keyboard focus.
- End call and a recognized goodbye reveal Chat while the conversation screen is open. Selecting Chat or pressing Back from Voice also stops the voice session. Wait for native audio/model cleanup if Send reports that work is still finishing.
- Spoken user messages are italicized and labeled “Spoken transcript.” Only final recognized input is retained; provisional ASR text is not a user message.
- Deleting a saved call also removes its linked transcript segment from chat.
- Calls remain available through Conversations → Saved voice calls. “Continue in chat” imports legacy calls explicitly; resuming voice adds a new segment to the selected shared thread without copying old messages twice.
- Generated assistant text is visible for review. For a voice interruption, model context uses the existing delivery ledger (actually spoken words and action receipts), not unsaid generated text. Unfinished typed replies remain visible but are excluded from subsequent context.
- New starts a separate thread. Conversations reopens saved threads. Selecting a model changes the engine but keeps the thread. Native context remains bounded; the full displayed history is not an unlimited model memory.
- Text chat does not require installed speech models. Voice setup still prepares its own speech dependencies.

## Catalog scope

The picker contains the existing 17 pinned models plus 73 additional entries: **90 total**, grouped by original model provider. Search matches provider, name and purpose. Each additional entry selects one portable bundle; this is not a list of every quantization, NPU-specific artifact or third-party conversion on the internet.

`model-catalog-audit.json` records exact publisher repository revisions, filenames, SHA-256 values, sizes, backend choices and exclusions, audited 2026-09-19 against the Hugging Face `litert-community` repositories. The app already pins LiteRT-LM 0.16.0. No runtime upgrade is introduced here.

Not added: models with only legacy `.task` or raw `.tflite` files; embedding/reranking/ASR/TTS models; retired FunctionGemma task-specific bundles; document/OCR bundles needing a separate preprocessing/prompt path; the non-running 1-bit Bonsai proof of concept; Gemma 4 12B, which requires runtime 0.17. Hardware-specific NPU exports are omitted because this app uses the portable CPU/GPU backends. Existing downloads remain pinned unchanged.

Publisher-gated models display an access notice and a publisher link; their automatic download button is disabled. Accept the publisher terms and download in a browser, then import the file. Jarvis does not collect a Hugging Face token.

New bundles are **experimental in Jarvis until tested on a phone**. Publisher format/backend evidence and a pinned hash do not prove every Android driver or conversation mode works. The built-in model test verifies file integrity, initialization and readable generation; it is not an accuracy benchmark. Llama base models and task specialists are labeled accordingly. Native tool registration remains enabled only for existing supported Gemma models; selecting a model trained for tools does not automatically integrate its tools.

Image-capable bundles are labeled as such, but the new text composer does not add image attachment or device GUI control. All conversational entries can be used through the existing ASR → text → Piper voice pipeline; this is not a claim of native audio support.

## Phone check

Manufacturer/model, chip, ARM64 support, total/free RAM and free internal storage are read locally. No device information is uploaded. Resource estimates are fallback guidance based on artifact size, CPU/GPU overhead and total device RAM. Currently free RAM does not disqualify a model: Android can reclaim cached memory, and an already-loaded model itself consumes RAM. An installed model with an unchanged verified file and a persisted successful reply test gets “Tested on your phone” ahead of memory estimates. Missing, replaced or invalid model files do not inherit that label. Fold6 guidance prioritizes the previously tested Gemma E2B over the slower E4B. Storage needed for a new download is shown separately and never changes an installed model’s suitability. These are estimates, not measurements of generation speed or a guarantee against Android process termination.

## Acceptance on a phone

1. Open Settings from Chat and Voice; confirm there is just one entry point and the same model/voice controls. On Fold6, E2B should remain recommended even with little free RAM. An installed, tested model should not show a download-storage warning. Search a provider/purpose, inspect guidance, download one small new model, run its test and chat. Switching to an installed model should not download it again. Delete a downloaded model and check freed storage.
2. Type “My dog's name is Luna,” leave an unsent draft, then switch to Voice call. Confirm the keyboard closes and the orb covers the transcript beneath the same header. Start voice, say Hey Jarvis, and ask “What is my dog's name?” Confirm no live transcript appears (including with TalkBack).
3. End call. Confirm the same chat and unsent draft reappear, the recognized user words are italicized, the reply appears once, and a typed follow-up refers to the same conversation. Repeat using the Chat selector and Android Back to end voice.
4. Interrupt a spoken reply, end the call, and inspect the generated/unfinished reply. Ask what was said; the model should use delivered speech, not the unspoken tail.
5. Rotate/fold and background the phone during a response, reopen it, then force-stop/relaunch after completion. Confirm saved thread order and no duplicate call entries.
6. Start New, confirm it has no prior thread context, then reopen the previous thread through Conversations. Try an older call's Continue in chat and Resume conversation.
7. Try starting voice while text generation is active and switching models during an active operation. Controls/runtime admission should prevent overlapping native work.

## Validation

42 focused JVM tests passed locally. These cover catalog pinning/provider coverage, Fold6 recommendations under low free RAM, separate download-storage notices, installed-test evidence and invalid/missing-evidence fallbacks, text→voice→text persistence, repeated call snapshots, late receipts, partial assistant context, legacy calls and linked-call serialization/resumption. Android release compilation, the full JVM suite, native packaging, signing and APK assembly run in the existing PR workflow. Physical audio, model speed, UI rendering and every newly listed model still require device testing.
