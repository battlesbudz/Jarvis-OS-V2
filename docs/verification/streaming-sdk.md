# Streaming SDK release boundary

The existing Android APK workflow has a source SDK producer prerequisite.
A successful SDK producer starts APK assembly and full-E2B host quality in
parallel; neither early assembly nor signing establishes final verification.
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
- Actual model/hosted: after the verified Android SDK artifact has been downloaded, bounded full-E2B
  native quality probes must complete successfully. A resource refusal, failed
  inference, missing result, or failed export is a failed quality job and blocks verified publication.
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

The SDK producer has45minutes and the independent quality job115minutes,
preserving the combined160-minute ceiling. Quality's internal compiler budget is
60minutes, its step63minutes and its model orchestration50minutes; all consume
the same115-minute quality ceiling, including setup and evidence export. Model
subprocess CPU/wall/address-space/RSS/headroom limits are unchanged and fail
closed. Four admitted compiler CPUs/jobs retain the6GiB tree-RSS watchdog and
2GiB system reserve. Quality source is reconstructed exactly from the verified
source receipt, pinned SDK, patch and recorded overlays; Android binaries/cache
are not transferred to the fresh quality runner.

SDK and quality artifacts are selected against their own successful producer
job names, exact IDs and retained attempt identities. A quality retry may use an
older successful SDK from this run; a newer failed producer prohibits fallback.
Final binding ties the quality build's source-receipt SHA to both APKs' embedded
SDK provenance. Early APK upload includes an upload-time unverified notice with
the exact run URL; a later passing receipt supersedes it. Published release assets
remain APKs only, and every publication path explicitly depends on quality.

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
