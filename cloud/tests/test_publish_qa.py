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

    def test_interrupted_draft_resumes_without_second_creation(self):
        from unittest.mock import patch
        from contextlib import redirect_stdout
        target = "c" * 40
        data = {"yuran-note-qa.apk": b"synthetic note", "yuran-photo-qa.apk": b"synthetic photo"}
        def asset(name):
            return {"name": name, "size": len(data[name]), "state": "uploaded",
                    "digest": "sha256:" + qa.sha(data[name]),
                    "browser_download_url": "https://github.com/" + qa.REPO + "/releases/download/" + qa.TAG + "/" + name}
        release = {"id": 7, "tag_name": qa.TAG, "target_commitish": target,
                   "draft": True, "prerelease": True, "assets": [asset("yuran-note-qa.apk")],
                   "upload_url": "https://uploads.github.com/repos/" + qa.REPO + "/releases/7/assets{?name,label}",
                   "html_url": "https://github.com/" + qa.REPO + "/releases/tag/" + qa.TAG}
        class Client:
            creates = 0
            uploads = 0
            def json(self, path, method="GET", body=None, missing=False):
                if path == "/git/ref/heads/main":
                    return {"object": {"sha": target}}
                if path.startswith("/git/ref/tags/") or path.startswith("/releases/tags/"):
                    return None
                if path.startswith("/releases?"):
                    return [release]
                if path == "/releases" and method == "POST":
                    self.creates += 1
                    raise AssertionError("Existing draft must be resumed")
                if path == "/releases/7":
                    if method == "PATCH":
                        release.update(body)
                    return release
                raise AssertionError(path)
            def request(self, url, method, body, media):
                self.uploads += 1
                name = qa.urllib.parse.parse_qs(qa.urllib.parse.urlsplit(url).query)["name"][0]
                self.assert_body = body
                value = asset(name)
                release["assets"].append(value)
                return io.BytesIO(json.dumps(value).encode())
        client = Client()
        environment = {"GITHUB_REPOSITORY": qa.REPO, "GITHUB_REF": "refs/heads/main", "GITHUB_SHA": target}
        with patch.dict(qa.os.environ, environment), patch.object(qa, "APK_HASHES", {n: qa.sha(d) for n, d in data.items()}), patch.object(qa, "APK_SIZES", {n: len(d) for n, d in data.items()}), patch.object(qa.urllib.request, "urlopen", side_effect=lambda url, timeout: io.BytesIO(data[url.rsplit("/", 1)[1]])), redirect_stdout(io.StringIO()):
            qa.publish(client, data)
        self.assertEqual(client.creates, 0)
        self.assertEqual(client.uploads, 1)
        self.assertFalse(release["draft"])

    def test_known_v4_signature_sidecar_allowed(self):
        self.assertEqual(set(self.check(self.fixture(extra="yuran-note-qa.apk.idsig"))), set(qa.ALLOWED))

    def test_draft_lookup_reads_later_pages(self):
        draft = {"id": 7, "tag_name": qa.TAG, "draft": True}
        class Client:
            def json(self, path):
                if path.endswith("page=1"):
                    return [{"tag_name": "unrelated-" + str(i)} for i in range(100)]
                return [draft]
        self.assertEqual(qa.find_release(Client()), draft)

    def test_ambiguous_drafts_rejected(self):
        class Client:
            def json(self, path):
                return [{"tag_name": qa.TAG, "id": 1}, {"tag_name": qa.TAG, "id": 2}]
        with self.assertRaises(ValueError):
            qa.find_release(Client())
