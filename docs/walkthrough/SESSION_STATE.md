# Session state: PB-69 on main; M8's eight analyst screens built against mocks, stopped for handover

Rewritten 2026-10-18 on `m8/frontend-rebase`, after the laptop restarted mid-session; the M8
sections rewritten 2026-10-19 as the M8 handover (the owner's "then stop"). Facts only;
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
| 3 | **M8** | eight analyst screens built against mocks on `m8/frontend-rebase`, not merged; stopped for handover 2026-10-19 |
| 4 | M9 | infrastructure; the owner's decisions are in `docs/parallel/M9_updates.md` |
| 4b | M8B | the analyst API, alongside M9; on `main` before M10 (`docs/parallel/M8B_updates.md`) |
| 5 | M10 | the verification campaign, once M6 to M9 and M8B are on `main` |
| 6 | M11 | the paper, with final numbers |
| 7 | M12 | the final audit and v1.0.0 |

The M7 close-out round (seven rows) runs after M8. **M13** (operational reporting, SRS v5.0 FR-08)
is in build prompt D.3 between M11 and M12 but is not in this plan; the register sorts it last.
Where it goes is the owner's to settle.

## M8: where it stands (handover, 2026-10-19)

The full handover is `docs/parallel/M8_updates.md`, section 10. In short:

- **Branch.** `m8/frontend-rebase` is 85+ commits on `a7f0528` (`main`), not merged. CI at job
  level on `1ec7ef1`: everything green except the two checks expected red (below).
- **What exists.** Eight analyst screens: the alert feed (`/alerts`), the investigation drawer
  with its decision panel, the SHAP explanation and the account's context, the anomaly queue
  (`/anomalies`), escalations (`/escalations`), search (`/search`) and my performance
  (`/me/performance`). Plus the public auth pages from before. Every other shell section is a
  placeholder that says it is not built.
- **Everything is mocked.** No analyst operation exists on `main`; the screens run against MSW
  handlers built from the contract's examples. **A mock is not evidence**: 36 M8 rows are
  IN_PROGRESS, 2 BLOCKED (FR-04-02, UX-DASH-01), 101 NOT_STARTED, none DONE. **No row leaves
  IN_PROGRESS until M8B serves its operations on `main` and the screen is exercised against them.**
- **Open rows filed by M8:** PB-131 to PB-140 (`docs/backlog/product.md`). High: PB-133 (React and
  MUI too heavy for 3G at 1 Mbps; due M9 or M10), PB-134 (no test decides from the escalations
  drawer), PB-140's open part (below). PB-136 is fixed.
- **Pushing:** only through `tools/bin/safe-push` (see the last section).
- **Sign-out (PB-140, D-27): no sign-out claim can be made yet.** `signOut` clears the token, the
  caches and the return address, tested, but two gaps must close first: **no sign-out control
  exists**, so nothing in the console calls it; and **an expired session is never ended** (a 401
  does not end it), so stale data stays on screen.
- **Expected red in CI:** the bundle gate (MOB-PERF-06 and D-39's margin; first visit 209,715 B
  against 200 KB) and the 3G first paint at 1 Mbps (2.76 s against 2.5 s; 1.98 s at 1.6 Mbps
  passes). Both are kept as recorded, owner's decision, until PB-133.
- **`/system/status` is not in the contract.** It is proposed on `m8/system-status-proposal` (ADR
  0081, Proposed) and belongs to M8B; `main` implements neither it nor `/admin/health`, so the
  degraded-mode banners have nothing to read.
- **M8B's questions** from building against the contract are items 5 to 11 of
  `docs/parallel/M8B_updates.md`: what `listAlerts` lists during PENDING_COMMIT, the time zone of
  `hourly_counts`, the comparison attribute names (same owner as PB-132), uniqueness of the
  account context's lists, the anomaly queue's order, search across queues, a daily performance
  series.

## Branches

| Branch | State |
|---|---|
| `main` | `a7f0528`, PB-69 |
| `m8/frontend-rebase` | M8 on `main`, eight screens against mocks; not merged (head `1ec7ef1` or later) |
| `m8/first-paint-experiment` | an M8 experiment on the 3G first paint; not for merging |
| `m8/system-status-proposal` | one commit on `main`: the `/system/status` proposal, for review |
| `m8/frontend` | 40f5bdc, the pre-rebase branch, superseded by `m8/frontend-rebase` |
| `m6/featurestore-fallback`, `m6/pb69-on-main` | merged as `a7f0528` |
| `m9/infra`, `m10/verification`, `m11/paper` | in progress on their own branches |

The default branch is still `m0/bootstrap`, a repository setting only the owner can change (GOV-9).

## Things that will bite whoever picks this up

- **Memory is the constraint.** The laptop has 7.6 GB, often under 2 GB free.
  - Run every heavy local step through `tools/bin/mem-gate` (floor 1536 MB; exit 75 means it
    refused and the command did not run). A `free` reading in front of a command is not a gate.
  - Run one heavy job at a time.
  - Run Docker verification on GitHub Actions wherever a dispatch can do it.
  - There is no local Maven repository: Java is compiled and tested in CI only. The m8 worktree
    has `frontend/node_modules`; under load the component tests time out locally at 5 s, so CI
    is the record for the frontend.
- **Read CI at job level.** `ci` runs on every push. `stack` and `devcontainer` are path-filtered on
  push, so dispatch them (`gh workflow run stack.yml --ref <branch>`) and check that each job ran
  rather than being skipped.
- **Traceability, in order.** After editing `requirements.yaml`, run `fs-traceability-seed`, then
  `fs-traceability render`, then `fs-traceability check`. Without a virtual environment they run as
  `PYTHONPATH=<worktree>/tools/src python3 -m fraudshield_tools.<module>` (system Python has PyYAML).
- **Push only through `tools/bin/safe-push`** (owner, 2026-10-19). It refuses a dirty tree, a
  detached HEAD, `main`, and any force but `--force-with-lease=<branch>:<sha>`. It pins HEAD,
  reads the remote branch's real tip with `ls-remote`, and runs `fs-commit-msg` over every commit
  the push adds plus eight of CI's governance checks (traceability, seed, defect register, scope
  guard, README status, compose budget, contract baselines, migration guard). Only if all pass,
  and HEAD and the tree have not moved, does it push that commit, leased on the tip it checked
  against. Not run locally: `fs-dataset provenance --check` and `docker compose config`; and
  traceability only warns locally on a run URL it cannot verify. So a pass is necessary, not
  sufficient: read CI at job level. A rule to remember ("chain with `&&`") failed four times; the
  script is the safeguard. Do not use a plain `git push`. Check chains still use `set -euo pipefail` or no pipes.
- **Several sessions share `/home/marius/fraudshield`.** Work in a worktree (`/home/marius/fs-wt/…`)
  and use absolute paths. M8's worktrees: `fs-wt/m8` and `fs-wt/m8-status`.
- **Claim an id before using it** (`docs/backlog/product.md`, claims table). ADR numbers, Flyway
  versions and backlog ids are global. M8 holds ADR 0080 to 0089 (0080 to 0085 used), defects
  D-52 to D-54 and PB-131 to PB-140 (all used; a further row needs a new claim).
- **Test tags are requirement IDs only.** `fs-traceability check` rejects a backlog id such as
  `[PB-140]` in a test name; name the row in a comment.
