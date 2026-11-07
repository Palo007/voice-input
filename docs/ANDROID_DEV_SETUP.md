# Android development on and-code (Alpine / PRoot, aarch64)

Learnings from setting up a fast Android build environment on the phone itself.
Date: 2026-11-05. Device: Android 15/16, aarch64, 4 cores, ~11 GB RAM.

## TL;DR

```bash
. /root/android-env.sh            # JDK 17 + SDK on PATH (also sourced from ~/.bashrc)
cd /workspace/gapp && gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Cold Gradle build: ~3m20s (includes downloading AGP and dependencies).
Incremental rebuild: ~5s.

## Environment facts

- OS: Alpine Linux 3.24 (musl) under PRoot. Workspace is `/workspace`. `/sdcard` is FUSE (no exec bit, no symlinks), so keep builds off it.
- `adb devices` shows `emulator-5554`. It is the physical host phone, not an emulator.
- Network reaches `dl.google.com`, Maven Central, `maven.google.com`, GitHub.
- Preinstalled: JDK 17 and 21, Gradle 8.14.4, adb 35.0.2 (Alpine package), node, python3, git, cmake, gcc, gcompat.

## Key learnings

### 1. JDK 21 cannot start; JDK 17 can, with JIT
- `/usr/bin/java` points to **JDK 21**, which fails with `Failed to mark memory page as executable - check if grsecurity/PaX is enabled`.
- **JDK 17 starts fine with the full JIT.** I first misdiagnosed this and ran with `-Xint`, which was about 7-8x slower on a hot loop (400M iterations: 0.67s with JIT).
- Always use `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`. Do not rely on the default `java`.

### 2. Google's SDK native binaries are x86_64 and won't run
- `aapt2`, `aapt`, `aidl`, `zipalign`, `dexdump`, `split-select` in Google's build-tools are x86_64 glibc. They fail with `required file not found`.
- Fix: static aarch64 builds from https://github.com/lzhiyong/android-sdk-tools (release 35.0.2, `android-sdk-tools-static-aarch64.zip`), copied over the originals in `build-tools/34.0.0`.
- Gradle must be pointed at the aarch64 `aapt2` with `android.aapt2FromMavenOverride` (set in `~/.gradle/gradle.properties`). Otherwise AGP downloads the x86_64 one from Maven.
- Pure-Java tools (`d8`, `apksigner`, `javac`) work fine.
- Alpine's `android-tools` package supplies adb. `platform-tools/adb` in the SDK is a symlink to `/usr/bin/adb`.

### 3. `sdkmanager` is unreliable here
- It was very slow and once failed with `pthread_create failed (EAGAIN)`.
- Workaround: read `https://dl.google.com/android/repository/repository2-3.xml` for the zip names and download them with `curl` into `$ANDROID_HOME`, then `unzip`.
  - build-tools: `build-tools_r34-linux.zip` (inner dir `android-14` becomes `build-tools/34.0.0`)
  - platform: `platform-34-ext7_r03.zip` (inner dir becomes `platforms/android-34`)
- Command-line tools zip: `commandlinetools-linux-11076708_latest.zip`.

### 4. Tool-session gotchas
- Long foreground waits can get the session cut off. Run long jobs with `setsid nohup ... &` writing to a log, then watch the log (a Monitor with a grep on the log works well).
- Do not use `pkill -f <pattern>` that matches your own shell's command line. It kills the tool shell (exit 144). Use `pkill -x java`.

### 5. Device install is gated by a phone setting
- `adb install` returned `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`.
- Fix on the phone: Developer options, enable **Install via USB** (aka "USB debugging (Security settings)"), or approve the on-screen install prompt. Alternatively open the APK from a file manager.
- Status when this was written: not yet resolved, so installing and launching were **not verified**.

## Files and locations

| Path | Purpose |
|---|---|
| `/root/android-env.sh` | Env setup (JDK 17, `ANDROID_HOME`, PATH). Sourced from `~/.bashrc`. |
| `/root/android-sdk` | SDK: cmdline-tools, `platforms/android-34`, `build-tools/34.0.0` (aarch64 native tools), `platform-tools/adb` |
| `/root/.gradle/gradle.properties` | Gradle tuning plus aapt2 override |
| `/root/debug.keystore` | Debug signing key (alias `k`, password `android`) |
| `/workspace/gapp` | Gradle sample app (AGP 8.7.3, compileSdk 34, minSdk 24) |
| `/workspace/hello` | Manual-pipeline sample app |
| `/root/build-hello.sh` | Manual build: `aapt2`, `javac`, `d8`, `zipalign`, `apksigner` (~40s) |
| `/root/android-sdk/dl` | Downloaded zips (safe to delete) |

### `~/.gradle/gradle.properties`
```properties
org.gradle.java.home=/usr/lib/jvm/java-17-openjdk
org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UseCodeCacheFlushing -Dfile.encoding=UTF-8
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.workers.max=4
org.gradle.vfs.watch=false
android.aapt2FromMavenOverride=/root/android-sdk/build-tools/34.0.0/aapt2
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.daemon.jvmargs=-Xmx1g
```

## Per-project requirements
- `local.properties` containing `sdk.dir=/root/android-sdk` (or rely on `ANDROID_HOME`).
- `buildToolsVersion '34.0.0'` and `compileSdk 34` (the only installed versions). Install others by downloading the zips as in section 3.
- AGP 8.7.x works with the installed Gradle 8.14.

## Not tested / open items
- Kotlin and Jetpack Compose builds (the Kotlin Gradle plugin downloads its own compiler, so it should work; the first build will be slow).
- NDK / native code builds.
- Installing and launching the APK on the device (blocked by the phone setting above).
- Memory is tight (~4 GB free with the daemon running). Stop the daemon with `gradle --stop` if the system gets low.
- `/workspace/hello` manifest: `android:name` must be the fully qualified class (`com.ex.Main`), because `.Main` resolves against the manifest package.

---

## Project: voice-input (FUTO Voice Input fork), set up 2026-11-05

This repo needs much more than the sample app: SDK 36, NDK 28.2, CMake, Rust 1.98.1 and a Gradle daemon pinned to JDK 21.

### One-command build
```bash
/root/build-voice-input.sh                     # :app:assembleDevDebug, detached
/root/build-voice-input.sh :app:testDevDebugUnitTest
# then watch: until grep -q BUILD_EXIT /tmp/vi-build.log; do sleep 10; done
```
APK: `app/build/outputs/apk/dev/debug/`. A cold native build (llama.cpp, ggml, Harper Rust, ~550 ninja steps) takes a long time. Incremental Kotlin-only builds are fast.

### What was installed and why
| Item | How | Gotcha |
|---|---|---|
| Platform 36 | `platform-36_r02.zip` → `platforms/android-36` | |
| Build-tools 36.0.0 | `build-tools_r36_linux.zip` (inner dir `android-16`) → `build-tools/36.0.0` | Native tools replaced with the aarch64 static ones from `34.0.0` |
| NDK 28.2.13676358 (r28c) | `HomuHomu833/android-ndk-custom` release `r28`, asset `android-ndk-r28c-aarch64-linux-musl.tar.xz` → `ndk/28.2.13676358` | Google ships x86_64 only. BusyBox `tar` can't unpack this xz: `apk add xz` and use `xz -dc ... \| tar -x`. `prebuilt/linux-x86_64` is a symlink to `linux-arm64`, so AGP finds it. |
| CMake "3.22.1" | AGP auto-installs the SDK package (x86_64). Its `bin/{cmake,ctest,cpack,ninja}` are now symlinks to `/usr/bin` (CMake 4.2, `apk add ninja`). Originals kept as `*.x86`. | CMake 4 needs `CMAKE_POLICY_VERSION_MINIMUM=3.5` |
| Rust 1.98.1 + `aarch64-linux-android` | `rustup-init` for `aarch64-unknown-linux-musl`, `--profile minimal` | `~/.cargo/config.toml` has `net.git-fetch-with-cli = true`. libgit2 fails under PRoot with `refs/commit/.l2s...lock is not valid`. If Gradle still hits it, run `cargo fetch --locked` in `app/src/main/rust/harper_android` once. |
| Submodules | `git submodule update --init --recursive` | |
| `local.properties` | `sdk.dir=/root/android-sdk` (gitignored) | |

### Local-only change that must NOT be committed
`gradle/gradle-daemon-jvm.properties` is overwritten with `toolchainVersion=17`, because the JBR 21 that the repo pins fails with "Unpacked JDK archive does not contain a Java home" and system JDK 21 can't start. The original is saved at `/root/gradle-daemon-jvm.properties.orig`. When committing, stage files explicitly; never `git add -A`. The app itself targets Java 1.8, so JDK 17 compiles it fine.

### Other notes
- Transient `dl.google.com: Try again` DNS failures happen on the first dependency download. Just rerun.
- Signing: local builds use `~/.android/debug.keystore`, created by the first build. **Keep this file.** Updates over ADB only work if it stays the same. Use the `dev` flavor (app id suffix `.dev`) so it installs next to the release app.
- The phone blocks the first `adb install` (`INSTALL_FAILED_USER_RESTRICTED`). The user installs the first APK by hand (copy to `/sdcard/Download`); later same-signature updates go through `adb install -r`.
- Never `pkill -f` a pattern that matches your own shell (exit 144). Use `pkill -x java` or `./gradlew --stop`.
- No GitHub credentials in this environment: commit locally only.
- Expected unit-test failure here: `ComparisonResultsTest.incompleteAndUnscoredInputsNeverBecomeZeroOrComparableMeasurements`. It uses JDK 21's `List.removeLast()`, and tests run on JDK 17. CI (JDK 21) is unaffected. Use `--continue` so lint and assemble still run.
- Desktop sherpa-onnx (v1.13.4 Linux aarch64 prebuilt) cannot create an onnxruntime session under PRoot/gcompat, so model decoding can only be tested on the phone through the app.
- Timing on this phone: a cold full native build takes roughly 30+ min. Unit tests take ~3 min. Lint takes a long time (~30-60 min).
- git has no user configured: commit with `-c user.name=... -c user.email=...` (the user is Palo007), otherwise git picks up a wrong identity.
