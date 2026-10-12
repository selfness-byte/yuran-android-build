"""Read-only audit of public Yuran releases. No keys, installs, or publication."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.parse
import urllib.error
import urllib.request

REPO = "https://api.github.com/repos/selfness-byte/yuran-app-updates"
HOST = "https://inanimals-1977.web.app"
TAG = "v2026.10.12.3"
EXPECTED = {
    "note": ("1.19.64", 95, "com.gongyo.orderly",
             "b781db0abff3652d04b2389337f31829114a5301a8a1ec0c9e75993f5c380146"),
    "photo": ("1.7.43", 67, "com.gongyo.yuranphoto",
              "6bbc79ca64aebe42eb686f0f36a791a751ce0fbfa0eff184a4146bb9f62c7bfb"),
}

def emit(label, value):
    print("AUDIT " + label + " " + json.dumps(value, ensure_ascii=False), flush=True)

def get_bytes(url, limit):
    request = urllib.request.Request(url, headers={"User-Agent": "yuran-production-read-only-audit/1"})
    with urllib.request.urlopen(request, timeout=45) as response:
        body = response.read(limit + 1)
        if len(body) > limit:
            raise ValueError("Public response exceeds bounded audit limit")
        return body, {"status": response.status, "url": urllib.parse.urlunsplit(urllib.parse.urlsplit(response.url)._replace(query="", fragment="")),
                      "content_type": response.headers.get("Content-Type"),
                      "cache_control": response.headers.get("Cache-Control"),
                      "age": response.headers.get("Age")}

def command(*args, limit=200000):
    completed = subprocess.run([str(x) for x in args], text=True, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, timeout=90, check=False)
    output = completed.stdout
    if len(output) > limit:
        output = output[:limit] + "\n[truncated]"
    return {"exit": completed.returncode, "output": output}

def attempt(label, action):
    try:
        value = action()
        emit(label, value)
        return value
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        emit(label, {"error_type": type(error).__name__, "error": str(error)[:1500]})
        return None

def apk_inspect(name, path, sdk, analyzer):
    bt = sdk / "build-tools/35.0.0"
    signed = command(bt / "apksigner", "verify", "--verbose", "--print-certs", path)
    emit(name + ".signer", signed)
    emit(name + ".alignment", command(bt / "zipalign", "-c", "-P", "16", "4", path))
    badging = command(bt / "aapt2", "dump", "badging", path)
    emit(name + ".badging", {"exit": badging["exit"], "lines": [
        line for line in badging["output"].splitlines()
        if line.startswith(("package:", "sdkVersion:", "targetSdkVersion:", "uses-permission:",
                            "launchable-activity:", "native-code:"))
    ]})
    package_line = next((line for line in badging["output"].splitlines() if line.startswith("package:")), "")
    expected_version, expected_code, expected_package, _ = EXPECTED[name]
    emit(name + ".expected_identity", {
        "matches": (badging["exit"] == 0
                    and "name='" + expected_package + "'" in package_line
                    and "versionCode='" + str(expected_code) + "'" in package_line
                    and "versionName='" + expected_version + "'" in package_line),
        "package": expected_package, "version_code": expected_code,
        "version_name": expected_version})
    if analyzer is None:
        emit(name + ".dex", {"unavailable": "SDK apkanalyzer not preinstalled"})
        return
    manifest = command(analyzer, "manifest", "print", path)
    emit(name + ".manifest", manifest)
    packages = command(analyzer, "dex", "packages", "--defined-only", path, limit=2000000)
    classes = []
    for line in packages["output"].splitlines():
        fields = line.split()
        if len(fields) >= 6 and fields[0] == "C":
            cls = fields[-1]
            if cls.startswith("com.gongyo.") and re.search("Update|Install|IncomingRegistrationActivity", cls):
                classes.append(cls)
    emit(name + ".classes", classes)
    for cls in classes:
        if "$" in cls or not re.search("Activity$|Worker$|Installer$|UpdateRequest$|UpdateSession", cls):
            continue
        code = command(analyzer, "dex", "code", "--class", cls, path, limit=400000)
        print("AUDIT_CODE_BEGIN " + name + " " + cls, flush=True)
        print(code["output"], flush=True)
        print("AUDIT_CODE_END " + name + " " + cls + " exit=" + str(code["exit"]), flush=True)

def main():
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "/missing-sdk")
    analyzers = list(sdk.glob("cmdline-tools/*/bin/apkanalyzer"))
    analyzer = next((p for p in analyzers if "/latest/" in str(p)), analyzers[0] if analyzers else None)
    emit("toolchain", {"sdk": str(sdk), "apkanalyzer": str(analyzer) if analyzer else None})
    def manifest_fetch():
        data, headers = get_bytes(HOST + "/version.json", 131072)
        return {"http": headers, "sha256": hashlib.sha256(data).hexdigest(),
                "json": json.loads(data)}
    attempt("version_json", manifest_fetch)
    release_raw, release_headers = get_bytes(REPO + "/releases/tags/" + TAG, 262144)
    release = json.loads(release_raw)
    emit("release", {"tag": release["tag_name"], "draft": release["draft"],
                     "published_at": release["published_at"], "http": release_headers})
    assets = {entry["name"]: entry for entry in release["assets"]}
    for filename in ("BUILD-INFO.json", "INSTALL.txt", "SHA256SUMS"):
        entry = assets[filename]
        def metadata_fetch(entry=entry):
            data, headers = get_bytes(entry["browser_download_url"], 131072)
            return {"http": headers, "sha256": hashlib.sha256(data).hexdigest(),
                    "github_digest": entry.get("digest"), "text": data.decode("utf-8")}
        attempt(filename, metadata_fetch)
    with tempfile.TemporaryDirectory(prefix="yuran-public-apk-audit-") as folder:
        for name, (version, code, package, expected_sha) in EXPECTED.items():
            filename = "yuran-" + name + "-" + version + ".apk"
            entry = assets[filename]
            path = Path(folder) / filename
            def download(entry=entry, path=path, expected_sha=expected_sha):
                data, headers = get_bytes(entry["browser_download_url"], 78643200)
                digest = hashlib.sha256(data).hexdigest()
                if digest != expected_sha:
                    raise ValueError("Pinned release APK digest mismatch")
                path.write_bytes(data)
                return {"http": headers, "sha256": digest, "size": len(data),
                        "github_digest": entry.get("digest")}
            loaded = attempt(name + ".github_download", download)
            def hosting_compare(filename=filename, expected_sha=expected_sha):
                data, headers = get_bytes(HOST + "/downloads/" + filename, 78643200)
                digest = hashlib.sha256(data).hexdigest()
                return {"http": headers, "sha256": digest, "size": len(data),
                        "matches_pinned_github_apk": digest == expected_sha}
            attempt(name + ".hosting_download", hosting_compare)
            if loaded:
                attempt(name + ".inspection", lambda: apk_inspect(name, path, sdk, analyzer))
        previous_raw, _ = get_bytes(REPO + "/releases/tags/v2026.10.12.2", 262144)
        previous = json.loads(previous_raw)
        for entry in previous["assets"]:
            if not entry["name"].endswith(".apk"):
                continue
            def prior_signature(entry=entry):
                data, _ = get_bytes(entry["browser_download_url"], 78643200)
                digest = hashlib.sha256(data).hexdigest()
                if "sha256:" + digest != entry["digest"]:
                    raise ValueError("Prior release GitHub digest mismatch")
                path = Path(folder) / entry["name"]
                path.write_bytes(data)
                return {"sha256": digest, "signer": command(
                    sdk / "build-tools/35.0.0/apksigner", "verify", "--verbose", "--print-certs", path)}
            attempt("previous." + entry["name"], prior_signature)
    emit("scope", "Public artifacts inspected only. No phone install, key access, app change, or release publication.")

if __name__ == "__main__":
    main()
