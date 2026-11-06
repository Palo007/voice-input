# Issue #52: Slovak Parakeet fine-tune: implementation notes

- Date: 2026-11-06
- Branch: `codex/issue-52-slovak-finetune` (from `codex/issue-52-parakeet-ultra`). Committed locally only; not pushed (no GitHub credentials here).
- Roadmap: step 4 of [parakeet-ultra-roadmap.md](parakeet-ultra-roadmap.md). Follows the pattern of [Parakeet Ultra](ISSUE-52-parakeet-ultra-notes.md).
- Status: implemented, unit-tested and built on the phone. **Not yet tested on the phone with real speech.**

## What changed

| Area | Change |
|---|---|
| `parakeet/ParakeetSlovakModel.kt` (new) | Model `parakeet-tdt-0.6b-v3-sk`, runtime `parakeet_sk`, 5 pinned artifacts with sizes and SHA-256 |
| `parakeet/ParakeetBackend.kt` | `parakeetSlovakBackend()`: `SherpaParakeetDecoder`, featureDim 128 |
| `parakeet/ParakeetEngineManager.kt` | `parakeet_sk` added to `WARM_PARAKEET_RUNTIMES` and the warm backend factory. Still one engine at a time. |
| `recognition/ManagedRecognitionBackend.kt` | Maps `parakeet_sk` to the backend |
| `recognition/RecognitionModelCatalog.kt` | New card `parakeet-sk`, after Parakeet Ultra |
| `settings/Settings.kt` | `SpeechBackendType.ParakeetSlovak("parakeet_sk")` |
| `settings/pages/ModelPresentation.kt` | Parakeet family; model-info link to KInIT's model card; its own language guidance ("Tuned for Slovak. Quality in other languages, including English, may be lower.") |
| `AudioRecognizer.kt`, `diagnostics/DiagnosticConfiguration.kt`, `benchmark/BenchmarkModels.kt` | Runtime id added to the runtime-version, accepted-runtime and auto-language lists |
| `harper/HarperEnglishGate.kt`, `s1/S1MiniEnglishGate.kt` | Not assumed English (see decision 4) |
| Unit tests | Pinned-package test, warm-runtime set, catalog 13→14, presentations 23→24, Parakeet family, language guidance, Harper and S1 gate tests |
| Docs | This file, roadmap step 4, `docs/model-guide.md` |

## Source research

**Original model:** [kinit/parakeet-tdt-0.6b-v3-sk](https://huggingface.co/kinit/parakeet-tdt-0.6b-v3-sk), revision `45003b0f84e6013400f05cb5eff564461d5f4774`.

- License: CC BY 4.0, inherited from NVIDIA Parakeet TDT 0.6B V3.
- Training: full fine-tune, 3 epochs, on an internal curated Slovak corpus: SloPalSpeech, municipal council recordings, read literature, Common Voice, TEDxSK/JumpSK, FLEURS and internal KInIT recordings. 75% of samples had synthetic noise added.
- Slovak WER from the card (fine-tune vs base V3): Common Voice 24 test 7.92% vs 9.07%; internal KInIT set 6.62% vs 24.32%.
- Other languages: the card says Slovak-only fine-tuning "significantly degrades" the other 24 languages and calls non-Slovak audio out of scope. No English numbers are given.
- Also from the card: training data is mostly short utterances; long-form audio and timestamps were not evaluated after fine-tuning.

**Export:** [vibeide-dev/parakeet-tdt-0.6b-v3-sk-sherpa-onnx-int8](https://huggingface.co/vibeide-dev/parakeet-tdt-0.6b-v3-sk-sherpa-onnx-int8), revision `656db267f4e0d5b1335c78bc6aec1d37ca37c6ec`.

- License: CC BY 4.0 (card metadata and README text). The README credits KInIT and NVIDIA.
- Provenance (README): restored the KInIT `.nemo` at revision `45003b0f…` (its SHA-256 is listed), exported with k2-fsa's `scripts/nemo/parakeet-tdt-0.6b-v3/export_onnx.py` (copy included), dynamic int8 quantization. No weights changed; tokenizer identical to the base model.
- The README also gives a 6.3% WER on 60 FLEURS `sk_sk` dev clips that may overlap the training data. Not used in the app.
- The README's usage example says `featureDim: 80`. That is wrong for this model: the encoder input is `[N, 128, T]` and the metadata says `feat_dim=128`. The app uses 128.

## Decisions (please review)

1. **Community export, not a self-export.** Same reasoning as Ultra (no NeMo/PyTorch on this phone, no hosting). The export is the standard V3 recipe and its decoder and joiner have exactly the same byte sizes as the twmht V3 export (different weights, so different hashes). If you prefer a self-export, only `ParakeetSlovakModel.kt` (revision, URLs, hashes) changes.
2. **The README is the attribution file.** The export repo has no LICENSE or NOTICE file. Its README holds the CC BY 4.0 statement and attribution chain, so it is downloaded as a pinned artifact (3,251 bytes), like Ultra's LICENSE/NOTICE. The `source` string also names KInIT, NVIDIA, VibeiDE and CC BY 4.0.
3. **Own runtime id (`parakeet_sk`) and directory, and kept warm.** It can be installed, selected, deleted and compared next to V3 and Ultra, and it shares the single warm engine slot with them. Switching between any two closes the old engine first. The V3-only VAD and auto-stop extras are not applied.
4. **English cleanup gates: not English by default.** V3 and Ultra are assumed English for S1 (and for Harper when the user declares English), because they are multilingual. The Slovak model's output is meant to be Slovak, so Harper and S1 run on it only when English is detected or forced. Harper ignores the "explicitly English" declaration for this model. Change one line in each gate if you want it to behave like Ultra.
5. **Text in the app.** Label "Slovak (other languages, including English, may be less accurate)". The description says it was fine-tuned on Slovak only and that other languages, including English, may be worse than V3. No speed or accuracy numbers.
6. **Not the default, not bundled; saved choices untouched.**

## Verification

| Check | Result |
|---|---|
| Revision pinned | `656db267f4e0d5b1335c78bc6aec1d37ca37c6ec` (latest at research time, 2026-10-24) |
| Model files: sizes and SHA-256 | All downloaded in full with resume and hashed locally. All match the Hugging Face LFS metadata and the README table (see below). |
| Quantization | Standard dynamic int8 like the twmht V3 export, **not** `MatMulNBits`. Encoder: `MatMulInteger` 217, `ConvInteger` 77, `DynamicQuantizeLinear` 223; ~608.6 MB uint8 weights, opset 17. Decoder: `com.microsoft::DynamicQuantizeLSTM` ×2. Joiner: `MatMulInteger` ×3. |
| Feature dim / type | Encoder input `audio_signal [N, 128, T]`; metadata `feat_dim=128`, `vocab_size=8192`, `normalize_type=per_feature`, `model_type=EncDecRNNTBPEModel`. Joiner output 8198 = 8193 tokens + 5 TDT durations, so `nemo_transducer`. `tokens.txt` identical to V3 and Ultra. |
| `:app:testDevDebugUnitTest` | 286 tests, 3 skipped, 1 failed: only the known `ComparisonResultsTest` JDK 17 failure (`List.removeLast()`); all new tests passed |
| `:app:lintDevDebug` | Passed |
| `:app:assembleDevDebug` | Passed (131.5 MB dev-debug APK, copied to `/sdcard/Download`) |
| Decode test on this machine | Not possible (sherpa-onnx Linux binaries abort under PRoot; see the Ultra notes) |
| On-device recognition | **Not run** |

Pinned files:

| File | Size (bytes) | SHA-256 |
|---|---:|---|
| `encoder.int8.onnx` | 652,282,294 | `d487c474a6de1a783c7198303088bcff0b200f31f953fdd5192fffd4a0ce0039` |
| `decoder.int8.onnx` | 11,845,275 | `3a3dbd37acd24dad2f1a712743dcdcb0d60c013033273847c194d7d0464a6bf5` |
| `joiner.int8.onnx` | 6,355,277 | `0d999f3ab9c46a64f35ed81eef45086d7bd14f408d73296fa1a424a7fa5ca2e0` |
| `tokens.txt` | 93,939 | `d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d` |
| `README.md` | 3,251 | `b5b99b9adb5b7668fb8c06e9afe854f79467980ef67f21f2b070150e1b86c8c7` |
| Total | 670,580,036 | |

## Device test plan

Install the dev APK, then download **Parakeet TDT V3**, **Parakeet Ultra** and **Parakeet TDT V3 Slovak**. Use Model comparison (it records each sentence once and runs every model on the same audio), or dictate the same sentence with each model.

Sentences (read naturally, normal speed):

Slovak
1. „Dnes ráno som išiel do obchodu kúpiť chlieb, mlieko a trochu ovocia.“
2. „Zajtra o tretej popoludní máme stretnutie s klientom v Bratislave.“
3. „Môžeš mi prosím poslať tú prezentáciu ešte dnes večer?“
4. „Ďakujem, že ste si našli čas. Uvidíme sa budúci týždeň v pondelok.“
5. „Štyridsaťpäť ľudí čakalo pred budovou, kým sa neotvorili dvere.“

English
6. "Please schedule a meeting with the sales team for next Tuesday afternoon."
7. "The quick brown fox jumps over the lazy dog near the river bank."
8. "I'll send you the updated report by the end of the day."

Mixed (optional)
9. „Pošli mi ten report cez email, prosím, a daj mi vedieť feedback.“

For each model and sentence, note:

| What | How |
|---|---|
| Slovak errors | Wrong or missing words |
| Czech/Polish spellings | Any of ř, ů, ě, ł, ą, ę, ś, ż, ź, ć, ń, or Czech words like "jsem", "jak", "že jsi", "příští" |
| Diacritics | ľ, ĺ, ŕ, ô, ä and long vowels (á, í, ú) present and correct |
| English quality (6 to 8) | Readable? Words replaced by Slovak-sounding forms? |
| Stop-to-text time | Model comparison screen; note cold (first run) and warm (second run within 5 minutes) |
| Memory | Model comparison screen |

Also check:
- Switch V3 → Slovak → Ultra → Slovak: no crash, no growing memory. With `PARAKEET_ENGINE_DIAGNOSTICS` on, the second dictation logs `engine_acquired warm=true`.
- Delete the Slovak model while it is warm, then pick it again and re-download.
- With Harper or S1 English cleanup on, Slovak dictation is not changed by the English cleanup.
- A longer dictation (30 to 60 s) in Slovak, since the card warns the training data was mostly short utterances.

## Open questions / follow-ups

- If English is weak and Slovak strong, see roadmap step 3/4 (route by language) or keep it as an explicit "Slovak only" choice.
- Should it appear in the model guide's "Try first" for Slovak once device numbers exist?
- Review (Opus sub-agent, given only the diff): no correctness bugs; wiring, gates, counts and all pinned sizes/hashes confirmed. Doc wording fixes applied. Not changed (low impact, would need a rebuild):
  - The Harper settings screen shows "Your selected language is not English" for this model, although it has no language selector (Whistle already behaves the same). A model-specific line could be added.
  - Benchmark reports label it `auto (recording language …)`, like the other Parakeet models, because it takes no language input.
- CI: an optional pinned-artifact check like Orukeet's could be added.
