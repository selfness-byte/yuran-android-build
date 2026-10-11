"""Create a matched, isolated pair of installable QA APKs, never a production update."""
import hashlib, json, os, pathlib, re, subprocess, tempfile
root = pathlib.Path.cwd()
out = root / "qa-download"
out.mkdir(exist_ok=True)
sdk = pathlib.Path(os.environ["ANDROID_HOME"]) / "build-tools" / "35.0.0"
jdk = pathlib.Path(os.environ["JAVA_HOME_17_X64"])
def run(*args):
    return subprocess.check_output([str(a) for a in args], text=True, stderr=subprocess.STDOUT)
records = []
with tempfile.TemporaryDirectory(prefix="yuran-qa-sign-") as temp:
    key = pathlib.Path(temp) / "test-only.jks"
    run(jdk / "bin/keytool", "-genkeypair", "-noprompt", "-keystore", key,
        "-alias", "yuranqa", "-keyalg", "RSA", "-keysize", "3072", "-validity", "3650",
        "-storepass", "android", "-keypass", "android", "-dname", "CN=Yuran QA Test Only",
        "-storetype", "JKS")
    for name, package in [("note", "com.gongyo.orderly.qa"), ("photo", "com.gongyo.yuranphoto.qa")]:
        source = root / "outputs" / ("yuran-" + name + "-unsigned-qa.apk")
        target = out / ("yuran-" + name + "-qa.apk")
        run(sdk / "apksigner", "sign", "--ks", key, "--ks-key-alias", "yuranqa",
            "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", target, source)
        verify = run(sdk / "apksigner", "verify", "--verbose", "--print-certs", target)
        signer = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", verify).group(1)
        run(sdk / "zipalign", "-c", "-P", "16", "4", target)
        badging = run(sdk / "aapt2", "dump", "badging", target)
        match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
        if not match or match.group(1) != package:
            raise RuntimeError("Refusing artifact with unexpected production package identity")
        records.append(dict(file=target.name, package=package, versionCode=match.group(2),
                            versionName=match.group(3), sha256=hashlib.sha256(target.read_bytes()).hexdigest(),
                            signingCertificateSHA256=signer, bytes=target.stat().st_size))
if records[0]["signingCertificateSHA256"] != records[1]["signingCertificateSHA256"]:
    raise RuntimeError("Companion and owner must share the same QA signer")
production_signer = "4781bf14ca9fcfd67d3c518dfce01d9c6347d775dd89d222815e21a61206838f"
if records[0]["signingCertificateSHA256"] == production_signer:
    raise RuntimeError("This workflow is QA only")
info = dict(commit=os.environ["GITHUB_SHA"],
            sourceBaseline="d16f9f3b8f87a0b474ee175298e42516835461bc",
            purpose="Isolated synthetic-data/device QA; not a production replacement",
            originalSourceVersions=dict(note="1.17.1", photo="1.4.0"), apks=records)
(out / "BUILD-INFO.json").write_text(json.dumps(info, indent=2, ensure_ascii=False) + "\n")
(out / "SHA256SUMS.txt").write_text("".join(r["sha256"] + "  " + r["file"] + "\n" for r in records))
(out / "읽어주세요.txt").write_text("""유란노트 / 유란사진 검사용 APK
1. 유란노트 검사용 APK를 먼저, 유란사진 검사용 APK를 다음에 설치하세요.
2. 검사용 앱은 기존 유란노트/유란사진과 따로 설치되며 기존 재고를 읽지 않습니다.
3. 합성 품번과 수량으로 사진 선택/카메라/엑셀 동작을 먼저 확인하세요.
4. 기존 앱 삭제 또는 실제 재고 백업 가져오기는 하지 마세요. 현재 배포 자료 형식과 다릅니다.
5. 이 빌드는 두 APK가 같은 임시 시험 서명을 사용합니다. 다른 시험 빌드와의 업데이트 호환성은 보장하지 않습니다.
6. 정식 덮어쓰기 배포에는 기존 서명 경로와 현재 자료 형식 호환 작업이 필요합니다.
소스와 APK 해시는 BUILD-INFO.json / SHA256SUMS.txt에 있습니다.
실제 Galaxy S24 Ultra의 초점·색상/사이즈 OCR·회전·완료음과 PC 엑셀 표시 검증은 별도로 필요합니다.
""")
print(json.dumps(info, indent=2))
