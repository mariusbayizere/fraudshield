# Contributing

## Workflow

1. Branch from `main` as `m<N>/<short-scope>` (for example `m3/velocity-features`).
2. For each requirement: restate the acceptance criterion and any `D-xx` resolution, write
   the tagged test first, implement, run the checks, record evidence in
   `docs/traceability/requirements.yaml`.
3. Run `make ci` before pushing. Never use `--no-verify`.
4. A branch reaches `main` only after a Principal Review recorded in `docs/reviews/` with
   verdict `APPROVED` or `APPROVED_WITH_MINORS` (see `docs/adr/0002`).

## Linking tests to requirements

| Language | Tag syntax |
|---|---|
| Java (JUnit 5) | `@Tag("FR-01-03")` |
| Python (pytest) | `@pytest.mark.req("FR-02-04", "D-05")` |
| TypeScript (Vitest/Playwright) | test title starting `[FR-04-04]` or `[D-33, UX-DASH-01]` |

`uv run fs-traceability check` fails on unknown IDs, on completed rows without verifiable
evidence, and on Must rows in closed milestones without tagged tests.

## Tests that need Docker

| Language | Marker |
|---|---|
| Java | `@Tag("requires-docker")` |
| Python | `@pytest.mark.requires_docker` |
| Make target | wrap the command in `tools/bin/docker-gate <suite> <ci-job> -- …` |

Without Docker they print `SKIPPED: … requires Docker, verified in CI`; with `REQUIRE_DOCKER=1`
(CI, Codespaces) a missing daemon fails the run (ADR 0010). Performance numbers used for gates come
only from a machine recorded in `docs/benchmarks/hardware.md`, never from CI runners.

## Commits

Conventional Commits, imperative subject ≤ 72 characters, body explaining why, with
`Refs:` (requirement and defect IDs) and `Tests:` trailers. Types: `feat`, `fix`, `perf`,
`refactor`, `test`, `docs`, `build`, `ci`, `chore`, `ml`, `data`, `sec`.

## Hard rules

- No real personal data anywhere: fixtures, logs, screenshots and docs use synthetic tokens.
- No datasets, model binaries or `.env` files in git.
- No new dependency without a stated reason, a licence check (Apache/MIT/BSD-compatible, or an
  ADR) and an exact version pin.
