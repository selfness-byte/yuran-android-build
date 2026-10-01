# Yuran Android cloud QA source

Private source-only snapshot, independent of the original repository and history.
Main app, photo companion and photo contract source are included. No signing keys,
photos, inventory databases, logs, generated artifacts, SDK/JDK or cached binaries
are included. Snapshot includes uncommitted development source; it is not a release.

Original worktree HEAD: c03e77726d0680296ba7b35663c3de537d79bb18.
The copied build scripts are adapted only to produce unsigned QA APKs and mark QA
manifests debuggable. Original files and signing setup remain untouched.

## Minimal cloud check

Use an isolated Linux cloud container with Python 3, JDK 17, zip and Android SDK
platforms;android-35 and build-tools;35.0.0 already installed/licensed.
Set JAVA_HOME and ANDROID_SDK_ROOT, then run `bash cloud/build-qa.sh` once.
No release signing, emulator, device installation or runtime tests. The unsigned
APK cannot be installed as delivered. A compilation pass is not functional QA.
If SDK/license access is unavailable, stop and report HOLD without accepting terms.

The companion source is retained, but the one minimal check builds the main app
only. Companion dependencies are public ZXing 3.5.4 and locked ML Kit Chinese text
recognition 16.0.1. Download caches/model artifacts are deliberately excluded.
The companion needs separate explicit dependency hydration before it can build.
Do not update dependency versions, resolve locks or run companion builds automatically.
