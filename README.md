# Jarvis OS V2

A standalone native Android voice assistant prototype.

## Direction

- Kotlin and Jetpack Compose
- Local Gemma 4 E2B for conversation and reasoning
- Gemma 4 E2B native tool calls for local mobile-action routing
- Kotlin validates and executes typed actions
- No cloud backend required for the core assistant loop

PR #1 includes the testable local assistant loop. Type a request such as
“What is my battery level?” in the chat: Gemma 4 E2B emits the registered
`read_battery` tool call, Kotlin validates it, and the Android executor returns
the phone's live battery percentage.
No cloud backend is required for this loop.

## Voice implementation plan

The [local voice implementation plan](docs/local-voice-implementation-plan.md)
defines the proposed audio-pr2 work for continuous capture, natural interruptions,
playback-aware memory, local turn detection, model reuse, and stable Paul speech.
It includes dependencies, affected files, phone acceptance checks, and measurement
goals. Implementation status is tracked in the plan.

## APK signing

When a commit reaches `main`—including after a PR is merged—GitHub Actions builds and publishes a fresh release APK. Release APKs are signed with the repository's permanent release key, and each build receives an increasing Android `versionCode`, so a new APK installs as an update instead of requiring the previous app to be deleted. The keystore is intentionally not committed. The repository Actions secrets required for this are `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`.
