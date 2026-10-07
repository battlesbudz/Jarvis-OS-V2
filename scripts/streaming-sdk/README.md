# Reviewed streaming SDK producer

This directory builds one pinned LiteRT-LM 0.16.0 Kotlin/JNI pair for Android
ARM64, with the native PCM owner and checked Session/Conversation lifecycle ABI.
It is consumed only as an explicit local AAR. There is no stock Maven fallback.

See [the SDK review contract](../../third_party/litert-lm-0.16.0/README.md) and
[release integration coverage](../../docs/verification/streaming-sdk.md).

Run the dependency-free helper tests:

    python3 -m unittest discover -s scripts/streaming-sdk -p 'test_*.py' -v
    python3 scripts/streaming-sdk/build_android_sdk.py

The second command is a dry plan. A real build requires `--run`, a reviewed patch,
its SHA256, reviewed-source manifest, the unchanged page auditor and a fresh output
directory. It requires existing licensed SDK35, NDK28.1.13356709, JDK21 and Clang.
Only the existing hosted pipeline should supply these for this candidate. The
script does not install SDK packages or accept licenses. Workflow sdkmanager steps
require an existing SDK-license file and read EOF rather than accepting new terms.

`build_android_sdk.py` verifies the source pin/patch/manifest, applies two strict
build-only overlays, downloads exact checksum-pinned tools and runtime roots,
builds the regular SDK and normal owner JNI targets, and compiles the exact fifteen
production Kotlin sources to Java17. No Kotlin/JNI test target is compiled into
this AAR; no models are downloaded by this builder.

`package_android_aar.py` packages all seven GPU/runtime dlopen roots plus recursive
DT_NEEDED closure. API30 public NDK stubs alone are treated as platform libraries.
It verifies the exact ninety production class names, thirty SDK native method
descriptors, thirty-one SDK JNI exports and six normal-owner methods/exports. The
unchanged page auditor checks every selected native object for ARM64/16KB safety.
It retains the upstream manifest/notices and embeds a complete source receipt.

`validate_artifact.py` rehashes the AAR, classes, all native entries and external
provenance, verifies its embedded receipt, source patch, manifest, production
inventory, native closure and the current run/source plus original successful
SDK producer attempt. It runs before BOTH
normal and compact Gradle invocations, and again as their preBuild prerequisite.
The consumer checks the exact artifact ID against the current run's successful
producer window using the existing artifact resolver, rather than selecting the
newest numeric ID or an artifact from another run. The producer exports its exact
artifact names/IDs and original attempt. A downstream-only retry may reuse that
successful producer; a newer failed producer still forbids fallback.

Gradle requires all four properties:

- `litertLmBridgeAar`: exact AAR path
- `litertLmBridgeProvenance`: matching external provenance path
- `litertLmBridgeSha256`: producer's AAR SHA256
- `litertLmBridgeProvenanceSha256`: producer's provenance SHA256
- CI additionally requires `litertLmBridgeProducerAttempt`: retained successful
  producer attempt; it must not be inferred from the consumer's retry number

A local AAR loses Maven transitive metadata, so app Gradle explicitly retains
Gson2.13.2, kotlin-reflect2.3.21 and coroutines-android1.9.0. App native compilation
continues to use NDK27.2.12479018 for Sherpa/MicroWakeWord.

## Parallel build and quality producers

The ARM64 SDK producer has a45-minute job budget. It uploads only its verified
AAR, provenance and exact source-receipt after packaging validation, then finishes.
App assembly and the independent full-E2B quality job may start together using
that exact successful SDK artifact. The quality job has115minutes total, including
setup, a60-minute internal compiler limit/63-minute step and unchanged50-minute
model orchestration. The SDK+quality outer budgets remain160minutes combined.

The fresh quality runner verifies all three producer hashes, checks the source
receipt equals the AAR's embedded source, reconstructs the pinned source+reviewed
patch+both build overlays, and downloads the pinned host-only inputs. Its source
snapshot deliberately ignores separately pinned prebuilt `.so` files; Android
prebuilts and the Android Bazel cache are not transferred or rebuilt for that check.

SDK and quality have distinct producer job names, IDs, artifact names and attempts.
A retained SDK attempt1 may support a successful quality attempt2 and a later
receipt retry. Latest failed producer attempts cannot fall back to older artifacts.
The final binding requires the quality build's android_source_receipt_sha256 to
match the exact SDK used in both APKs, so equal patch hashes alone cannot conceal
a different SDK producer. Both original producer identities are retained.

Early signed APK artifacts include an upload-time UNVERIFIED-CANDIDATE.txt notice
with the exact workflow-run URL and authoritative receipt location. It is not a
release approval; a later successful receipt supersedes it. All old verification
and publication requirements remain, with quality now an explicit prerequisite
on every publication path. GitHub Release assets remain verified APKs only.

## Shared C++ runtime boundary

The pinned rules_android_ndk `BUILD.ndk_sysroot.tpl` selects `libc++_static.a` and
`libc++abi.a`. The normal owner's Android-only `linkstatic=True` overlay prevents
its Linux host-test dynamic dependency topology from being packaged on Android.
The Linux setting and separate test target are unchanged. This is source evidence;
actual compiled Android dependencies still decide the packaged closure.

Sherpa separately copies the NDK27 `libc++_shared.so`. If the SDK closure requires
its NDK28 shared runtime, consumer validation stops with both hashes before Gradle.
It never removes a required runtime, silently uses `pickFirst`, or assumes the old
and new runtime are compatible. A real collision requires symbol/ABI inspection
and review of a deterministic single-runtime adapter before continuation.

## Packaged Kotlin contract

The producer compiles `PackagedSealedContentContract.kt` as a separate SDK-owned
friend test module against the actual production classes.jar. Its 13 assertions
check copied finite synthetic bytes, signed zero, wire metadata and independent
JSON snapshots before packaging. App tests use only public SDK API. Compilation
and execution have separate 60-second/384 MiB heap and 20-second/128 MiB heap
limits; those heap values are not total process RSS caps.
The result is bound to the jar and test-source SHA in source-receipt.json and a
standalone packaged-kotlin-contract.json; test classes never enter the AAR.
