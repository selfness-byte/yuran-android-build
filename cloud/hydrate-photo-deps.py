"""Fetch only the versions/hashes already approved in the source snapshot."""
import concurrent.futures, hashlib, json, pathlib, urllib.request
base = pathlib.Path("work/photo-android/libs/mlkit")
lock = json.loads((base / "dependencies.lock.json").read_text())
def fetch(artifact):
    record = artifact["file"]
    path = base / record["path"]
    if not path.resolve().is_relative_to(base.resolve()):
        raise ValueError("Dependency path outside cache")
    url = record["url"]
    if not url.startswith(("https://dl.google.com/dl/android/maven2/", "https://repo.maven.apache.org/maven2/", "https://repo1.maven.org/maven2/")):
        raise ValueError("Unexpected dependency source")
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read(150_000_000)
    if len(data) != record["bytes"] or hashlib.sha256(data).hexdigest() != record["sha256"]:
        raise ValueError("Locked dependency hash/length mismatch: " + record["path"])
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
    list(pool.map(fetch, lock["artifacts"]))
url = "https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.4/core-3.5.4.jar"
with urllib.request.urlopen(url, timeout=60) as response:
    data = response.read(5_000_000)
with urllib.request.urlopen(url + ".sha1", timeout=60) as response:
    expected = response.read(200).decode().strip()
if hashlib.sha1(data).hexdigest() != expected:
    raise ValueError("ZXing checksum mismatch")
target = pathlib.Path("work/photo-android/libs/zxing-core-3.5.4.jar")
target.write_bytes(data)
print("Hydrated", len(lock["artifacts"]), "locked OCR dependencies; ZXing SHA256", hashlib.sha256(data).hexdigest())
