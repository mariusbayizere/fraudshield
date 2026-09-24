# M7 evidence at 84a61eb (prepared merge with M5)

- **Commit:** `84a61eb` on `m7/staff-auth`: the merge of `origin/main` at `0547539` (M5 scoring,
  tagged `m5-complete`) into `48f9410`. Preparation only; nothing merged to `main`, nothing tagged.
- **The merge changed no code.** `git diff 48f9410 84a61eb -- backend/ frontend/` is empty. M5
  landed in `ml/`, `docs/`, `contracts/` and `tools/`. Its contract change is the gRPC scoring
  proto (`ScoreRequest.context` reserved, ADR 0033), not the OpenAPI that `ContractPolicy` reads,
  so the authorisation matrix is untouched.
- **Command:** `./mvnw -B -ntp -o clean verify -fae` over the whole backend reactor, 03:50:20Z to
  04:02:39Z on 2026-09-24. Load average 1.8 at the start, 9.9 at the end.

## Result (`m7_verify_84a61eb.txt`)

| Module | Tests | Failures | Checkstyle | SpotBugs | Coverage gate |
|---|---|---|---|---|---|
| common | 1,131 | 0 | 0 | 0 | met |
| persistence (V1-V11, V70) | 60 | 0 | 0 | 0 | met |
| audit | 50 | 0 | 0 | 0 | met |
| auth | 137 | 0 | 0 | 0 | met |
| admin | 28 | 0 | 0 | 0 | met |

Flyway applied 12 migrations (V1-V11 and V70), which is the expected set after the renumbering.

## Gate items

| Gate | Bound (ADR 0071 §6) | At `84a61eb` | At `0fe8ca7` |
|---|---|---|---|
| Role × endpoint matrix | — | 756 calls; 69 × 401 and 441 × 403 for unlisted callers; identical | identical |
| token_version invalidation, announcement and Redis write lost | 2 s + skew | p50 1,929 ms, max 2,432 ms | p50 1,965 ms, max 2,384 ms |
| same, with pub/sub | — | p50 5 ms, max 23 ms | 5 / 19 ms |
| Revoked API key → 401, announcement lost | 2 s | max 1,798 ms | 1,916 ms |
| same, with pub/sub | — | max 299 ms | 29 ms |

The maxima above the bound are check path, not staleness: the measurement stops when the first
refusal returns, which includes that request's own database read, and the run ended at load
average 9.9. The bounds themselves are asserted in fake time and do not depend on load.

## Governance on the merged tree

Run from the worktree with its own `uv sync --all-packages` environment:

- `fs-traceability check` → 258 rows, 1,083 tagged tests, 0 errors, 0 warnings;
- `fs-traceability render` → reproduces the merged matrix exactly (no diff);
- `fs-defect-register --check`, `fs-traceability-seed --check` → up to date;
- `fs-migration-guard --against origin/main` → 11 merged migrations, 0 changed;
- `fs-scope-guard` → 0 of 789 files with out-of-scope terms;
- `fs-readme-status` → agrees with the register;
- `uv lock --check` → the merged lockfile is what resolution produces.

## CI and hooks

- CI at the previous head `48f9410` was green on all seven `ci` jobs, including
  `java (checkstyle, junit, spotbugs, jacoco)` and `pre-commit hooks on all files (G.5 guards)`,
  confirmed at job level with `gh run view`.
- Local hooks are the real ones: `core.hooksPath` is unset in this checkout and in the worktree,
  which therefore share `.git/hooks`, and each commit's output shows the 16 pre-commit checks and
  the commit-msg check. They have blocked commits here (G.3 subject, stale matrix, missing
  `fs-exit-criteria`).
- **Caveat for merge commits:** pre-commit reports "Checking merge-conflict files only", so
  file-scoped hooks see only the conflicted files. `make governance` is `always_run` and still runs
  in full, and CI's all-files job covers the rest.
