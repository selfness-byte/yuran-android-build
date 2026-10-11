# SDD ledger — plan: docs/superpowers/plans/2026-10-11-yuran-repairs.md

Ruling: use isolated remote branches because the selected executor failed; no shell/worktree scripts are callable. GitHub Actions verifies pinned commits. No local or production data access.

| Tasks / interface | Check and ruling |
|---|---|
| Totals / main header | Pure long total feeds selected SKU header; 14 regressions and main compile passed. |
| Parsing / intake | Ambiguous fields must not become automatic stock; five review findings remain under correction. |
| Owner / companion | Both sides use generation + targetWorkId + requestId + sourceHash + SKU/color/counts; target frozen at preparation. |
| Capture / owner | Durable photo+metadata before success sound; owner commit follows separately; lookup never mutates stock. |
| Preview / export | Preview and XLSX must read the same frozen ExportSnapshot and pinned MediaSource. |
| Deployment / state | Published note1.19.59/code90 uses schema14 and certificate4781bf14ca9fcfd67d3c518dfce01d9c6347d775dd89d222815e21a61206838f. This QA source starts schema11. Product replacement requires compatibility and original signing path, not deleting old state. |
| Core tests | 21 passed on32c7d1c; both unsigned Android apps compiled. Red review regressions3752a19 andfedf4a9 are intentional before correction. |
| UI tests | No implementation-mirroring UI tests; validate compile, targeted pure state/capture/ZIP tests, and list device-only checks. |

## Task: core corrections and continuous-capture gate (requirements)
Base fedf4a90a2791bbb4e168932076bf21a3765401e.
Use separate branch codex/yuran-intake-fixes-20261011. Never edit main or the controller branch. No subagents.

Fix:
1. IntakeText must flag malformed visible size quantities (M3/4, M1000000, M -3) as not ready; do not silently register other partial counts.
2. Stop color at next inline field/annotation. COLOR: BLUE SIZE: 95(M) must yield BLUE.
3. Full explicit SKU wins over shorter catalog prefix. STYLE: QA100-R2 with catalog QA100 must retain QA100-R2, without a base candidate.
4. Preserve intake receipts with restored stock. Request generation is still checked, but content fingerprint excludes generation. Restore new generation + same photo/content adds nothing, changed counts conflict; old generation rejects.
5. Bind to current stock work. beginIntake initializes empty draft.stockSessionId atomically and returns current generation/targetWorkId. registerIntake checks existing receipt then validates current target for new mutations. Target work is included in fingerprint. Test delayed request after work switch and response retry for already committed work.
6. Implement CaptureGate pure Java with observe(String sku,long nowMillis): require two consistent nonempty reads at least300ms apart; saved(String sku) suppresses repeats of same label; missing label continuously>=600ms or a stable different SKU permits next. reset() is explicit user next/retry control. Empty/alternating observations cannot capture. Use the committed reflection regression; add edge cases.

Exact interfaces consumed by controller:
- IntakeContract.fingerprint(String generation,String targetWorkId,String hash,String sku,String color,long[] counts).
- IntakeRegistration.begin(InventoryRepository repo) -> InventoryRepository.Snapshot (ensure nonempty current draft.stockSessionId; fail if completed).
- IntakeRegistration.register(InventoryRepository repo,String generation,String targetWorkId,String id,String hash,String sku,String color,long[] counts) -> existing Result.
- PhotoClient.beginIntake() -> Bundle with generation,targetWorkId,revision,status,reason.
- PhotoClient.registerIntake(String generation,String targetWorkId,String requestId,String sourceHash,String sku,String color,long[] quantities) -> Bundle.
- CaptureGate.observe, saved, reset as above, package com.gongyo.photo.
- PhotoBundleCodec permits only the new exact fields/method. getStock remains read-only.

Global constraints: Java8/SDK35; source+synthetic data only, no user photos/values in repo; QA packages only; user authorized old-source work and deployment but no state destruction. Never create/install production-signed replacement without original key/migration.

Allowed files: core parsing/matching/contract/provider/client/repository and regression suite. Also your workflow copy to trigger only your branch and its concurrency group; controller will not import this workflow. Do not edit MainActivity, PhotoActivity, LabelScanActivity or LabelRecognition (controller works there).
CI: use .github/workflows/yuran-repairs.yml on your branch with branch filter and concurrency group changed to your isolated branch. Existing hydrate script now works. Both app builds pass on32c7d1c. Run/inspect red logs first where available, then green exact revision. Pin ZXing SHA25671de5d89341b5fcf5dd89da7f44e84d825d0e084cdf3ec77c9abe26b0f0ceb13 only if touching your build script; no dependency version updates.
Report: docs/superpowers/plans/core-corrections-report.md. Include changed paths, exact commit/test run, findings addressed and remaining device/migration limits. Return concise DONE/BLOCKED and commit; no code dump. No subagents.
