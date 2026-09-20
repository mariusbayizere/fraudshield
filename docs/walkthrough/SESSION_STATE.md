# Session state — M3, all 44 features implemented on both paths

Rewritten 2026-09-20. Facts only; where something is unverified, assumed or open it says so.

## Where the work is

- **Branch:** `m3/features`, pushed. **Tag `m2-complete`** is at `ed7a8d9` on `m2/generator`.
- **Milestone register:** `current: M3`, `completed: [M0, M1, M2]`.

| Commit | What |
|---|---|
| `654c6d8` | fail closed without a durable first-seen; every gate reports its size |
| `65c8351` | `corridor_class` from the packs (PB-30); the `categories` contract field |
| `ae41c88` | the dataset fingerprint (PB-41), and export refuses a report about other data |
| `01af5b6` | velocity, amount behaviour, temporal and geographic — 21 features |
| `083f92e` | counterparty, device, account profile, agent, synthetic identity — 19 features |

**Verified state at `083f92e`:** ml suite **222 passed, 96.80%** branch coverage; `mypy` clean over
24 ml files and 110 across the workspace; ruff clean; governance **258 rows, 573 tagged tests, 0
errors, 0 warnings**; scope guard 406 files. The **full dataset suite passed at `ae41c88`: 125
passed in 66 minutes, 93.98%**, and nothing since has touched `dataset/`.

## All 44 are implemented. What that does and does not mean

`fraudshield_ml.features.batch` and `.online` implement every registered feature, neither imports
the other, and `test_completeness` asserts the registry's list and the implemented list are the
same list — which nothing else did, because every other test tests the features it names.

**It does not mean 44 features can be computed on this dataset.** Thirty-six can. The other eight
read reference data neither the dataset nor M1's schema holds (**PB-44**), so they return NaN for
every row — which D-04 covers, and that is the danger: a feature NaN everywhere is
indistinguishable in a training run from one merely often missing, while the count still reads 44.

**Three features are declared degenerate** in the registry, for two different reasons that must
not be confused: `accounts_per_device_7d` and `synthetic_identity_score` by a generator gap due to
be fixed (PB-40), `corridor_class` because the owner has ruled the simulated country set is not to
be broadened (PB-43, ADR 0023) — two of its four classes are unreachable here.

## The parity mutation table: twelve of thirteen rows executed

Only **row 9** is pending, needing the DB fallback path (PB-36). Three rows changed what they mean
when they were run, which is the table's value:

- **Row 1, the control that must pass, failed its own precondition.** Both paths sum with the
  built-in `sum()`, and since CPython 3.12 that is Neumaier-compensated, so the reassociation the
  tolerance exists to permit does not occur between them. Not deleted as vacuous: the production
  online path will keep an incrementally updated running total, which cannot be compensated, so
  the difference arrives at M6 unchanged. Now measured against a naive running total — 7.5e-9
  against a tolerance of 1.0e-5.
- **Row 5 is the row the tolerance structurally cannot catch.** `abs(nan - 0.0)` is NaN and every
  comparison against NaN is False, so a tolerance check reports *agreement* — it fails open. That
  is why ADR 0025 states NaN positions as a separate rule rather than a tighter bound.
- **Rows 4 and 11 turn on their fixtures, not their values.** At local noon the local and UTC
  readings coincide; the two paths agree on thresholds for every transaction newer than the last
  configuration change. Both assert their precondition before comparing anything.

## Two owner decisions, recorded in the registry entries

- **`is_new_country_for_account` reads the counterparty's country.** "The scored transaction's
  country" is not computable: nothing records where a transaction happened, and resolving
  coordinates would put a geocoder in the feature path.
- **A predecessor is strictly earlier**, so the zero-elapsed clause for `implied_speed_kmh` is
  withdrawn and its branch deleted as unreachable. Strictly-earlier is the only bound the online
  path can implement, since a transaction stamped the same instant may not have arrived.
  Measured consequence: **zero of 201,243 rows share an (account, timestamp) pair**, so the
  silence costs nothing here, and `tx_count_60s` — whose job simultaneous bursts are — is non-zero
  for about **0.13%** of rows (6 pairs under a second, 261 under a minute).

## Open items

- **PB-44** — eight features have no data to read. Before M4 training.
- **PB-41's remainder** — the committed `realism_report.md` describes a 1,012,522-row run and
  carries no fingerprint until its next regeneration. Export refuses to bundle it with any other
  dataset meanwhile, and a test asserts that. **Do it together with adding `round_denominations`
  to the packs (PB-44)**: a parameter change makes the report stale anyway, so the two cost one
  evidence run instead of two.
- **PB-40** — the generator shares no device. Before M4 training, deliberately not during M3.
- **PB-43** — two `corridor_class` classes are unreachable. Needs an owner decision, either way.
- **PB-42** — `CROSS_BLOC_AFRICA` names a continent the rule does not test. Low.
- **PB-36/37/38** — untested DB fallback; no per-account durable table; the 8-day refresh under a
  30-day feature.
- **PB-25** — the 5M run, still blocked on the default branch being `m0/bootstrap`.
- **E3, E6, E11** — the single-feature AUC re-check, the `account_events` join measurement, and
  the milestone review. None started.

## Things that will bite whoever picks this up

- **An evidence run owns the tree**, and `pre-commit` stashes unstaged changes — so **do not commit
  while a suite runs**. Two commits in this session were entangled through
  `requirements_matrix.md`: the hook regenerates it with unstaged work stashed, so the matrix
  disagreed with the reduced tree and governance failed. Fix: `git stash push -u` the unrelated
  work, re-render, commit, pop.
- **An exit code from a piped pytest is `tail`'s, not pytest's.** Use `${PIPESTATUS[0]}` or drop
  the pipe.
- **`uv run --project <member>` can sync the workspace venv.** Use `.venv/bin/python` while a suite
  runs.
- **Hand-editing `requirements.yaml` breaks the seed check.** Run
  `python -m fraudshield_tools.traceability_seed` to normalise.
- **gitleaks flags `*_key` assignments** with entropy; remove the trigger, never allowlist a
  pattern.
- **Fast subset during development** (`ml/tests` + ruff + mypy, ~10 s); the dataset suite is
  **66 minutes** under load and only needs running when `dataset/` changes.
- **The commit hook wraps at 72 characters and caps the subject at 72** — use `git commit -F` with
  a body rewrapped per paragraph, not per line, or words end up orphaned.
- `git add -A` sweeps agent worktrees; scratch directories do not survive a session; this laptop is
  shared, so check `uptime` first.
