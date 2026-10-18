# Session state: PB-69 on main; M8 rebased and held for review before building

Rewritten 2026-10-18 on `m8/frontend-rebase`, after the laptop restarted mid-session. Facts only;
anything unverified, assumed or open says so. This file reaches `main` when M8 merges; until then
`main`'s copy is the one written at `m7-complete`.

## Read this first

- **`main` is at `a7f0528`: PB-69, the feature store's database fallback.** It was fast-forwarded
  from `c5c136f` (the M4 tail and M7's carries), with no merge commit, on 2026-10-18.
  - The evidence on `a7f0528`, read at job level before the merge: ci 39117521137 (all 7 jobs ran,
    no step skipped; ml 571 tests passed, none skipped, `REQUIRE_DOCKER=1`), stack 39117525636 and
    devcontainer 39117521992 (the real job ran in each; only the on-failure steps skipped).
  - PB-69's code is byte-identical to `b89cc1f`, which the fifth independent review approved
    (0 BLOCKER, 0 MAJOR). Follow-ups in `M6_updates.md`: `Worker.stop` does not close the
    fallback's pooled connection; no deployment passes `--feature-store-database` until M9.
  - `m6/featurestore-fallback` (`fccd2f8`) has the same tree as `a7f0528` and is superseded.
- **Tags:** `m0-complete` to `m7-complete`. No new tag since `m7-complete` (`71b0ec9`).
- **`milestones.yaml` still says `current: M7`, and M7 is not in `completed`.** M7's open Must rows
  were re-filed where a later milestone closes them (NFR-SEC-03 and FR-06-06 to M8B, D-51 to M9).
  - **Seven rows have no later home:** FR-06-01, FR-06-02, FR-07-01, FR-07-06, NFR-REL-06, D-26
    and D-32. Each lacks M7's own backend work (PB-104 to PB-112). Table:
    `docs/parallel/M7_updates.md`, "Tagged, and the carries". The close-out round (about two
    days) runs after M8.
- **The API-key cache follows `docs/architecture/api-key-cache-contract.md`** (option 1, ADR 0073).
  **Rule for any change to that cache:** amend the contract first, have it reviewed on its own,
  then change the code.
- **The deletion standard governs every claim:** a test is evidence for code only if deleting that
  code fails it.

## The owner's plan: every milestone before the paper

| # | Step | State |
|---|---|---|
| 1 | The M4 tail | merged |
| 2 | PB-69 | merged, `a7f0528` |
| 3 | **M8** | rebased (`m8/frontend-rebase`), reviewed changes pending; building waits for the owner's go |
| 4 | M9 | infrastructure; the owner's decisions are in `docs/parallel/M9_updates.md` |
| 4b | M8B | the analyst API, alongside M9; on `main` before M10 (`docs/parallel/M8B_updates.md`) |
| 5 | M10 | the verification campaign, once M6 to M9 and M8B are on `main` |
| 6 | M11 | the paper, with final numbers |
| 7 | M12 | the final audit and v1.0.0 |

The M7 close-out round (seven rows) runs after M8. **M13** (operational reporting, SRS v5.0 FR-08)
is in build prompt D.3 between M11 and M12 but is not in this plan; the register sorts it last.
Where it goes is the owner's to settle.

## M8: where it stands

- **Branches.** `m8/frontend-rebase` is `m8/frontend` (40f5bdc) rebased onto `a7f0528`, plus the
  changes below. The original is kept as `m8/frontend-pre-rebase-40f5bdc`; `m8/frontend` itself
  has not been moved.
- **Routes:** 29 with a path, plus `/` redirecting to `/alerts`. Six are built (the public auth
  pages); 23 are placeholders that say the screen is not built (22 shell sections and
  `/alerts/$alertId`).
- **`/system/status` is not in the contract.** M8 added it on its own branch in 46893d7; the rebase
  dropped that commit, the console no longer reads it, and the change is proposed on its own on
  `m8/system-status-proposal` (ADR 0081, Proposed) for an independent review. `main` implements
  neither `/system/status` nor `/admin/health`, so the degraded-mode banners have nothing to read.
- **Alert filters are `listAlerts`' parameters** (the contract wins): no free text, lists for
  tier, channel and outcome. FR-04-09 needs nothing more, so no contract change is proposed.
- **Mocks are not evidence.** Every analyst-screen row names, in its notes, the M8B operations it
  needs, and stays at most IN_PROGRESS until they exist on `main`. UX-ROLE-SN-02 (reassignment) and
  UX-ROLE-SN-04 (team performance) have no contract operation at all.
- Details: `docs/parallel/M8_updates.md`, sections 4 and 7.

## Branches

| Branch | State |
|---|---|
| `main` | `a7f0528`, PB-69 |
| `m8/frontend-rebase` | M8 on `main`; not merged |
| `m8/system-status-proposal` | one commit on `main`: the `/system/status` proposal, for review |
| `m8/frontend` | 40f5bdc, the pre-rebase branch, superseded by `m8/frontend-rebase` |
| `m6/featurestore-fallback`, `m6/pb69-on-main` | merged as `a7f0528` |
| `m9/infra`, `m10/verification`, `m11/paper` | in progress on their own branches |

The default branch is still `m0/bootstrap`, a repository setting only the owner can change (GOV-9).

## Things that will bite whoever picks this up

- **Memory is the constraint.** The laptop has 7.6 GB, often under 2 GB free.
  - Check `free -h` before any build, and stop below 1.5 GB.
  - Run one heavy job at a time.
  - Run Docker verification on GitHub Actions wherever a dispatch can do it.
  - There is no local Maven repository and no `frontend/node_modules`: Java and the frontend are
    compiled and tested in CI only.
- **Read CI at job level.** `ci` runs on every push. `stack` and `devcontainer` are path-filtered on
  push, so dispatch them (`gh workflow run stack.yml --ref <branch>`) and check that each job ran
  rather than being skipped.
- **Traceability, in order.** After editing `requirements.yaml`, run `fs-traceability-seed`, then
  `fs-traceability render`, then `fs-traceability check`. Without a virtual environment they run as
  `PYTHONPATH=<worktree>/tools/src python3 -m fraudshield_tools.<module>` (system Python has PyYAML).
- **Commit messages.** Chain `fs-commit-msg` and `git push` with `&&`, never `;`; check chains use
  `set -euo pipefail` or no pipes.
- **Several sessions share `/home/marius/fraudshield`.** Work in a worktree (`/home/marius/fs-wt/…`)
  and use absolute paths. M8's worktrees: `fs-wt/m8` and `fs-wt/m8-status`.
- **Claim an id before using it** (`docs/backlog/product.md`, claims table). ADR numbers, Flyway
  versions and backlog ids are global. M8 holds ADR 0080 to 0082 and defects D-52 to D-54; no other
  branch uses them (checked 2026-10-18).
