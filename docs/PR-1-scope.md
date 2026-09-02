# PR 1: Local two-model Android foundation

## Scope

This PR establishes the native Android shell and the first real local inference
boundary for the two confirmed models:

- Gemma 4 E2B is the primary conversational model.
- FunctionGemma MobileActions-270M is the fast local action router.
- LiteRT-LM is the Android inference runtime.
- Kotlin owns typed action validation and eventual Android execution.

Model binaries are intentionally not committed to the repository. The app
accepts model paths from the future setup/model-delivery flow.

## User-visible acceptance test

After merge, build and install the APK. On a clean install it opens a guided
model setup screen so the two user-supplied `.litertlm` files can be imported
privately. After both files pass the local smoke test, the app opens a simple
black-box Jarvis chat screen with no model names, implementation details, or
settings exposed in the main experience. Returning users go directly to that
chat screen while their verified local models remain available.

## Automated acceptance tests

- The LiteRT-LM adapter initializes a selected local model off the UI thread,
  maintains a conversation, streams generated text, and reports time to first
  token.
- The action-pipeline test sends a structured request shaped like
  MobileActions-270M output, validates it, and passes it to a recording
  executor.
- Invalid model output is rejected before it can reach an executor.

## Included

- Kotlin + Jetpack Compose Android shell
- LiteRT-LM Android dependency and streaming adapter
- Local model catalog for Gemma 4 E2B and MobileActions-270M
- Replaceable local model interface
- Typed mobile action contracts
- Mobile action router and executor boundary
- End-to-end action-pipeline contract tests
- Confirmed Galaxy Z Fold 6 benchmark record
- PR scope and acceptance documentation

## Deliberately deferred

Model download delivery, model-specific MobileActions tool-schema decoding,
full assistant-session wiring, speech and Kokoro, wake-word detection, EYE VUE,
Bluetooth, memory persistence, and broader real Android action coverage will be
separate focused PRs. The battery and volume executor is present as a safe
native boundary, but it is not yet connected to live model output.
