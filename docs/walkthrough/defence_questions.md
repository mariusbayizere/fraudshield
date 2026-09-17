# Defence questions

Hard questions a reviewer is likely to ask, with short answers that point to evidence.
Updated at the end of every milestone.

## M0 — governance and requirements

**Q: Your SRS says precision at 1% FPR must be at least 0.72. Why did you change a requirement?**
Because it is mathematically unreachable. At FPR 1%, false positives are 1% of the 99.13%
legitimate traffic (0.99% of all traffic), while true positives can be at most the 0.87% that
is fraud. Precision ≤ 0.0087 / (0.0087 + 0.009913) = 0.467 even with perfect recall. The gate
became recall at 1% FPR, and precision is still reported beside its ceiling.
Evidence: `ml/tests/metrics/test_operating_points.py`, D-01.

**Q: Recall ≥ 0.88 and F1 ≥ 0.80 — at what threshold, and are they consistent with each other?**
Both are measured at the flag threshold 0.60 (D-02). Together they imply precision 0.733 and an
FPR of about 0.28% at that threshold, which is consistent with the FPR < 1.5% target measured at
the block threshold 0.85. Evidence: `test_implied_operating_point_for_srs_recall_and_f1_targets`.

**Q: How do I know a requirement marked done was actually tested?**
Tests are linked to requirement IDs by tags in code, not by a hand-maintained list. CI's
traceability check fails if a row is marked complete without evidence and a tagged test, if a
tag names an unknown requirement, or if a closed milestone still has open Must rows.
Evidence: `tools/src/fraudshield_tools/traceability.py`, `tools/tests/test_traceability.py`, ADR 0004.

**Q: The SRS names Spring Boot 3, React 18 and MUI v5. Why are you not using them?**
Their supported lines had ended by 2026-09-17 (Spring Framework 6.2 end of life 2026-06-30; React
18 active support ended 2024; last MUI 5 release July 2025). A fraud-prevention system should not
ship on frameworks that no longer receive security fixes. Each substitution and the evidence for
it is in ADR 0003.

**Q: Why Redis 7.2 and not the newest Redis?**
Redis 7.4 onward is not under an OSI-approved permissive licence; 7.2 is the last BSD-3 line and
still receives patches (7.2.16, August 2026). ADR 0003.

**Q: Section 05B of your SRS describes a different, non-financial domain. Is this system reused from somewhere?**
No. That text was copied into the requirements document from a separate project by the same
author. Those rows were rewritten to their FraudShield equivalents during seeding (D-47), and a CI
check fails if that vocabulary appears anywhere in the codebase.
Evidence: `tools/tests/test_scope_and_registers.py`.

**Q: How are amounts in Rwandan francs versus Kenyan shillings handled?**
Exactly, never as floating point: `DECIMAL(18,4)` storage, and display rounding to each
currency's ISO 4217 minor unit (RWF and UGX 0 decimals; KES, TZS and CDF 2). The in-code table is
tested against the JDK's ISO data. Evidence: `MoneyTest`, D-43.
