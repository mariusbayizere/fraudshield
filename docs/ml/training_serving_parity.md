# Training/serving parity: test design

Written before any of the 44 features exists, deliberately. A batch/online divergence found after the
features are written does not merely cost rework: it invalidates every model metric measured in
between. M2 supplies the precedent — a fold-assignment defect inflated every single-feature AUC the
project had reported, and all of them had to be restated once it was found.

This document settles three things the test cannot be written without: **what counts as identical**,
**what the two paths are fed**, and **how the test is shown to be capable of failing**.

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

**Rejected: "the two paths produce the same decision."** That is the weaker check that passes in the
regime where the stronger one would catch something — the floor shape again. Decision equality hides
feature drift until it crosses a threshold, at which point it is a production incident rather than a
test failure.

## Decision 2 — what the two paths are fed

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
  window edge (60 s, 1 h, 24 h, 7 d, 30 d, 90 d). This is where the two paths diverge if either uses
  an inclusive bound where the other uses exclusive.
- **All six channels**, so the NaN contract is exercised: USSD (null fingerprint → four device
  features NaN), `AGENT_BANKING` (agent features present), and the four others (agent features NaN).
- **Label-derived features against `label_available_at`.** E.2 requires that these use only labels
  available before the transaction time. The online path cannot see a label that has not arrived;
  the batch path can, and will unless prevented. Prefix replay makes that failure visible.
- **Account grouping (M3 exit criterion E1).** Replay is per account; an account's rows never split
  across the comparison, because the unit of history is the account, not the row.

## Decision 4 — proving the test can fail

M2's lesson, twice over: a test that cannot fail proves nothing. The first attempt at the
fold-grouping test constructed data where both foldings gave identical results, and the shortcut
detector's power curve was uninterpretable until a negative control showed it responded to label
correlation rather than to the data having been touched.

So the parity suite includes **mutation cases**: a deliberately wrong online path that must be
caught.

| Mutation | What it simulates | Must be caught by |
|---|---|---|
| window bound `<` instead of `≤` | classic off-by-one at a boundary | the window-boundary cases |
| a 24 h window computed over 25 h | silent window drift | count equality |
| a device feature emitted as 0 rather than NaN for a null fingerprint | the NaN contract collapsing to a number | NaN-position equality |
| a label used whose `label_available_at` is after the transaction | future leakage in the batch path | prefix replay |
| a sum accumulated in float32 | precision loss masquerading as reassociation | the relative tolerance |

Each mutation is asserted to **fail** the parity test. If a mutation passes, the parity test is not
testing what it claims.

## Open question for the owner

The specification says `1e-9`, and this document proposes exact equality for integral features and
`1e-12 + 1e-12 × |value|` for real ones — **stricter than the specification everywhere it is
satisfiable, and satisfiable where the specification is not**. That is a deviation from a written
requirement, however favourable, and it is recorded here rather than made silently. If the `1e-9`
is to be kept literally, the amount-sum features need a documented per-feature exception, because at
the top of their range the tolerance is below what float64 can represent.
