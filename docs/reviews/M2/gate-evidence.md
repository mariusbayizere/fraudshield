# M2 gate evidence

Job-level results, not run conclusions (GOV-10): a path-filtered workflow concludes "success" while
the job the gate is about is skipped, so a run conclusion is not evidence.

## The two citations are pinned to different commits, deliberately

`ci` is cited at the branch head; `stack` is cited two commits earlier. That is not an oversight, and
the reason is the same trap GOV-10 exists for:

- **`ci` is not path-filtered**, so every job runs on every commit. Its citation therefore tracks the
  head and should be moved forward whenever the head moves — otherwise the tag rests on a commit that
  is not the one being tagged.
- **`stack` is path-filtered** (ADR 0010). M2 changed no compose, Dockerfile, infrastructure or
  lockfile path, so on any commit that touches none of them the run concludes "success" with
  `core compose stack healthy + smoke test` **skipped**. The head `817db76` is exactly such a commit:
  run [35323956739](https://github.com/mariusbayizere/fraudshield/actions/runs/35323956739) concluded
  success with the gate job **skipped**. Citing it would claim the stack was verified when it never
  ran. So the `stack` citation stays pinned at `f0da249`, the most recent commit where that job
  actually executed.

Moving a `stack` citation forward to a greener-looking run is precisely the mistake this file exists
to prevent.

## CI — at the branch head `817db76`

`ci` run [35323956798](https://github.com/mariusbayizere/fraudshield/actions/runs/35323956798) at
`817db76`, the current branch head, every job executed and succeeded:

| Job | Result |
|---|---|
| python (ruff, mypy --strict, pytest) | success |
| java (checkstyle, junit, spotbugs, jacoco) | success |
| frontend (tsc, eslint, prettier, vitest) | success |
| traceability-check, defect register, scope guard, commit messages | success |
| dependency licence inventory (ADR 0009) | success |
| pre-commit hooks on all files (G.5 guards) | success |
| gitleaks | success |

The previous citation, superseded by the one above, was `ci` run
[35310160693](https://github.com/mariusbayizere/fraudshield/actions/runs/35310160693) at
`f0da249`, also with every job executed and succeeded:

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

## Stack — pinned at `f0da249`, the last commit where the job executed

`stack` run [35310160631](https://github.com/mariusbayizere/fraudshield/actions/runs/35310160631) at
`f0da249`: **`core compose stack healthy + smoke test (M0 gate)` = success**, the job executed rather
than skipped.

This citation is deliberately **not** moved to the head. See the pinning note above: at `817db76` the
stack run concluded success with the gate job skipped behind the path filter, and quoting that would
assert something no job verified.

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
