# Jarvis OS V2

Current voice settings: Moonshine/Whisper, Gemma E2B/E4B, and fixed Piper Northern English. Experimental profile controls and old benchmark routes are removed. See [retained development diagnostics](docs/current-diagnostics.md).

A native Android voice assistant built with Kotlin and Jetpack Compose. The core
assistant runs locally without a cloud backend.

## Supported stack

- **Recognition:** Moonshine Small Streaming or Whisper base.en.
- **Conversation and tools:** selectable Gemma-4-E2B-it or Gemma-4-E4B-it.
- **Voice:** Piper Northern English Male medium.
- Kotlin validates typed tool calls before executing phone actions.

See [supported models, upgrades and shared dependencies](docs/supported-model-stack.md)
and [E2B/E4B switching](docs/ai-model-switching.md). Kokoro and Paul are retired;
old diagnostic results retain their original labels.

## Voice implementation

Work continues on `audio-pr2`, existing PR #6. The [current pipeline](docs/voice-pipeline-current.md)
is the source for current behavior and remaining acceptance. The
[implementation plan](docs/local-voice-implementation-plan.md) retains historical
progress. Piper voice quality was accepted; recognition, interruptions, latency,
sustained use and lifecycle acceptance remain open. E4B requires Fold 6 testing.

## APK signing

GitHub Actions tests, builds and publishes signed release APKs for the existing
PR and for pushes to `main`. Increasing version codes allow installation over an
existing app signed with the same permanent key. The signing key is not committed.
Required Actions secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`.

Never create or merge a PR without Justin's explicit permission; see [AGENTS.md](AGENTS.md).
