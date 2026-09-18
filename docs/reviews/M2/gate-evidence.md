# M2 gate evidence

Collected at branch head `70b6be8` on `m2/generator`. Job-level results, not run conclusions
(GOV-10): a path-filtered workflow concludes "success" while the job the gate is about is skipped, so
a run conclusion is not evidence.

## CI

`ci` run [35307770169](https://github.com/mariusbayizere/fraudshield/actions/runs/35307770169) at
`70b6be8`, every job executed and succeeded:

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

## Stack — not evidenced on this branch, and the reason

**No run on `m2/generator` has executed the stack job.** Every `stack` run on the branch concluded
"success" with `core compose stack healthy + smoke test` **skipped**, because M2 changed no compose,
Dockerfile, infrastructure or lockfile path and the workflow is path-filtered (ADR 0010).

The last executed stack evidence is `stack` run
[35235506847](https://github.com/mariusbayizere/fraudshield/actions/runs/35235506847) at `a7e6896` on
`main` (2026-09-17), where `core compose stack healthy + smoke test` succeeded — the M1 merge commit.
M2 adds no service and touches nothing the stack builds, so that remains the applicable evidence;
the M2 gate does not claim the stack was re-verified.

This is the finding GOV-10 was opened for, reproduced on this very milestone: quoting the three run
conclusions would have read "ci, stack and devcontainer green on the head", which is true of the runs
and false of the jobs.

## Local suites at the head

- `uv run pytest dataset/tests tools/tests -q` → **262 passed** (546 s).
- Dataset coverage 97.2%, above the 90% gate.

## Release-size run

Not performed. ML-DATA-01 requires 5,000,000 rows; the verification run is 1,006,249 rows
(`dataset/realism_report.md`). The run must happen on a runner rather than the build laptop (owner
direction) and `workflow_dispatch` registers only from the default branch, which is still
`m0/bootstrap`. Tracked as PB-25; ML-DATA-01 is `VERIFIED_AT_REDUCED_SCALE` in the register with the
same explanation.
