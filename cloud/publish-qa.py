"""Publish only the already verified, isolated QA pair. No rebuilding or signing."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

REPO = "selfness-byte/yuran-android-build"
SOURCE = "6d328f13840d4d1d1315b495d9da900094507228"
RUN = 38148683104
ARTIFACT = 11705491843
ARCHIVE_SHA = "dbfce91d87f863eb3d450d647bd03e0cc362101b6311cf4f66e00d06aea0768a"
ARCHIVE_SIZE = 24753526
CERT = "86ad771a9b587e08ae9d7534a16690ffd0d739484ddc7ed677490423ff1354a0"
TAG = "qa-2026.10.11.1"
APK_HASHES = {
    "yuran-note-qa.apk": "8abd86a259d6dda0a3c9b4fac2914bc5398d45798c578bf029705b4d155cf2c8",
    "yuran-photo-qa.apk": "05ce6579c55a9992b8115f4d16546fcfeb949ac3d715ccbf642553df5cd75372",
}
APK_SIZES = {"yuran-note-qa.apk": 451153, "yuran-photo-qa.apk": 53926046}
PACKAGES = {"yuran-note-qa.apk": "com.gongyo.orderly.qa",
            "yuran-photo-qa.apk": "com.gongyo.yuranphoto.qa"}
ALLOWED = set(APK_HASHES) | {"BUILD-INFO.json", "SHA256SUMS.txt", "읽어주세요.txt"}
API = "https://api.github.com/repos/" + REPO

def sha(data):
    return hashlib.sha256(data).hexdigest()

def require(condition, message):
    if not condition:
        raise ValueError(message)

def require_publish_target(repo, ref):
    require(repo == REPO and ref == "refs/heads/main", "Publication is limited to this repository's main branch")

def require_matching_asset(asset, data):
    require(asset.get("state") == "uploaded" and asset.get("size") == len(data)
            and asset.get("digest") == "sha256:" + sha(data), "Existing/uploaded release asset differs; refusing overwrite")

def validate_bundle(data, hashes=APK_HASHES, sizes=APK_SIZES):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        require(len(names) == len(ALLOWED) and set(names) == ALLOWED, "Unexpected or duplicate artifact entries")
        require(sum(i.file_size for i in archive.infolist()) < 64 * 1024 * 1024, "Artifact expands beyond the expected bound")
        files = {name: archive.read(name) for name in names}
    info = json.loads(files["BUILD-INFO.json"])
    require(info.get("commit") == SOURCE, "Artifact source revision differs")
    records = info.get("apks", [])
    require(len(records) == 2 and {r.get("file") for r in records} == set(APK_HASHES), "Expected the matched APK pair")
    for record in records:
        name = record["file"]
        require(record.get("package") == PACKAGES[name], "Production or unexpected package rejected")
        require(record.get("signingCertificateSHA256") == CERT, "Unexpected signing certificate")
        require(record.get("sha256") == hashes[name] == sha(files[name]), "APK hash differs")
        require(record.get("bytes") == sizes[name] == len(files[name]), "APK size differs")
    checksums = "".join(hashes[name] + "  " + name + "\n" for name in APK_HASHES)
    require(files["SHA256SUMS.txt"].decode("utf-8") == checksums, "Checksum document differs")
    return files

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

class GitHub:
    def __init__(self):
        self.token = os.environ["GH_TOKEN"]
        self.opener = urllib.request.build_opener(NoRedirect)

    def request(self, url, method="GET", body=None, content_type="application/json", missing=False):
        parsed = urllib.parse.urlsplit(url)
        require(parsed.scheme == "https" and parsed.netloc in {"api.github.com", "uploads.github.com"}
                and parsed.path.startswith("/repos/" + REPO + "/"), "Unexpected API destination")
        data = json.dumps(body).encode() if body is not None and not isinstance(body, bytes) else body
        req = urllib.request.Request(url, data=data, method=method, headers={
            "Accept": "application/vnd.github+json", "Content-Type": content_type,
            "Authorization": "Bearer " + self.token, "X-GitHub-Api-Version": "2026-03-10"})
        try:
            return self.opener.open(req, timeout=120)
        except urllib.error.HTTPError as exc:
            if missing and exc.code == 404:
                return None
            raise

    def json(self, path, method="GET", body=None, missing=False):
        response = self.request(API + path, method, body, missing=missing)
        if response is None:
            return None
        with response:
            return json.load(response)

    def archive(self):
        # The GitHub credential is never forwarded to the signed blob-storage redirect.
        try:
            with self.request(API + "/actions/artifacts/" + str(ARTIFACT) + "/zip") as response:
                return response.read(32 * 1024 * 1024 + 1)
        except urllib.error.HTTPError as exc:
            require(exc.code == 302, "Artifact download did not return a redirect")
            location = exc.headers.get("Location", "")
        parsed = urllib.parse.urlsplit(location)
        require(parsed.scheme == "https" and parsed.hostname is not None and
                parsed.hostname.endswith((".blob.core.windows.net", ".actions.githubusercontent.com",
                                          ".githubusercontent.com")), "Unexpected artifact storage destination")
        with urllib.request.urlopen(location, timeout=120) as response:
            return response.read(32 * 1024 * 1024 + 1)

def verified_artifact(client):
    run = client.json("/actions/runs/" + str(RUN))
    require(run.get("status") == "completed" and run.get("conclusion") == "success"
            and run.get("head_sha") == SOURCE
            and run.get("path") == ".github/workflows/yuran-repairs.yml", "The locked build did not pass")
    artifact = client.json("/actions/artifacts/" + str(ARTIFACT))
    origin = artifact.get("workflow_run", {})
    require(not artifact.get("expired") and artifact.get("name") == "yuran-qa-apks"
            and artifact.get("digest") == "sha256:" + ARCHIVE_SHA
            and artifact.get("size_in_bytes") == ARCHIVE_SIZE
            and origin.get("id") == RUN and origin.get("head_sha") == SOURCE
            and origin.get("head_repository_id") == 1399819797, "Artifact provenance differs")
    data = client.archive()
    require(len(data) == ARCHIVE_SIZE and sha(data) == ARCHIVE_SHA, "Downloaded archive differs")
    files = validate_bundle(data)
    sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "35.0.0"
    with tempfile.TemporaryDirectory(prefix="yuran-verified-qa-") as folder:
        for name in APK_HASHES:
            apk = Path(folder) / name
            apk.write_bytes(files[name])
            cert = subprocess.check_output([str(sdk / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
            require("Signer #1 certificate SHA-256 digest: " + CERT in cert, "APK certificate verification failed")
            badging = subprocess.check_output([str(sdk / "aapt2"), "dump", "badging", str(apk)], text=True)
            require(re.search(r"package: name='([^']+)'", badging).group(1) == PACKAGES[name], "APK package verification failed")
            subprocess.run([str(sdk / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)
    assets = dict(files)
    assets["INSTALL-ko.txt"] = assets.pop("읽어주세요.txt")
    assets["yuran-qa-2026-10-11.zip"] = data
    return assets

def publish(client, assets):
    require_publish_target(os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_REF"])
    target = os.environ["GITHUB_SHA"]
    require(re.fullmatch(r"[0-9a-f]{40}", target) is not None, "Invalid release revision")
    require(client.json("/git/ref/heads/main")["object"]["sha"] == target, "Main moved; stop for review")
    tag = client.json("/git/ref/tags/" + TAG, missing=True)
    require(tag is None or tag["object"].get("type") == "commit" and tag["object"].get("sha") == target,
            "Release tag belongs to another revision")
    release = client.json("/releases/tags/" + TAG, missing=True)
    body = """유란노트·유란사진 별도 설치 시험판입니다. 기존 앱을 덮어쓰는 정식 업데이트가 아닙니다.

1. yuran-note-qa.apk를 먼저 설치하고 yuran-photo-qa.apk를 설치하세요.
2. 사진의 명확한 품번·색상·S/M/L/XL 수량으로 새입고를 등록합니다.
3. 연속 촬영은 품번 확인 후 사진을 저장하고 완료음을 냅니다. 불명확한 수량은 확인 대기로 남습니다.
4. 품번 전체수량 표시, 엑셀 사진 미리보기, 버튼과 촬영/파일 복구를 개선했습니다.
5. 기존 앱 삭제나 실제 재고 백업 가져오기는 하지 마세요. 현재 정식 자료 형식과 다릅니다.

기준 소스: 노트 1.17.1 / 사진 1.4.0. 현재 정식 배포본에 덮어쓰려면 기존 서명과 자료 형식14 호환 작업이 필요합니다.
36개 회귀 검사 및 엑셀·촬영·복구 검사와 두 APK 빌드/서명/정렬 검사가 통과했습니다.
Galaxy S24 Ultra의 카메라·회전·완료음과 실제 Windows Excel 표시는 실기기 검증이 남았습니다.
이 APK 쌍은 같은 임시 QA 서명을 사용합니다. 다른 QA 빌드와의 업데이트 호환성은 보장하지 않습니다.

APK 빌드 소스: """ + SOURCE + """
검증된 빌드: https://github.com/""" + REPO + "/actions/runs/" + str(RUN) + """
이번 배포는 검증된 APK 바이트를 그대로 공개합니다. 릴리스 태그에는 동일한 앱 코드와 배포 자동화가 포함됩니다.
BUILD-INFO.json과 SHA256SUMS.txt에서 APK 출처와 해시를 확인할 수 있습니다.
ZIP SHA-256: """ + ARCHIVE_SHA + "\n"
    if release is None:
        release = client.json("/releases", "POST", {"tag_name": TAG, "target_commitish": target,
            "name": "유란노트·유란사진 시험판 2026-10-11", "body": body,
            "draft": True, "prerelease": True, "make_latest": "false"})
    require(release.get("tag_name") == TAG and release.get("prerelease") is True
            and release.get("target_commitish") == target, "Existing release identity differs")
    existing = {asset["name"]: asset for asset in release.get("assets", [])}
    require(set(existing) <= set(assets), "Unexpected existing release assets")
    upload = release["upload_url"].split("{", 1)[0]
    for name, data in assets.items():
        if name in existing:
            require_matching_asset(existing[name], data)
            continue
        require(release.get("draft") is True, "Refusing to add missing files to an already published release")
        media = "application/vnd.android.package-archive" if name.endswith(".apk") else "application/zip" if name.endswith(".zip") else "application/json" if name.endswith(".json") else "text/plain; charset=utf-8"
        with client.request(upload + "?" + urllib.parse.urlencode({"name": name}), "POST", data, media) as response:
            require_matching_asset(json.load(response), data)
    check = client.json("/releases/" + str(release["id"]))
    current = {asset["name"]: asset for asset in check["assets"]}
    require(set(current) == set(assets), "Release is missing files")
    for name, data in assets.items():
        require_matching_asset(current[name], data)
    if check.get("draft"):
        check = client.json("/releases/" + str(release["id"]), "PATCH",
                            {"draft": False, "prerelease": True, "make_latest": "false"})
    require(check.get("draft") is False and check.get("prerelease") is True, "Release did not publish")
    for name in APK_HASHES:
        url = current[name]["browser_download_url"]
        expected = "https://github.com/" + REPO + "/releases/download/" + TAG + "/" + name
        require(url == expected, "Unexpected public download location")
        with urllib.request.urlopen(url, timeout=120) as response:
            require(sha(response.read(APK_SIZES[name] + 1)) == APK_HASHES[name], "Anonymous APK download differs")
    print("Published and anonymously verified: " + check["html_url"])

def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--verify-only", action="store_true")
    mode.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    if args.publish:
        require_publish_target(os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_REF"])
    client = GitHub()
    assets = verified_artifact(client)
    print("Verified original QA artifact, APK hashes, signatures, package IDs and alignment.")
    if args.publish:
        publish(client, assets)

if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as exc:
        raise SystemExit("GitHub request failed with HTTP " + str(exc.code))
    except urllib.error.URLError:
        raise SystemExit("Network request failed")
    except (ValueError, KeyError, zipfile.BadZipFile) as exc:
        raise SystemExit("Publication stopped: " + str(exc))
