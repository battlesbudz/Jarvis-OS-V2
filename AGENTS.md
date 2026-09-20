# Repository collaboration rules

- Never create a new pull request without Justin Battles's explicit permission. A request to implement a feature or fix is not permission to open a new PR.
- Commits may be pushed to an existing open PR without asking again when they are part of the requested work.
- Continue the current audio work on `audio-pr2`, under existing PR #6. Do not create a separate PR for additions to that work.
- Do not merge a pull request without explicit permission.
- PR #7 is closed without merging and reserved for a future feature. Preserve its head branch `feature/gemma-model-switch`; do not delete it or create a replacement PR. Repurpose/reopen #7 only when Justin requests the next feature's PR.

## Development verification

- For Jarvis feature work and release candidates, read `.agents/skills/jarvis-verify/SKILL.md` and follow `docs/verification/README.md`. Maintain the acceptance map in `docs/verification/features.md`.
- Run applicable release JVM/native checks and the Android sandbox for the exact changed revision before calling an APK verified. Retain failed-run evidence and fix/retest within the bounded repair policy.
- Label missing real-model, physical audio and device-performance coverage explicitly. A passing emulator suite is a release candidate for Justin's final signoff.
