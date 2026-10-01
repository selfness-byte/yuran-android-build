#!/usr/bin/env bash
set -euo pipefail
[ "$(uname -s)" = Linux ] || { echo "HOLD: run this check in the cloud Linux container only" >&2; exit 2; }
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?HOLD: set JAVA_HOME to existing JDK 17}"
: "${ANDROID_SDK_ROOT:?HOLD: set ANDROID_SDK_ROOT to existing licensed Android SDK}"
export SDK_DIR="$ANDROID_SDK_ROOT"
export BUILD_TOOLS_DIR="$SDK_DIR/build-tools/35.0.0"
export ANDROID_JAR="$SDK_DIR/platforms/android-35/android.jar"
bash work/android/build.sh outputs/yuran-note-unsigned-qa.apk
