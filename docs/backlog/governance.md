# Governance backlog

Owner direction (2026-09-17): governance and traceability tooling is time-boxed. In M0, only
BLOCKER fixes are implemented; everything else is logged here as issue-style entries and scheduled
with the milestone where it matters. Entries are migrated to GitHub issues once `gh` access is
available.

Format: ID · title · source · priority · due · description · acceptance.

---

### GOV-1 · Benchmark evidence must name a dedicated machine for every evidenced status
- **Source:** M0 delta review DR-2 (MINOR) · **Priority:** high · **Due:** before FR-02-02 moves (M3)
- **Problem:** a `DONE` benchmark row passes with CI-runner numbers in any file under
  `docs/benchmarks/`, even `hardware.md` itself; any lower-case `##` heading counts as a machine;
  MOB-PERF, FR-04-07, D-13 and D-16 are not in `BENCHMARK_ROWS`.
- **Acceptance:** every evidenced benchmark row states `machine: <id>` for a section marked
  `dedicated: yes`; `hardware.md` is not accepted as measurement evidence; the measurement file
  names the machine; the row list is extended; tests cover each evasion.

### GOV-2 · Fail on runtime-skipped tests when Docker is required
- **Source:** DR-3 (MINOR) · **Priority:** high · **Due:** before M1 closes
- **Problem:** `pytest.skip()` inside a test body, JUnit `Assumptions`, and
  `@Testcontainers(disabledWithoutDocker = true)` pass silently under `REQUIRE_DOCKER=1`; some
  never-running forms still count as tagged tests (custom disabling annotations, abstract tests,
  `importorskip`, `__test__ = False`, Playwright `test.fixme`, `it.only` siblings, aliased
  `describe.skip`).
- **Acceptance:** under `REQUIRE_DOCKER`, any skipped test fails the run (pytest hook; Surefire
  report check or JUnit listener); Checkstyle forbids `disabledWithoutDocker`; remaining forms are
  handled or documented. Long term: derive tags from executed reports (planned for M9, ADR 0004).

### GOV-3 · Compose budget: unprofiled services, replicas, clean errors
- **Source:** DR-4 (MINOR) · **Priority:** medium · **Due:** before the first `ml`/`obs` service (M5)
- **Acceptance:** services without `profiles` are counted in every profile (or rejected);
  `deploy.replicas` multiplies limits; an unparseable limit is a reported error, not a traceback.

### GOV-4 · Tests for the shell gates and allowlist prefix matching
- **Source:** DR-6 (NIT) · **Priority:** low · **Due:** M1
- **Problem:** surviving mutants — `docker-gate` ignoring `REQUIRE_DOCKER`, the gitleaks cached-binary
  check disabled, scope-guard allowlist prefix matching.
- **Acceptance:** subprocess tests with a stub `docker` on PATH and a tampered gitleaks cache; a test
  that `docs/srs.bak/x.md` is not exempted by the `docs/srs/` entry.

### GOV-5 · Devcontainer workflow triggers and default branch
- **Source:** DR-7 (NIT) · **Priority:** low · **Due:** M1
- **Status:** triggers widened in the path-filtered workflows (lockfiles, manifests, `.mvn`);
  remaining: `pyproject.toml`, `tools/**`, `package.json` if post-create depends on them; the owner
  sets `main` as the default branch so the nightly schedule runs.

### GOV-6 · Line pragma inside fenced code blocks
- **Source:** DR-9 (NIT) · **Priority:** low · **Due:** M2
- **Acceptance:** the scope-guard pragma is ignored inside fenced code blocks in Markdown, or ADR 0008
  documents that it is honoured there.

### GOV-7 · Commit-message tool attribution trailers
- **Source:** R-8 remainder (NIT) · **Priority:** low · **Due:** M1
- **Problem:** `Assisted-by:` and `Co-developed-by:` trailers naming tools, "Made with …" lines, and
  placeholder summaries with trailing words ("updated things across modules") pass.
- **Acceptance:** those forms are rejected, with tests; owner trailers remain allowed.

### GOV-8 · Container images pinned by digest
- **Source:** ADR 0003, re-review residual risk · **Priority:** medium · **Due:** M9
- **Acceptance:** compose and Kubernetes images are pinned by digest, with an update process.

### GOV-9 · Branch protection on `main`
- **Source:** G.2; `gh` not authenticated on the build machine · **Priority:** high · **Due:** M1 (owner action)
- **Acceptance:** `main` is the default branch and requires the `ci` jobs (and `stack` when it runs).

### GOV-10 · Gate evidence must quote job results, not run conclusions
- **Source:** M0 milestone review F-4 (NIT) · **Priority:** medium · **Due:** M1
- **Problem:** with path filters, a skipped `stack` or `build-and-verify` job still makes the run
  conclude "success".
- **Acceptance:** evidence records and any automated run verification check the named job ran and
  succeeded (`GitEvidenceVerifier.ci_run` inspects jobs, not only the run conclusion).

### GOV-11 · D-17 must close on the M6 decision-engine property tests
- **Source:** M0 milestone review F-2 (NIT) · **Priority:** medium · **Due:** M6
- **Problem:** `MoneyBoundaryTest` is tagged D-17, which would satisfy the tagged-test rule before
  the generative property tests planned in ADR 0009 exist.
- **Acceptance:** D-17 is closed only with the M6 property tests as evidence (reviewer check), and the
  row note says so.

### GOV-12 · Rounding tie cases for the remaining currencies
- **Source:** M0 milestone review F-3 (NIT) · **Priority:** low · **Due:** next change to `MoneyBoundaryTest`
- **Acceptance:** tie cases for BIF (0 decimals) and USD/EUR/SSP/SOS (2 decimals).

### GOV-13 · Threat model document
- **Source:** M0 milestone review M-1 (MINOR) · **Priority:** high · **Due:** M1, before the first data-flow component
- **Status:** delivered in M1 as `docs/security/threat_model.md`.
- **Note:** this is a product security artefact (build prompt D-28, I.3), not governance tooling; it is
  delivered as part of M1.

### GOV-14 · Clear error for commit evidence in shallow clones
- **Source:** M0 closing-commit devcontainer run 35185341158 · **Priority:** low · **Due:** M1
- **Problem:** in a shallow clone, `fs-traceability check` reports cited commits as "not an ancestor of
  HEAD", which reads like bad evidence rather than missing history. Fixed for CI by full-history
  checkouts; the message should name the cause.
- **Acceptance:** when `git rev-parse --is-shallow-repository` is true, the error says the clone is
  shallow and how to fetch full history.

### GOV-15 · Gitleaks self-test: same file name in another directory

- **Source:** events final review F-11 (NIT), 2026-09-17.
- **Problem:** an allowlist path widened to match the fixture's file name in any directory (for
  example `^contracts/.*/signature-test-vectors\.json$`) is not caught; copies are planted only beside
  the fixture and in an unrelated directory.
- **Acceptance:** the self-test also plants each allowlisted value under the fixture's own file name
  in a sibling directory, and that path widening fails it.
