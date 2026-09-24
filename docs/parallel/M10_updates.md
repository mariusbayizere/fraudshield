# M10 updates (created by the M5 agent, branch `m5/scoring`)

This file exists so that the obligation M10 must discharge is written where M10 will read it,
rather than only inside documents addressed to M6 and M9 (M5 re-review, finding N7). It carries
nothing else. An M10 agent should keep its own content in this file and treat the section below as
inherited work.

## From M5 — PB-72: re-measure the training–serving skew, and require it to be zero

**Owner: M10. Source: ADR 0034 (Accepted 2026-09-22, option 3), owner's condition 2.**

M5's scoring service reads four trained features from state that no deployed component writes, so
in production they are served as constants. M5 measured what that costs, on M4's gate model over
the whole test period (101,909 rows, 985 frauds); the figures were reproduced independently by the
M5 re-review (`docs/reviews/M5/principal-re-review.md`):

| Serving state | Test AUC | Risk-tier changes | Frauds reaching 0.60 |
|---|---|---|---|
| as trained | 0.9700 | — | 725 |
| outcomes missing | 0.9619 | 235 | 585 |
| SIM swaps missing | 0.9699 | 25 | 712 |
| **both, as M5 ships** | **0.9611** | **254** | **563** |

AUC moves 0.0089 — just outside the ±0.0075 half-width of the gate's own 95% interval — while
**162 of 725 frauds that the evaluated model flags at 0.60 are not flagged by the served one: a
22% fall in detections at the operating point.** The measurement is conservative: the gate cache
has no `kyc_tier`/`account_age_days` columns, so `synthetic_identity_score` could not be degraded
in it, and production skew is therefore larger.

### What M10 must do

1. **Re-measure the skew end to end**, on the deployed scorer rather than in a notebook: score a
   verification set through the running service and compare each feature with the batch value the
   training path computes for the same transaction.
2. **Require the skew to be zero.** Not "close on AUC": the served features must equal the trained
   ones for every row. AUC is the quantity that hid this defect — it moved by less than one
   interval half-width while a fifth of the detections disappeared — so it is not the acceptance
   criterion here.
3. **FR-02-09 may not read DONE until this passes**, along with PB-70 and PB-71 (both owner M6):
   the `fs.labels` consumer that calls `apply_label`, and a contract plus producer for account
   reference state. Their acceptance criteria are in `docs/parallel/M6_updates.md` under "From M5".
4. **Check the alerts are silent for the right reason**: once PB-70 is deployed,
   `fs_feature_store_missing_producer_reads_total{state="outcomes"}` must stop increasing, and
   after PB-71 the other three states must too (`docs/parallel/M9_updates.md`, "From M5"). A
   silent counter with no producer deployed would mean the counter broke, so verify against a
   deliberately withheld verdict.

The backlog numbers above are proposals for the owner to confirm at integration: M5 does not edit
`docs/backlog/`, and PB-68 is already taken by the frontier write-up item.
