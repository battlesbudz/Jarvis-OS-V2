# Modular refactor acceptance

The refactor starts from `audio-pr2` commit
`923a0e6bc2aab3f93371ed5c691afc3244e6bf17` (published Build 885).
That build is a baseline, not verification of subsequent changes.

## Observable requirements

| Requirement | Verification |
| --- | --- |
| Runtime responsibilities have named owners and explicit dependencies | Code review and [architecture map](../architecture/README.md) |
| A newcomer can find entry points, feature owners and checks | README, CONTRIBUTING and developer-command review |
| Model setup/import/download retains selection and operation ownership | Existing model JVM tests and journeys 01–03, 10–12, 32–33, 90 |
| Chat and the floating voice overlay retain their controls and navigation | Existing journeys 25–31, 46–47 |
| Corrected/erased memory cannot leak through an in-flight reply | Existing memory delivery, receipt and invalidation JVM tests; journeys 24–26, 34–39 |
| Accepted phone actions remain journaled, ordered and cancellation-aware | Existing action queue/lease/ledger JVM tests; journeys 14–23, 40–44 |
| Speech keeps PCM, queue, drain and interruption contracts | Existing voice JVM tests, focused extracted-component tests, native packaging/callback checks |
| Invalid action requests and failed storage still stop effects | Existing rejection/failure JVM tests and journeys 05–06, 08, 15–17, 35, 38, 40–43 |
| Helper checks work from a fresh checkout without another session's files | Python suites, including portable artifact replay and developer-command failure cases |

This is structural work. Preference keys, storage schemas, model identities,
Android component names, native callbacks, release selectors and all named
journey assertions remain compatibility constraints.

## Release gate

The exact changed revision must pass the existing `Android APK` workflow:
release JVM/native/helper checks, signed normal and compact APKs, every named
journey in `scripts/verification/scenarios.json` on API 30/API 35, the same-run
receipt, and publication. Refactoring does not waive any step. Use the run's
receipt to identify both the branch head and tested PR merge commit.

Review the new collaborator boundaries for lifetime, dispatcher, cancellation,
memory fence and model lease ownership before pushing. Do not infer correctness
from smaller file sizes alone.

Real model inference, microphone/speaker behavior, successful large model
transfers and Fold 6 performance remain physical-device checks. A structural
refactor does not establish improved speech accuracy or lower latency.
