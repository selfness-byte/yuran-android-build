# Core corrections

Transferred from core_corrections agent as exact patches because its mutation calls stalled. Root applied and published; sources inspected at114b4e3. Red run38144425570 job114484691687:21pass/5fail. Pending final combined CI.

Fixes: malformed count annotations require review; color stops at field markers; explicit full SKU excludes shorter catalog prefix; restored stock keeps intake receipts; request fingerprint includes frozen work identity and excludes generation while requests still validate current generation. beginIntake freezes/creates work identity atomically. CaptureGate reserves a stable SKU once until saved/reset and supports restored last-SKU suppression.

Same source photo in a different work remains CONFLICT after prior successful registration. Existing receipt retry is checked before current-work conflict, so a lost success response from workA can still be acknowledged after switching work. No silent retargeting.

Seven additional synthetic regression cases cover malformed counts, inline fields, begin/failed persistence/completed work, delayed/retried work, restore, reservation timing and restored suppression. Actual Android camera lifecycle is a separate UI task.
