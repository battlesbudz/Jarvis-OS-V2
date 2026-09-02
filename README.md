# Jarvis OS V2

A standalone native Android voice assistant prototype.

## Direction

- Kotlin and Jetpack Compose
- Local Gemma 4 E2B for conversation and reasoning
- FunctionGemma MobileActions-270M for local mobile-action routing
- Kotlin validates and executes typed actions
- No cloud backend required for the core assistant loop

The first implementation PR establishes the Android shell, model boundaries, action contracts, and local benchmark harness.
