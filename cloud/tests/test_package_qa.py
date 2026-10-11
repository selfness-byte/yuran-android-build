"""Synthetic packaging guards; never access a real keystore or invoke Android tools."""
import base64
import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = pathlib.Path(__file__).parents[1] / "package-qa.py"
EXPECTED = "86ad771a9b587e08ae9d7534a16690ffd0d739484ddc7ed677490423ff1354a0"
PRODUCTION = "4781bf14ca9fcfd67d3c518dfce01d9c6347d775dd89d222815e21a61206838f"


def load_qa():
    spec = importlib.util.spec_from_file_location("package_qa_test", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    with patch("pathlib.Path.mkdir", side_effect=AssertionError("Import must not write artifacts")), \
         patch("subprocess.check_output", side_effect=AssertionError("Import must not invoke tools")):
        spec.loader.exec_module(module)
    return module


def badging(name, package=None, version=None):
    packages = {"note": "com.gongyo.orderly.qa", "photo": "com.gongyo.yuranphoto.qa"}
    versions = {"note": 24, "photo": 7}
    return "package: name='%s' versionCode='%s' versionName='synthetic-test'\n" % (
        package or packages[name], versions[name] if version is None else version)


def cert_output(signer=EXPECTED):
    return "Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: " + signer + "\n"


class SyntheticTools:
    def __init__(self, unsigned_change=None, signed_change=None, signer_change=None):
        self.calls = []
        self.unsigned_change = unsigned_change or {}
        self.signed_change = signed_change or {}
        self.signer_change = signer_change or {}

    def __call__(self, *args):
        args = [str(value) for value in args]
        self.calls.append(args)
        tool = pathlib.Path(args[0]).name
        if tool == "aapt2":
            path = pathlib.Path(args[-1])
            name = "note" if "note" in path.name else "photo"
            changes = self.unsigned_change if "unsigned" in path.name else self.signed_change
            return badging(name, **changes.get(name, {}))
        if tool == "apksigner" and args[1] == "sign":
            target = pathlib.Path(args[args.index("--out") + 1])
            target.write_bytes(("synthetic signed " + target.name).encode())
            return ""
        if tool == "apksigner" and args[1] == "verify":
            name = "note" if "note" in args[-1] else "photo"
            return cert_output(self.signer_change.get(name, EXPECTED))
        if tool == "zipalign":
            return ""
        raise AssertionError("Unexpected tool or attempted key generation: " + repr(args))


class PackageChecks(unittest.TestCase):
    def setUp(self):
        self.qa = load_qa()

    def environment(self, folder):
        return {
            "YURAN_QA_KEYSTORE": str(folder / "synthetic-existing.jks"),
            "YURAN_QA_KEY_ALIAS": "existing-qa",
            "YURAN_QA_STORE_PASSWORD": "synthetic-store-secret",
            "YURAN_QA_KEY_PASSWORD": "synthetic-key-secret",
            "ANDROID_HOME": str(folder / "synthetic-sdk"),
            "JAVA_HOME_17_X64": str(folder / "synthetic-jdk"),
            "GITHUB_SHA": "a" * 40,
        }

    def fixtures(self, folder):
        env = self.environment(folder)
        pathlib.Path(env["YURAN_QA_KEYSTORE"]).write_bytes(b"synthetic placeholder, not a keystore")
        sources = folder / "outputs"
        sources.mkdir()
        for name in ("note", "photo"):
            (sources / ("yuran-" + name + "-unsigned-qa.apk")).write_bytes(b"synthetic unsigned")
        return env

    def package(self, folder, env, tools, signer=EXPECTED):
        with patch.object(self.qa, "keystore_signer", return_value=signer):
            return self.qa.package_pair(folder, env, runner=tools)

    def test_import_has_no_signing_or_directory_side_effects(self):
        self.assertEqual(self.qa.EXPECTED_SIGNER, EXPECTED)

    def test_missing_credentials_block_before_output_or_tool_calls(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = pathlib.Path(temp)
            tools = SyntheticTools()
            with self.assertRaisesRegex(ValueError, "YURAN_QA_KEYSTORE"):
                self.qa.package_pair(folder, {}, runner=tools)
            self.assertEqual(list(folder.iterdir()), [])
            self.assertEqual(tools.calls, [])

    def test_each_credential_required_with_no_default_password(self):
        with tempfile.TemporaryDirectory() as temp:
            env = self.environment(pathlib.Path(temp))
            for name in ("YURAN_QA_KEYSTORE", "YURAN_QA_KEY_ALIAS",
                         "YURAN_QA_STORE_PASSWORD", "YURAN_QA_KEY_PASSWORD"):
                for value in (None, ""):
                    incomplete = dict(env)
                    if value is None:
                        incomplete.pop(name)
                    else:
                        incomplete[name] = value
                    with self.subTest(name=name, value=value), self.assertRaisesRegex(ValueError, name):
                        self.qa.signing_config(incomplete)

    def test_relative_keystore_path_rejected(self):
        env = self.environment(pathlib.Path("/synthetic"))
        env["YURAN_QA_KEYSTORE"] = "new-test.jks"
        with self.assertRaisesRegex(ValueError, "absolute"):
            self.qa.signing_config(env)

    def test_config_contains_environment_references_not_password_values(self):
        config = self.qa.signing_config(self.environment(pathlib.Path("/synthetic")))
        self.assertNotIn("synthetic-store-secret", repr(config))
        self.assertNotIn("synthetic-key-secret", repr(config))

    def test_missing_keystore_never_generates_replacement(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = pathlib.Path(temp)
            tools = SyntheticTools()
            with self.assertRaisesRegex(ValueError, "keystore"):
                self.qa.package_pair(folder, self.environment(folder), runner=tools)
            self.assertEqual(list(folder.iterdir()), [])
            self.assertEqual(tools.calls, [])

    def test_public_certificate_digest_uses_der_bytes(self):
        der = b"synthetic-public-certificate-only"
        pem = "-----BEGIN CERTIFICATE-----\n" + base64.b64encode(der).decode() + "\n-----END CERTIFICATE-----\n"
        self.assertEqual(self.qa.certificate_digest(pem), hashlib.sha256(der).hexdigest())
        for invalid in ("", "not a certificate", pem + pem):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.qa.certificate_digest(invalid)

    def test_package_and_strictly_newer_version_required_for_both_apps(self):
        for name, previous in (("note", 23), ("photo", 6)):
            record = self.qa.validate_badging(name, badging(name))
            self.assertGreater(int(record["versionCode"]), previous)
            for version in (0, previous - 1, previous, "not-a-number", 2100000001):
                with self.subTest(name=name, version=version), self.assertRaises(ValueError):
                    self.qa.validate_badging(name, badging(name, version=version))
            for package in ("com.gongyo.orderly", "com.gongyo.yuranphoto", "unrelated.qa"):
                with self.subTest(name=name, package=package), self.assertRaises(ValueError):
                    self.qa.validate_badging(name, badging(name, package=package))

    def test_only_existing_single_qa_signer_is_accepted(self):
        self.assertEqual(self.qa.validate_signer(cert_output()), EXPECTED)
        for signer in (PRODUCTION, "0" * 64, "bad-digest"):
            with self.subTest(signer=signer), self.assertRaises(ValueError):
                self.qa.validate_signer(cert_output(signer))
        multi = cert_output().replace("Number of signers: 1", "Number of signers: 2")
        multi += "Signer #2 certificate SHA-256 digest: " + EXPECTED + "\n"
        with self.assertRaises(ValueError):
            self.qa.validate_signer(multi)

    def test_wrong_keystore_signer_blocks_before_signing_or_download_directory(self):
        for signer in (PRODUCTION, "0" * 64):
            with tempfile.TemporaryDirectory() as temp:
                folder = pathlib.Path(temp)
                env = self.fixtures(folder)
                tools = SyntheticTools()
                with self.assertRaises(ValueError):
                    self.package(folder, env, tools, signer=signer)
                self.assertEqual(tools.calls, [])
                self.assertFalse((folder / "qa-download").exists())
                self.assertEqual(sorted(p.name for p in folder.iterdir()), ["outputs", "synthetic-existing.jks"])

    def test_both_unsigned_identities_checked_before_first_sign(self):
        for changes in ({"photo": {"package": "com.gongyo.yuranphoto"}},
                        {"photo": {"version": 6}}, {"note": {"version": 23}}):
            with tempfile.TemporaryDirectory() as temp:
                folder = pathlib.Path(temp)
                env = self.fixtures(folder)
                tools = SyntheticTools(unsigned_change=changes)
                with self.assertRaises(ValueError):
                    self.package(folder, env, tools)
                self.assertFalse(any(args[1] == "sign" for args in tools.calls))
                self.assertFalse((folder / "qa-download").exists())

    def test_second_apk_failure_leaves_no_download_or_manifest(self):
        for changes in ({"signer_change": {"photo": "0" * 64}},
                        {"signed_change": {"photo": {"package": "com.gongyo.yuranphoto"}}},
                        {"signed_change": {"photo": {"version": 6}}}):
            with tempfile.TemporaryDirectory() as temp:
                folder = pathlib.Path(temp)
                env = self.fixtures(folder)
                tools = SyntheticTools(**changes)
                with self.assertRaises(ValueError):
                    self.package(folder, env, tools)
                self.assertFalse((folder / "qa-download").exists())
                self.assertEqual(sorted(p.name for p in folder.iterdir()), ["outputs", "synthetic-existing.jks"])

    def test_success_uses_env_passwords_and_only_publishes_verified_pair(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = pathlib.Path(temp)
            env = self.fixtures(folder)
            tools = SyntheticTools()
            result = self.package(folder, env, tools)
            out = folder / "qa-download"
            info = json.loads((out / "BUILD-INFO.json").read_text())
            self.assertEqual(info, result)
            self.assertEqual(len(info["apks"]), 2)
            self.assertEqual({r["signingCertificateSHA256"] for r in info["apks"]}, {EXPECTED})
            signing = [args for args in tools.calls if args[1] == "sign"]
            self.assertEqual(len(signing), 2)
            for args in signing:
                self.assertEqual(args[args.index("--ks-pass") + 1], "env:YURAN_QA_STORE_PASSWORD")
                self.assertEqual(args[args.index("--key-pass") + 1], "env:YURAN_QA_KEY_PASSWORD")
                self.assertNotIn("synthetic-store-secret", args)
                self.assertNotIn("synthetic-key-secret", args)
                self.assertNotIn("keytool", args[0])
            first_sign = next(i for i, args in enumerate(tools.calls) if args[1] == "sign")
            self.assertEqual(sum(pathlib.Path(args[0]).name == "aapt2" for args in tools.calls[:first_sign]), 2)
            self.assertFalse(any("-genkeypair" in args for args in tools.calls))
            self.assertTrue((out / "SHA256SUMS.txt").is_file())
            self.assertTrue((out / "읽어주세요.txt").is_file())

    def test_existing_download_directory_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = pathlib.Path(temp)
            env = self.fixtures(folder)
            out = folder / "qa-download"
            out.mkdir()
            marker = out / "preserve.txt"
            marker.write_text("existing download")
            tools = SyntheticTools()
            with self.assertRaises(ValueError):
                self.package(folder, env, tools)
            self.assertEqual(marker.read_text(), "existing download")
            self.assertEqual(tools.calls, [])


if __name__ == "__main__":
    unittest.main()
