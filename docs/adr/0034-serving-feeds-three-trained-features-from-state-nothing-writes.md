# 0034 — Three trained features are fed from state no deployed component writes

- **Status:** Proposed. The measurement and the two halves of the fix are settled; **which option
  closes it is the owner's decision**, and M5 must not be tagged with FR-02-09 marked DONE until
  it is taken.
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

The reviewer measured the same figures independently. AUC moves by 0.009, inside the gate's
interval; **the decisions do not**: 162 frauds that the evaluated model flags at 0.60 are not
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
3. **Carry, with the cost recorded and the rows honest (recommended).** FR-02-09 stays
   `IN_PROGRESS`; a backlog row per half names its owner (labels consumer: M6/M9; account
   reference state: a contract plus a producer); ML-GATE rows keep a note that their figures
   describe the trained model and that production currently differs by the table above; and the
   scorer reports the shortfall rather than hiding it.

## Decision

**Not taken.** The author recommends option 3, with one addition that is cheap and belongs in M5
whatever the owner chooses: the scorer should make the gap visible rather than silent — a metric
counting how many scored transactions were computed with an outcome-free counterparty or cell, and
a startup log line naming the producers that are absent.

## Consequences (of the recommendation)

- FR-02-09 cannot read DONE at M5 close; the review's finding 1 stays open against it.
- Whoever deploys the labels consumer closes half 1 with no code change in `ml/`.
- M11's paper must not describe the gate figures as production behaviour while this stands.
