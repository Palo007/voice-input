# Issue #52: route Slovak to the Slovak model, English to the selected model

- Date: 2026-11-06
- Branch: `codex/issue-52-language-routing` (from `codex/issue-52-slovak-finetune`). Committed locally only; not pushed (no GitHub credentials here).
- Roadmap: step 4b of [parakeet-ultra-roadmap.md](parakeet-ultra-roadmap.md). Follows [the Slovak fine-tune](ISSUE-52-slovak-finetune-notes.md).
- Device result that motivated it: Parakeet TDT V3 Slovak (`parakeet_sk`) is very good in Slovak and bad in English. Parakeet Ultra is the general model for English.
- Status: implemented, unit-tested and built on the phone. **Not yet tested on the phone.**

## Research: how FUTO Keyboard starts voice input

Source read: [futo-org/android-keyboard](https://github.com/futo-org/android-keyboard) at commit `7d61ab4`. Paths are under `java/`.

| Question | Finding |
|---|---|
| How the mic key starts an external voice app | **Input method switch.** With an external provider selected (`USE_SYSTEM_VOICE_INPUT`, `SYSTEM_VOICE_INPUT_PACKAGE`, `uix/Settings.kt:372-380`), the mic key runs `trySwitchToShortcutIMEorInternal()` (`LatinIME.kt:944-948`), which calls `InputMethodManager.setInputMethodAndSubtype(token, imeId, voiceSubtype)` (`RichInputMethodManager.java:341-362`). The subtype is the target app's first subtype with mode `voice`. |
| `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` / `SpeechRecognizer` | Not used anywhere in the repo. So our `RecognizeActivity` is never started by FUTO Keyboard, only `VoiceInputMethodService`. |
| Language passed | **None.** No intent is built, so there is no `EXTRA_LANGUAGE`, `EXTRA_LANGUAGE_PREFERENCE` or custom extra. Only the IME id and our voice subtype. |
| Languages registered as `InputMethodSubtype`s | **No.** `res/xml/method.xml` declares one subtype: mode `keyboard`, no locale. The user's languages live in FUTO's DataStore (`latin/Subtypes.kt`). `setAdditionalInputMethodSubtypes` only appears in commented-out tests. A language switch only changes internal state (`LatinIME.changeSubtype` → `onCurrentInputMethodSubtypeChanged`, no system call). |
| `EditorInfo.hintLocales` | Set by the app being typed into, not by the keyboard. FUTO Keyboard neither sets nor uses it. |
| Return to the keyboard | FUTO Keyboard expects the voice IME to switch back. Ours does (`switchToPreviousInputMethod`). |

**Signals our app can read:**

| Path | Signal | Expected with FUTO Keyboard |
|---|---|---|
| `VoiceInputMethodService` | `InputMethodManager.getLastInputMethodSubtype()` | FUTO's single keyboard subtype, no locale |
| `VoiceInputMethodService` | Enabled subtypes of the previous input method | The same single subtype |
| `VoiceInputMethodService` | `EditorInfo.hintLocales` | Only if the target app sets it; reflects the app, not FUTO's language |
| `RecognizeActivity` | `EXTRA_LANGUAGE`, `EXTRA_LANGUAGE_PREFERENCE` | Not used by FUTO. Other callers (search, maps) may send them |

**Conclusion:** switching FUTO Keyboard between Slovak and English is very likely invisible to the app. The probe (below) is there to confirm this on the phone. Automatic selection still works for apps that set `hintLocales` and callers that send `EXTRA_LANGUAGE`. Making FUTO pass the language would need a change in FUTO Keyboard (for example a locale on the subtype it switches with, or a `voice` subtype per language). That is outside this repo.

## What changed

| Area | Change |
|---|---|
| `recognition/LanguageRouting.kt` (new) | `DictationLanguage` (SK/EN), the routing rule, locale parsing, cleanup language, initial language. Pure Kotlin. |
| `diagnostics/KeyboardLanguageProbe.kt` (new) | `DiagnosticLanguageProbe` (locale tags and ids only, sanitized) and the Android collectors for both paths |
| `diagnostics/DiagnosticStore.kt`, `AppDiagnostics.kt`, `DiagnosticArchive.kt` | `LANGUAGE_PROBE` event, `languageProbe` field on the record, latest probe kept in memory, summary section |
| `settings/pages/AppDiagnosticOptions.kt` | "Last keyboard language check" row |
| `settings/Settings.kt` | `ROUTE_BY_LANGUAGE` (off), `DICTATION_LANGUAGE` (`en`, the remembered manual choice) |
| `settings/pages/Models.kt` | "Pick the model by language" toggle; disabled with an explanation until the Slovak model is installed |
| `AudioRecognizer.kt` | Per-dictation `sessionSelection` (saved choice or `parakeet_sk`) used for readiness, VAD, drain, load and cleanup instead of re-reading `SPEECH_BACKEND`. `setDictationLanguage()` switches before or during recording. `routingLanguage` goes to the cleanup gates. |
| `recognition/RecognitionModelLifecycle.kt` | `load(..., persistSelection)`: a routed load does not write `SPEECH_BACKEND` |
| `RecognizerView.kt` | SK \| EN button (`DictationLanguageSwitch`), probe at start, remembered choice, caption follows the routed model |
| `VoiceInputMethodService.kt`, `RecognizeActivity.kt` | Supply the probe for their path |
| Strings | Button labels and TalkBack descriptions, setting text, diagnostics row |
| Tests | `LanguageRoutingTest`, `KeyboardLanguageProbeTest` |
| Docs | This file, roadmap, user guide, diagnostics |

## Decisions

1. **Routing rule.** SK and the Slovak model installed and the setting on → `parakeet_sk`. Anything else → the saved model. If the Slovak model is missing, the setting and the button have no effect (the button is hidden).
2. **`SPEECH_BACKEND` untouched.** The routed selection lives only in the dictation session. `RecognitionModelLifecycle.load` used to write the selection back on every load; routed loads now skip that.
3. **One warm engine.** No change to `ParakeetEngineManager` or the 5-minute keep-warm. Switching SK↔EN releases the old engine with keep-warm off (closed at once) and then loads the other. The engine for the button's current language loads when recording starts, as before.
4. **Switching during recording** (your choice): the tap releases the old engine and starts loading the other model at once, while you keep talking. Stop waits for that load. The tap is refused after Stop. It is also refused during recording if either side is a live-text model (ASR4ALL, Moonshine, Nemotron, Parakeet Unified), because those receive audio as it is recorded and cannot be swapped mid-recording; the button is shown disabled then.
5. **Cleanup language.** While routing is active, `routingLanguage` (`sk` or `en`) is given to the Harper and S1 gates as their forced language (`forcedLanguage ?: routingLanguage`). EN opens both gates; SK closes them. Whisper's `forceLanguage` is unchanged: it alone still decides Whisper's model choice and its language provider. A detected language still wins over both.
6. **Automatic language.** At dictation start the probe's `resolved` language (`sk` or `en`) sets the button. Otherwise the remembered choice is used. Unknown or other languages (for example `cs`) leave it unchanged. Only manual taps are saved as the remembered choice, so a keyboard-provided language does not overwrite it.
7. **Probe privacy.** Locale tags and ids only. The IME path names the input method that owns the last subtype (no public API gives the previous IME, so the owner of the last subtype is used). The activity path names the caller only if it is an input method, otherwise `other`, to keep receiving-app names out of diagnostics.
8. **Model comparison and benchmark** keep their own selection; routing and the button are off for them.
9. **Text in the app** makes no speed or accuracy claims. It says that only one model is kept in memory, so the first dictation after an SK↔EN switch takes a few seconds longer while the other model loads.
10. **Dictation never writes `SPEECH_BACKEND`.** The dictation path now always calls `load(..., persistSelection = false)`; the write it used to do only repeated the value it had just read, and it could race with a model change in Settings or (with routing) store `parakeet_sk`.
11. **Switch targets must be installed.** `create()` checks which of the two targets are installed (including Whisper's files). A tap to a target that is not installed is refused.
12. **The tap is committed late.** `create()` checks the model chosen so far, then commits the latest SK | EN choice after its last suspension, just before recording starts. A tap during start-up therefore never leaves the caption, deletion lock or live-text buffering on the other model.

## Verification

| Check | Result |
|---|---|
| `:app:testDevDebugUnitTest` | 302 tests, 3 skipped, 1 failed: only the known `ComparisonResultsTest` JDK 17 failure (`List.removeLast()`). New: `LanguageRoutingTest` 10/10, `KeyboardLanguageProbeTest` 6/6. |
| `:app:lintDevDebug` | Passed (0 errors; warnings unchanged and unrelated) |
| `:app:assembleDevDebug` | Passed (133 MB dev-debug APK, copied to `/sdcard/Download`) |
| `:app:compileDevDebugAndroidTestKotlin` | Passed. Two new `RecognitionContentTest` cases (tap SK switches without stopping, hidden after Stop; locked tap neither switches nor stops) are compiled only, **not run** (no device test run here). |
| Review (Opus sub-agent, given the diff and requirements) | 9 findings. Fixed: a dimmed button let the tap reach the tap-to-stop surface; a cancel race could write `parakeet_sk` to `SPEECH_BACKEND`; the old language picker could override SK for cleanup; switching to a model that is not installed; a tap during start-up mixing old and new model state; the deletion lock could stay on the old model; another keyboard language falling through to an English hint; no new engine load after a failed load; doc text claiming warm switching. Kept and documented: the VAD settings stay as at recording start, a tap during the first load waits for it, the diagnostics session keeps the start model id. |
| On-device | **Not run** |

## Device test plan

Install the dev APK from `/sdcard/Download`. Download **Parakeet Ultra** (select it) and **Parakeet TDT V3 Slovak**. Turn on Diagnostics collection (Settings > Support > Diagnostics) and, for engine timings, `PARAKEET_ENGINE_DIAGNOSTICS` (dev settings).

1. **Probe.**
   - In FUTO Keyboard switch to Slovak, tap the mic, dictate something short, finish. Open Diagnostics and read **Last keyboard language check**.
   - Switch FUTO Keyboard to English and repeat.
   - Expected (from the source research): both show `lastSubtype=(no locale)/keyboard` and `resolved=none`. If either shows `sk` or `en`, the keyboard language is visible and the button follows it automatically.
   - Optional: try a messaging app that sets `hintLocales`, and a caller using `RecognizeActivity` with `EXTRA_LANGUAGE`.
2. **Button and model.**
   - Model Options: with the Slovak model downloaded, turn on **Pick the model by language**.
   - Start dictation: the SK | EN button shows under "Model: …". Tap SK: the caption says Parakeet TDT V3 Slovak. Dictate a Slovak sentence. Tap EN: the caption says Parakeet Ultra. Dictate an English sentence.
   - Model Options still shows Parakeet Ultra selected afterwards.
   - The next dictation starts with the last tapped language.
3. **Switch time.**
   - Cold: SK dictation, then immediately EN (and back). Note the wait after Stop (with `PARAKEET_ENGINE_DIAGNOSTICS`, `engine_acquired warm=false`).
   - Warm: repeat the same language within 5 minutes (`warm=true`).
   - Mid-recording: start in EN, tap SK while speaking, keep talking a few seconds, Stop. The text should be Slovak-model output.
4. **Cleanup gates.** Turn on Harper and/or S1 English cleanup. SK dictation: text unchanged by English cleanup. EN dictation: cleanup runs.
5. **Slovak model missing.** Delete Parakeet TDT V3 Slovak. The setting is greyed out with its explanation, the button is hidden, and dictation uses Parakeet Ultra.
6. **TalkBack.** The two options read as "Slovak. Uses the Slovak model." / "English. Uses your selected model." with a selected state.
7. **Regression.** Setting off: no button, dictation unchanged. Model comparison: no button.

## Open questions / follow-ups

- If the probe confirms FUTO Keyboard is invisible, automatic selection with FUTO needs an upstream FUTO change (a locale on the voice switch).
- With plain Parakeet V3 as the selected model, a mid-recording switch keeps the VAD settings chosen at recording start (V3 has its own VAD toggle and a less aggressive mode). Minor; documented, not changed.
- A tap while the first model is still loading waits for that load to finish before closing it, so Stop may wait for both loads. Cancelling a native load in progress is not safe, so this is kept.
- The diagnostics session keeps the id of the model the dictation started with after a mid-recording switch; `MODEL_LOAD_STARTED` records the routed runtime.
- Benchmark and Model comparison ignore the button by design.
