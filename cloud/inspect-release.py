"""Read public release package metadata only; never access device or user data."""
import hashlib, pathlib, subprocess, urllib.request, zipfile, io, os, re
url = "https://github.com/selfness-byte/yuran-app-updates/releases/download/v2026.10.10/yuran-note-1.19.59.apk"
with urllib.request.urlopen(url, timeout=60) as response:
    data = response.read(5_000_000)
if hashlib.sha256(data).hexdigest() != "030b81d9605cb44ef45cba1d9877344c53e572afb1136016b7c59817fd7abca7":
    raise ValueError("Release digest mismatch")
folder = pathlib.Path("outputs/release-inspection"); folder.mkdir(parents=True, exist_ok=True)
apk = folder / "published-note.apk"; apk.write_bytes(data)
tools = pathlib.Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
badging = subprocess.check_output([str(tools/"aapt2"), "dump", "badging", str(apk)], text=True)
print(next(line for line in badging.splitlines() if line.startswith("package:")))
certificate = subprocess.check_output([str(tools/"apksigner"), "verify", "--print-certs", str(apk)], text=True)
print("\n".join(line for line in certificate.splitlines() if "SHA-256 digest" in line))
with zipfile.ZipFile(io.BytesIO(data)) as archive:
    for name in archive.namelist():
        if not re.fullmatch(r"classes[0-9]*\.dex", name): continue
        dex = folder/name; dex.write_bytes(archive.read(name))
        dump = subprocess.check_output([str(tools/"dexdump"), str(dex)], text=True)
        for block in dump.split("Class #"):
            if "Class descriptor  : 'Lcom/gongyo/orderly/PhotoLimits;'" in block:
                print(block[:14000])
