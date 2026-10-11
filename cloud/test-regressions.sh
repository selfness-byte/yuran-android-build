#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
qa_classes="$(mktemp -d)"
trap 'rm -rf "$qa_classes"' EXIT
qa_jdk="${JAVA_HOME_17_X64:-${JAVA_HOME:-}}"
[ -x "$qa_jdk/bin/javac" ] || { echo "JDK 17 is required" >&2; exit 2; }
qa_sources=()
while IFS= read -r -d '' qa_source; do qa_sources+=("$qa_source"); done < <(find work/photo-contract/src cloud/tests/java -name '*.java' ! -name 'PhotoBundleCodec.java' -print0)
"$qa_jdk/bin/javac" -encoding UTF-8 --release 8 -sourcepath "cloud/tests/java:work/android/src:work/photo-contract/src:work/photo-android/src" -d "$qa_classes" \
 work/android/src/com/gongyo/orderly/Inventory.java \
 work/android/src/com/gongyo/orderly/PhotoCatalog.java \
 work/android/src/com/gongyo/orderly/PhotoLimits.java \
 work/photo-android/src/com/gongyo/yuranphoto/LabelMatcher.java \
 "${qa_sources[@]}"
"$qa_jdk/bin/java" -cp "$qa_classes" com.gongyo.tests.RegressionSuite
