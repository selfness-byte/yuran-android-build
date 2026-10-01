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
OUTPUT_APK="${1:-$APP_DIR/../../outputs/yuran-note-1.17.1.apk}"

for required_file in "$JDK_DIR/bin/javac" "$JDK_DIR/bin/java" "$ANDROID_JAR" "$BUILD_TOOLS_DIR/aapt2" "$BUILD_TOOLS_DIR/zipalign" "$BUILD_TOOLS_DIR/lib/d8.jar"; do
    if [ ! -f "$required_file" ]; then
        printf 'Missing build dependency: %s\n' "$required_file" >&2
        exit 1
    fi
done

mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/generated" "$BUILD_DIR/dex" "$(dirname "$OUTPUT_APK")"
find "$BUILD_DIR/classes" "$BUILD_DIR/generated" "$BUILD_DIR/dex" -type f -delete
python3 - "$APP_DIR/AndroidManifest.xml" "$BUILD_DIR/AndroidManifest.xml" "$SUFFIX" <<'PYGEN'
import sys, xml.etree.ElementTree as E
source,out,suffix=sys.argv[1:]; tree=E.parse(source); root=tree.getroot(); old=root.attrib['package']; ns='{http://schemas.android.com/apk/res/android}'
root.set('package',old+suffix)
for node in root.iter():
    name=node.get(ns+'name')
    if name and name.startswith('.'): node.set(ns+'name',old+name)
    for attr in ('name','readPermission','writePermission'):
        value=node.get(ns+attr)
        if value and value.startswith(old+'.permission.'): node.set(ns+attr,old+suffix+value[len(old):])
    if node.tag=='package' and node.get(ns+'name')=='com.gongyo.yuranphoto': node.set(ns+'name','com.gongyo.yuranphoto'+suffix)
    auth=node.get(ns+'authorities')
    if auth:
        prefix=old if auth.startswith(old+'.') else 'com.gongyo.yuranphoto'
        node.set(ns+'authorities',prefix+suffix+auth[len(prefix):])
if suffix:
    root.find('application').set(ns+'label','유란노트 검사용')
    root.find('application').set(ns+'debuggable','true')
    queries=root.find('queries')
    E.SubElement(queries,'package',{ns+'name':'com.gongyo.photoprobe.qa'})
E.register_namespace('android','http://schemas.android.com/apk/res/android'); tree.write(out,encoding='utf-8',xml_declaration=True)
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
    --manifest "$BUILD_DIR/AndroidManifest.xml" --custom-package com.gongyo.orderly -I "$ANDROID_JAR" \
    --java "$BUILD_DIR/generated" --min-sdk-version 26 --target-sdk-version 35)
if [ -d "$APP_DIR/assets" ]; then LINK_ARGS+=(-A "$APP_DIR/assets"); fi
"$BUILD_TOOLS_DIR/aapt2" "${LINK_ARGS[@]}" "$BUILD_DIR/resources.zip"

SOURCES=()
while IFS= read -r -d '' source_file; do SOURCES+=("$source_file"); done < <(find "$APP_DIR/src" "$APP_DIR/../photo-contract/src" "$BUILD_DIR/generated" -name '*.java' -print0)
"$JDK_DIR/bin/javac" -encoding UTF-8 --release 8 \
    -classpath "$ANDROID_JAR" -d "$BUILD_DIR/classes" "${SOURCES[@]}"
if [ ! -f "$BUILD_DIR/classes/com/gongyo/orderly/MainActivity.class" ]; then
    printf 'Missing launcher activity: com.gongyo.orderly.MainActivity\n' >&2
    exit 1
fi
"$JDK_DIR/bin/jar" cf "$BUILD_DIR/classes.jar" -C "$BUILD_DIR/classes" .
"$JDK_DIR/bin/java" -cp "$BUILD_TOOLS_DIR/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api 26 --lib "$ANDROID_JAR" --output "$BUILD_DIR/dex" "$BUILD_DIR/classes.jar"
cp "$BUILD_DIR/resources.apk" "$BUILD_DIR/unsigned.apk"
(cd "$BUILD_DIR/dex" && zip -q -0 "$BUILD_DIR/unsigned.apk" classes*.dex)
"$BUILD_TOOLS_DIR/zipalign" -f -p 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"

cp "$BUILD_DIR/aligned.apk" "$OUTPUT_APK"
"$BUILD_TOOLS_DIR/zipalign" -c -p 4 "$OUTPUT_APK"
"$BUILD_TOOLS_DIR/aapt2" dump badging "$OUTPUT_APK" > "$BUILD_DIR/apk-manifest.txt"
shasum -a 256 "$OUTPUT_APK"
printf 'Built APK: %s\n' "$OUTPUT_APK"
