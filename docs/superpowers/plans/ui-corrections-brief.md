# UI capture corrections — implementation brief

Repository selfness-byte/yuran-android-build; implementation starts from root controller commit supplied at dispatch, on a NEW isolated branch codex/yuran-ui-fixes-20261011. Never mutate main/controller or unrelated repos. User approved fixes/deployment; real device/source environment failed, use GitHub APIs and Actions. Read cloud instructions/plan/code only, no actual user images/inventory/secrets. All tests/data MUST be synthetic.

Read review package at fcce0ab76cd3c18e8c02f94cd818d66228d83991 docs/superpowers/plans/ui-review.diff (UI introduced ac142ae4). Five source-confirmed review blockers:
1. LiveLabelActivity creates fresh CaptureGate per Activity, so rotation/recreation re-arms same physical label and fresh frame hash can double stock.
2. saveFrame clears saving before owner.submit, then unconditional finally clears another capture's reservation. Single ownership needed.
3. CaptureInbox.save dedupe generation+sourceHash+mode omits targetWorkId, reuses work A under B.
4. IntakeActivity file lookup's requested stock result is replaced by generic completion text.
5. Multi-file selected URIs/remaining batch only live in closure. Process death loses unfinished imports, even though partial URI permissions obtained.

Deliver implementation+meaningful synthetic tests on your isolated branch. You own ONLY:
- work/photo-android/src/com/gongyo/yuranphoto/{CaptureInbox,LiveLabelActivity,IntakeActivity,LabelRecognition}.java
- any narrowly necessary new helper classes under that package or work/photo-contract/src/com/gongyo/photo/
- NEW separate synthetic test classes under cloud/tests/java (do not rewrite RegressionSuite)
- cloud/test-regressions.sh ONLY append new test main commands as needed (preserve existing).
- docs/superpowers/plans/ui-corrections-report.md
- your own .github/workflows/yuran-repairs.yml copy ONLY branch filter/concurrency to your branch/group. Root will NOT import your workflow.
Do not modify core protocol/owner/parser files handled by core task, MainActivity/export preview, production or release repo.

Behavior and acceptance:
- Preserve capture gate and in-flight ownership across rotation. Recover saved same-label suppression after process recreation from durable capture session/inbox identity. Frozen generation/target must survive too. Do not share Activity references across rotation.
- Consider retaining a small session holder (gate + atomic busy + frozen context); persist camera-session identity before processing and enough suppression state atomically with successful photo index publication. Blank/different stable label or explicit same-SKU next action re-arms. New saved session suppresses until observed valid transition. Match CaptureGate's merged pending semantics; errors cannot silently create repeated captures. Keep busy for entire operation if simplest. Audio only after durable photo publication, not inferred owner success.
- Source-file queue must be durable BEFORE processing any item, includes generation, target, mode, URI/durable-source identity; persist grants before queue publication. If provider cannot persist grants, durable private copy (bounded) or clearly stop/retain requiring reselection; do not claim recoverability while only temporary permission exists. Stable source hash/request identity and queue advancement AFTER durable inbox publication make crash retries dedupe. Do not discard unprocessed/failed items silently. Resume pending queue, release grants only when terminal safely saved; respect limits and do not delete user content. Recovery of an uncertain submitted owner request always reuses immutable id/content; no retarget.
- Include targetWorkId in local photo dedupe. For definitive stale-work/generation rejection, explain reselection/explicit new import; do not silently retarget uncertain request. UI can retain rejected historical record, but new selection in B must create B-bound record.
- Show durable stock lookup result for single file; for multiple files keep each associated result accessible. Lookup never registers inventory.
- ML Kit geometry helper currently joins separate exact size/digit blocks on same row. If feasible extract pure geometry into helper and add synthetic tests for right annotation pairing, duplicate labels, price/95(M)/size range excluded, ambiguous/multiple candidates fail closed. No false positive addition from ambiguous geometry.
- Existing camera transform default portrait stretch should preserve aspect if code change is straightforward, but no claimed S24 device validation.
- Keep all storage bounded; generation/target/request IDs unchanged after submission. Work to completion, run full synthetic runner + both APK compile on own branch; do not deliver build artifacts or production deploy. Final report exact commit/files/tests plus remaining physical-device checks. Root will import allowed files at immutable SHA and run combined CI.
