#!/bin/bash
# Build an unsigned QA Android APK with JDK 17 and Android SDK 35 tools.
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$APP_DIR/../toolchain}"
SDK_DIR="${SDK_DIR:-${ANDROID_SDK_ROOT:-$TOOLCHAIN_DIR/android-sdk}}"
BUILD_TOOLS_DIR="${BUILD_TOOLS_DIR:-$SDK_DIR/build-tools/35.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$SDK_DIR/platforms/android-35/android.jar}"
JDK_DIR="${JAVA_HOME:-$TOOLCHAIN_DIR/jdk/Contents/Home}"
APP_VARIANT="qa"
case "$APP_VARIANT" in release) SUFFIX="" ;; qa) SUFFIX=".qa" ;; *) printf "Unknown APP_VARIANT\n" >&2; exit 1 ;; esac
BUILD_DIR="$APP_DIR/build"
if [ "$APP_VARIANT" = qa ]; then BUILD_DIR="$APP_DIR/build/qa"; fi
OUTPUT_APK="${1:-$APP_DIR/../../outputs/yuran-photo-1.4.0.apk}"
ZXING_JAR="$APP_DIR/libs/zxing-core-3.5.4.jar"
MLKIT_HELPER="$APP_DIR/tools/prepare_mlkit.py"
MLKIT_LOCK="$APP_DIR/libs/mlkit/dependencies.lock.json"
MLKIT_DIR="$BUILD_DIR/mlkit"

for required_file in "$JDK_DIR/bin/javac" "$JDK_DIR/bin/java" "$ANDROID_JAR" "$ZXING_JAR" "$MLKIT_HELPER" "$MLKIT_LOCK" "$BUILD_TOOLS_DIR/aapt2" "$BUILD_TOOLS_DIR/zipalign" "$BUILD_TOOLS_DIR/lib/d8.jar"; do
    if [ ! -f "$required_file" ]; then
        printf 'Missing build dependency: %s\n' "$required_file" >&2
        exit 1
    fi
done

mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/generated" "$BUILD_DIR/dex" "$(dirname "$OUTPUT_APK")"
find "$BUILD_DIR/classes" "$BUILD_DIR/generated" "$BUILD_DIR/dex" -type f -delete
# Offline only. Missing or changed locked artifacts fail before compilation.
# Network dependency updates are an explicit, separate `prepare_mlkit.py resolve` operation.
python3 "$MLKIT_HELPER" prepare --destination "$MLKIT_DIR"
MLKIT_JARS=()
COMPILE_CLASSPATH="$ANDROID_JAR:$ZXING_JAR"
while IFS= read -r jar_path; do
    [ -n "$jar_path" ] || continue
    MLKIT_JARS+=("$MLKIT_DIR/$jar_path")
    COMPILE_CLASSPATH="$COMPILE_CLASSPATH:$MLKIT_DIR/$jar_path"
done < "$MLKIT_DIR/jars.txt"
python3 - "$APP_DIR/AndroidManifest.xml" "$BUILD_DIR/AndroidManifest.xml" "$SUFFIX" <<'PYGEN'
import sys, xml.etree.ElementTree as E
source,out,suffix=sys.argv[1:]; tree=E.parse(source); root=tree.getroot(); ns='{http://schemas.android.com/apk/res/android}'
root.set('package','com.gongyo.yuranphoto'+suffix)
if suffix:
 root.find('application').set(ns+'label','유란사진 검사용')
 root.find('application').set(ns+'debuggable','true')
for node in root.iter():
 for attr in ('name','authorities'):
  value=node.get(ns+attr)
  if value and value.startswith('com.gongyo.orderly'):
   node.set(ns+attr,'com.gongyo.orderly'+suffix+value[len('com.gongyo.orderly'):])
  if attr=='authorities' and value and value.startswith('com.gongyo.yuranphoto'):
   node.set(ns+attr,'com.gongyo.yuranphoto'+suffix+value[len('com.gongyo.yuranphoto'):])
  if attr=='name' and value and value.startswith('com.gongyo.yuranphoto.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'):
   node.set(ns+attr,'com.gongyo.yuranphoto'+suffix+value[len('com.gongyo.yuranphoto'):])
E.register_namespace('android','http://schemas.android.com/apk/res/android');tree.write(out,encoding='utf-8',xml_declaration=True)
PYGEN
mkdir -p "$BUILD_DIR/generated/com/gongyo/photo"
cat > "$BUILD_DIR/generated/com/gongyo/photo/RuntimeIds.java" <<IDS
package com.gongyo.photo;
public final class RuntimeIds {
 private RuntimeIds() {}
 public static final String OWNER_PACKAGE="com.gongyo.orderly$SUFFIX";
 public static final String PHOTO_PACKAGE="com.gongyo.yuranphoto$SUFFIX";
 public static final String PHOTO_AUTHORITY="com.gongyo.orderly$SUFFIX.photos";
 public static final String CAPTURE_AUTHORITY="com.gongyo.yuranphoto$SUFFIX.capture";
 public static final String SHARE_AUTHORITY="com.gongyo.orderly$SUFFIX.share";
 public static final String READ_PHOTOS="com.gongyo.orderly$SUFFIX.permission.READ_PHOTOS";
 public static final String WRITE_PHOTOS="com.gongyo.orderly$SUFFIX.permission.WRITE_PHOTOS";
}
IDS
"$BUILD_TOOLS_DIR/aapt2" compile --dir "$APP_DIR/res" -o "$BUILD_DIR/resources.zip"
LINK_ARGS=(link -o "$BUILD_DIR/resources.apk" \
    --manifest "$BUILD_DIR/AndroidManifest.xml" --custom-package com.gongyo.yuranphoto -I "$ANDROID_JAR" \
    --java "$BUILD_DIR/generated" --output-text-symbols "$BUILD_DIR/R.txt" \
    --auto-add-overlay --min-sdk-version 26 --target-sdk-version 35 \
    -0 tflite -0 binarypb -0 fb -0 pb -0 bincfg -0 conv_model -0 lstm_model)
resource_index=0
while IFS= read -r resource_path; do
    [ -n "$resource_path" ] || continue
    resource_index=$((resource_index + 1))
    resource_archive="$MLKIT_DIR/resources-$resource_index.zip"
    "$BUILD_TOOLS_DIR/aapt2" compile --dir "$MLKIT_DIR/$resource_path" -o "$resource_archive"
    LINK_ARGS+=(-R "$resource_archive")
done < "$MLKIT_DIR/resources.txt"
LINK_ARGS+=(-R "$BUILD_DIR/resources.zip" -A "$MLKIT_DIR/assets")
if [ -d "$APP_DIR/assets" ]; then LINK_ARGS+=(-A "$APP_DIR/assets"); fi
"$BUILD_TOOLS_DIR/aapt2" "${LINK_ARGS[@]}"
python3 "$MLKIT_HELPER" rjava --destination "$MLKIT_DIR" --symbols "$BUILD_DIR/R.txt" --generated "$BUILD_DIR/generated"

SOURCES=( "$APP_DIR/../android/src/com/gongyo/orderly/CheckedAtomicFile.java" "$APP_DIR/../android/src/com/gongyo/orderly/CommitUncertainException.java" "$APP_DIR/../android/src/com/gongyo/orderly/AndroidFileOps.java" )
while IFS= read -r -d '' source_file; do SOURCES+=("$source_file"); done < <(find "$APP_DIR/src" "$APP_DIR/../photo-contract/src" "$BUILD_DIR/generated" -name '*.java' -print0)
"$JDK_DIR/bin/javac" -J-Xmx512m -encoding UTF-8 --release 8 \
    -classpath "$COMPILE_CLASSPATH" -d "$BUILD_DIR/classes" "${SOURCES[@]}"
if [ ! -f "$BUILD_DIR/classes/com/gongyo/yuranphoto/PhotoActivity.class" ]; then
    printf 'Missing launcher activity: com.gongyo.yuranphoto.PhotoActivity\n' >&2
    exit 1
fi
"$JDK_DIR/bin/jar" cf "$BUILD_DIR/classes.jar" -C "$BUILD_DIR/classes" .
"$JDK_DIR/bin/java" -Xmx1024m -cp "$BUILD_TOOLS_DIR/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api 26 --lib "$ANDROID_JAR" --output "$BUILD_DIR/dex" "$BUILD_DIR/classes.jar" "$ZXING_JAR" "${MLKIT_JARS[@]}"
cp "$BUILD_DIR/resources.apk" "$BUILD_DIR/unsigned.apk"
(cd "$BUILD_DIR/dex" && zip -q -0 "$BUILD_DIR/unsigned.apk" classes*.dex)
if [ -d "$MLKIT_DIR/native/lib" ]; then
    (cd "$MLKIT_DIR/native" && zip -q -r -0 "$BUILD_DIR/unsigned.apk" lib)
fi
if [ -d "$MLKIT_DIR/java-resources" ]; then
    (cd "$MLKIT_DIR/java-resources" && zip -q -r "$BUILD_DIR/unsigned.apk" .)
fi
"$BUILD_TOOLS_DIR/zipalign" -f -P 16 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"

cp "$BUILD_DIR/aligned.apk" "$OUTPUT_APK"
"$BUILD_TOOLS_DIR/zipalign" -c -P 16 4 "$OUTPUT_APK"
"$BUILD_TOOLS_DIR/aapt2" dump badging "$OUTPUT_APK" > "$BUILD_DIR/apk-manifest.txt"
shasum -a 256 "$OUTPUT_APK"
printf 'Built APK: %s\n' "$OUTPUT_APK"
