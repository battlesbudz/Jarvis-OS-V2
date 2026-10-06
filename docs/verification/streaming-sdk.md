# Streaming SDK release boundary

The existing Android APK workflow now has a source SDK producer prerequisite.
It uses the exact upstream/source patch under `third_party/litert-lm-0.16.0`.
Implementation and commands are in [the helper guide](../../scripts/streaming-sdk/README.md).

## Observable acceptance checks

- Packaging/helper: the frozen production classes and normal JNI exports are
  present together; old Session/Conversation descriptors, test classes/entrypoints,
  changed source files, fake owner libraries and a missing runtime dependency fail.
- Provenance/helper: changed AAR/class/native/receipt bytes, a stale workflow
  run/attempt/source, wrong producer digest, or unknown ABI fail before Gradle.
- Native/hosted: crosscompile with NDK28.1.13356709 and native API30; inspect all
  seven GPU/runtime roots and recursive closure; retain every ARM64/16KB audit.
- Actual model/hosted: after the Android compile has exited, bounded full-E2B
  native quality probes must complete successfully. A resource refusal, failed
  inference, missing result, or failed export is a failed producer prerequisite.
  Only allowlisted weightless receipts are uploaded. This is host-model evidence,
  not Android or phone inference coverage.
- Preserved release gates: NDK27 Sherpa/MicroWakeWord, all release JVM/native tests,
  existing signing identity, normal+compact equivalence and signatures, every
  API30/API35/API36 five-profile sandbox/upgrade/layout gate, exact-build receipt
  and all existing publication dependencies stay required.

The successful SDK AAR is selected by its producer's exact artifact ID plus the
current run's source and successful producer timestamp window. Both Gradle
invocations recheck the producer's AAR/provenance hashes and current workflow
identity, retaining the original successful SDK attempt on downstream-only
retries. Exact exported producer artifact names/IDs remain required; a newer
failed producer forbids fallback to an older successful artifact. The matching provenance and selection manifest are retained as
`jarvis-streaming-sdk-consumer`; the consolidated release receipt also verifies
both APKs embed exactly that provenance and retains the SDK and successful
weightless quality evidence. The full producer/source/ELF evidence is retained
separately. New source cannot silently fall back to Maven0.16.0.

The serial SDK+quality job is bounded to160minutes: Android compile/package,
then at most48minutes probe compilation and50minutes quality orchestration.
Quality subprocess memory/CPU/wall limits are independent and fail closed.
No signing keys are copied to the SDK job, no new secrets are provisioned, and no
model weights are uploaded. SDK provisioning reads EOF if a license prompt would
require accepting new terms; that is a blocker requiring review, never a pass.

## Explicit coverage limits

Helper tests and source inspection are not an ARM64 build. A source-built AAR is
not a signed APK. A signed APK is not a completed sandbox run. Host-model speech
quality does not verify Android GPU loading or physical Fold6 microphone/speaker,
Bluetooth/echo/barge-in, latency, memory pressure, thermal behavior or performance.
Those remain separately identified until measured on the actual device.

Do not claim a release candidate verified until the exact-revision existing
full release pipeline finishes. Do not merge or change any publication gate to
hide a missing stage. Preserve failed compiler, audit, helper and quality receipts.
