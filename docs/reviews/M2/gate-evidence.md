# M2 gate evidence

Collected at branch head `f0da249` on `m2/generator`. Job-level results, not run conclusions
(GOV-10): a path-filtered workflow concludes "success" while the job the gate is about is skipped, so
a run conclusion is not evidence.

## CI

`ci` run [35310160693](https://github.com/mariusbayizere/fraudshield/actions/runs/35310160693) at
`f0da249`, every job executed and succeeded:

| Job | Result |
|---|---|
| python (ruff, mypy --strict, pytest) | success |
| java (checkstyle, junit, spotbugs, jacoco) | success |
| frontend (tsc, eslint, prettier, vitest) | success |
| traceability-check, defect register, scope guard, commit messages | success |
| dependency licence inventory (ADR 0009) | success |
| pre-commit hooks on all files (G.5 guards) | success |
| gitleaks | success |

## Devcontainer

`devcontainer` run [35274800793](https://github.com/mariusbayizere/fraudshield/actions/runs/35274800793)
at `6071444`: **"build devcontainer, post-create make ci, smoke test inside" = success.** That is the
most recent run on this branch whose main job actually executed; later pushes touched only paths
outside `DEVCONTAINER_INPUTS`, so their runs concluded success with the job skipped and are not cited
here.

## Stack — executed on the branch head

`stack` run [35310160631](https://github.com/mariusbayizere/fraudshield/actions/runs/35310160631) at
`f0da249`: **`core compose stack healthy + smoke test (M0 gate)` = success**, the job executed rather
than skipped.

Getting that run took two attempts and both failure modes are now documented in `stack.yml`, beside
the settings that cause them:

1. **Every earlier stack run on this branch skipped the job.** M2 changed no compose, Dockerfile,
   infrastructure or lockfile path, and the workflow is path-filtered (ADR 0010), so each run concluded
   "success" with `core compose stack healthy + smoke test` **skipped**. Quoting those conclusions would
   have claimed the stack was verified when it never ran. `gh` has no credentials in this session, so
   `workflow_dispatch` was unavailable; the owner-approved alternative was a deliberate touch to a
   path-filtered input — `stack.yml` itself — which now documents the trap for the next reader.
2. **The first executing run was cancelled by my own next push.** The concurrency group cancels
   in-progress runs on the same ref, including for a docs-only commit that would have skipped the job.
   A run collected as gate evidence has to be the last push until it finishes.

For completeness, the last executed stack evidence before this branch is run
[35235506847](https://github.com/mariusbayizere/fraudshield/actions/runs/35235506847) at `a7e6896` on
`main`, the M1 merge commit.

## Local suites at the head

- `uv run pytest dataset/tests tools/tests -q` → **262 passed** (546 s).
- Dataset coverage 97.2%, above the 90% gate.

## Release-size run

Not performed. ML-DATA-01 requires 5,000,000 rows; the verification run is 1,006,249 rows
(`dataset/realism_report.md`). The run must happen on a runner rather than the build laptop (owner
direction) and `workflow_dispatch` registers only from the default branch, which is still
`m0/bootstrap`. Tracked as PB-25; ML-DATA-01 is `VERIFIED_AT_REDUCED_SCALE` in the register with the
same explanation.
