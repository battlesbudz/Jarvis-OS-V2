# microWakeWord provenance

The native frontend, engine, JNI bridge and Kotlin wrapper are adapted from Home Assistant Android commit `ad25245be514613881100eee83023a72d55f7d2c`, module `microwakeword` (Apache-2.0). Copyright Home Assistant contributors. Source: https://github.com/home-assistant/android/tree/ad25245be514613881100eee83023a72d55f7d2c/microwakeword

Jarvis changes: package names, removed Timber and HWASan dependency, host-test CMake target, private dependency symbols, score/warm-up accessors, inference failure propagation, selected-source dependency builds, and explicit no-exceptions for the TFLite Micro interpreter.

The bundled Hey Jarvis v2 model and manifest are by Kevin Ahrendt, from ESPHome micro-wake-word-models, also bundled at the Home Assistant commit above. Repository license: Apache-2.0. Model SHA-256: `21a7976add39ee24ec96c63d96b7aaa18e24d1d9824b963e451da8feb4b78b77`. Source: https://github.com/esphome/micro-wake-word-models/tree/main/models/v2

The model is used with its published feature step (10 ms), cutoff (0.97), sliding window (5), and the matching fixed-point microfrontend. No rescaled Sherpa features or generic mel implementation are substituted.

Native build dependencies are pinned by commit and SHA-256 in CMake: TensorFlow Lite Micro, FlatBuffers, gemmlowp, ruy (Apache-2.0), and KissFFT (BSD-3-Clause/Unlicense). License texts are distributed inside `assets/licenses/`. The native runtime has no external account, key, network service, or model download requirement.
