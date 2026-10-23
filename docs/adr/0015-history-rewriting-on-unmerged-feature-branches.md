# 0015 — History rewriting on unmerged feature branches

- **Status:** Accepted (repository owner decision, 2026-09-17)
- **Date:** 2026-09-17
- **Requirements affected:** none directly (process: build prompt G.1, G.6, I.2)
- **Amends:** build prompt G.6 "rewriting published history" (the prompt file itself is kept verbatim;
  this ADR is the authoritative wording)

## Context

G.6 forbids rewriting published history. Applied to feature branches that CI builds but that are not
merged yet, this left red intermediate commits on `m1/contracts-events` (ced4324 and 065a66c fail two
contract tests that a later commit fixes; events re-review NF-E07). A bisect or a revert of such a
commit on `main` would hit a broken state. The owner decided that feature branches may be cleaned
before they merge.

## Decision

1. **Rewriting is allowed on unmerged feature branches before merge, and forbidden on `main` and on
   tags.** A branch is unmerged while its head is not reachable from `origin/main`. Once any of its
   commits is on `main`, the branch is not rewritten again.
2. **How.** Rebase (for example with fixups and rewording) so that **every commit passes the fast
   checks on its own**. Push only the feature branch, with `git push --force-with-lease=<branch>:<old
   sha>` naming the exact remote head that was reviewed. Never force-push `main`, a tag, or a branch
   that is already merged. The owner reported creating a `main` ruleset that blocks force pushes and
   deletions. The unauthenticated GitHub API cannot confirm it (it reports `protected: false`), so
   this ADR does not rely on it.
3. **Proof.** `tools/bin/verify-branch-commits <base> <branch> <report.md>` checks out each commit of
   the branch in a temporary worktree. For each commit it runs the commit-message check, the governance
   checks and the fast checks for the components the commit touches:
   - Python packages: ruff, format, mypy and pytest.
   - Backend: `mvnw verify`.
   - Frontend: typecheck, lint, format and tests.
   - Secret scanning: the gitleaks self-test.

   It writes one row per commit to the report, and the review response records the report. Docker
   suites stay in CI (ADR 0010).

   The checks are selective, as the owner specified ("fast checks for touched components"). A
   component a commit does not touch keeps the result it had at the previous commit, so on a branch
   whose first commit passes, a failure appears at the commit that introduces it. Changes to shared
   Python files run all Python suites. The limit: a commit that breaks another component without
   touching its files (for example a tool it calls) is not detected by this script; full CI on the
   branch head still runs every suite.
4. **Records.** Review records and responses keep the SHAs they were written against. A response
   written after a rewrite includes the old-to-new SHA mapping, so earlier references stay traceable.
5. **Still forbidden (G.6):** `--no-verify`; rewriting `main` or tags; deleting remote branches or tags
   not created in this work unless the owner directs it; merging with failing checks.

## Consequences

- `main` keeps a history in which every commit passes its checks, so bisecting and reverting are safe.
- Reviewers of a rewritten branch compare trees, not SHAs; the verification report and SHA mapping
  make that practical.
- Anyone who fetched the old branch must reset to the new one. On this project that is only the author
  and the review agents.
