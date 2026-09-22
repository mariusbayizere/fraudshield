# 0034 — Three trained features are fed from state no deployed component writes

- **Status:** Accepted, 2026-09-22 (owner). Option 3, carry, with the five conditions in
  `## Decision`. FR-02-09 stays NOT DONE at M5 close and closes in M10, not here.
- **Date:** 2026-09-22
- **Raised by:** the independent Principal Review of M5 (`docs/reviews/M5/principal-review.md`,
  finding 1, BLOCKER), and reproduced independently by the author.
- **Requirements affected:** FR-02-09, FR-02-01, FR-02-05, ML-GATE-01 … ML-GATE-11 (their figures
  describe the trained model, not the served one), TEST-08
- **Defects referenced:** D-04, D-06, D-25
- **ADRs referenced:** 0027 (a requirement filed where it cannot be met is carried, not marked
  done), 0033 (the scorer reads the store), 0032

## Context

M5's feature store is written by exactly one producer: `StoreWriter`, which folds in each scored
transaction (`featurestore/writer.py`). Four of the store's other write paths —
`observe_outcome`, `record_sim_swap`, `set_opened_at`, `set_kyc_tier`, `set_agent_standing` — had
**no caller outside tests**, so in a deployment the features that read them are constants:

| Feature | Trained on | Served today | Reads |
|---|---|---|---|
| `counterparty_confirmed_fraud_90d` | counts from the labelled corpus | always 0 | outcomes |
| `geo_cell_fraud_rate_30d` | the cell's own lagged rate | always the prior (0.0087) | outcomes |
| `days_since_sim_swap` | days since the MNO's last swap | always NaN | account events |
| `synthetic_identity_score` | four terms | two terms, silently | KYC tier, account age |
| `kyc_tier`, `account_age_days` | (not trained on this benchmark) | always absent | account table |

The first four are **trained features** (`smoke.trainable_features()`).

## What it costs, measured

On M4's gate model rebuilt at 22f6b1d from `features_gate_d8083dbc.parquet` (seed 1), scored over
the whole test period (101,909 rows, 985 frauds), each state replaced by what serving actually
supplies:

| Serving state | Test AUC | Risk-tier changes | Frauds reaching 0.60 | Worst score move |
|---|---|---|---|---|
| as trained | **0.9700** | — | **725** | — |
| outcomes missing (no labels consumer) | 0.9619 | 235 | 585 | 0.969 |
| SIM swaps missing (no topic) | 0.9699 | 25 | 712 | 0.926 |
| **both, i.e. as M5 shipped** | **0.9611** | **254** | **563** | 0.980 |

The reviewer measured the same figures independently. AUC moves by 0.0089, about the half-width
of the gate's 95% interval (±0.0075) and well inside its width, so no AUC-expressed gate would
catch it; **the decisions do not**: 162 frauds that the evaluated model flags at 0.60 are not
flagged by the served one, a 22% fall in flagged fraud. This is the difference between a metric
and a decision, and it is why the gate's numbers cannot be said to describe production.

## The two halves

1. **Outcomes have a frozen topic.** `fs.labels` carries verdicts with `label_available_at`, which
   is exactly what the store needs. **Done in M5** (22f6b1d): `featurestore/ingest.apply_label`
   applies one event, honours E.2's leakage rule and replaces a superseded verdict, with tests
   against the frozen schema. **What remains is deployment**: a consumer of `fs.labels` calling it.
   M5 ships no Kafka client (none is a dependency, and adding one is a licence review), so this is
   M6/M9's to run.
2. **Account reference state has no topic at all.** `contracts/kafka/topics.yaml` carries nothing
   for SIM swaps, KYC tier changes, account opening or agent standing. D-25 already records the
   MNO signal's availability as unresolved. There is nothing to consume, so this half cannot be
   closed by wiring; it needs a contract and a producer, in M6 or M9.

## Options

1. **Deploy both producers before M5 is called done.** Closes the gap; needs a Kafka client in
   `ml/` (licence review) and, for half 2, a contract that does not exist. Not deliverable inside
   M5.
2. **Drop the four features from the served model and re-evaluate.** Honest and self-consistent:
   the gate would then describe the served model. It means retraining and re-running M4's gate,
   losing `counterparty_confirmed_fraud_90d`, which the battery shows carrying real signal, and
   re-opening M4's closed rows.
3. **Carry, with the cost recorded and the rows honest (recommended).** FR-02-09 does not reach
   DONE (the row is proposed as `IN_PROGRESS`, which is what the matrix renders for work carried
   rather than finished); a backlog row per half names its owner (labels consumer: M6/M9; account
   reference state: a contract plus a producer); ML-GATE rows keep a note that their figures
   describe the trained model and that production currently differs by the table above; and the
   scorer reports the shortfall rather than hiding it.

## Decision

**Option 3: carry, with the cost recorded and the rows honest.** Taken by the owner on 2026-09-22,
on the author's recommendation, under five conditions. Conditions 2 to 5 are discharged in the
artefacts named beside them; condition 1 **cannot be discharged on this branch** and is proposed
for the owner to apply, because M5 does not edit `requirements.yaml` (re-review V5).

1. **FR-02-09 stays NOT DONE, and its row carries the measured skew.** The row text is proposed in
   `docs/parallel/M5_updates.md` §15 — served AUC **0.9611** against **0.9700**, **254** risk tier
   changes, and **162 of 725** frauds no longer reaching the flag threshold, a **22%** fall in
   detections at the operating point, blocked on PB-69, PB-70, PB-71 and PB-72. The figures are
   the table above, reproduced independently by the re-review
   (`docs/reviews/M5/principal-re-review.md`).
   **State on this branch:** `requirements.yaml` is untouched, so the generated matrix still
   renders FR-02-09 as `NOT_STARTED` with no evidence, no skew and no link here. The owner applies
   the row at integration and re-renders with `fs-traceability render`. Until that happens this
   condition is **open**, and the matrix is not evidence that it was met.
2. **Two carries with acceptance tests, each with one owning milestone and a backlog row.**
   - **PB-70 — a consumer of `fs.labels` that calls `apply_label`. Owner: M6** (it owns the
     decision-side services and their database); M9 deploys it and M10 verifies it. *Acceptance:*
     a verdict published to `fs.labels` reaches the feature store and the affected features change
     — `counterparty_confirmed_fraud_90d` rises for the counterparty, `geo_cell_fraud_rate_30d`
     for the cell — with E.2's `label_available_at` honoured. The seam exists and is tested in M5
     (`featurestore/ingest.apply_label`, `ml/tests/featurestore/test_ingest.py`); the consumer does
     not.
   - **PB-71 — a contract and a producer for account reference state. Owner: M6.** KYC tier,
     SIM-swap events and account opening have no contract today, so nothing can publish them.
     *Acceptance:* a published change to an account's tier, a SIM swap and an opening date reach
     the store, and a read afterwards returns them — `days_since_sim_swap` finite where a swap was
     published, `kyc_tier` and `account_age_days` from the producer rather than a constant.
   - Both are written into `docs/parallel/M6_updates.md` and `docs/parallel/M9_updates.md` with
     these criteria. **PB-72 (owner: M10)** requires M10's end-to-end verification to re-measure
     the skew on the served model and to require it to be **zero** before FR-02-09 may read DONE;
     it is carried in `docs/parallel/M10_updates.md` so the milestone that must close it sees it.
3. **The shortfall stays visible.** `fs_feature_store_missing_producer_reads_total{state}` counts
   reads served from a constant, and M9's rules get an alert over it
   (`docs/parallel/M9_updates.md` §"From M5"). The alert distinguishes a producer that is absent
   from a label that has not arrived yet, and its `sim_swaps`/`kyc_tier`/`account_opened_at` arms
   stay inhibited until PB-71 lands — a rule that fires continuously is a rule that gets silenced.
4. **It belongs in the paper.** The lab notebook carries the measurement and its reading; M11 takes
   the four-row table into the discussion and limitations, with what may not be claimed from the
   gate figures (`docs/parallel/M11_updates.md`).
5. **The fixes were re-reviewed independently**, in a fresh worktree, at `2afbb0e`
   (`docs/reviews/M5/principal-re-review.md`): every figure in the table above reproduced to the
   digit, and its seven new findings are answered on this branch.

The addition the author recommended is in M5: the metric above, and a startup line naming the
producers no deployed component writes.

## Consequences

- FR-02-09 cannot read DONE at M5 close; finding 1 stays open against it, and against PB-70,
  PB-71 and PB-72.
- The ML-GATE rows describe the **trained** model. Until PB-70 and PB-71 close, production differs
  by the table above, and no gate figure may be quoted as production behaviour — M11's paper
  included.
- Half 1 (PB-70) closes with no code change in `ml/`: the seam is built and tested.
- Half 2 (PB-71) cannot start until a contract exists, which is why it is carried rather than
  scheduled inside M5 or M6's current scope.
- The skew is measured at the operating point on the gate's test period; production skew is
  **larger**, because the gate cache has no `kyc_tier`/`account_age_days` columns and
  `synthetic_identity_score` could not be degraded in the measurement.
