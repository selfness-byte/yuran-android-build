# Yuran photo intake and repair plan

> Execute in the isolated codex/yuran-repairs-20261011 branch using test-driven-development and executing-plans. User authorized implementation and deployment, including using the available 1.17.1 baseline.

Goal: retain complete product numbers, distinguish garment sizes from stock quantities, improve capture flow and visibility, and deliver verified app updates.

Baseline: d16f9f3b8f87a0b474ee175298e42516835461bc (note 1.17.1/code 23). Published binaries are note 1.19.59 and photo 1.7.37. User accepted loss of later features in the old-source replacement. No inventory migration or data deletion is authorized.

Architecture: retain owner repository / photo companion separation. Parse labels and quantity annotations in the shared pure-Java photo contract. Persist captures before success audio and next-image navigation. Only the owner app changes stock, with a stable request ID and one atomic intake commit. Missing or ambiguous quantities/color remain pending for review.

Tech stack: Java 8-compatible source, Android SDK 35, bundled ML Kit and existing ZXing; JDK 17 for QA.

Spec: user requests in this task. Garment sizes are the meaning of 호칭. Clear file-photo counts register intake; SKU capture saves a durable item, plays completion audio, then permits another image. Capture saved and intake committed must be distinct states.

## Global constraints
- Read only project source/instructions; no real device inventory, authentication files or unrelated projects.
- Supplied photos may be interpreted in chat, but never publish those photos, stock counts or product IDs as public test fixtures.
- Use synthetic catalogue and labels in CI. Keep unsigned QA isolation until release signing/update compatibility is verified.
- The local executor failed. Use official GitHub tools and branch QA; compilation does not prove device/OCR/Excel runtime behavior.
- Baseline has spreadsheet export but no PC-send spreadsheet-preview screen; inspect drawing/media and preview the same frozen export.

## Review focus
- Garment notation, size ranges, prices and barcode suffixes must never become intake quantities.
- Repeated label copies do not duplicate quantities; conflicting counts require review.
- Preserve SKU suffixes and multiword colors.
- Sound/next capture follow durable save; retries/recreation do not duplicate intake.
- Newer state/signing compatibility is unknown; never force a data downgrade.

## Tasks
- [ ] Add red JVM regressions with production Inventory/LabelMatcher and synthetic provider boundaries. Run cloud/test-regressions.sh and inspect failure output.
- [ ] Implement Inventory.totalForSku(List<Row>, String), connect a header next to quick adjustment, verify selection/quantity refresh and long totals.
- [ ] Implement com.gongyo.photo.IntakeText.parse(String), returning sku, color, quantities[4], ready and warnings. Preserve label suffixes/multiword colors; exclude explicit size spans. Verify regressions and compile both apps.
- [ ] Add owner request/receipt handling, companion file import and durable SKU capture queue. Test retry/conflict before implementing.
- [ ] Address touch/insets, overlap OCR/barcodes and bounded high-resolution label decoding. Verify workbook drawing/media with synthetic images and frozen-export preview.
- [ ] Review, build, check signature/package/version/state compatibility, then publish a verified installation artifact.

## Progress
- Isolated remote branch created.
- Existing Android QA compile passed: https://github.com/selfness-byte/yuran-android-build/actions/runs/38141476467
- No product implementation or new deployment yet.
