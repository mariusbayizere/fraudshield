# M3 exit criteria

M3 builds the feature pipeline on the M2 benchmark. The criteria below are the milestone's
definition of done: each is checkable, and the first two exist because M2 paid for them.

## Rules M2 paid for

### E1 — every fold, split, sample and target encoding is account-grouped and time-respecting

M2 found the same defect three times, each a correct fix that stopped one step short of the
structural form of the problem (`docs/research/lab_notebook.md`, "The pattern"). The third instance
was the categorical encoding: it removed a row's own label from its own score and then assigned
folds **per row**, so the other rows of the same fraud incident stayed inside the estimate that
scored it. Fraud arrives as incidents sharing an account; a row is not an independent unit.

Required of every feature, metric and model in M3:

- fold assignment, train/validation/test splits, negative sampling and target encoding group by
  **account**, never by row;
- and respect time — no future row informs a past one, which the temporal split already enforces at
  the dataset level and which feature windows must not undo;
- each carries a test that **fails if the grouping is removed**, in the shape of
  `test_the_categorical_encoding_folds_by_account_not_by_row`: construct data where grouped and
  ungrouped genuinely disagree, and assert they do.

A test that passes under both groupings tests nothing; M2's first attempt at this test did exactly
that, because the constructed categories were purely fraud or purely legitimate.

### E2 — every reported metric states the scale it was measured at

Single-feature AUC on this benchmark depends on dataset size by more than seed noise: ten seeds at
300,000 rows give a seed-to-seed sd of 0.0055, while the range across scales is 0.065. The cause is
**not** the fold-grouping defect — account-grouped folds remove the same amount at 300,000 and
1,000,000 rows — and it is **unexplained** (M2 milestone review, area 1).

Until it is explained:

- every metric that depends on target encoding is reported at a fixed, stated scale;
- comparisons between feature sets, models or ablations use the **same** dataset size, and say so;
- a metric quoted without its scale is not admissible as evidence, in the paper or the review record.

## Feature pipeline

- **E3** — the 44 engineered features are re-checked against the D-08 single-feature AUC ceiling of
  0.80, with account-grouped encoding, at a stated scale.
- **E4** — feature computation is deterministic from the dataset and a seed, verified byte-identical
  across batch sizes, as the generator is.
- **E5** — no feature reads a column the anti-leakage gates exclude by construction (identifier
  bytes, sub-second timestamp parts, row position, label delay), and a test asserts the feature
  set's columns are disjoint from that list.
- **E6** — the `account_events` join is available to features, and any feature derived from it is
  measured and reported alongside the transaction features. The event-to-transaction delay separates
  the classes at 0.758 and is the dataset's strongest single channel; a feature set that uses it is
  not comparable to one that does not, and each must say which.

## Prerequisites landing in the same branch

- **E7** — PB-26: the `month=` partition key is the local month while timestamps are UTC. Fixed
  **before** any feature reads those partitions, or time-window features are silently distorted.
- **E8** — ADR 0023's country packs and `corridor_class` generalisation land here rather than in a
  separate branch: M3 features consume them, and splitting would cost a full review cycle. No
  country, currency or bloc is hard-coded in feature code.

## Governance

- **E9** — every new parameter carries provenance, and `fs-dataset provenance --check` passes.
- **E10** — the traceability register covers every M3 requirement with a tagged test, and the
  rendered matrix is current.
- **E11** — a milestone review covering: leakage under the new features, determinism, the grouping
  rules above, and whether every claim in the M3 walkthrough is backed by a test or a measurement.
