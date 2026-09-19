# Session state — M3 in progress, feature contract settled

Rewritten 2026-09-19. The previous version of this file described M2's close and said "M3 not
started"; by then five M3 commits existed and none of their decisions were written down anywhere.
That gap is the reason this file is rewritten whenever a decision is settled, not at session end.

Facts only; where something is unverified, assumed or awaiting your answer it says so.

## Where the work is

- **Branch:** `m3/features`, ahead of `m2-complete` (at `ed7a8d9` on `m2/generator`).
- **Milestone register:** `current: M3`, `completed: [M0, M1, M2]`.
- **Commits so far, oldest first:**

| Commit | What |
|---|---|
| `be6fce4` | the parity test design, written before any feature exists |
| `67b5beb` | ADR 0025 — the `1e-9` tolerance replaced by a scale-aware rule |
| `71a1cf5` | PB-26 — the `month=` partition key pinned and documented |
| `683b7b6` | E12–E14 — tests must assert the precondition they depend on |
| `dce89fe` | PB-29 — every country fact moved into packs (ADR 0023) |
| `5855386` | E15 — tests may not mutate shared state; bloc sources recorded |
| `5f0a8a1` | **all 44 features declared against a ten-field contract; E15's guard implemented and mutation-proved** |

SESSION_STATE's old "exact first three steps of M3" — parity design, PB-26, country packs — are
**all done**. Do not redo them.

## The feature contract (the core of this session)

`ml/src/fraudshield_ml/features/registry.py`. Declarative only, computes nothing, and is the single
module both feature paths import (parity Decision 4).

Part E.2 gives nine descriptive fields. **Seven more describe how a window is read**, and exist
because each is a fork where two independent implementations can disagree while both look correct
in review. Validation **refuses to leave any of them blank** on a feature that declares a window,
and refuses a contract on a feature that declares none.

| Field | Values | Settled convention |
|---|---|---|
| `self_inclusion` | `EXCLUDED` / `INCLUDED` | **`EXCLUDED`** — the scored transaction is not in its own window |
| `nesting` | `SHORT_EXCLUDED` / `SHORT_INCLUDED` / `NOT_NESTED` | **`SHORT_EXCLUDED`** — the 1 h numerator is removed from the 30 d denominator, so the baseline does not move with the burst it measures |
| `smoothing` | `alpha`, `placement`, `prior` | **equal alpha on both terms**; the prior is what zero evidence returns |
| `history_basis` | `OBSERVED_CAPPED` / `ASSUMED_FULL` / `NOT_TIME_NORMALISED` | **`OBSERVED_CAPPED`** — divide by history actually observed, capped at the window |
| `history_requirement` | `DURABLE` / `CACHE_SUFFICIENT` | `OBSERVED_CAPPED` **forces `DURABLE`**, enforced in `__post_init__` |
| `fallback_behaviour` | `EXACT` / `NAN_UNDER_FALLBACK` | see the third path, below |
| `label_basis` | `NOT_LABEL_DERIVED` / `AVAILABLE_AT_LAG` | label-derived features filter on `label_available_at`, never `confirmed_at` |
| `minimum_history` | observations + below-threshold value | a hard cliff, distinct from smoothing: a MAD over four points is meaningless, not imprecise |
| `history_key` | `ACCOUNT` / `COUNTERPARTY` / `DEVICE` / `GEO_CELL` / `AGENT` / `MERCHANT` | **the one with teeth** — see below |
| `reference_data_basis` | `AS_OF_EVENT` / `CURRENT` / `NOT_REFERENCE_DATA` | on `FeatureSpec`, not the contract, because `just_below_limit_flag` has no window (ADR 0026) |

**Why `OBSERVED_CAPPED` matters:** `ASSUMED_FULL` scores a three-day-old account as though it had
been quiet for 27 days — indistinguishable from dormancy, and wrong in the direction that makes new
accounts look safe.

**Why equal alpha on both terms:** a zero-history account returns exactly `1.0` — "this account
looks like its own baseline" — rather than `0.0` (reads as suspiciously quiet) or NaN (discards the
row).

**`prior`, `NOT_TIME_NORMALISED` and `label_basis` were added by the second feature**, which broke a
schema fitted to the first in three places. Written up in the lab notebook; it is the strongest
methodological result of this session.

## E1 IS INSUFFICIENT FOR 7 OF THE 44 — the open item needing your decision

E1 requires account-grouped folds because fraud arrives as incidents sharing an account. **That
assumes the thing a feature aggregates over is the account.** For seven features it is not, and for
those, account grouping isolates nothing.

| Key | Features | Control that works |
|---|---|---|
| `counterparty` | `counterparty_unique_senders_24h`, `counterparty_confirmed_fraud_90d` | component folding |
| `device` | `accounts_per_device_7d`, `device_age_days` | component folding |
| `agent` | `agent_cashout_count_1h`, `agent_unique_customers_1h` | component folding |
| `geo_cell` | `geo_cell_fraud_rate_30d` | training-fold restriction |

**The two controls are not interchangeable.** Component folding — grouping folds by connected
component of the account-device (or -counterparty, -agent) graph — gives complete aggregates inside
a fold with no leak and no skew, but only while the graph is sparse. The account-geo-cell graph is
**dense**: every account in one city shares cells, so components degenerate toward the whole dataset
and validation becomes impossible. Geo-cell must therefore use the training-fold restriction and
accept that a cell rate estimated on training rows differs from the serving-time value.

Each feature is registered with the control that works for it, and a non-`ACCOUNT` key is refused at
construction unless it names **both** its control and the mutation that would detect the leak if the
control failed. **E1's text still says "group by account" and needs amending to say what the unit of
history actually is.**

## The two features registered, and why these two

Chosen as the hardest available, so the contract is attacked rather than confirmed:

- **`velocity_ratio_1h_vs_30d`** — nested windows, a ratio, Laplace smoothing, a zero-history case,
  and the only feature so far needing durable state. Registered `NAN_UNDER_FALLBACK`.
- **`geo_cell_fraud_rate_30d`** — label-derived, so it is the feature most able to leak: subject to
  the D-08 AUC ceiling of 0.80, to E1's account-grouped folds for its fitted prior, and to E.2's
  `label_available_at` rule. Registered `EXACT`. Its prior is `PriorSource.GLOBAL_TRAIN_RATE`, never
  a literal — a literal would be scale-dependent (E2) and computing it over all rows would leak
  validation labels into every cell.

Tests: `ml/tests/features/test_registry.py`, 14 tests, **100% branch coverage** on the registry,
each asserting its precondition per E12.

## There are three feature paths, not two

**This corrects the parity design's original premise.** M1 already built the third:
`account_activity_hourly`, a continuous aggregate whose own comment names it the "database fallback
for velocity features when Redis is down (C.4)". FR-02-09 requires it, and M1's milestone review
already recorded the continuous aggregates as **implemented, untested (MAJOR-2)**.

It buckets to the hour, so `tx_count_60s` is not computable from it at all, and a trailing 1 h count
is not the same number as a bucket-aligned one. **The divergence appears only during an incident** —
Redis down, fallback serving, features computed a different way, in a regime no warm-path test
visits. Hence `fallback_behaviour`, and hence the rule that a bucket-aligned substitute for a
trailing window is forbidden: it passes every warm-path test and diverges only where nothing is
looking.

Recorded as Decision 6 in `docs/ml/training_serving_parity.md`, with mutations 9 and 10 and a
cold-cache coverage case — the only case that exercises `history_requirement=DURABLE`.

## Recorded prediction — REPORTED, and a new one open

**Before writing any further feature:** `counterparty_unique_senders_24h` will force an **eighth**
contract field, because its window is keyed by the *counterparty* while every field currently in the
contract silently assumes the account is the unit of history.

**Outcome: confirmed on mechanism, refuted on sequence.** Field 8 (`minimum_history`) arrived first,
from the amount group, not the counterparty group. Field 9 (`history_key`) then arrived from
`counterparty_unique_senders_24h` exactly as predicted and for the stated reason — and immediately
exposed a false claim in `geo_cell_fraud_rate_30d`'s leakage note, which asserted that
account-grouped folds kept validation labels out of cell estimates. They do not. Full account in the
lab notebook.

**New prediction, open:** `accounts_per_device_7d` will be the first feature whose fold-safety
requirement and whose purpose are in genuine conflict — cross-account aggregation *is* the signal —
and will need an owner decision rather than an implementation.

## Open, and needing your answer

- **`fallback_behaviour` for `velocity_ratio_1h_vs_30d` is my choice, not yours.** I registered it
  `NAN_UNDER_FALLBACK` (feature absent under fallback, D-04 missing handling covers it) rather than
  `EXACT` (fallback reads raw `transactions`, heavier query on the degraded path). Changing the enum
  value later is cheap; adding the field later would have meant revisiting all 44, which is why I
  did not block on it.

## The pack-refactor suite: 107 passed, 1 FAILED — and exit code 0 was a lie

The full dataset suite over `5855386` ran 53 minutes and reported **1 failed, 107 passed**. The
background command piped through `tail -12`, so the *pipeline* exited 0 — `tail`'s status, not
pytest's. A red suite looked green. **Never read an exit code from a piped pytest**; use
`${PIPESTATUS[0]}` or drop the pipe.

The failure is **PB-39**, and it is the guard working, not failing:
`test_the_committed_report_describes_the_current_parameters` — the guard M2 built for MAJOR 4.1,
whose defect was a shipped report describing a superseded parameter set. PB-29's pack refactor
changed the parameters, so `dataset/realism_report.md` (last regenerated at `984351d`) is stale:
committed digest `aa0ec909…`, current `58f314e4…`, and its footer still says "of 81" parameters.

**Fixing it is a 1,006,249-row generation at seed 20260917** — a citable evidence run that owns the
tree and the environment for its duration. Not started; it is an owner call.

## Defects found this session, all logged not chased

- **PB-35** — FR-02-02's register row lists group counts summing to **46**, not 44. E.2 enumerates
  every feature and sums to 44, so the row is wrong. It matters because FR-02-02's acceptance
  criterion is itself a count.
- **PB-36** — the DB fallback path is untested (high; due with the parity suite).
- **PB-37** — **M1 has no per-account table at all**, so `account_first_seen_at` has nowhere to live;
  `OBSERVED_CAPPED` needs it. M6 migration, declared now so it is not a surprise then. Related:
  `transactions` has a compression policy but **no retention policy**, so first-seen is currently
  recoverable by scan — which is not a contract.
- **PB-38** — `account_activity_hourly` refreshes only 8 days while a 30-day feature reads it.

## Exact next steps

1. **Amend E1** to say the unit of history, not "the account" — see the seven features above. This
   blocks nothing yet, but every fold decision after this point depends on it.
2. **PB-39 — regenerate the realism report.** A 1M-row evidence run; decide when.
3. **Write the two chosen features end-to-end** — `batch` and `online` modules, independent, with
   the import-graph test asserting neither imports the other, and hand-computed unit tests per E.2.
   The registry's contracts are now the specification for both.
4. **Then the parity suite**: prefix replay, the fallback path, the cold-cache case and the
   required configuration-change fixture, working the 12-row mutation table from `pending`.
5. **PB-30 — `corridor_class`** is declared in the registry but not implemented.
6. Only then the other 42 implementations.

**Done this session and not needing repeating:** E15's fixture is implemented, tested and
mutation-proved; all 44 features are declared; ADRs 0025 and 0026 are accepted.

## Things that will bite whoever picks this up

- **An evidence run owns the tree *and* the environment**, and that includes **commits**:
  `pre-commit` stashes unstaged changes, which moves files under a running suite. Hold commits, not
  just edits, while a citable run is in flight.
- **`uv run --project <member>` can sync the workspace venv.** Use `.venv/bin/python` directly while
  a suite runs; it cannot sync.
- **`ml` has `--cov-fail-under=90` with branch coverage.** A new module ships with tests or the ml
  suite fails.
- Everything else from M2 still applies: `git add -A` sweeps agent worktrees; the commit-message
  hook wraps at 72 characters, so use `git commit -F`; the traceability matrix is generated and must
  be staged; scratch directories do not survive a session, so anything citable belongs in the repo;
  this laptop is shared, so check `uptime` first.

## Where the evidence lives

- `docs/ml/training_serving_parity.md` — six decisions, ten mutations, the three paths.
- `docs/adr/0025-parity-tolerance-replaces-the-specified-1e-9.md` — the ulp arithmetic.
- `docs/traceability/m3_exit_criteria.md` — E1–E15.
- `docs/research/lab_notebook.md` — including this session's contract-fitted-to-one-example result
  and its recorded prediction about `counterparty_unique_senders_24h`.
- `docs/reviews/M2/` — the M2 record, unchanged.
