# Issue #52, idea 1: Parakeet Ultra: implementation notes

- Date: 2026-11-06 (overnight, unattended session)
- Branch: `codex/issue-52-parakeet-ultra` (from `origin/development`). Committed locally only. Not pushed: this environment has no GitHub credentials.
- Status: implemented, unit-tested and built on-device. **Not yet tested on the phone** (needs the first manual APK install).

## What changed

| Area | Change |
|---|---|
| `parakeet/ParakeetUltraModel.kt` (new) | Model declaration: id `parakeet-ultra-tdt-0.6b-v3`, runtime `parakeet_ultra`, 6 pinned artifacts with sizes and SHA-256 |
| `parakeet/ParakeetBackend.kt` | `parakeetUltraBackend()`: the existing `SherpaParakeetDecoder`, featureDim 128 (same as Orukeet) |
| `recognition/ManagedRecognitionBackend.kt` | Maps `parakeet_ultra` to the backend |
| `recognition/RecognitionModelCatalog.kt` | New card `parakeet-ultra`, placed after Parakeet TDT |
| `settings/Settings.kt` | `SpeechBackendType.ParakeetUltra("parakeet_ultra")` |
| `settings/pages/ModelPresentation.kt` | Grouped under the **Parakeet** family. Model-info link to Moondream. Automatic language guidance like the other v3 derivatives. |
| `AudioRecognizer.kt`, `diagnostics/DiagnosticConfiguration.kt`, `benchmark/BenchmarkModels.kt` | Runtime id added next to `orukeet` (runtime version in diagnostics, accepted runtime, auto-language label) |
| `harper/HarperEnglishGate.kt`, `s1/S1MiniEnglishGate.kt` | Ultra treated exactly like Orukeet (multilingual, no language detection) |
| Unit tests | Pinned-package test for Ultra. Catalog and presentation counts 12→13 and 22→23. Ultra added to the Parakeet family, language-guidance and Harper gate tests. |
| Docs | `docs/model-guide.md` (model table, size table, download note), `docs/parakeet-redux-comparison.md` (Ultra no longer "not integrated") |

## Decisions (please review)

1. **Weights: community int8 export, not a self-export.**
   [`erponce/sherpa-onnx-parakeet-ultra-tdt-0.6b-v3-int8`](https://huggingface.co/erponce/sherpa-onnx-parakeet-ultra-tdt-0.6b-v3-int8) pinned at `a2d3be97d0b5d3f01a5cf5829d25bcf690aa1be9`.
   - Why: exporting it ourselves needs NeMo and PyTorch, which aren't realistic on this phone (Alpine musl under PRoot). It would also need a place to host 685 MB.
   - Provenance: the NOTICE says SideKick (github.com/eponce00/sidekick) converted it from `moondream/parakeet-ultra` revision `73175eb` with a published script, CC BY 4.0. The LICENSE and NOTICE files are downloaded along with the model, as Orukeet does.
   - Quantization: weight-only int8 using onnxruntime's `MatMulNBits` contrib operator (block 128, accuracy level 4). This differs from the dynamic int8 quantization in the k2-fsa/twmht v3 export. **Risk:** the Sherpa-ONNX 1.13.4 Android runtime must support `MatMulNBits`. ORT has shipped it for a long time, but only a device test proves it. The 47.7 MB decoder is explained by weight-only quantization: the embedding and LSTM weights are not MatMuls, so they stay fp32.
   - Alternative if you prefer: self-export with `scripts/nemo/parakeet-tdt-0.6b-v3` from sherpa-onnx in CI or on a PC, host it in a repo you control, and change only `ParakeetUltraModel.kt` (revision, URLs, hashes).
2. **Individual files, no archive.** The repo has no archive, so the download is ~685 MB and needs the same space installed. Each file resumes and is hash-checked separately, like Parakeet V3.
3. **Its own runtime id (`parakeet_ultra`) and directory.** It doesn't reuse `parakeet` with a variant, so Parakeet V3 and Ultra can be installed, selected, deleted and compared side by side.
4. **Behaves like Orukeet, not like plain Parakeet V3.** Parakeet V3 has extras that are keyed on `SpeechBackendType.Parakeet`: the warm engine manager (`PARAKEET_KEEP_WARM`), the `PARAKEET_USE_VAD` toggle, aggressive VAD mode and the shorter auto-stop drain. Orukeet has none of these, and Ultra follows Orukeet. So the model loads per recording and is released afterwards. Extending the warm manager to all v3 derivatives would be a separate, reviewable change.
5. **Not the default and not bundled.** ASR4ALL Medium stays the default and saved choices are untouched. `-PbundleParakeetModel` still bundles only V3.
6. **Text in the app** describes the publisher's results as "fewer errors than V3 in published FLEURS results". No Android speed or accuracy is claimed.

## Verification

| Check | Result |
|---|---|
| `:app:assembleDevDebug` (full native build, on the phone) | Passed (132 MB dev-debug APK) |
| `:app:testDevDebugUnitTest` | 282 tests, 3 skipped, 1 failed. `ComparisonResultsTest.incompleteAndUnscored...` fails **only here**: tests run on JDK 17 and that test calls `List.removeLast()`, which is JDK 21 API. It is unrelated to this change; CI uses JDK 21. |
| `:app:lintDevDebug` | Passed |
| Model files: sizes and SHA-256 | All 6 downloaded in full and matched (twice: a sub-agent, and the Opus reviewer against Hugging Face). Note: one download was silently truncated with HTTP 200 and fixed by resuming, so the app's hash check matters. |
| Decode test on this machine | **Not possible.** The sherpa-onnx 1.13.4 Linux aarch64 binaries abort in onnxruntime under PRoot/gcompat even with a tiny Whisper control model, so this is an environment issue and not the model. ONNX inspection confirmed `MatMulNBits` (265 in the encoder, 2 in the joiner, 1 in the decoder). The decoder is ~99% fp32 (embedding and LSTM). |
| **On-device recognition (Slovak/Czech/English)** | **Not run.** Needs the APK installed. |
| Instrumentation tests | Not run (no device install possible yet) |

## Open questions / follow-ups

- Review (Opus sub-agent): no correctness bugs. Applied its fixes: a stale model-guide sentence, and the accuracy claims now say they are for the original (not 8-bit) weights. It also added an S1 gate test.
- **Load-failure risk:** if the model fails to load on the phone (for example an unsupported `MatMulNBits`), `acquireRuntime` marks the installation invalid, so the user may re-download 685 MB and fail again. Test the first load before sharing the build.
- On-device test: download, select, dictate in Slovak, Czech and English. Check Stop-to-text time and memory against Orukeet and V3 (Model comparison screen).
- If `MatMulNBits` fails on Android, the fix is a self-export with standard dynamic int8 quantization (decision 1, alternative).
- CI: `release-apk.yml` checks the pinned Orukeet archive. A similar optional check for one Ultra artifact could be added.
- Should Ultra replace V3 in the "Try first / Next option" table of `docs/model-guide.md` for Slovak, Czech and Polish? Not changed until there are device numbers.
- Ideas 2 (live text at pauses) and 3 (custom model import) are not started.

## Environment notes for the reviewer

How this phone builds the project (NDK for aarch64, CMake 4, the JDK 17 override, Cargo's git CLI) is in `docs/ANDROID_DEV_SETUP.md`. `gradle/gradle-daemon-jvm.properties` is modified locally and must not be committed.
