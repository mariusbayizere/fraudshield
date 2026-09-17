# 0002 — Trunk-based branching without a develop branch

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** OPS-CI-01 … OPS-CI-08
- **Defects referenced:** —

## Context

SRS 8.1 triggers integration tests on "push to develop/main". Build prompt G.2 mandates a
single protected `main` and short-lived `m<N>/<scope>` branches, with no `develop`.

At bootstrap the GitHub CLI is installed but not authenticated on the build machine, so
pull requests cannot be opened programmatically. The remote repository was empty (no
initial commit), contrary to the build prompt's assumption.

## Options considered

1. Keep a `develop` branch to match SRS 8.1 literally — two long-lived branches, merge
   drift, and a second integration point that reviewers must reason about.
2. Trunk-based: stages that the SRS attaches to `develop` run on pull requests targeting
   `main` and on pushes to `main`.

## Decision

Option 2. CI (`.github/workflows/ci.yml`) runs lint, type checks, unit tests and the
traceability check on every push to any branch and on pull requests; integration and
security stages run on pull requests to `main` and pushes to `main` once they exist (M9).

Because `gh` is unauthenticated, branches are merged by rebasing onto `main` and
fast-forwarding locally, then pushing (G.2 fallback). Branch protection on `main` cannot be
enabled from this machine and is recorded for the author in the M0 walkthrough.

The very first commit (M0 bootstrap) necessarily creates `main`; it is pushed to the
`m0/bootstrap` branch first (gate: "test commit visible on the remote"), reviewed, and then
fast-forwarded into `main`.

## Consequences

- One integration branch; `main` must stay green.
- Without server-side branch protection, "no merge with failing checks" is enforced by the
  pre-push gate (G.5) and by checking the CI run for the branch head before fast-forwarding.
