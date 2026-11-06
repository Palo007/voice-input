#!/bin/sh
# Builds the replay runner with the Android NDK and copies it, the Sherpa-ONNX libs and the
# Slovak + Ultra model files to /data/local/tmp/replay on the connected phone.
# Usage: tools/replay-bench/build-and-push.sh [app package, default org.futo.voiceinput.dev]
# Afterwards: adb push your .wav files to /data/local/tmp/replay and run
#   adb shell "cd /data/local/tmp/replay && LD_LIBRARY_PATH=. ./replay sk a.wav b.wav"
# (model dirs: sk = Parakeet TDT V3 Slovak, ultra = Parakeet Ultra). Remove the directory when done:
#   adb shell rm -rf /data/local/tmp/replay
set -eu
PKG="${1:-org.futo.voiceinput.dev}"
SHERPA_VERSION=1.13.4   # keep equal to BuildConfig.SHERPA_ONNX_VERSION
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${TMPDIR:-/tmp}/replay-bench"
D=/data/local/tmp/replay
. /root/android-env.sh
mkdir -p "$WORK"
cd "$WORK"
if [ ! -f jniLibs/arm64-v8a/libsherpa-onnx-c-api.so ]; then
  curl -sfLO "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-v$SHERPA_VERSION-android.tar.bz2"
  tar xjf "sherpa-onnx-v$SHERPA_VERSION-android.tar.bz2" ./jniLibs/arm64-v8a/
fi
[ -f c-api.h ] || curl -sfL -o c-api.h "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v$SHERPA_VERSION/sherpa-onnx/c-api/c-api.h"
CC=$(ls "$ANDROID_HOME"/ndk/*/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android26-clang | tail -1)
"$CC" -I. -o replay "$HERE/run.c" -LjniLibs/arm64-v8a -lsherpa-onnx-c-api
adb shell mkdir -p $D/sk $D/ultra
adb push replay jniLibs/arm64-v8a/libsherpa-onnx-c-api.so jniLibs/arm64-v8a/libonnxruntime.so $D/ >/dev/null
adb shell chmod 755 $D/replay
# Model files are copied on the phone itself (no 650 MB round trip); needs a debuggable build for run-as.
for pair in "sk:parakeet-tdt-0.6b-v3-sk-sherpa-onnx-int8" "ultra:sherpa-onnx-parakeet-ultra-tdt-0.6b-v3-int8"; do
  dir="${pair%%:*}"; src="${pair#*:}"
  adb shell "cd $D/$dir && for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do run-as $PKG cat files/$src/\$f > \$f; done"
done
echo "ready: $D (models: sk, ultra)"
