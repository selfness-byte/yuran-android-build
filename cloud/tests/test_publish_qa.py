import importlib.util
import io
import json
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("publish_qa", Path(__file__).parents[1] / "publish-qa.py")
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)

class PublishChecks(unittest.TestCase):
    def fixture(self, change=None, extra=None):
        apks = {name: ("synthetic " + name).encode() for name in qa.APK_HASHES}
        info = {"commit": qa.SOURCE, "apks": [
            {"file": name, "package": qa.PACKAGES[name], "bytes": len(data),
             "sha256": qa.sha(data), "signingCertificateSHA256": qa.CERT}
            for name, data in apks.items()]}
        if change:
            change(info)
        files = dict(apks)
        files["BUILD-INFO.json"] = json.dumps(info).encode()
        files["SHA256SUMS.txt"] = "".join(qa.sha(data) + "  " + name + "\n" for name, data in apks.items()).encode()
        files["읽어주세요.txt"] = "합성자료 검사용".encode()
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            for name, data in files.items():
                archive.writestr(name, data)
            if extra:
                archive.writestr(extra, b"unexpected")
        return buffer.getvalue(), {n: qa.sha(d) for n, d in apks.items()}, {n: len(d) for n, d in apks.items()}

    def check(self, bundle):
        data, hashes, sizes = bundle
        return qa.validate_bundle(data, hashes, sizes)

    def test_complete_matched_pair(self):
        self.assertEqual(set(self.check(self.fixture())), set(qa.ALLOWED))

    def test_wrong_source(self):
        with self.assertRaises(ValueError):
            self.check(self.fixture(lambda i: i.update(commit="0" * 40)))

    def test_production_package_rejected(self):
        with self.assertRaises(ValueError):
            self.check(self.fixture(lambda i: i["apks"][0].update(package="com.gongyo.orderly")))

    def test_signer_mismatch_rejected(self):
        with self.assertRaises(ValueError):
            self.check(self.fixture(lambda i: i["apks"][1].update(signingCertificateSHA256="0" * 64)))

    def test_changed_apk_hash_rejected(self):
        data, hashes, sizes = self.fixture()
        hashes["yuran-note-qa.apk"] = "0" * 64
        with self.assertRaises(ValueError):
            qa.validate_bundle(data, hashes, sizes)

    def test_unexpected_archive_path_rejected(self):
        with self.assertRaises(ValueError):
            self.check(self.fixture(extra="../escaped.apk"))

    def test_duplicate_file_rejected(self):
        with self.assertRaises(ValueError):
            self.check(self.fixture(extra="yuran-note-qa.apk"))

    def test_existing_asset_must_match(self):
        data = b"synthetic"
        good = {"size": len(data), "state": "uploaded", "digest": "sha256:" + qa.sha(data)}
        qa.require_matching_asset(good, data)
        with self.assertRaises(ValueError):
            qa.require_matching_asset(dict(good, digest="sha256:" + "0" * 64), data)

    def test_only_selected_repo_main_can_publish(self):
        qa.require_publish_target(qa.REPO, "refs/heads/main")
        for repo, ref in [(qa.REPO, "refs/heads/codex/yuran-repairs-20261011"), ("unrelated/project", "refs/heads/main")]:
            with self.assertRaises(ValueError):
                qa.require_publish_target(repo, ref)
