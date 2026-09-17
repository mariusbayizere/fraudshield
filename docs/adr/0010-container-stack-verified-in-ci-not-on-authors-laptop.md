# 0010 — Container stack verified in CI, not on the author's laptop

- **Status:** Accepted
- **Decided by:** repository owner (items 1–4 and the existence of per-profile memory budgets,
  2026-09-17); the author proposed the enforcement design and budget numbers, which the owner
  confirmed on 2026-09-17 ("your implementation is correct"), plus the path-filtered CI triggers
  the owner requested later the same day. Each item below says which.
- **Date:** 2026-09-17
- **Requirements affected:** OPS-CI-01 … OPS-CI-08, NFR-PERF-01 … NFR-PERF-08, NFR-PERF-10,
  FR-01-01, FR-01-06, FR-02-02, FR-02-07, FR-02-09, FR-03-01, FR-03-03, ML-GATE-12, NFR-REL-01 …
  NFR-REL-06, TEST-05, TEST-09, TEST-10, TEST-13
- **Defects referenced:** D-13, D-15, D-16, D-51

## Context

The reference development machine (`dev-laptop-01` in `docs/benchmarks/hardware.md`: 2 cores,
7.8 GiB RAM, about 1.8 GiB free while the owner works) cannot run the Docker stack alongside normal
work. The owner decided that Docker will not run on it. The M0 gate requires "`make up` healthy";
from M1 onward, Testcontainers, database security, integration and chaos suites need Docker; and
M5, M6 and M10 have latency and throughput gates.

Measured so far: the `core` profile sets memory limits totalling 3,968 MiB, more than the laptop
has free. GitHub-hosted `ubuntu-24.04` runners provide Docker, and the CI `stack` job brought the
stack up (`make up --wait`) and passed the functional smoke test on runs 35178641969 and
35179479949, after intermittent MLflow worker deaths were diagnosed and fixed (commit 2882a99).

Shared CI runners are unsuitable for performance gates: their CPU model, neighbours and I/O vary
between runs, so latency percentiles are not repeatable.

## Options considered

1. **Run Docker on the laptop anyway** — rejected by the owner; also starves the machine.
2. **Treat unverified Docker suites as passing locally** — a silent skip is indistinguishable from a
   pass; rejected.
3. **CI as the authoritative Docker environment, Codespaces for interactive Docker work, explicit
   skips locally, dedicated machines for benchmarks.**

## Decision

Option 3.

1. **M0 gate evidence** *(owner decision)*. The CI job `stack` (`make up` with `--wait`, then `make smoke`) is the
   authoritative evidence for "`make up` healthy". Because the job was intermittent before
   2882a99, M0 requires **three consecutive green `stack` runs** on successive commits of the
   branch, recorded with run IDs in `docs/reviews/M0/stack-gate-evidence.md`, before merging to
   `main` and tagging `m0-complete`. The run on the commit that is tagged must also be green.
2. **Codespaces** *(owner decision; configuration by the author)*. `.devcontainer/devcontainer.json` (4 cores / 16 GB; Docker-in-Docker; Temurin
   21.0.12; Node 24.21.0 with pnpm via Corepack; uv 0.12.15 and Python 3.12.14, checksum-verified)
   runs `REQUIRE_DOCKER=1 make ci` after creation. The `devcontainer` workflow builds it on a
   GitHub runner and re-runs the smoke test inside it. **Status: verified** by run 35184012247 on
   6942c96 (post-create including the Docker stack, then the in-container smoke test), after four
   failing runs whose causes — git ownership of the mounted workspace, the pnpm store inside the
   workspace, and PATH — were found through the post-create log annotation and fixed.
3. **Docker-dependent suites** *(owner decision; mechanisms designed by the author)*. They run in CI on every push (`REQUIRE_DOCKER=1` for all CI jobs)
   and can be run interactively in Codespaces. On a machine without Docker they are skipped with
   an explicit message, never silently:
   - Make targets: `tools/bin/docker-gate <suite> <ci-job> -- <command>` prints
     `SKIPPED: <suite> requires Docker, verified in CI (job: <ci-job>)`; `make ci` includes
     `stack-test` through it.
   - JUnit: tag Docker tests `@Tag("requires-docker")`; `make test-java` excludes that group without
     Docker and prints a SKIPPED line. (Lower-case tags are execution groups, not requirement IDs.)
   - pytest: `@pytest.mark.requires_docker` (plugin `fraudshield_tools.pytest_docker`) skips with a
     reason and a terminal summary line.
   - With `REQUIRE_DOCKER` set (CI, Codespaces), a missing Docker daemon is an error.
4. **Performance benchmarks** *(owner decision; enforcement designed by the author)* (M5, M6, M10 and every row in
   `fraudshield_tools.evidence.BENCHMARK_ROWS`) are never measured on shared CI runners for gate
   purposes. Gate numbers come from a dedicated machine — a Codespaces machine type or a cloud
   instance — whose exact specification (CPU model, core count, memory, OS, kernel, JVM and
   Python versions, and whether it is shared) is recorded as a machine section in
   `docs/benchmarks/hardware.md` at the time of measurement. Until such a machine is used, those
   rows may only reach `VERIFIED_AT_REDUCED_SCALE`, with `reduced_scale` naming the machine
   (`machine: <id>`). `fs-traceability check` enforces part of this today: benchmark rows need a
   file under `docs/benchmarks/`, a CI run URL alone is not accepted, and a reduced-scale row must
   name a recorded machine. **Known gaps** (delta review DR-2, logged in
   `docs/backlog/governance.md`, to be closed before FR-02-02 moves in M3): a `DONE` row is not yet
   required to name a dedicated machine, any file under `docs/benchmarks/` is accepted, and some
   timing rows (MOB-PERF, FR-04-07, D-13, D-16) are not yet in the benchmark list. CI may
   still run benchmarks as smoke tests (does it run, does it regress grossly), labelled as such.
5. **Memory budgets per compose profile** *(owner request; numbers proposed by the author and
   confirmed by the owner; ml/obs/full are provisional until those services exist)* (MiB, sum of `deploy.resources.limits.memory`):
   core 4,096; ml 6,144; obs 5,120; full 10,240 — sized for the 16 GB Codespaces machine with room
   for the IDE, Maven and test JVMs. `fs-compose-budget` (governance job) fails if any service lacks
   a limit, uses a profile without a budget, or a profile exceeds its budget. Known gaps (DR-4,
   backlog): services without profiles and `replicas` are not counted.
6. **CI minutes** *(owner direction)*. The `stack` job (`stack.yml`) and the `devcontainer`
   workflow run only when their inputs change (compose files, Dockerfiles, container scripts,
   `.devcontainer/`, Makefile, dependency locks and manifests), and always on pushes to `main`,
   nightly and on manual dispatch. Scheduled runs use the default branch, so they start once
   `main` is the default branch.

## Consequences

- The laptop remains fully usable for everything that does not need Docker: unit tests, lint, type
  checks, governance, licence checks, feature code, and ML training at reduced scale.
- A Docker-dependent failure is found in CI minutes after a push, not before it; developers who need
  a fast Docker loop use Codespaces.
- By policy, benchmark rows are not closed as `DONE` until a dedicated machine is provisioned and
  recorded; the check does not yet fully enforce this (DR-2), so reviewers verify it until then.
- The Codespaces machine and GitHub runners are billed or quota-limited resources of the owner's
  account; their use is the owner's decision.
