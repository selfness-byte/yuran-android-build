# QA packaging signing gate

The previously published QA pair used signer SHA-256
`86ad771a9b587e08ae9d7534a16690ffd0d739484ddc7ed677490423ff1354a0`.
The old packager generated a random keystore for each build and deleted it, so a
later pair could not update that existing installation. A certificate fingerprint
cannot recover the missing private signing key.

`cloud/package-qa.py` now requires the existing durable keystore through
`YURAN_QA_KEYSTORE` (absolute path), `YURAN_QA_KEY_ALIAS`,
`YURAN_QA_STORE_PASSWORD`, and `YURAN_QA_KEY_PASSWORD`.
Missing configuration, an unavailable keystore, or any different certificate
blocks packaging before APK signing or creation of download output. The script
never creates a signing key, supplies a fallback password, or copies a keystore.

The public certificate is exported only to process stdout and checked before
signing. Passwords use environment references, following the official
[Java 17 keytool options](https://docs.oracle.com/en/java/javase/17/docs/specs/man/keytool.html)
and [Android apksigner options](https://developer.android.com/tools/apksigner).
Do not place key material or password values in source, logs, or test fixtures.

Both source APKs must use the existing QA package identities:
- `com.gongyo.orderly.qa`, versionCode greater than 23
- `com.gongyo.yuranphoto.qa`, versionCode greater than 6

Production identities, any different signer, equal/lower versions, multiple
signers, and changed identities after signing are rejected. Both unsigned APKs
are checked before signing starts. Signed APKs and their verification results
remain in private staging until both pass signer, identity, version, and alignment
checks. Only then are metadata files written and the complete directory renamed
to `qa-download`. Existing download directories are never intentionally replaced.
No GitHub release or external publication is performed by this script.

Synthetic checks: `python3 -m unittest discover -s cloud/tests -p 'test_package_qa.py' -v`.
RED evidence before implementation: commit
`1cbbf5a3caaa7eb7975eb0d51ef5c4befeb9bfcf`,
[run 38154578347](https://github.com/selfness-byte/yuran-android-build/actions/runs/38154578347),
job 114514795057: all 14 packaging tests failed at the guarded import-time output
directory creation; the 13 existing publisher tests passed.

These changes prevent creation of another incompatible QA pair. The existing
private signer has not been recovered or supplied, and no signing credentials were
read during this work. Real APK signing, on-device update/data preservation, and
automatic update delivery remain unverified or unavailable. No updater is
implemented. A new key would require a separate migration decision; deleting
installed apps or their data is not a workaround.
