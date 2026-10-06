# Hosted full-Gemma E2B native quality prerequisite

Status: source prepared and lightweight orchestration tests run. **This hosted
recipe has not been compiled or executed. No full-model pass is claimed.**
The earlier native Conversation source compiled locally, but no completed
full-E2B paired run is borrowed from that work. Every input and receipt below is
regenerated in a new hosted directory. Actual CI and first APK remain pending.

## Minimal integration

Copy this directory's SOURCE-MANIFEST.json allowlist, plus the manifest itself,
as scripts/streaming-quality/. Do not copy __pycache__, transient test files, or
research logs. Merge hosted-steps.yml.example into the existing
build-streaming-sdk job after build_android_sdk.py succeeds. That helper must
provide the recorded reviewed patch and its two source overlays. The fragment
is only a proposal until integrated/reviewed; it does not trigger anything.

The host probes build in the same exact SDK checkout after the Android compiler
exits. Bazel batch mode exits before any model download, reconstruction or native
inference. The pipeline first verifies a fresh worktree recreated from the exact
SDK commit + reviewed source PATCH + exactly the Android API30 and Android-only
owner linkstatic overlays equals the actual Android source tree. Then it installs
a separate hosted experimental package, hashes every SDK source/config input,
checks official pinned Git-LFS host libraries, compiles three tiny probe targets,
and hashes the resulting binaries and resolved dynamic dependencies. Source and
runtime identities are rechecked before both Conversation lanes.

A passed quality step is a prerequisite for the dependent APK job. Build,
download, source/hash, numerical, native-execution, evidence, semantic-reference,
and resource failures all leave the job failing. There is no continue-on-error,
retry, automatic budget expansion, skipped-pass, or legacy-model substitution.
Retain the compiled Android AAR separately if desired; failure receipts do not
make it a quality-approved APK. Keep all preexisting app checks.

## Fresh input chain

- Official LiteRT-LM 0.16 commit 924e79c91542761242244e4f1651851f822e4cbb;
  its WORKSPACE LiteRT pin 0ff28117f1cb5556d0e015bf80b773f74e2bee51.
- Full public bundle from the official HF revision
  6e5c4f1e395deb959c494953478fa5cec4b8008f, 2,588,147,712 bytes,
  SHA256 181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c.
  The complete file hash is checked on download, before section extraction,
  by the reassembler, and freshly before each Conversation lane.
- Exact official public roses-are.wav, 295,404 bytes, SHA256
  9e42e31cbc41cb3c31dc4ddb1dc05fef322c61b98cc03a38c06749b2db94f6b2.
  NumPy2.3.5/SciPy1.17.0 reproduce the existing resample operation into all
  49,221 Float32 mono16kHz samples; expected SHA256
  f57adbb58a9a9ce6f198a56166b8f8f8021ff0073f7a54fc2c5171161f933c12.
  A float WAV copies those exact bits, SHA256
  6438b41f257e31bfdd94148bd9d805a7845ed27a5dfd57d774f1c0ff3ef3cb7b.
- The current full SDK MiniAudio frontend checks WAV decoding against those PCM
  bits, then independently preprocesses WAV and PCM, compares the complete Mel
  arrays bitwise, and writes fresh307x128 Mel bytes. Python requires SHA256
  243f69cbf70e632fd44535c33ba7f8d01fb3e9907e708880d550a5c5d3cdd851.
- Original encoder/adapter/EOA sections are copied from that full bundle with
  pinned offsets, sizes and hashes. The existing audited Java source and220,198
  structural-only asset bytes reconstruct the103,668,112-byte explicit-state
  encoder, SHA256
  d5c50b140ace235717e6713d287e73ccfa4f32d0090e1cceb9d00714da850a1b.
  Learned source payload is absent from the recipe assets. Reassembly and model
  derivative license scope are documented in recipe/; no reconstructed model is
  copied into the source tree, artifact upload, or APK assets.
- A same-pinned-native-runtime oracle runs original static encoder+adapter and
  the reconstructed stateful encoder across48,48,48,48,48,48,19 Mel-frame steps.
  It checks counts/masks, finite compared outputs, all77x1536 valid post-adapter rows
  bitwise, and learned EOA equality. Fresh projected prefix SHA256 must be
  e51d19c68f19e02ea3075720674932ddcc83948f8e4c8b507047e055b06f93e9.
  Padded rows and every internal layer cache are outside this minimal oracle;
  they have separate earlier tests, not imported success receipts.

All exact hashes are fail-closed checks, never recomputed expectations accepted
on mismatch. model-structure.json is fixed tensor schema/section metadata for
those hashes, not a borrowed inference receipt. The native runner also checks
actual signature names, tensor types, shapes and byte sizes.

## Pair and semantic scope

Fresh native C++ processes run projected_null, then raw, each with a fresh engine
and Conversation, same complete PCM, same prompt and context512, max64 output
tokens, CPU1, greedy sampling, thinking/speculation/tools disabled. The projected
lane supplies only77 post-adapter rows, no learned EOA; the runtime owns its EOA.
Both lanes must invoke the post-adapter callback exactly once with all77 rows
bitwise equal to the fresh oracle, consume positive prefill/decode tokens, finish
below the decode cap, and pass checked drain/delete. The comparator requires
identical full response JSON, text, tool-call arrays, counts and all identities.

In addition, both transcripts must match "Roses are red, violets are blue" after
case/punctuation/whitespace normalization. The reference is the official Google
documentation's example output for this exact public audio. It is **not a new
human listening annotation**. An equal but wrong transcript fails this reference
check. A pass is one narrow native transcription prerequisite. It is not an
Android/JNI full-model test, a human-annotated benchmark, an action-intent test,
or a phone-performance result. No tools/actions are dispatched.

## Resource and evidence policy

Each native/reassembly process uses a hard4GiB address-space limit, sampled
3.5GiB RSS watchdog, minimum5GiB MemAvailable,1GiB ongoing system reserve,
240s wall,239s CPU soft signal/240s hard CPU, CPU affinity1 and thread hints1.
Reassembly adds Java128MiB heap and small JVM reservations. The regular-file
output limit is128MiB so its103.7MB temporary model can be assembled; this does
not relax any memory/time limit. Watchdog stops kill the process immediately.
Probes never overlap.
No claim that the complete model fits these caps is made.

Only an explicit memory-headroom refusal, supervisor watchdog reason, SIGXCPU,
ENOMEM, std::bad_alloc, RESOURCE_EXHAUSTED, or explicit allocation/mapping out-of-
memory diagnostic is called resource_constrained. An unadorned
per_layer_embedding_lookup_ load failure, generic mmap failure, or SIGKILL is
native_execution_failure, not evidence of memory exhaustion. Both are failures.
Unknown/unrun/inconclusive status can never pass. Download/build bounds are
separate from inference limits: one bounded600s bundle transfer,45min host build,
and finite CI step/job windows; no automatic retries.

export_evidence.py exports only the enumerated structured receipts: source/model
hashes, compiler/binary identities, process limits/exit/peaks, frontend/oracle
booleans, exact small textual Conversation responses and the comparison. It
excludes all model files, PCM/WAV/Mel, activation arrays, request blobs, runfiles,
binaries and raw stdout/stderr logs. A malformed/oversized/raw-data receipt makes
export fail closed. Always upload only streaming-quality-evidence/, never the
build/run/input trees. EVIDENCE-INDEX.json includes the exact CI commit/run/attempt.

## Source adaptations

native_conversation_quality_probe.cc and compare_native_pair.py are unchanged
from the previously compiled native-only fallback. pinned_encoder_probe.cc is the
existing native probe with its CPU thread count reduced from2 to1. The frontend
probe is the earlier full-SDK boundary check with a small fresh-Mel output branch
instead of reading a historical Mel file; both native paths still compare bitwise
and Python checks the pinned Mel hash. Recipe assets and Java are byte-for-byte
copies of the reviewed weightless recipe. No SDK source or CI job was executed
while preparing this package.
