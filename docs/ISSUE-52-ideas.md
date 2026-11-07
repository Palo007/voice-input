# Issue #52: Parakeet Ultra, live text for offline models, custom model import

- Source: https://github.com/Today20092/voice-input/issues/52 (open, filed by Palo007 on 2026-11-03, no labels, no comments when fetched)
- Type: feature request
- Goal: help users who dictate in languages with no good streaming model (Slovak, Czech, Polish) and/or let them use custom models.
- This file is a local summary of the issue. The issue itself is the source of truth.

The issue contains three ideas. They are listed in the order the author proposes them.
Ideas 2 and 3 work well together: with idea 2, any imported offline model gets live text at pauses.

---

## 1. Add Parakeet Ultra as a selectable model

**Why:** [Parakeet Ultra](https://huggingface.co/moondream/parakeet-ultra) (Moondream) is a post-trained Parakeet TDT 0.6B v3. It has the same size, the same 25 languages and the same API, and fewer errors than v3 in every FLEURS language measured.

FLEURS WER from [Handy's model catalog](https://github.com/cjpais/Handy/blob/main/src-tauri/src/catalog/catalog.json) (Q8_0, lower is better):

| Model | Slovak | Czech | English |
|---|---|---|---|
| Parakeet TDT 0.6B v3 | 10.2 % | 12.3 % | 4.8 % |
| Orukeet | 9.0 % | 10.2 % | 4.4 % |
| **Parakeet Ultra** | **7.8 %** | 11.0 % | **4.3 %** |

**How:** add a model entry like `app/src/main/java/org/futo/voiceinput/parakeet/OrukeetModel.kt` that uses the existing `SherpaParakeetDecoder` (`nemo_transducer`).

- A community sherpa-onnx int8 export exists: [erponce/sherpa-onnx-parakeet-ultra-tdt-0.6b-v3-int8](https://huggingface.co/erponce/sherpa-onnx-parakeet-ultra-tdt-0.6b-v3-int8). It is untested. Its `decoder.int8.onnx` is 47.7 MB, so the decoder looks unquantized.
- A cleaner route is to export it yourself with the sherpa-onnx script for v3: [scripts/nemo/parakeet-tdt-0.6b-v3](https://github.com/k2-fsa/sherpa-onnx/tree/master/scripts/nemo/parakeet-tdt-0.6b-v3).
- License: CC BY 4.0, inherited from NVIDIA Parakeet v3.

**Estimated effort:** small. About 40 lines plus one catalog line, then an export and a test.

---

## 2. Live text for offline Parakeet models (cut at pauses)

**Why:** the only multilingual streaming model, Nemotron 3.5, has about 23 % WER in Slovak. Offline Parakeet models are about 3x better but show text only after you stop speaking, so users of these languages get no good live preview.

**Idea:** a "simulated streaming" backend for offline models:

1. Buffer audio while the user talks.
2. At each pause (VAD), or every ~8 s, decode that piece with the offline model.
3. Call `onPartial(textSoFar)` so the existing live UI shows it.
4. *(Optional)* Decode the current piece again every ~1 s so words appear to grow live.
5. *(Optional)* At the end, decode the full recording once for the best final text.

**Why it fits:** the app already has the pieces.

- The `StreamingSpeechBackend` interface in `app/src/main/java/org/futo/voiceinput/backend/SpeechBackend.kt` (`startStreaming(onPartial)`, `acceptAudio`, `finishStreaming`).
- The live partial-text UI used by Nemotron, Moonshine and Parakeet Unified.
- A VAD already in the app (WebRTC); sherpa-onnx also ships Silero VAD.

**Estimated effort:** medium. One new class of about 150-250 lines wrapping the offline decoder in `StreamingSpeechBackend`. No UI changes. It could be a per-model toggle for Parakeet v3, Orukeet and Ultra.

**Trade-offs:**

- More CPU and battery, because pieces are decoded during recording.
- Short pieces have less context, so accuracy can drop a little. Step 5 fixes the final text.
- Text arrives at pauses (about 0.5-1.5 s late), not word by word, unless step 4 is added.

**Similar approaches elsewhere:**

- sherpa-onnx ["simulated streaming" Android demo](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html) for Parakeet v3.
- [Handy](https://github.com/cjpais/Handy): VAD-based chunked live decoding, in `src-tauri/src/managers/transcription/chunked.rs`.

---

## 3. Import a custom sherpa-onnx model

**Why:** new fine-tunes appear every few weeks, for example Parakeet Ultra and the Slovak-only [kinit/parakeet-tdt-0.6b-v3-sk](https://huggingface.co/kinit/parakeet-tdt-0.6b-v3-sk), which has a [sherpa-onnx export](https://huggingface.co/vibeide-dev/parakeet-tdt-0.6b-v3-sk-sherpa-onnx-int8). An import option lets users try them without a new app release. [Handy](https://github.com/cjpais/Handy) does this for GGUF files and detects the model family from file metadata (`src-tauri/src/managers/gguf_meta.rs`).

**Idea:** add an "Import model..." button in Models.

1. The user picks a `.tar.bz2` or a folder containing `encoder*.onnx`, `decoder*.onnx`, `joiner*.onnx` and `tokens.txt`.
2. The app copies it to `filesDir/custom/<name>/` and computes sizes and hashes at import. The existing archive extraction in `ModelArchive.kt` can be reused.
3. The app detects the type:
   - Feature dim (80 or 128) from the encoder's input shape.
   - Offline or streaming from the file layout and ONNX metadata, or the user picks it in a dropdown.
4. The app registers it as a dynamic `RecognitionModel` using the existing decoders:
   - **Offline transducer** (Parakeet family): `SherpaParakeetDecoder` with configurable file names and feature dim.
   - **Streaming transducer** (Nemotron-like): `SherpaOnlineDecoder`, so true streaming also works.

**Note on streaming:** true streaming needs a model trained for streaming. With idea 2, any imported offline model still gets live text at pauses.

**Estimated effort:** medium.

- v1 (offline Parakeet-family transducers only): about 200-350 lines, mostly the import screen and the dynamic catalog entry.
- v2 (streaming transducers and CTC models): more type detection and testing.
- Risk: users may import broken or wrong models. A clear load error plus a "Remove" button is enough.

---

## Suggested order

Not stated in the issue; this is a reading of the effort estimates.

1. Idea 1 (small, self-contained, immediate benefit).
2. Idea 2 (medium, UI unchanged, benefits Ultra and Orukeet).
3. Idea 3 v1 (medium, builds on both).

## Open questions to settle before coding

- Which export of Parakeet Ultra to use: the community int8 (decoder looks unquantized) or a self-export with the sherpa-onnx v3 script.
- Where the model files are hosted and how the catalog references them (see how `OrukeetModel.kt` does it).
- Whether idea 2 is a per-model toggle or the default for offline models.
