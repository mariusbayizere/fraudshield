# M0 gate evidence: container stack healthy (ADR 0010)

Rule (owner decision, 2026-09-17): the CI `stack` job — `make env`, `make up` (all healthchecks,
`--wait`), `make smoke` (functional checks incl. an MLflow artifact stored in and read back from the
S3 store) — is the authoritative evidence for "`make up` healthy". M0 requires three consecutive
green runs on successive commits, plus a green run on the head that becomes `main` and is tagged.
Run conclusions were read from the public GitHub Actions API
(`/repos/mariusbayizere/fraudshield/actions/runs/<id>/jobs`).

## Consecutive green runs

| # | Commit | Workflow run | `stack` job | Other `ci` jobs |
|---|---|---|---|---|
| 1 | 2882a99 | [35178641969](https://github.com/mariusbayizere/fraudshield/actions/runs/35178641969) | success (make up 104 s, smoke 22 s) | 7 of 7 success |
| 2 | cd99dc9 | [35179479949](https://github.com/mariusbayizere/fraudshield/actions/runs/35179479949) | success | 7 of 7 success |
| 3 | b08ba61 | [35181726185](https://github.com/mariusbayizere/fraudshield/actions/runs/35181726185) | success (04:23:13Z–04:24:53Z; includes the bucket assertion added in 4238698) | 7 of 7 success |

Preceding failures, for completeness: 485bdf8, ce4c10d, ce6df6a and 162cc44 failed (smoke-test
health parsing, then intermittent MLflow worker deaths fixed in 2882a99); the 4c7a81e run was
cancelled by a newer push. Commit 2018bec was pushed together with later commits and has no run of
its own. `docker-compose.yml` did not change across runs 1–3.

## Merge-candidate head

Recorded when the head that is fast-forwarded to `main` has green `ci`, `stack` and `devcontainer`
runs.

| Commit | `ci` run | `stack` run | `devcontainer` run |
|---|---|---|---|
| _pending_ | | | |
