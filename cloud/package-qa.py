"""Package QA updates only with the existing QA signer; never create a new key."""
import base64
import binascii
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile

EXPECTED_SIGNER = "86ad771a9b587e08ae9d7534a16690ffd0d739484ddc7ed677490423ff1354a0"
QA_APPS = {
    "note": ("com.gongyo.orderly.qa", 23),
    "photo": ("com.gongyo.yuranphoto.qa", 6),
}
STORE_PASSWORD_ENV = "YURAN_QA_STORE_PASSWORD"
KEY_PASSWORD_ENV = "YURAN_QA_KEY_PASSWORD"


def signing_config(environment):
    """Validate explicit configuration without accessing a key or returning secrets."""
    required = ("YURAN_QA_KEYSTORE", "YURAN_QA_KEY_ALIAS", STORE_PASSWORD_ENV, KEY_PASSWORD_ENV)
    for name in required:
        if not isinstance(environment.get(name), str) or not environment[name]:
            raise ValueError("Missing " + name + "; existing QA signing credentials are required. No replacement key will be generated.")
    key = pathlib.Path(environment["YURAN_QA_KEYSTORE"])
    if not key.is_absolute():
        raise ValueError("YURAN_QA_KEYSTORE must be an absolute path to the existing durable QA keystore")
    return {"keystore": key, "alias": environment["YURAN_QA_KEY_ALIAS"]}


def certificate_digest(pem):
    matches = re.findall(r"-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----", pem, re.DOTALL)
    if len(matches) != 1:
        raise ValueError("Expected exactly one public signing certificate")
    try:
        der = base64.b64decode("".join(matches[0].split()), validate=True)
    except (ValueError, binascii.Error) as failure:
        raise ValueError("Invalid public signing certificate") from failure
    if not der:
        raise ValueError("Empty public signing certificate")
    return hashlib.sha256(der).hexdigest()


def keystore_signer(config, jdk, runner):
    # Export only the public certificate to stdout; never export, copy, or create a key.
    pem = runner(jdk / "bin" / "keytool", "-exportcert", "-rfc",
                 "-keystore", config["keystore"], "-alias", config["alias"],
                 "-storepass:env", STORE_PASSWORD_ENV)
    return certificate_digest(pem)


def validate_badging(name, badging):
    if name not in QA_APPS:
        raise ValueError("Unexpected QA app")
    lines = [line for line in badging.splitlines() if line.startswith("package:")]
    match = re.match(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'(?:\s|$)",
                     lines[0]) if len(lines) == 1 else None
    package, minimum = QA_APPS[name]
    if not match or match.group(1) != package:
        raise ValueError("Production or unexpected package identity rejected for " + name)
    code = match.group(2)
    if not re.fullmatch(r"[0-9]{1,10}", code) or not minimum < int(code) <= 2100000000:
        raise ValueError(name + " versionCode must be greater than installed QA version " + str(minimum))
    return {"file": "yuran-" + name + "-qa.apk", "package": package,
            "versionCode": code, "versionName": match.group(3)}


def validate_signer(output):
    counts = re.findall(r"^Number of signers:\s*([0-9]+)\s*$", output, re.MULTILINE)
    markers = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest:", output, re.MULTILINE)
    digests = re.findall(r"^Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$", output, re.MULTILINE)
    if counts != ["1"] or len(markers) != 1 or len(digests) != 1 or digests[0].lower() != EXPECTED_SIGNER:
        raise ValueError("APK must use exactly the existing QA signing certificate")
    return EXPECTED_SIGNER


def run(*args, environment):
    try:
        return subprocess.check_output([str(arg) for arg in args], text=True,
                                       stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                       env=dict(environment))
    except (OSError, subprocess.CalledProcessError) as failure:
        # Tool output may contain private environment details; do not print it.
        raise ValueError("QA packaging tool failed: " + pathlib.Path(args[0]).name
                         + ". No download was published.") from failure


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def package_pair(root, environment, runner=None):
    config = signing_config(environment)  # Must precede directories, tool calls, and APK writes.
    root = pathlib.Path(root)
    out = root / "qa-download"
    if out.exists() or out.is_symlink():
        raise ValueError("Existing qa-download will not be overwritten; use a clean isolated workspace")
    if not config["keystore"].is_file():
        raise ValueError("Existing QA keystore is unavailable; no replacement key will be generated")
    for name in ("ANDROID_HOME", "JAVA_HOME_17_X64", "GITHUB_SHA"):
        if not environment.get(name):
            raise ValueError("Missing " + name)
    if not re.fullmatch(r"[0-9a-fA-F]{40}", environment["GITHUB_SHA"]):
        raise ValueError("GITHUB_SHA must identify the exact source commit")
    sdk = pathlib.Path(environment["ANDROID_HOME"]) / "build-tools" / "35.0.0"
    jdk = pathlib.Path(environment["JAVA_HOME_17_X64"])
    invoke = runner if runner is not None else lambda *args: run(*args, environment=environment)
    if keystore_signer(config, jdk, invoke) != EXPECTED_SIGNER:
        raise ValueError("Existing QA signer mismatch; production and newly generated signers are rejected")

    # Check both unsigned inputs before creating staging or signing either APK.
    inputs = []
    for name in QA_APPS:
        source = root / "outputs" / ("yuran-" + name + "-unsigned-qa.apk")
        if not source.is_file():
            raise ValueError("Missing unsigned QA APK for " + name)
        identity = validate_badging(name, invoke(sdk / "aapt2", "dump", "badging", source))
        inputs.append((name, source, identity))

    # Only this private staging area can contain incomplete signed output.
    with tempfile.TemporaryDirectory(prefix=".yuran-qa-stage-", dir=root) as temp:
        staging = pathlib.Path(temp)
        records = []
        for name, source, expected in inputs:
            target = staging / expected["file"]
            invoke(sdk / "apksigner", "sign", "--ks", config["keystore"],
                   "--ks-key-alias", config["alias"], "--ks-pass", "env:" + STORE_PASSWORD_ENV,
                   "--key-pass", "env:" + KEY_PASSWORD_ENV, "--v4-signing-enabled", "false",
                   "--out", target, source)
            signer = validate_signer(invoke(sdk / "apksigner", "verify", "--verbose", "--print-certs", target))
            invoke(sdk / "zipalign", "-c", "-P", "16", "4", target)
            identity = validate_badging(name, invoke(sdk / "aapt2", "dump", "badging", target))
            if identity != expected:
                raise ValueError("Signed APK identity changed from its verified unsigned input")
            records.append(dict(identity, sha256=sha256_file(target),
                                signingCertificateSHA256=signer, bytes=target.stat().st_size))

        # Both APKs have passed signer, package, version, and alignment checks.
        info = {
            "commit": environment["GITHUB_SHA"],
            "purpose": "Existing-signer QA update candidate; device validation required",
            "installedQaVersionCodes": {name: values[1] for name, values in QA_APPS.items()},
            "updateDelivery": "Manual QA artifacts only; no automatic updater is implemented",
            "apks": records,
        }
        (staging / "BUILD-INFO.json").write_text(json.dumps(info, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        (staging / "SHA256SUMS.txt").write_text("".join(r["sha256"] + "  " + r["file"] + "\n" for r in records), encoding="utf-8")
        (staging / "읽어주세요.txt").write_text("""유란노트 / 유란사진 QA 업데이트 후보
1. 기존 QA 앱과 같은 패키지·서명을 사용하며 버전 코드는 기존 QA보다 높아야 합니다.
2. 검증된 두 APK를 함께 확인하세요. 자동 다운로드·설치 기능은 구현되지 않았습니다.
3. 기존 앱 삭제 또는 실제 재고 백업 가져오기를 하지 마세요.
4. 기존 QA 서명키가 없으면 이 묶음은 생성되지 않습니다. 새 시험키로 대체하지 않습니다.
5. 합성 자료로 업데이트와 기존 자료 유지, 사진 선택·촬영·OCR·엑셀 동작을 별도로 확인해야 합니다.
6. 정식 앱 덮어쓰기 배포는 대상이 아닙니다.
소스와 APK 해시는 BUILD-INFO.json / SHA256SUMS.txt에 있습니다.
""", encoding="utf-8")
        if out.exists() or out.is_symlink():
            raise ValueError("qa-download appeared during verification; refusing to overwrite it")
        staging.rename(out)
    return info


def main():
    try:
        info = package_pair(pathlib.Path.cwd(), os.environ)
    except (ValueError, OSError) as failure:
        print("QA packaging blocked: " + str(failure), file=sys.stderr)
        return 1
    print(json.dumps(info, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
