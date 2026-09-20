# The 44 features: what each one needs, and which the benchmark can feed

Part E.2 names 44 engineered features. All 44 are implemented on both the batch and the online
path, and `ml/tests/features/test_completeness.py` asserts that the registry's list and the
implemented list are the same list.

**Implemented is not the same as fed.** A feature whose inputs do not exist returns NaN for every
row; D-04's native missing handling covers that, so nothing raises and nothing warns, and the
feature count still reads 44. In a training run a feature that is NaN everywhere is
indistinguishable from one that is merely often missing. That is PB-44, and this page is where the
distinction is written down.

## The declaration, and the check that keeps it honest

Every feature declares `computable` in `ml/src/fraudshield_ml/features/registry.py`:

| Value | Meaning |
|---|---|
| `COMPUTABLE` | the benchmark holds the data this feature reads |
| `NO_SOURCE_DATA` | it does not, and `source_data_gap` says what is missing and which milestone supplies it |

The registry refuses a `NO_SOURCE_DATA` feature whose gap is blank, and refuses one whose gap names
no milestone — "this needs a table" without a date is a note rather than a plan, and it is how a
gap survives three milestones. It also refuses a `COMPUTABLE` feature that carries a gap, because a
field that can be set anywhere means nothing.

**The declaration is only worth having because something checks it.**
`fs-features computability <dataset> --packs <packs.json>` computes all 44 over a generated dataset
and fails in **both** directions:

- a feature declared `COMPUTABLE` that is NaN for **100%** of rows is **dead** — trained on,
  counted, carrying nothing;
- a feature declared `NO_SOURCE_DATA` that is **not** NaN for every row is **revived** — somebody
  wired the data and the register is now stale in the other direction, which is no more true than
  optimism and is exactly as trusted by a reader.

**100%, not a threshold.** Four device features are NaN on every USSD transaction and four agent
features on every non-agent one; those are the D-04 structural NaNs, and they are correct. Picking
a cut-off would mean deciding how dead is dead. "Never produced a number" is the only unambiguous
reading.

The check reports how many rows it scanned and refuses an empty sample, per ADR 0009's
generalisation: a check that has only ever run over an empty scope is untested.

## The six the benchmark cannot feed

Measured, not estimated. An earlier note in this repository said eight, from reasoning about which
inputs were missing rather than from computing the features; the check disagreed, which is what it
is for.

| Feature | Needs | Where it comes from | Milestone |
|---|---|---|---|
| `account_age_days` | the account's opening date | a per-account table — M1 has none at all (PB-37) | M6 |
| `counterparty_account_age_days` | the counterparty account's opening date | the same table | M6 |
| `kyc_tier` | tier assignments carrying `effective_at` | a per-account tier history (ADR 0026) | M6 |
| `agent_float_utilisation_ratio` | float balance and limit, as-of | an agent standing table; agents are only a token column today | M6 |
| `agent_distance_from_registered_km` | the registered premises, as-of | the same agent table | M6 |
| `round_sum_flag` | the currency's common denominations | a `round_denominations` field in the country packs (ADR 0023) | M3 |

`round_sum_flag` is the cheapest and is scheduled with **PB-41's pending report regeneration**: a
new parameter makes the committed realism report stale anyway, so the two cost one evidence run
together rather than two apart.

**Two substitutes were considered and rejected**, because each is the kind that reads plausibly and
is wrong in one direction:

- *the counterparty's earliest transaction in the data*, for its account age — bounded below by the
  dataset's own start, so the whole population would read as no older than the benchmark;
- *the current KYC tier*, for the tier in force — upgrades are frequently triggered by
  investigation, so today's tier of an investigated account is a consequence of the fraud being
  scored. That is a label arriving through a profile column, not merely an anachronism.

## One the benchmark can feed, and one it feeds only by inference

`days_since_sim_swap` is **computable**: `account_events` carries `SIM_SWAP` rows with timestamps.
It needs a join, and forgetting the join is exactly what the check reports — the feature goes NaN
for every row while nothing raises. `ml/tests/features/test_vector.py` runs that case deliberately.

**The dataset does not carry the account's own country**, which six features need: the five
local-time features need a UTC offset, and `corridor_class` needs the corridor's origin.
`transactions` has `counterparty_country` and nothing for the sender. The pipeline recovers it from
the transaction's **currency**, which inverts exactly because the generator derives the currency
*from* the customer's country — but only while currencies are distinct across the simulated packs.
`fs-features` therefore **refuses** when two packs share a currency (XOF across West Africa is the
obvious case) rather than tie-breaking, because an ambiguous inversion would place transactions in
the wrong country and shift every local-time feature by hours. The honest fix is an
`account_country` column, not a cleverer inference; recorded in PB-44.

## What M4 must do with this

1. **Train on the features that carry information, and report that number with every metric.**
   Never "44 features" where fewer were used. If one of the six becomes computable later, that is a
   documented change to the model's input and a reason to restate earlier numbers — not a silent
   improvement.
2. **`corridor_class` is evaluated over two of its four classes.** `CROSS_BLOC_AFRICA` and
   `INTERCONTINENTAL` are implemented and unit-tested against the synthetic Country Z pack, and no
   row of the shipped dataset produces either: every simulated country is in the EAC and on one
   continent, and every cross-border corridor ends at another simulated country. A model trained
   here has never seen them. That is a stated limitation, not a defect to fix — ADR 0023's owner
   direction is that the simulated country set is not to be broadened (PB-43).
3. **`accounts_per_device_7d` is constant and `synthetic_identity_score` is one term short** until
   the generator shares devices between accounts (PB-40), which is scheduled before M4 training.
4. **The synthetic-identity composite's equal weights are a choice, not a derived optimum.**
   Part E.2 delegates the form; the weights are equal because the composite carries no fitted
   quantity and there was no evidence to argue any other weighting from. M4 may revisit them with
   evidence, and should say so if it does.
