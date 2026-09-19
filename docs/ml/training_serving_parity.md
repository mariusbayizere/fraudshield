# Training/serving parity: test design

Written before any of the 44 features exists, deliberately. A batch/online divergence found after the
features are written does not merely cost rework: it invalidates every model metric measured in
between. M2 supplies the precedent — a fold-assignment defect inflated every single-feature AUC the
project had reported, and all of them had to be restated once it was found.

This document settles six things the test cannot be written without: **what counts as identical**,
**what the paths are fed**, **which cases must be covered**, **how independence between them is
enforced**, **how the test is shown to be capable of failing**, and **how many paths there are** —
which turned out not to be two.

## What the test proves

That the feature vector a model is *trained* on and the feature vector it is *served* are the same
function of the same history. If they are not, every metric measured offline is a measurement of a
system that does not exist in production, and the gap is invisible in both.

Part E.2 requires it: "training/serving skew test (same raw history through the batch path and the
online path yields identical vectors to 1e-9)".

## Decision 1 — what counts as identical

**The specification's `1e-9` is taken as intent, not as the literal rule, because as an absolute
tolerance it is simultaneously unsatisfiable and vacuous.**

One ulp — the smallest difference float64 can represent — at each feature's realistic magnitude:

| Feature | magnitude | one ulp | against 1e-9 absolute |
|---|---:|---:|---|
| `round_sum_flag` | 1 | 2.2e-16 | 4.5 million ulps of slack — no constraint at all |
| `amount_log1p` | 12 | 1.8e-15 | ~560,000 ulps of slack |
| `distance_from_last_tx_km` | 500 | 5.7e-14 | ~18,000 ulps of slack |
| `amount_sum_24h` (RWF) | 3e4 | 3.6e-12 | ~275 ulps of slack |
| `amount_sum_7d` (RWF) | 1e6 | 1.2e-10 | ~9 ulps of slack |
| `amount_sum_7d`, heavy user | 1e7 | **1.9e-9** | **unsatisfiable** — below the representable resolution |

So a single absolute tolerance lets a genuine bug through on a flag while failing an honest
reassociation of a large sum. That is the same defect shape M2 found in `max(0.03, ...)`: one
constant applied across regimes where it means different things, inert where it was meant to bind
and binding where it should not.

**The rule instead:**

1. **Exact equality for anything integral or categorical.** Counts (`tx_count_60s` … `tx_count_7d`,
   `device_changes_24h`, `unique_counterparties_24h`), flags, ordinals (`kyc_tier`), and the two
   categoricals (`channel`, `corridor_class`). A count that differs by any amount is a bug, never a
   rounding artefact, and admitting a tolerance here hides exactly the errors worth catching.
2. **Relative tolerance with an absolute floor for real-valued features:**
   `|batch − online| ≤ 1e-12 + 1e-12 × |batch|`. At every magnitude in the table above this is
   **stricter than the specification's 1e-9** — by three orders of magnitude at typical values — and
   it remains satisfiable at the top of the range, which 1e-9 does not.
3. **NaN positions must match exactly.** Structural NaN is part of the contract (D-04): exactly four
   device features are NaN for a null fingerprint, four agent features are NaN outside
   `AGENT_BANKING`. A NaN on one path and a number on the other is a contract violation, not a
   numerical difference, and must never be absorbed by a tolerance.

Where an order-dependent aggregation makes (2) genuinely unreachable for a specific feature, that
feature gets a documented per-feature tolerance naming the aggregation and the reason — never a
blanket loosening.

**Rejected: "the paths produce the same decision."** That is the weaker check that passes in the
regime where the stronger one would catch something — the floor shape again. Decision equality hides
feature drift until it crosses a threshold, at which point it is a production incident rather than a
test failure.

## Decision 2 — what the paths are fed

**Prefix replay, not whole histories.**

The batch path naturally sees an account's complete history; the online path sees it one transaction
at a time. Handing both a completed history proves they agree on a situation the online path never
encounters, and — worse — it cannot detect a batch window that reaches forward in time, because both
paths would see the same future rows.

The test therefore, for each account:

1. takes its transactions in timestamp order;
2. feeds the online path transaction 1, 2, … *n*, snapshotting the 44-slot vector after each;
3. computes the batch path on the **prefix ending at transaction *k*** only, for each *k*;
4. compares snapshot *k* against batch-on-prefix-*k*, under the rule above.

Any batch feature whose window includes a row the online path has not yet seen fails immediately and
loudly, which is the leakage this design exists to catch.

## Decision 3 — the cases the test must cover

Chosen so that a passing test means something:

- **Zero history.** The first transaction of an account: every window feature at its 0-history value,
  `seconds_since_last_tx` NaN, `velocity_ratio_1h_vs_30d` at its Laplace-smoothed value.
- **Window boundaries.** An account with transactions placed just inside and just outside each
  window edge (60 s, 1 h, 24 h, 7 d, 30 d, 90 d). This is where the paths diverge if any uses
  an inclusive bound where the other uses exclusive.
- **All six channels**, so the NaN contract is exercised: USSD (null fingerprint → four device
  features NaN), `AGENT_BANKING` (agent features present), and the four others (agent features NaN).
- **Label-derived features against `label_available_at`.** E.2 requires that these use only labels
  available before the transaction time. The online path cannot see a label that has not arrived;
  the batch path can, and will unless prevented. Prefix replay makes that failure visible.
- **Account grouping (M3 exit criterion E1).** Replay is per account; an account's rows never split
  across the comparison, because the unit of history is the account, not the row.
- **Cold cache.** The online store is flushed mid-replay and the vector after the flush is asserted
  against each feature's declared `fallback_behaviour`. See Decision 6; this is the only case that
  exercises `history_requirement=DURABLE`.
- **A configuration change (REQUIRED FIXTURE PROPERTY, ADR 0026).** The replayed history must span
  at least one threshold or rule change, and the test **asserts `changes > 0` before asserting
  agreement** (E12). Features declaring `reference_data_basis=AS_OF_EVENT` read mutable operational
  configuration; the batch path naturally sees today's values and the online path sees what was
  live. **The two paths agree for every transaction newer than the last configuration change**, so
  a fixture set without one passes with the bug present — the suite would not be weak here, it
  would be blind. This is E12's vacuous-precondition failure arriving in a new place, which is why
  it is a property of the fixture rather than a case in a list.

## Decision 4 — the paths must be independent implementations

**A parity test is blind to any bug in code both paths share.** If the online path is the batch path
behind a different entry point, the test proves only that a function equals itself. The value of the
test is exactly the size of the surface the paths do *not* share.

Enforced, not merely intended:

- the batch path lives in `fraudshield_ml.features.batch`, the online path in
  `fraudshield_ml.features.online`, and **a test asserts neither imports the other**, directly or
  transitively, by walking the import graph;
- both import `fraudshield_ml.features.registry` — the *declarative* contract only: name, group,
  dtype, window, source, NaN rule, template key. The registry computes nothing;
- any shared primitive must be listed explicitly in the registry module's `SHARED_PRIMITIVES` and
  carries its own unit tests **with hand-computed expectations**, because the parity test cannot
  see into it. Haversine distance and the FX conversion table are the expected members; a shared
  window-aggregation helper would defeat the purpose and is not permitted.

The honest statement of the test's reach: parity proves the paths agree, and hand-computed
per-feature tests (Part E.2) prove they are both *right*. Neither substitutes for the other, and the
shared surface is covered only by the second.

## Decision 5 — proving the test can fail

M2's lesson, twice over: a test that cannot fail proves nothing. The first attempt at the
fold-grouping test constructed data where both foldings gave identical results, and the shortcut
detector's power curve was uninterpretable until a negative control showed it responded to label
correlation rather than to the data having been touched.

So the parity suite includes **mutation cases**: a deliberately wrong online path that must be
caught.

Each mutation is applied to the online path, and the parity test is asserted to **fail**. A mutation
that passes means the parity test is not testing what it claims, and the mutation is then the
specification for a case the test is missing.

| # | Mutation | What it simulates | Caught by | Result |
|---|---|---|---|---|
| 1 | reassociate a window sum (accumulate in reverse order) | the benign case the tolerance must *tolerate* | — | **must PASS** (see note) |
| 2 | window bound `<` instead of `≤` | classic off-by-one at a boundary | window-boundary cases, count equality | **DETECTED** 2026-09-19: 0.96 → 2.0 |
| 3 | a 24 h window computed over 25 h | silent window drift | count equality | **DETECTED** 2026-09-19 (run as 30 d over 31 d) |
| 4 | local time applied in one path only | D-43 timezone handling diverging | `local_hour_sin/cos`, `is_local_night` | pending |
| 5 | a structural missing emitted as `0.0` rather than NaN | the D-04 contract collapsing to a number | NaN-position equality | pending |
| 6 | a category encoded from a different fold | the M2 encoding defect, reproduced in serving | exact categorical equality | pending |
| 7 | a label used whose `label_available_at` is after the transaction | future leakage in the batch path | prefix replay | **DETECTED** 2026-09-19: 1.5/59 → 2.5/60 |
| 8 | a sum accumulated in float32 | precision loss masquerading as reassociation | the relative tolerance | pending |
| 9 | the fallback path serves a bucket-aligned 1 h count as if it were trailing | the degraded path diverging where no warm-path test looks | cold-cache case, `fallback_behaviour` | pending |
| 10 | `account_first_seen_at` lost on flush, so `OBSERVED_CAPPED` divides by a shorter history | a `DURABLE` field that is not durable | cold-cache case | **DETECTED** 2026-09-19 — see note |
| 11 | the batch path joins current thresholds instead of as-of ones | ADR 0026's configuration drift | the required configuration-change fixture | pending |
| 12 | a non-account-keyed aggregate computed over all rows rather than training folds | cross-account leakage E1's grouping cannot see | single-feature AUC rises above its clean value | **DETECTED** 2026-09-19, one step earlier: the value changes at all |

**Mutation 1 is the control, and it is the one that must pass.** Without it the suite cannot
distinguish "the tolerance catches bugs" from "the tolerance catches everything, including honest
reassociation" — in which case it would be loosened under pressure and stop catching anything. It is
the same role the 0.5-strength plant played in M2's power curve: without a case that *should not*
fire, a detector that fires at everything looks identical to one that works.

**Five rows executed 2026-09-19** in `ml/tests/features/test_mutations.py`, against the two
implemented features. Each applies the divergence and asserts the values disagree by more than ADR
0025's tolerance — a mutation that slips inside the tolerance is the specification for a missing
case, not a curiosity.

**Seven rows stay `pending` and are not marked passed by omission.** Rows 1, 5 and 8 need an
amount-sum or structural-NaN feature; row 4 a temporal feature; row 6 a categorical; row 9 the DB
fallback path; row 11 `just_below_limit_flag`. None exists yet.

**Note on row 10, because the obvious version of it is the wrong one.** Two flush scenarios behave
oppositely. *Everything lost* — arrivals and first-seen re-derived together — shrinks numerator and
denominator in step, moves the ratio barely, and is nearly invisible. *Arrivals restored, first-seen
not* divides a full 30 days of rows by a two-day apparent history, inflating the baseline and
collapsing the ratio on every established account at once. The second is **exactly what M1's schema
produces today**, since the transactions come back from the database and there is no per-account
table for first-seen (PB-37). The test asserts detection rather than a direction, and asserts the
ordering between the two scenarios so the reasoning fails loudly if it is ever wrong.

Results are recorded in this table as the mutations are implemented, not summarised elsewhere.

## Decision 6 — there are three paths, not two

**Added after the first two features were registered, correcting this document's original premise.**

M1 already built the third one. `V10__timescale_policies_and_aggregates.sql` creates a continuous
aggregate whose own comment names its purpose:

```sql
-- Hourly activity per account: database fallback for velocity features when Redis is down (C.4).
CREATE MATERIALIZED VIEW account_activity_hourly ...
  time_bucket(interval '1 hour', transaction_timestamp) AS bucket,
  count(*) AS transaction_count, sum(amount_rwf) AS amount_rwf
```

FR-02-09 requires it — "stale key handled by DB fallback" — and M1's own milestone review already
recorded the continuous aggregates as **implemented, untested (MAJOR-2)**. So the paths are:

| Path | Reads | When it serves |
|---|---|---|
| batch | the dataset, whole histories | training, evaluation |
| online | Redis feature store | normal serving |
| **fallback** | `account_activity_hourly` | **Redis down or key stale** |

### Why this is not a third column in the same table

The aggregate buckets to the hour. From hourly buckets, `tx_count_60s` is **not computable at all**,
and a trailing 1 h count is **not the same number** as a bucket-aligned one: at 10:30 the trailing
hour covers 09:30–10:30 while the bucket covers 10:00–10:30. Both are defensible; they are not
equal.

That divergence has a property that makes it worse than an ordinary bug: **it appears only during an
incident.** Redis goes down, the fallback engages, and the model receives features computed a
different way — at the exact moment the system is already degraded, and in a regime no warm-path
test ever visits.

### The rule

Every windowed feature declares `fallback_behaviour` in the registry, and the option that is *not*
available is "approximately right":

- **`EXACT`** — the fallback reproduces the online value under Decision 1's rule. For a sub-hour
  window this means reading raw `transactions`, not the aggregate. Costs a heavier query on the
  degraded path, which is the honest price.
- **`NAN_UNDER_FALLBACK`** — the feature is declared absent while the fallback serves, and the
  models' native missing handling (D-04) covers it. The model sees *fewer* features rather than
  *wrong* ones.

`velocity_ratio_1h_vs_30d` is registered `NAN_UNDER_FALLBACK`; `geo_cell_fraud_rate_30d` is `EXACT`,
being DB-resident already and reading no bucketed source.

**A bucket-aligned substitute is forbidden.** It passes every warm-path test and diverges only where
nothing is looking, which is the floor shape a third time.

### Two consequences for the test

1. **The parity suite runs the fallback path too**, under the same prefix replay and the same
   tolerance rule, with `NAN_UNDER_FALLBACK` features asserted NaN rather than skipped. A skip
   would let a feature silently become `EXACT`-but-wrong.
2. **A cold-cache case joins Decision 3's list**: replay an account, flush the online store
   mid-replay, and assert the vector after the flush matches the declared behaviour — `EXACT`
   features unchanged, `NAN_UNDER_FALLBACK` features NaN. This is also the only test that exercises
   `history_requirement=DURABLE`: a `DURABLE` field that does not survive the flush fails here and
   nowhere else.

### An open defect this exposes

The aggregate's refresh policy is `start_offset => interval '8 days'`, while
`velocity_ratio_1h_vs_30d` needs a **30-day** basis. Buckets older than 8 days are materialised once
and never refreshed, so a transaction arriving more than 8 days late is never reflected in them.
Whether that can happen depends on the ingest path's out-of-order tolerance, which is an M6
question; it is recorded now so it is not discovered then.

## Status of the tolerance deviation

**Accepted by the owner, recorded as ADR 0025.** The specified `1e-9` is replaced by the rule in
Decision 1: it is stricter than the specification everywhere the specification is satisfiable, and
satisfiable at the top of the amount-sum range where the specification is not. FR-02-02's register
row cites the ADR, so a reader comparing code against Part E.2 finds the difference explained rather
than apparently unimplemented.
