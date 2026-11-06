# Troubleshooting recognition ("nothing detected", missing or bad text)

How to investigate when dictation returns no text or much worse text than expected.
Written after the issue #52 investigation (November 2026); details and numbers are in
[the language-routing notes](ISSUE-52-language-routing-notes.md#follow-up-empty-slovak-results-and-switching-while-speaking).

## Lessons so far

1. **Check the input level first.** The `VOICE_RECOGNITION` microphone source has no
   automatic gain. With the phone held a bit further away, recordings reach only about
   -37 dBFS average (normal: about -30). Parakeet TDT models (Ultra and the Slovak fine-tune
   alike) then return **empty text**, and the app shows "nothing detected". This is not a crash
   or a bug in routing.
   - On synthetic speech both models have a hard cliff at about -28 dBFS peak (-45 to -51
     dBFS mean): above it full text, below it nothing.
   - Long quiet recordings fail where short ones survive: the same failed audio decoded fine
     when cut into 10 s chunks.
   - The cliff is not clean on real speech: a failed and a successful recording differed by
     only 0.3 dB.
   - Fix in place: [`parakeet/InputLevel.kt`](../app/src/main/java/org/futo/voiceinput/parakeet/InputLevel.kt)
     raises quiet input to -25 dBFS RMS before every offline Parakeet decode (peak-limited,
     max +20 dB, never lowers). ASR4ALL, Moonshine, Nemotron and Whisper do **not** get it;
     if one of them shows "nothing detected" on quiet audio, check the level and consider the
     same treatment.
2. **Language mismatch drops speech.** The Slovak fine-tune returns empty or garbled text for
   English, and with English followed by Slovak it keeps mostly the Slovak part. Ultra handles
   Slovak reasonably well. So "empty Slovak result" can also mean the speech was English.
3. **Routing was not the cause.** A routed SK dictation uses the same engine, decoder config
   and audio as selecting the Slovak model directly. Do not chase routing code before checking
   level and language with the replay bench below.
4. **Switching while speaking** used to send the whole recording to the new model. Now the
   audio before the tap is decoded by the old model first
   ([`recognition/SwitchSegments.kt`](../app/src/main/java/org/futo/voiceinput/recognition/SwitchSegments.kt),
   `AudioRecognizer.decodeSegmentBeforeSwitch`), so nothing is dropped.

## Where the evidence is

All on the phone, inside the app's private storage. Use a debuggable (`dev` debug) build so
`run-as` works. Package below: `org.futo.voiceinput.dev`.

| What | Path / source | Notes |
|---|---|---|
| Diagnostics events | `files/app-diagnostics/*.jsonl` | One JSON per line. Per session: `MODEL_LOAD_STARTED` (`configuration.runtime`, `language`), `CAPTURE_SUMMARY` (`CAPTURED_SAMPLES`), `RECOGNITION_FINISHED` (`CHARACTERS`; missing/0 = model returned nothing), `NO_SPEECH`. Content-free. |
| Saved recordings | `no_backup/audio-history/<ms>-<uuid>.pcm` + `.txt` | Only when audio history is on. Raw 16 kHz mono s16le, no header. The `.txt` is what was delivered; **0 bytes = empty result**. Match to a session by timestamp and `CAPTURED_SAMPLES * 2` = file size (plus drain). Private speech: keep copies local and delete them after. |
| Level per recording | Logcat tag `AudioRecognizer`, line `capture samples=… rms=… peak=…` | Debug builds only; the buffer rotates within hours. rms 0.03 is about -30 dBFS. |
| Parakeet engine timing | Logcat tag `ParakeetDiagnostics` | When the Parakeet engine diagnostics setting is on. |

Notes:
- After a mid-recording switch, the session's `model` field keeps the model the dictation
  started with; the routed model is in `MODEL_LOAD_STARTED.configuration.runtime`.
- `ls -R` inside `run-as … sh -c` did not descend into `no_backup` here; list
  `no_backup/audio-history` directly, or you may wrongly conclude no recordings exist.

```sh
P=org.futo.voiceinput.dev
adb shell run-as $P ls -la no_backup/audio-history          # find 0-byte .txt files
adb exec-out run-as $P cat no_backup/audio-history/ID.pcm > ID.pcm
adb exec-out run-as $P sh -c 'cat files/app-diagnostics/*.jsonl' > diag.jsonl
ffmpeg -f s16le -ar 16000 -ac 1 -i ID.pcm ID.wav             # for the replay bench
ffmpeg -i ID.wav -af volumedetect -f null - 2>&1 | grep -E 'mean_volume|max_volume'
```

## Replay bench: run the real model on the real audio

[`tools/replay-bench`](../tools/replay-bench) decodes WAV files on the phone with the same
Sherpa-ONNX version and settings as the app, without the app around it.

```sh
tools/replay-bench/build-and-push.sh            # NDK build, pushes runner + libs, copies sk/ultra models on the phone
adb push ID.wav /data/local/tmp/replay/
adb shell "cd /data/local/tmp/replay && LD_LIBRARY_PATH=. ./replay sk ID.wav"     # or: ultra
adb shell rm -rf /data/local/tmp/replay         # when done (about 1.3 GB)
```

Useful experiments (ffmpeg on the host, then replay):
- **Level:** `-af volume=+9dB` / `-af volume=-20dB`. If a gain brings text back, it is a level problem.
- **Length:** `-ss 0 -t 10` chunks. If chunks decode but the whole file does not, it is the
  long-quiet-audio failure.
- **Language:** split at the language change (or a switch tap; tap time = load start minus
  session start in the diagnostics) and decode each part with both models.
- **Synthetic speech:** `espeak-ng -v sk` / `-v en-us` gives reproducible test sentences when
  no recording exists. Real recordings are better evidence.

The runner does **not** apply `InputLevel`, so it shows the raw model behaviour; apply gain
with ffmpeg to imitate the app.

### Why a phone-side runner (environment pitfalls)

On the and-code host (Alpine/musl, PRoot, Python 3.14):
- `pip install sherpa-onnx` fails (no wheel for Python 3.14, source build fails).
- The glibc Linux release binaries do not load (`__strftime_l: symbol not found`).
- `sherpa-onnx-v<ver>-android.tar.bz2` ships only libraries, so `run.c` is a minimal client
  of the Sherpa-ONNX C API, built with the SDK's NDK clang for API 26.
- The 650 MB encoders are copied on the phone with `run-as … cat > /data/local/tmp/…`,
  which avoids pulling them through the host.

## Checklist for the next "nothing detected" report

1. Find the session in the diagnostics: was `CHARACTERS` 0 (model returned nothing) or did
   cleanup or delivery fail later?
2. Find the recording (0-byte `.txt`) and measure its level. Below about -33 dBFS mean is suspect.
3. Replay it whole, with +9 dB, and in 10 s chunks, with the model that was used and with Ultra.
4. Ask which language was spoken, or tell by decoding with Ultra.
5. Only then look at app code: routing, segment joining, cleanup gates.
