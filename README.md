# Jarvis OS V2

A standalone native Android voice assistant prototype.

## Direction

- Kotlin and Jetpack Compose
- Local Gemma 4 E2B for conversation and reasoning
- FunctionGemma MobileActions-270M for local mobile-action routing
- Kotlin validates and executes typed actions
- No cloud backend required for the core assistant loop

The first implementation PR establishes the Android shell, model boundaries, action contracts, and local benchmark harness.

## APK signing

Release APKs are signed by GitHub Actions with the repository's permanent release key. The keystore is intentionally not committed. Before merging the first PR, add these repository Actions secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Future release APKs signed by this same key will install as updates to the previous release.
