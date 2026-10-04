# Jarvis OS V2

![Build](https://github.com/battlesbudz/Jarvis-OS-V2/actions/workflows/android.yml/badge.svg)
![Latest release](https://img.shields.io/github/v/release/battlesbudz/Jarvis-OS-V2)

A native Android assistant written in Kotlin and Jetpack Compose. Conversation,
speech recognition and speech output run on the device. Model downloads and
explicit reference lookup use the network; there is no required conversation backend.

[<img src="demo/jarvis-voice-demo-thumbnail.jpg" width="320" alt="Watch the Jarvis voice demo">](https://cdn.jsdelivr.net/gh/battlesbudz/Jarvis-OS-V2@main/demo/jarvis-voice-demo.mp4)

*53-second demo: airplane mode on, phone locked, "Hey Jarvis," interrupted mid-answer. Click to watch.*

Wake-word activation and conversational barge-in keep the core assistant loop
on the device, using Moonshine/Whisper recognition, Gemma through LiteRT-LM,
and Piper speech output.

## Reported device measurements

| Metric | Value |
| --- | --- |
| First-token latency (median) | 740 ms |
| Time to first spoken word (median) | 3.6 s |
| Generation speed (average) | 32.8 tokens/sec |
| Test setup | Hundreds of voice turns on a Galaxy Z Fold 6 |

These reported phone measurements are separate from the emulator release gate.

## Start here

| Goal | Read |
| --- | --- |
| Understand the application and its owners | [Architecture](docs/architecture/README.md) |
| Set up a checkout, build or install | [Development setup](docs/architecture/development.md) |
| Find the right place to change a feature | [Change guide](docs/architecture/change-guide.md) |
| Make a maintainable contribution | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Run checks and understand release evidence | [Verification workflow](docs/verification/README.md) and [feature coverage](docs/verification/features.md) |
| Find feature notes, plans and measurements | [Documentation index](docs/README.md) |

For a quick source map and environment check, run from the repository root:

```bash
python3 scripts/dev.py map
python3 scripts/dev.py doctor
```

The app is one Android Gradle module, `:app`, divided into responsibility-focused
packages and collaborators. [The module decision](docs/architecture/adr-001-package-boundaries.md)
explains the native and release-test constraints. [The refactoring map](docs/app-modularization.md)
records the typed stage owners and justified cohesive components.

## Current stack

| Role | Implementation |
| --- | --- |
| UI | Jetpack Compose; conversation surface with voice overlay, setup, history and diagnostics |
| Local inference | LiteRT-LM 0.16.0; Gemma E2B/E4B and curated experimental Qwen bundles |
| Recognition | Moonshine Small Streaming or Whisper base.en |
| Speech output | Piper Northern English Male medium |
| Phone actions | Validated battery, media-volume and installed-app actions with durable receipts |
| Memory | Device-local SQLite store and source archive; retrieval, review and mutation fences |

[`ModelCatalog.kt`](app/src/main/java/com/battlesbudz/jarvis/v2/ai/ModelCatalog.kt)
is the authority for selectable model capabilities, filenames and pinned downloads.
Model availability is separate from measured accuracy or speed on a particular
phone. See [model selection](docs/qwen-model-selection.md),
[Gemma switching](docs/ai-model-switching.md) and [voice acceptance notes](docs/verification/voice-audio-and-metrics.md).
Kokoro and Pocket/Paul are retired; historical evidence keeps its original labels.

## Build and release

The hosted `Android APK` workflow runs native/helper checks, release JVM tests,
signed normal and compact ARM64 builds, recorded speech, native page-size checks,
API 29/30/35/36, foldable and genuine 16 KB emulator journeys, and an
exact-build evidence receipt before publishing GitHub Release APKs.
The app targets Android 10+; the shipped native ABI is `arm64-v8a`.

Use the [GitHub Releases page](https://github.com/battlesbudz/Jarvis-OS-V2/releases)
for installable APKs. A green emulator run is a release candidate: real model,
microphone, speaker, Bluetooth and Fold 6 performance still require device evidence.

Work continues on `audio-pr2` under existing PR #6. Read [AGENTS.md](AGENTS.md)
before automated work; creation or merging of a PR requires Justin's explicit permission.

## License

Source-available under the PolyForm Noncommercial License 1.0.0: free for personal,
study, research, and other noncommercial use. Commercial use needs permission.
See [LICENSE.md](LICENSE.md).
