# UI corrections

Five source-confirmed blockers addressed by app-owned CaptureSession (no Activity references), checked camera reservation/suppression journal, token ownership through complete owner exchange, target-aware inbox deduplication, persistent bounded file queue with upfront owned grants and frozen work identity, and durable per-photo lookup summaries. Queue retries reuse saved entry IDs; failed heads remain until retry or explicit cancellation. Journal CommitUncertain fences both camera/queue operations until a fresh checked reopen. Explicit-SKU capture readiness precedes optional bounded generation-consistent catalog load.

Red evidence:034e91de/run38147501586/job114493825842 required CaptureSession absent (5javac errors). Synthetic checks: CaptureSessionChecks,CaptureRecoveryChecks; combined final CI follows. Root applied exact subagent transfers because agent mutation calls stall. Cancellation holds recovery lock while releasing owned grants before durable queue removal; source images and inbox requests remain intact.

Independent parser re-review added two boundary regressions:275f033/run38147654215 had34pass2fail; fix e3bfac6 requires review for unreadable explicit counts and quantities following color. Numeric whole color codes remain valid; printed size ranges remain excluded.

Limits: no actual Camera2/S24/audio/PC Excel device validation. Geometry helper still uses original row pairing and preview aspect behavior; production replacement still requires schema14 compatibility and original signing. QA artifacts use separate package IDs and one matched temporary signer. All automated fixtures are synthetic.
