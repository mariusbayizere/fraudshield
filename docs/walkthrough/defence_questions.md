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
Twelve of 81 parameters are sourced from documents read in full, and they are the structural ones:
transactions per active customer, agents and merchant acceptance points per customer, the urban
share of customers, per-channel mean amounts, the person-to-person versus merchant split, volume
growth, the cross-border share, exchange rates, currency codes, time zones and the school terms. The
rest are modelling choices, each with
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
of Tanzania's payment systems report does not break fraud out at all. So no fraud parameter is
sourced: 21 of the 25 stay ASSUMED and four are calibrated to SRS targets, which are requirements
rather than observations. Sourcing them from a vendor report or a global aggregate presented as
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

**Q: How do you know your synthetic data doesn't leak the label?**
Because leakage is a gate, not a hope, and every gate has a test that fails when its property is
broken. Three checks carry it. Single-feature AUC caps every raw and cheap per-row feature at 0.80
(D-08), measured as `max(AUC, 1 - AUC)` with out-of-fold encoding, so a category cannot be scored
using its own rows' labels. A shortcut detector runs a depth-3 tree over the non-behavioural columns
only — all sixteen transaction-id bytes, the sub-second timestamp, row position in file, and the
label-availability delay — and must land inside a band sized to that statistic's own null, with
folds grouped by account so one incident's rows cannot straddle a split. Identifier construction
runs the same tree over every character of every account, counterparty and device token. Eleven
plant-a-leak tests, one per gate, each fail without their fix; three of them exist because a
reviewer planted markers the earlier detectors could not see — in id byte 5, in token character 10,
and in a one-microsecond label delay — and every leakage number stayed bit-identical.
Evidence: `dataset/realism_report.md`, `dataset/tests/test_realism_checks.py`.

**Q: What is the strongest single signal in the dataset, and is it a leak?**
0.758, and no. It is the time from a SIM swap or device change to that account's next transaction,
reached by joining `account_events` to the transactions on the account token. It is above the
strongest transaction column, `merchant_category_code` at 0.706, and both are inside the 0.80 D-08
ceiling — the delay by 0.042. It is not a leak: a SIM swap before a takeover is how that fraud
works, and a model is meant to learn it, which is why it is measured and reported but deliberately
not gated. Two caveats belong with the number. Part of it is an artefact of the generator rather
than the phenomenon: the lead is drawn from `fraud.takeover_lead_minutes = [5, 60]`, provenance
`ASSUMED`, so every enabling event is followed by its drain in a tight uniform window with no long
tail and no unexploited swap, which real life does not guarantee. And the separation grows with
sample size — 0.709 at 60,000 rows, 0.758 at 1,006,249 — so it must be quoted at release scale.
We found this after the review closed, by measuring the channel that an exclusion had excused: it
had been asserted in a comment as "0.537 on its own", which was the *event type* channel, while the
delay channel was never measured and no gate judged either. Both are now in the report on every run.
Evidence: `docs/reviews/M2/delta-recheck.md`, `dataset/realism_report.md`.

**Q: What is the smallest dataset your generator can produce, and why does it matter?**
About 170,000 rows, and it matters because below that the dataset is missing a fraud type. The
mule-account scenario needs customers holding the mule role, that role is drawn per customer at
0.4%, and a run of 30,000 rows has only a few hundred customers — so the pool is often empty and
the scenario cannot be staged. The generator used to skip it and carry on, and the check that would
have reported the absence is release-only, so every development run quietly produced seven of the
eight scenarios. It now refuses by default and names the size that would work; a development run
can waive the refusal explicitly, and the scenarios that could not be staged are recorded in
`manifest.json`, so a deficient dataset identifies itself.
Evidence: `docs/reviews/M2/milestone-review.md` MAJOR M-4, `dataset/tests/test_generator.py`.

**Q: How do you know the 170,000-row minimum is right?**
Because it is derived and pinned, and because two earlier derivations were rejected for failing an
obvious check. Both sides of the requirement scale with the run, so it looks scale-free and the
original guard assumed it was; what does not scale is the integer pool actually drawn, whose spread
grows only like the square root of the run — at 30,000 rows one seed held 27 mules and another 2.
The minimum is where the expected pool clears the requirement by three standard deviations, and it
is solved **numerically** because below 300,000 rows the requirement is floored at one whole role
holder and so is not proportional to the run size.
The first attempt treated the month-summed capacity as the random quantity, which understates the
spread roughly 24-fold, since one role holder supplies about 24 customer-months. The second solved a
closed form that assumed proportionality straight through that floor. Both were rejected on the same
sanity check: they returned a minimum *smaller* than a run size that had already failed, which would
have sent a reader in a circle. The surviving derivation is pinned by a test that computes it from
four different run sizes and requires agreement: 169,492 / 170,455 / 169,972 / 170,069.
Evidence: `test_the_minimum_size_is_a_property_of_the_parameters_not_the_run`.

**Q: When you quote a number, how do I know what it was measured on?**
Because every figure offered as evidence states its scale, and whether that scale is at or above the
170,000-row minimum at which the shipped parameters stage all eight fraud scenarios. This is a
standing rule adopted after we found that runs below that minimum silently produced seven of the
eight scenarios, which meant some of our own cited figures came from a structurally different
dataset than others.
A sub-minimum figure is not automatically wrong. A missing scenario has its share reallocated to the
scenarios that can run, so the fraud rate is still met exactly and rate-like figures — overall fraud
rate, channel mix, country mix — are unaffected. So are construction-like figures, because
identifiers and timestamps are built identically whatever scenario produced a row. What is affected
is anything depending on the scenario *mix*: the eight-scenario property itself, the merchant-
category lift, and the statistical power of the leakage detectors. Without the scale on the figure,
a reader cannot tell which of those they are looking at — and neither can the author.
The realism report and the datasheet label any figure measured below the minimum.
Evidence: `docs/reviews/M2/milestone-review.md`, consequence audit.

**Q: You reported a three-sigma anomaly and then withdrew it. What happened?**
We used the wrong denominator, and repeated seeds caught it. A single 1,006,249-row run read a
shortcut-detector AUC of 0.510. Standardised against the *analytic* null standard error of 0.00346
that is t = +2.89, and we recorded a prediction that it signalled a real construction signal in the
generator. Five seeds at the same scale then gave 0.5096, 0.4916, 0.5070, 0.5119 and 0.5044: the
seed-to-seed standard deviation is 0.0079, more than twice the analytic figure, and 0.510 is simply
the second-highest of five ordinary readings. Pooled across every scale above the dataset's minimum,
the mean is 0.5031 with a bootstrap 95% interval of [0.4992, 0.5070], which straddles 0.5. There is
no offset, and the prediction was refuted.
The mistake is worth naming because it is easy to make: an analytic null describes how the statistic
varies on one fixed dataset, while what actually varies between releases is the dataset. The spread
that matters is the larger one. We had corrected exactly this error one level down an hour earlier —
it is why the null band's inflation factor was re-derived from clean datasets rather than a Monte
Carlo over a fixed one — and then made it again while standardising a single reading.
The rule we now hold to: **a single three-sigma reading is not evidence; repeated seeds are.** Every
figure standardised against a theoretical null says so, and every claimed effect rests on repeated
draws of whatever varies in deployment.
Evidence: `docs/reviews/M2/shortcut_diagnosis.jsonl`, `docs/research/lab_notebook.md`.

**Q: Did you ever predict something and get it wrong?**
Twice in M2, both recorded with their reasoning and their test before the data existed. We predicted
the leakage detector's power would not carry over from datasets missing a fraud scenario to complete
ones, because restoring the mule-account scenario changes the positive class; power rose
monotonically instead and caught every planted leak from 60,000 rows up. And we predicted the
positive offset above. Both were refuted by measurement.
They are in the record deliberately. A prediction written after the result cannot be wrong, and so
cannot be evidence of anything; one written before it can be, and the two that failed here did more
to discipline the analysis than the ones that held.
Evidence: `docs/research/lab_notebook.md`, entries timestamped before each tier ran.

**Q: What does M2 leave unresolved?**
Three things, stated as limitations rather than buried.
First, **single-feature AUC depends on dataset size by more than seed noise, and we cannot explain
it.** Ten seeds at 300,000 rows give a seed-to-seed standard deviation of 0.0055; the range across
scales is 0.065, twelve times that. It is not the fold-grouping defect we found and fixed —
account-grouped folds remove the same amount at 300,000 and 1,000,000 rows, and for the seed
measured at both there is no gap at all. This is the one open scientific question from the
milestone. Until it is explained, every metric depending on target encoding is reported at a fixed,
stated scale, and comparisons use the same dataset size (M3 exit criterion E2). The experiments that
would separate the candidate causes are written down in the lab notebook.
Second, **the null band's inflation factor is grounded only where it was measured.** 1.62 comes from
45 clean datasets at 10,000–60,000 rows, where per-scale estimates agree. Above 60,000 the sweep
cannot settle whether it is constant: four to ten seeds give estimates from 0.84 to 2.32. Settling it
needs about 50 seeds per scale, roughly eight hours at a million rows. The value is used above that
range as an extrapolation, and the code says so.
Third, **the release-size run has never happened.** ML-DATA-01 asks for 5,000,000 rows; the
verification run is 1,006,249. It sits at `VERIFIED_AT_REDUCED_SCALE` with that reason recorded, not
quietly marked done.
Evidence: `docs/reviews/M2/milestone-review.md`, `docs/research/lab_notebook.md`.
