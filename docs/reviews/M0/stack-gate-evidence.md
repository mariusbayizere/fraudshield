# M0 gate evidence: container stack healthy (ADR 0010)

Rule (owner decision, 2026-09-17): the CI `stack` job — `make env`, `make up` (all healthchecks,
`--wait`), `make smoke` (functional checks incl. an MLflow artifact stored in and read back from the
S3 store) — is the authoritative evidence for "`make up` healthy". M0 requires three consecutive
green runs on successive commits, plus a green run on the head that becomes `main` and is tagged.
Run conclusions were read from the public GitHub Actions API
(`/repos/mariusbayizere/fraudshield/actions/runs/<id>/jobs`).

## Consecutive green runs (gate: three)

| # | Commit | Workflow run | `stack` job | Other `ci` jobs |
|---|---|---|---|---|
| 1 | 2882a99 | [35178641969](https://github.com/mariusbayizere/fraudshield/actions/runs/35178641969) | success (make up 104 s, smoke 22 s) | 7 of 7 success |
| 2 | cd99dc9 | [35179479949](https://github.com/mariusbayizere/fraudshield/actions/runs/35179479949) | success | 7 of 7 success |
| 3 | b08ba61 | [35181726185](https://github.com/mariusbayizere/fraudshield/actions/runs/35181726185) | success (04:23:13Z–04:24:53Z; includes the bucket assertion added in 4238698) | 7 of 7 success |

Later runs on the branch, all with the `stack` job executed (not skipped by change detection) and
green: 4 · ed7223c · run 35182920003 · 5 · 5761d21 · run 35183327832 · 6 · ff2e30d · run
35183578414 · 7 · 6942c96 · run 35184012351 (job 05:00:07Z–05:01:41Z). Seven consecutive green runs
in total; none failed after 2882a99.

Preceding failures, for completeness: 485bdf8, ce4c10d, ce6df6a and 162cc44 failed (smoke-test
health parsing, then intermittent MLflow worker deaths fixed in 2882a99); the 4c7a81e run was
cancelled by a newer push. Commit 2018bec was pushed together with later commits and has no run of
its own. `docker-compose.yml` did not change across runs 1–3.

## Merge-candidate head

`6942c96` is the first head where all three workflows passed with every job executed:

| Commit | `ci` run | `stack` run | `devcontainer` run |
|---|---|---|---|
| 6942c96 | [35184012244](https://github.com/mariusbayizere/fraudshield/actions/runs/35184012244) — 7 of 7 jobs success | [35184012351](https://github.com/mariusbayizere/fraudshield/actions/runs/35184012351) — make up and smoke success | [35184012247](https://github.com/mariusbayizere/fraudshield/actions/runs/35184012247) — devcontainer built, post-create `REQUIRE_DOCKER=1 make ci` (stack included) and in-container smoke success |

## `main` created at 83a9090 (fast-forward, no force)

Job-level results (a skipped job would still make a run read "success", so each job is quoted):

| Workflow run | Job | Result | Executed (UTC) |
|---|---|---|---|
| ci [35184974170](https://github.com/mariusbayizere/fraudshield/actions/runs/35184974170) | python, java, frontend, governance, licences, pre-commit, gitleaks | 7 of 7 success | 05:14:44–05:15:34 |
| stack [35184974152](https://github.com/mariusbayizere/fraudshield/actions/runs/35184974152) | core compose stack healthy + smoke test | success | 05:14:52–05:16:29 |
| devcontainer [35184974136](https://github.com/mariusbayizere/fraudshield/actions/runs/35184974136) | build devcontainer, post-create make ci, smoke test inside | success | 05:14:49–05:18:55 |

## Closing commit

The M0 closing commit (review records, D-47/D-48 DONE, `completed: [M0]`) is pushed to `main`,
which forces the stack and devcontainer jobs to run; `m0-complete` is tagged on it only after all
three runs are green with jobs executed. Those run IDs are recorded in the tag annotation and in
the M1 walkthrough.
