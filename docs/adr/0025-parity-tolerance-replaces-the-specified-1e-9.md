# ADR 0025 — The parity tolerance replaces Part E.2's `1e-9`

- **Status:** accepted
- **Date:** 2026-09-19
- **Context:** M3 step one, before any of the 44 features exists.

## Context

Part E.2 specifies the training/serving skew test as: "same raw history through the batch path and
the online path yields **identical vectors to 1e-9**".

Read as an absolute tolerance, `1e-9` fails at both ends of the feature set's range. The arithmetic,
with one ulp being the smallest difference float64 can represent at a given magnitude
(`numpy.spacing`, float64 eps = 2.220446e-16):

| Feature | Magnitude | One ulp | `1e-9` expressed in ulps |
|---|---:|---:|---|
| `round_sum_flag` | 1 | 2.2e-16 | **≈ 4,500,000 ulps** — constrains nothing |
| `amount_log1p` | 12 | 1.8e-15 | ≈ 560,000 ulps |
| `distance_from_last_tx_km` | 500 | 5.7e-14 | ≈ 18,000 ulps |
| `amount_sum_24h` (RWF) | 3e4 | 3.6e-12 | ≈ 275 ulps |
| `amount_sum_7d` (RWF) | 1e6 | 1.2e-10 | ≈ 9 ulps |
| `amount_sum_7d`, heavy user (RWF) | 1e7 | **1.9e-9** | **< 1 ulp — unsatisfiable** |

At a heavy user's seven-day amount sum the tolerance is **below float64's representable
resolution**: two mathematically equal sums accumulated in different orders — which is exactly what
a batch path and an online path do — cannot be guaranteed to agree within it. At a 0/1 flag the same
number admits a difference of four and a half million ulps, so a genuine bug passes unnoticed.

**This is the same defect shape as the band floor removed in M2** (`max(0.03, z × k × SE)`): one
constant spanning regimes where it means different things, inert where it was meant to bind and
binding where it should not. It is the third time the shape has appeared in this project, after the
guard that digested values but not the check set, and the encoding that folded per row but not per
incident.

It is also **the first instance caught before the code was written rather than after**.

## Decision

The specified `1e-9` is replaced by a rule whose meaning does not change with magnitude:

1. **Exact equality** for counts, flags, ordinals and categoricals — `tx_count_60s` … `tx_count_7d`,
   `unique_counterparties_24h`, `device_changes_24h`, every `*_flag`, `kyc_tier`, `channel`,
   `corridor_class`. A count that differs by any amount is a bug, never rounding, and a tolerance
   here would hide precisely the errors worth catching.
2. **`|batch − online| ≤ 1e-12 + 1e-12 × |batch|`** for real-valued features. At every magnitude in
   the table this is **stricter than `1e-9`** — by three orders of magnitude at typical values — and
   it stays satisfiable at the top of the range, where `1e-9` does not.
3. **Exact NaN positions.** Structural NaN is the D-04 contract: exactly four device features are
   NaN for a null fingerprint, four agent features are NaN outside `AGENT_BANKING`. A NaN on one
   path against a number on the other is a contract violation, not a numerical difference, and must
   never be absorbed by any tolerance.

Where an order-dependent aggregation makes (2) genuinely unreachable for one feature, that feature
carries a documented per-feature tolerance naming the aggregation and the reason. Never a blanket
loosening.

## Consequences

- The deviation is **stricter than the specification everywhere the specification is satisfiable**,
  so it cannot be a way of making a failing test pass.
- `docs/ml/training_serving_parity.md` holds the full design, including prefix replay and the
  mutation cases that prove the test can fail.
- The requirement register's FR-02-02 row cites this ADR, so a reader comparing the code against
  Part E.2 finds the difference explained rather than apparently unimplemented.

## Alternatives rejected

- **Keep `1e-9` literally.** Rejected: the amount-sum features would need per-feature exceptions at
  the top of their range anyway, so the single constant survives in name only while the exceptions
  carry the meaning.
- **Bit-identical everywhere.** Rejected: it forbids honest reassociation, which two independent
  implementations will legitimately differ by, so the test would fail for reasons that are not bugs
  and would be loosened under pressure — the floor shape re-created.
- **Compare decisions rather than vectors.** Rejected as the weakest form: it passes in exactly the
  regime where the stronger check would catch something, and turns feature drift into a production
  incident rather than a test failure.
