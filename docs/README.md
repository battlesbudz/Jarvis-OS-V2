# Documentation index

New maintainers should read [architecture](architecture/README.md),
[development setup](architecture/development.md),
[where to change a feature](architecture/change-guide.md), and
[CONTRIBUTING.md](../CONTRIBUTING.md) in that order. Use
[scripts/README.md](../scripts/README.md) for build and harness entry points.

## Current navigation and contracts

| Area | Starting point |
| --- | --- |
| Package ownership and runtime flow | [Architecture](architecture/README.md) |
| Refactoring boundaries and remaining coordination | [App modularization](app-modularization.md) |
| Why one Gradle module | [ADR 001](architecture/adr-001-package-boundaries.md) |
| Automated acceptance and its limits | [Verification workflow](verification/README.md), [feature map](verification/features.md) |
| Model files, integrity and selection | [Model management](model-management.md), [Gemma switching](ai-model-switching.md), [Qwen catalog](qwen-model-selection.md) |
| Voice behavior and device acceptance | [Voice pipeline](voice-pipeline-current.md), [current diagnostics](current-diagnostics.md), [voice/audio/metrics checks](verification/voice-audio-and-metrics.md) |
| Conversation and voice presentation | [Chat and model picker](chat-voice-and-model-picker.md), [call display](voice-call-display.md) |
| Memory storage and implemented milestones | [Memory OS checkpoints](memory-os-native.md), [memory wiki](memory-wiki.md) |
| Supported native phone actions | [Short multi-action contract](verification/short-multi-action.md) |
| Exported production measurements | [Pipeline benchmark contract](verification/pipeline-benchmarks.md), [per-reply latency](per-reply-latency.md) |
| Native packaging and size | [APK size audit](apk-size-audit.md), [supported stack](supported-model-stack.md) |

The source tree and executable checks are authoritative for current behavior.
Feature notes can contain earlier checkpoints and pending device acceptance in the
same file. Check their dates and the exact-build receipt before relying on a status.

## Plans and product decisions

- [Local voice implementation plan](local-voice-implementation-plan.md) and
  [Jarvis roadmap](jarvis-roadmap.md) preserve the evolution and remaining acceptance.
- [Tools implementation plan](plans/tools-implementation-plan.md),
  [interview decisions](plans/tools-interview-decisions.md), and
  [integration log](plans/tools-integration-log.md) distinguish implemented native
  actions from future integrations, workflows and proactive scheduling.
- Memory OS checkpoint notes distinguish shipped storage/review/archive behavior
  from planned extraction, embeddings and external source adapters.

These are planning and decision records. A planned feature is not an available
capability until its source and verification contract exist.

## Historical evidence

Build-specific reports such as `voice-build-*.md`, `voice-repair-*.md`,
`fold6-benchmarks.md`, and `measurements/` retain their original revision, model,
route and test conditions. The files in `research/` are dated integration research.
Paul/Kokoro experiments and old comparison routes are retained for investigation,
even though those voices/routes may no longer be selectable.

When adding documentation, put navigation/contracts in `architecture/` or
`verification/`, future work in `plans/`, and dated numeric evidence in
`measurements/` or a clearly labeled report. Link it from this index or its feature
entry point; preserve earlier evidence instead of relabeling it as a current pass.
