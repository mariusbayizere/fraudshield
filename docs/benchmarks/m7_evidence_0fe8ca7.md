# M7 evidence at 0fe8ca7 (prepared merge with origin/main)

- **Commit:** `0fe8ca7` on `m7/staff-auth`: the merge of `origin/main` at `72e7790` (M4 close-out)
  into `685dc63`. Preparation only; nothing was merged to `main` and nothing tagged.
- **Working tree:** clean for `backend/` during the run; only this evidence record and the
  `M7_updates.md` merge-state section were written afterwards.
- **The merge changed no code.** `git diff 685dc63 0fe8ca7 -- backend/ frontend/` is empty: M4's
  work is in `ml/`, `dataset/`, `docs/` and `tools/`. The backend result below is therefore the
  same code as `m7_evidence_91ee255.md`, re-verified in the merged tree.
- **Command:** `./mvnw -B -ntp -o clean verify -fae` over the whole backend reactor, from
  17:36:27Z to 17:49:04Z on 2026-09-22. Load average 10.3 at the start and 10.7 at the end, the
  highest of any M7 run.

## Result (`m7_verify_0fe8ca7.txt`)

| Module | Tests | Failures | Checkstyle | SpotBugs | Coverage gate |
|---|---|---|---|---|---|
| common | 1,131 | 0 | 0 | 0 | met |
| persistence (V1-V11, V70) | 60 | 0 | 0 | 0 | met |
| audit | 50 | 0 | 0 | 0 | met |
| auth | 137 | 0 | 0 | 0 | met |
| admin | 28 | 0 | 0 | 0 | met |

## Gate items

| Gate | Bound (ADR 0071 §6) | At `0fe8ca7` | At `91ee255` |
|---|---|---|---|
| Role × endpoint matrix | — | 756 calls; 69 × 401 and 441 × 403 for unlisted callers; identical | identical |
| token_version invalidation, announcement and Redis write lost | 2 s + skew | p50 1,965 ms, max 2,384 ms | p50 1,966 ms, max 2,203 ms |
| same, with pub/sub | — | p50 5 ms, max 19 ms | 3 / 10 ms |
| Revoked API key → 401, announcement lost | 2 s | max 1,916 ms | 1,893 ms |
| same, with pub/sub | — | max 29 ms | 50 ms |

The 2,384 ms maximum is the analytic 2 s bound plus 384 ms of check path (the refusing request's
own database read) on a machine at load average 10. The bound itself is asserted in fake time and
does not depend on load.

## Governance on the merged tree

Run from the worktree, with its own `uv sync --all-packages` environment, so the tools are the
merged branch's code and not another checkout's:

- `fs-traceability check` → 258 rows, 967 tagged tests, 0 errors, 0 warnings;
- `fs-traceability render` → reproduces the merged matrix exactly (no diff);
- `fs-defect-register --check` → up to date, 51 defects;
- `fs-traceability-seed --check` → up to date;
- `fs-migration-guard --against origin/main` → 11 merged migrations, 0 changed;
- `uv lock --check` → the merged lockfile is what resolution produces;
- `pytest tools/tests` → 198 passed, coverage 90.57%.

**Licences:** run in `/home/marius/fraudshield`, which has `node_modules` installed: 369
dependencies, **only the 3 pre-existing Python flags** (`scipy`, `nvidia-nccl-cu12`, `xgboost`,
all dev scope). The same command in the worktree reports 144 extra npm "Unknown" entries purely
because `frontend/node_modules` is not installed there. The merge changes no dependency manifest,
so the installed-tree result applies.
