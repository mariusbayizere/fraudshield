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
Tests are linked to requirement IDs by tags in code, not by a hand-maintained list, and
comments or disabled tests do not count. CI fails if a row is marked complete without a tagged
test, if its evidence is free text rather than an existing file, commit or CI run, if a tag
names an unknown requirement, if a closed milestone still has open Must rows, or if anyone edits
a requirement's SRS-derived text, priority or milestone by hand. One honest limit: source
inspection cannot see a test skipped at runtime; counting only passed tests from executed CI
reports replaces it in M9.
Evidence: `tools/src/fraudshield_tools/traceability.py`, `tools/tests/test_traceability.py`, ADR 0004.

**Q: The SRS names Spring Boot 3, React 18 and MUI v5. Why are you not using them?**
Their supported lines had ended by 2026-09-17 (Spring Framework 6.2 end of life 2026-06-30; React
18 active support ended 2024; last MUI 5 release July 2025). A fraud-prevention system should not
ship on frameworks that no longer receive security fixes. Each substitution and the evidence for
it is in ADR 0003.

**Q: Why Redis 7.2 and not the newest Redis?**
Redis 7.4 onward is not under an OSI-approved permissive licence; 7.2 is the last BSD-3 line and
still receives patches (7.2.16, August 2026). ADR 0003.

**Q: Section 05B of your SRS talks about patients, triage and clinics. Is this system reused from somewhere?** <!-- scope-guard: allow D-47 -->
No. That text was copied into the requirements document from a separate project by the same
author. Those rows were rewritten to their FraudShield equivalents during seeding (D-47), and a CI
check fails if that vocabulary appears anywhere in the codebase.
Evidence: `tools/tests/test_scope_and_registers.py`.

**Q: How are amounts in Rwandan francs versus Kenyan shillings handled?**
Exactly, never as floating point: `DECIMAL(18,4)` storage, and display rounding to each
currency's ISO 4217 minor unit (RWF and UGX 0 decimals; KES, TZS and CDF 2). The in-code table is
tested against the JDK's ISO data. Evidence: `MoneyTest`, D-43.

## M2 — dataset generator

**Q: Your dataset's parameters are invented. Why should I believe anything measured on it?**
Ten of 81 parameters are sourced from documents read in full, and they are the structural ones:
transactions per active customer, agents and merchant acceptance points per customer, the urban
share of customers, per-channel mean amounts, the person-to-person versus merchant split, volume
growth, exchange rates, time zones and the school terms. The rest are modelling choices, each with
a written rationale, and the SRS distribution targets are calibrated design requirements rather
than observations. Nothing measured on the benchmark is a claim about real fraud; the datasheet
says so in the same words.
Evidence: `dataset/params_provenance.md`, `docs/research/sourcing_pass.md`.

**Q: What did the sources actually change? Or did you source values you had already guessed?**
Five assumed values were wrong, and the corrections changed the dataset materially:

| Parameter | Assumed | Sourced | Consequence |
|---|---|---|---|
| transactions per active customer-month | 14 | 8.8 | 59% more simulated customers for the same row count |
| customers per cash agent | 150 | 30 | five times as many agents, so agent-fraud exposure is spread |
| customers per merchant | 40 | 12 | three times as many merchants, fewer repeat visits each |
| urban share of customers | 0.45 implied | 0.32 | population is mostly rural, which broke the old channel-mix algebra |
| school-fee months | Jan, May, Sep | Jan, Apr, Sep | the third seasonal peak moved a month earlier |
| DRC time zone | +01:00 (Kinshasa) | +02:00 (eastern DRC) | local-hour features for Congolese rows shift by an hour |

The urban share forced two design changes. Channel mixes used to be solved by fixing one segment's
mix and letting the others absorb whatever the SRS mix left over; with a rural majority that
remainder pushed urban customers to a fifth of their payments on cards and nearly nothing through
agents. Mixes are now fitted by iterative proportional fitting, so the SRS mix is met exactly at
any population split while each segment keeps a plausible profile. Separately, the generator used
to rewrite a smartphone owner's USSD payment into a wallet payment; once every segment carried some
USSD that rewriting moved the realised mix 3.8 points off target, so it is gone, and whether a row
carries a device now follows the channel.
Evidence: `docs/research/sourcing_pass.md`, `dataset/tests/test_generator.py`.

**Q: How did you decide which parameters were worth sourcing?**
By measuring, not by judgement. Each parameter is perturbed by 10%, the dataset is regenerated with
the same seed, and the largest change in the headline properties is scored in units of that
property's tolerance. The method and its limits (local, one-at-a-time, and blind to share mappings
that scaling leaves unchanged) are recorded with the ranking.
Evidence: `docs/research/parameter_influence.md`, `dataset/src/fraudshield_dataset/sensitivity.py`.

**Q: The fraud parameters are all assumed. Isn't that the part that matters most?**
It is, and it cannot be sourced from published data. No central bank in the region publishes fraud
incidence by type: the National Bank of Rwanda's annual report discusses a Fraud Prevention Forum
and lists mobile money fraud as a consumer complaint category without incident counts, and the Bank
of Tanzania's payment systems report does not break fraud out at all. All 25 fraud parameters
therefore stay ASSUMED. Sourcing them from a vendor report or a global aggregate presented as
regional would look stronger and be worse. What the benchmark can support is a comparison between
methods under the same synthetic conditions, not an estimate of real fraud rates.
Evidence: `docs/research/sourcing_pass.md`, `docs/research/claims_register.md` (C-7).

**Q: The sourced numbers are Tanzanian but your dataset is Rwanda-weighted. Isn't that inconsistent?**
It is a known weakness, stated in every affected citation. Tanzania publishes value and volume by
transaction category; Rwanda publishes access points and subscriber counts but not the same
breakdown. So the Rwandan sources fix the population structure (agents, merchants, urban share) and
the Tanzanian ones fix the per-transaction levels, which are used as an order-of-magnitude anchor
rather than a claim about a single market.

**Q: Did the sourcing break the SRS distribution targets?**
No, and they take precedence by design: the fraud rate, channel mix and country mix are calibrated
to SRS 7.1 and are met by construction. Re-verified after the changes at 60,000 rows: fraud rate
0.870% overall and 0.906% in the test period, channel mix within 0.34 pp, country mix within
0.12 pp.
Evidence: `dataset/realism_report.md`, `dataset/tests/test_realism_checks.py`.
