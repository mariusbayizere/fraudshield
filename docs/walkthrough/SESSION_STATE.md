# Session state — M3, feature contract complete, implementation next

Rewritten 2026-09-19. Facts only; where something is unverified, assumed or open it says so.

## Where the work is

- **Branch:** `m3/features`, pushed. **Tag `m2-complete`** is at `ed7a8d9` on `m2/generator`.
- **Milestone register:** `current: M3`, `completed: [M0, M1, M2]`.

| Commit | What |
|---|---|
| `5855386` | E15 — tests may not mutate shared state; bloc sources recorded |
| `5f0a8a1` | all 44 features declared against a ten-field contract; E15's guard implemented and mutation-proved |
| `d85385f` | session findings, the prediction register, PB-36 to PB-38 |
| `a36d252` | PB-39 — report regenerated; E1's density measured and its table corrected |
| `7a6fc90` | the re-draw's consequences resolved across the record; E1 resolved; PB-40/41 |

**Verified state:** ml suite 47 passed, 100% branch coverage. Governance gate 258 rows, 320 tagged
tests, 0 errors, 0 warnings. `mypy` clean across 95 files. Dataset suite's two report guards pass.

## The thing that cost this session, and the rule it produced

**PB-29 changed no parameter value and re-drew the entire benchmark.** `countries.simulated()`
sorts, so country iteration went from declaration order to alphabetical and every downstream draw
shifted. The pack FX rates are byte-identical to the table they replaced. Every gate still passes
and every rank order holds; absolute figures moved — event delay 0.758 → **0.746**,
`merchant_category_code` 0.706 → **0.707**, rows 1,006,249 → **1,012,522**.

**No guard saw it for three commits.** The report digests parameter *values*, so a changed draw with
unchanged values is invisible to it **by construction**. It caught PB-39 only because the refactor
also moved the parameter *structure*. Sixth instance of the guard-with-two-doors shape.

- **Standing rule: a figure is quoted with its tree hash, not only its scale.** "Deterministic from
  a seed" was true throughout and was not sufficient.
- **PB-41 is the structural fix** and is **not yet implemented**: a dataset fingerprint over output
  rows, in `release.json` and the report, keeping the parameter digest alongside.

## The feature contract — complete

`ml/src/fraudshield_ml/features/registry.py`. Declarative, computes nothing, the only module both
paths import. All **44 declared**, group counts matching Part E.2 exactly (8/5/6/5/5/5/4/4/1/1).

Ten contract fields, none of which validation will leave blank on a windowed feature:
`self_inclusion` (EXCLUDED), `nesting` (SHORT_EXCLUDED), `smoothing` (equal alpha both terms, with
an explicit `prior`), `history_basis` (OBSERVED_CAPPED forces DURABLE), `history_requirement`,
`fallback_behaviour`, `label_basis`, `minimum_history`, `history_key`, `reference_data_basis`.

Six of the ten arrived by attacking the schema, not designing it. **The lesson, for the paper:**
derive a contract from one case, then immediately attack it with the most differently-shaped case
available, before it has dependents.

## E1 — resolved

E1 now states the rule **per `history_key`**, not globally. Account grouping isolates 37 features
and not the other seven.

**Component folding was measured and rejected** (`docs/research/component_sizes.json`, tree
`d85385f`): counterparty and geo-cell graphs are single components spanning **100%** of accounts,
agent reaches **38%**. It is available for one of four relational keys, and that one —
device — only because nothing is shared.

**The adopted rule: out-of-fold encodings are computed over rows strictly earlier in time than the
row being encoded, never over random folds.** The split is already temporal with an embargo, and a
validation row reading earlier cross-account rows is what serving does, not leakage. The live
exposure was only out-of-fold target encoding *inside* the training period — where M-8 lived.
Carried to `ML-GATE-01`–`04`, `ML-GATE-11` and `FR-02-03` so M4 cannot be designed as if open.

## Open items

- **PB-41** — the dataset fingerprint. Designed, not built.
- **PB-40** — no device is shared, so `accounts_per_device_7d` is identically 1 and
  `synthetic_identity_score` loses a term. Both **declare `degeneracy`** in the registry with tests.
  The generator fix is scheduled **before M4 training, deliberately not during M3**: changing it
  re-draws the dataset again.
- **PB-36/37/38** — untested DB fallback path; no per-account table for `account_first_seen_at`;
  `account_activity_hourly` refreshes 8 days under a 30-day feature.
- **PB-30** — `corridor_class` declared, not implemented.
- **PB-25** — the 5M run, still blocked on the default branch being `m0/bootstrap`.

## Predictions, four recorded

One partially right. Full register in the lab notebook, including why three refutations are worth
more than four confirmations. **Outcomes are reported in the same place, confirmed or refuted, and
a refuted prediction is never edited away.**

## Exact next steps

1. **The two features end-to-end** — `fraudshield_ml.features.batch` and `.online`, independent,
   with the import-graph test asserting neither imports the other, and hand-computed unit tests per
   E.2. The registry's contracts are the specification for both.
2. **The parity suite** — prefix replay, the fallback path, the cold-cache case and the required
   configuration-change fixture, working the 12-row mutation table from `pending`.
3. **PB-41**, before the next release export.
4. Then the other 42 implementations.

## Things that will bite whoever picks this up

- **An exit code from a piped pytest is `tail`'s, not pytest's.** A 53-minute suite reported
  1 failed / 107 passed while its pipeline exited 0. Use `${PIPESTATUS[0]}` or drop the pipe.
- **An evidence run owns the tree, the environment and commits** — `pre-commit` stashes unstaged
  changes, which moves files under a running suite.
- **`uv run --project <member>` can sync the workspace venv.** Use `.venv/bin/python` while a suite
  runs.
- **Hand-editing `requirements.yaml` breaks the seed check.** Run
  `python -m fraudshield_tools.traceability_seed` to normalise; progress fields survive.
- **gitleaks flags `*_key` assignments** with entropy. The config allowlists exact values only,
  never patterns, so remove the trigger rather than the finding.
- **Fast subset during development** (`ml/tests` + ruff + mypy, ~30 s); full dataset runs only at
  commit boundaries — 53 minutes under load.
- `git add -A` sweeps agent worktrees; the commit hook wraps at 72 characters, so use `git commit -F`;
  scratch directories do not survive a session; this laptop is shared, so check `uptime` first.
