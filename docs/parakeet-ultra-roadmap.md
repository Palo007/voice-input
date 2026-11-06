# Parakeet Ultra improvement roadmap: speed and Slovak/English accuracy

- Date: 2026-11-06
- Context: follows [issue #52 idea 1](ISSUE-52-parakeet-ultra-notes.md) (Parakeet Ultra added as a selectable model)
- Goals:
  1. Shorter wait between Stop and text with Parakeet Ultra.
  2. Better Slovak recognition. When the user dictates in Slovak or English, the output must never become Czech or Polish.
  3. A setting to pick up to three languages. The app detects which of them is spoken and decodes for that language.

## Background: why there is no "language" switch

Parakeet TDT V3 and its derivatives (Ultra, Orukeet) have no language input. The encoder decides the language internally, and Slovak audio sometimes comes out spelled the Czech way. All 25 languages share one vocabulary of 8,192 word pieces (`tokens.txt`).

The app runs these models through sherpa-onnx 1.13.4 (`SherpaParakeetDecoder` in `parakeet/ParakeetBackend.kt`). That API gives back only the final text, tokens and timestamps:

- no per-token scores
- no way to forbid tokens
- `lang` is empty for Parakeet models

Its options for steering the output are `hotwordsFile`, `blankPenalty`, and rule FSTs, which rewrite the text after decoding. None of them can exclude a language. So **steering the language needs the app to control the decoding loop** (step 3).

## Step 1: Keep Ultra loaded between recordings: done on this branch

**Problem:** Ultra loaded its 685 MB model from storage at every recording and released it afterwards. Only plain Parakeet V3 used the warm engine (`ParakeetEngineManager`).

**Change:**

- `ParakeetEngineManager` now keeps one warm engine for either runtime in `WARM_PARAKEET_RUNTIMES` (`parakeet`, `parakeet_ultra`). It holds only one at a time, because two 0.6B engines don't fit in RAM next to each other on most phones.
  - Switching between V3 and Ultra closes the old engine before loading the new one.
- Both models use the existing `PARAKEET_KEEP_WARM` setting (default on) and `PARAKEET_KEEP_WARM_TIMEOUT_MS` (default 5 minutes idle).
- Choosing a non-Parakeet model frees whichever Parakeet engine is warm. So does starting Model comparison.
- Deleting or replacing a model's files closes its engine first (`releaseParakeetArtifacts` / `RecognitionModel.releaseRuntime`).

**Not changed (on purpose):**

- **Orukeet** still loads per recording. It could join `WARM_PARAKEET_RUNTIMES` in one line after a device check.
- **V3's auto-stop tuning** (the "Parakeet auto-stop on silence" toggle, less aggressive VAD, 300 ms drain). It's about recording behavior, not speed. Ultra already auto-stops through the general "Stop on silence" setting.
  - Correction to an earlier suggestion: this VAD does not trim silence from the audio, so turning it on would not make decoding faster.

**Cost:** about 0.7 to 1 GB of RAM stays in use for up to 5 minutes after the last dictation. Android may kill the app in the background on low-RAM phones. The existing timeout and keep-warm settings control this.

**Verify on the phone:**

- First dictation (cold) vs. second dictation within 5 minutes (warm). Turn on `PARAKEET_ENGINE_DIAGNOSTICS` to log `engine_acquired warm=…`.
- Switch V3 → Ultra → V3: no crash, and memory doesn't keep growing.
- Delete Ultra while it is warm.

## Step 2: Measure Ultra on the phone (needed before steps 3 to 5)

- Install the dev APK, download Ultra, and dictate in Slovak, Czech and English.
- **Open risk from idea 1:** the community export uses the `MatMulNBits` 8-bit weight format. If sherpa-onnx's Android runtime can't load it, Ultra needs a self-export with the standard sherpa-onnx v3 script and standard 8-bit quantization. That changes the model files that step 3 builds on.
- Use the Model comparison screen to record:
  - Stop-to-text time and memory for V3, Orukeet and Ultra
  - how often Slovak comes out with Czech or Polish spellings
- Try 2, 4 and 6 threads. `numThreads` is currently `min(cores, 4)`, and on big.LITTLE phones the choice of fast cores matters.
- Optionally compare a standard 8-bit export of Ultra for speed.

## Step 3: Language setting with restricted decoding (main accuracy work)

**Setting:** in Model Options, "Languages: Automatic (all 25) | Choose up to 3", for example Slovak + English. It applies to Ultra, Orukeet and V3.

**Method:**

1. **Token masks (built offline, shipped as small files).** For each supported language, a list of vocabulary tokens it may use.
   - Tokens containing letters only Czech or Polish use (ř, ů, ě; ł, ą, ę, ś, ż, ź, ć, ń) are always excluded when those languages aren't selected.
   - Frequent Czech-only word pieces (for example "jsem", "jak") are found by counting tokens in Slovak vs. Czech text corpora. Tokens common in Czech but almost absent in Slovak are excluded.
2. **Run the encoder once.** It is about 90% of the compute.
3. **Decode once per selected language,** with that language's mask applied. Masked tokens get a score of minus infinity, so the model must choose the best allowed spelling. This step is cheap (a small decoder and joiner).
4. **Choose the language:** the hypothesis with the best total of
   - the average token score from the model
   - a small character n-gram language score (a few hundred KB, built offline)
5. **Report the chosen language** as `detectedLanguage`. Then English-only cleanup (Harper, S1 gates) runs only for English.

**Result:** Slovak + English mode can never output Czech or Polish letters, and Czech-only words are strongly discouraged. Cost: about one encoder pass plus up to three cheap decodes.

**Implementation options** (sherpa-onnx doesn't expose the loop):

- **A (preferred): run the three ONNX files directly** with ONNX Runtime and write the TDT greedy loop in Kotlin, about 100 to 150 lines.
  - Risk: the audio features (128-bin log-mel) must match what NeMo and sherpa-onnx compute exactly. Check this by comparing the output with sherpa-onnx on the same recordings.
  - The ORT dependency must not clash with the onnxruntime that sherpa-onnx links statically.
- **B: patch sherpa-onnx's C++** to add a token mask, per-token scores and encoder-output reuse. This means building and maintaining a fork of the library.

**Tests:**

- Unit tests for the mask builder and language choice, using fake decoder outputs.
- On the phone: a fixed set of Slovak and English recordings. Count WER and Czech/Polish leaks with Automatic vs. SK+EN.

**Estimated effort:** medium, about 2 to 4 sessions including the corpus work.

## Step 4: Try the existing Slovak fine-tune

[kinit/parakeet-tdt-0.6b-v3-sk](https://huggingface.co/kinit/parakeet-tdt-0.6b-v3-sk) has a [sherpa-onnx export](https://huggingface.co/vibeide-dev/parakeet-tdt-0.6b-v3-sk-sherpa-onnx-int8).

- Add it like Ultra (a catalog entry with pinned hashes), or through custom import (#52 idea 3).
- Check its English quality. A Slovak-only fine-tune can forget English.
- If it's good in Slovak but weak in English, step 3's language choice could route Slovak to it and English to Ultra. But two engines in RAM is a lot for a phone.

## Step 5: Own fine-tuning (only if steps 3 and 4 aren't enough)

- **What:** continue training Ultra on Slovak plus some English, so it keeps its English.
  - Slovak data: Common Voice, FLEURS, or kinit's data if its license allows.
- **Where:** a PC or cloud GPU with NeMo (one 24 to 80 GB GPU for hours to a day). This can't be done on the phone.
- **Then:** export with sherpa-onnx's `scripts/nemo/parakeet-tdt-0.6b-v3`, quantize to 8-bit, host the files, and add a catalog entry.
- **Risks:** data licenses, English getting worse, and a ~650 MB download hosted by us.

## Related #52 ideas that also help speed

- **Idea 2, live text at pauses.** Decoding pieces while the user speaks leaves only the last piece to decode after Stop. This shortens the wait more than any setting does, and step 3's decoder can be reused for each piece.
- **Idea 3, custom import.** Makes step 4 possible without a new release.

## Order and parallel work

1 → 2 → 3, then 4 and idea 2 in either order, and 5 last.

Step 3 should run in **its own fresh session, after step 2**, not in parallel with step 1:

- It depends on step 2's result. If the `MatMulNBits` export fails on the phone, the model files and their quantization change, and the custom decoder must be built against the final files.
- It edits the same code as step 1 (`ParakeetBackend.kt`, `SherpaParakeetDecoder`, the engine manager), so working on both at once would conflict.
- It starts with substantial research: NeMo feature parity, ONNX input/output names, and corpus statistics for the masks. That deserves a clean context.
- This phone can run only one Gradle build at a time.

What *can* be done anytime, without conflicts, is the offline data work for step 3: analyze `tokens.txt` and build the Czech/Polish-only token lists from text corpora. It touches no app code.
