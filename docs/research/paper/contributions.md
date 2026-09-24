# What this paper can claim, and what it cannot

**Status: the evidence-backed contribution list, rewritten 2026-09-22 after M4's battery.** The
LaTeX draft (E.13, M11) has not been started; this is what it must be built from. Every entry
names the artefact that supports it, and every entry that was on this list and is no longer says
why.

The rule this list is kept under: a contribution is something a reader could disagree with and
check. "We built a system" is not one.

---

## 1. A benchmark-construction methodology, and its measured failure modes

**The contribution is the failure modes, not the benchmark.** Anyone can generate synthetic
transactions. What this project has that a reader cannot get elsewhere is a record of a synthetic
benchmark being interrogated until it gave up its structure, with each interrogation published
whether or not it flattered the dataset.

| Finding | Evidence |
|---|---|
| A single engineered feature reaches `max(AUC, 1−AUC)` **0.893** — so a model AUC quoted against 0.5 overstates by four times the quantity that matters | `docs/benchmarks/single_feature_baseline.md`, PB-46 |
| **Four disjoint feature groups each reach ≥ 0.845 alone**, so leave-one-out ablation reports every group as free and cannot say which features matter | `docs/benchmarks/m4_battery.md`, PB-60 |
| **Leave-one-country-out is null**: removing a country from training entirely costs ≤ 0.002 AUC | PB-59 |
| **The novel-variant temporal hold-out is null too**: the unseen shape is caught at 100% against 95.1% for the familiar one | PB-61 |
| One cause explains all three: the benchmark encodes fraud as **bursts**, and every variant, every country and every feature group is a view of that one structure | PB-61 |
| **The M4 gate misses recall at the 0.60 flag threshold, and more training data does not close it**: recall 0.736 [0.707, 0.765] at 30,000 training rows against 0.88 (FNR 0.264), with the other nine M4 gate metrics passing. A pre-registered learning curve (`d071147`) then measured 30K, 60K, 120K and 240K training rows on the same 101,909 test rows: recall at 0.60 moves 0.767 → 0.795 on five-seed means across an 8× increase, every interval excluding 0.88, so the shortfall is a property of this benchmark and model at the volumes measured, not of training volume. It is a ranking shortfall, not a threshold choice — recall is 0.860 at the 1% FPR budget even at 240K — reported as measured, with D-02's threshold unchanged and nothing tuned on the test period. A larger effect appeared on the way: which training rows are used (corpus depth, PB-69) moves recall several times more than how many | ADR 0031 and its 2026-09-24 amendment, PB-62, PB-67, `docs/benchmarks/m4_gate_d8083dbc_v3.txt`, `docs/benchmarks/m4_learning_curve_{30k,60k,120k,240k}_d8083dbc.txt` |
| **A pre-registered, typology-grounded non-burst variant is the one genuine failure found**: `reversal_scam_social_engineering` (single victim-initiated transfer, established counterparty) is caught at **9.1%** (6/66, Wilson 95% CI [4.2%, 18.4%]) against base's 94.4% [92.7%, 95.8%] (first measured at 6.1% [2.4%, 14.6%] with a target encoding E1 forbids, and restated after the correction; the interval now reaches past the pre-registered range's 0.15 lower edge) — confirming, on a held-out shape rather than by inference from ablation margins, that detection power concentrates in burst-structure and counterparty-novelty | PB-61, ADR 0028, `docs/benchmarks/m4_battery_pb61.txt` |
| The dataset carries a **fingerprint over its output rows**, because a parameter digest cannot see a changed draw | PB-41, PB-54 |
| Evidence artefacts carry the commit **and the working-tree state**, because a hash written by hand records an intention | PB-52, PB-53 |

**Why this is publishable as a negative-results contribution.** The literature on synthetic fraud
benchmarks reports what its generators produce. This reports what one generator's benchmark
*cannot support*, with the measurements that establish it. A reader building a synthetic benchmark
learns which experiments to run before trusting theirs.

## 2. The latency–explainability frontier

Accuracy against **single-request** p99, with and without exact TreeSHAP, across tree complexity
(D-16). One row per request, never a batch, because the scoring service handles one transaction
per call and a batch amortises exactly the overhead a single request pays.

**This is the most defensible quantitative contribution left, and the reason is everything above.**
Every accuracy claim on this benchmark is weakened by the benchmark. The cost of explaining a
decision is not: it is a property of the model and the SHAP algorithm, and a tree twice as deep
costs what it costs whether the fraud is easy or hard to find.

Evidence: `docs/benchmarks/m4_frontier_6abde44e.txt`. **The absolute milliseconds are not gate
numbers** — ADR 0010 permits latency percentiles as gate evidence only from the dedicated machine
— so the paper reports the *shape* and says on which machine it was measured.

## 3. The redundancy finding, stated on its own

Four disjoint feature groups each reaching ≥ 0.845 alone is a result about **how a scenario-driven
generator encodes signal**, not a detail of one ablation table. A generator that plants fraud as
incidents writes the same structure into velocity, counterparty, temporal and geographic views
simultaneously, so feature-importance and ablation analyses on such a benchmark measure
redundancy rather than importance.

This retires C-4's planned measurement (below) and is worth more than the measurement would have
been.

## 4. A system built to a specification, with its deviations recorded

Not a research contribution and not claimed as one. It belongs in the paper as the setting: 44
features on two independent paths with a parity suite, a published D-07 split, a governance
apparatus that refuses stale records. The ADRs are the interesting part for a reader building
something similar.

---

## Removed from the contribution list

**Cross-country generalisation (LOCO).** Removed 2026-09-22. The benchmark's fraud mechanisms are
country-invariant by construction — no fraud parameter is keyed by a country code, and
`test_no_fraud_parameter_is_keyed_by_country` asserts it. Country enters only as a lookup for
*which* mule, merchant, agent, currency or UTC offset an incident uses. **Country-level
generalisation cannot be evaluated on this benchmark and belongs to the real-data validation
plan.** It is reported as a null result rather than dropped, because a reader who saw it omitted
would assume it was unflattering.

The generator was **not** changed to make countries differ. Engineering country-specific fraud so
that the experiment becomes informative would be tuning the benchmark to produce a result, which
is the failure this project spent M2 and M3 learning to avoid (owner decision, 2026-09-22).

**Generalisation to an unseen fraud variant.** Removed the same day, for the same reason one level
along: the novel sub-variant differs in *timing*, not in transaction pattern, so it is not an
out-of-distribution test. A real one needs a variant whose pattern differs, which changes the draw
and is an owner decision — and designing one until the experiment fails would be the same error.

**"Western models achieve 0.72–0.78 on East African data" and its planned replacement (C-4).**
The claim is unverified and the replacement — "card-style feature set only vs full EAC feature
set" — is defeated by the redundancy finding: on a benchmark where four disjoint groups each reach
0.85 alone, any subset comparison shows a small difference whatever is true of real systems.
Reporting it as evidence would be a control narrower than the claim it justifies.

---

## Still owed before the draft is written

- **Seed variance** (C-6): measured over five seeds (`docs/benchmarks/m4_seed_variance_d8083dbc_e1.txt`); the ensemble does not reduce variance against XGBoost alone. The gate reports every gate metric's mean and SD over five refits.
- **Per-month performance** (C-5), which is measurable and currently unmeasured.
- **Precision at a fixed alert budget**, which is the operational number and is not yet reported.
- **ONNX export parity**, and the LaTeX tables themselves.
- A **related-work** section. The prompt's instruction stands: cite only papers read in full, and
  leave `\cite{TODO-verify}` rather than inventing a reference.
