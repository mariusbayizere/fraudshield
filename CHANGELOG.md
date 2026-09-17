# Changelog

All notable changes are recorded here, grouped by milestone. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow Semantic Versioning.

## [Unreleased]

### M1 — Contracts and data model

- Contracts: OpenAPI 3.1 (89 operations, six channel examples, RFC 9457 problem catalogue, golden
  authorisation matrix), 13 Kafka JSON Schemas with baselines and a compatibility guard, scoring
  protobuf checked by `buf`, webhook signature specification with test vectors (ADR 0011, 0012, 0016).
- Asymmetric dual control for thresholds, circuit breakers and the MEDIUM timeout policy (ADR 0014).
- Database (`backend/persistence`, ADR 0017): Flyway V1–V11, least-privilege roles, tenant isolation
  (RLS; view isolation on hypertables), composite tenant foreign keys, append-only tables, audit hash
  chain partitioned by transaction time, compression, retention and continuous aggregates; Timescale
  License analysis (ADR 0018).
- Demo seeding with locally generated credentials, a database marker and an eager synthetic-data
  guard; `GET /environment` for the D-21 banner (ADR 0019).
- EPL-2.0 runtime binaries allowed (ADR 0020); merged migrations immutable (`fs-migration-guard`).
- MLflow HTTP 500 root cause fixed (job-execution processes at the memory limit) with a deterministic
  memory and OOM-kill smoke check.
- Threat model (`docs/security/threat_model.md`); re-plan of D-20, NFR-SEC-03, D-32, D-49 (ADR 0021).

### M0 closed — 2026-09-17

- Gate: `make up` healthy evidenced in CI (ADR 0010) by seven consecutive green `stack` jobs and the
  `main` runs at 83a9090 (ci 35184974170, stack 35184974152, devcontainer 35184974136); CI green;
  traceability-check runs; commits visible on the remote.
- Milestone review: APPROVED_WITH_MINORS (0 BLOCKER, 0 MAJOR, 4 MINOR, 8 NIT), findings in
  `docs/backlog/governance.md` GOV-1…GOV-13 with due milestones.
- Rows moved to DONE: D-47, D-48.

### M0 — Bootstrap and governance

- Repository skeleton, Apache-2.0 licence, security and contribution policies.
- SRS v1.0 imported and extracted to Markdown; binding defect register (D-01 … D-51)
  generated from the build specification.
- Traceability matrix seeded with 258 rows and a `traceability-check` that enforces evidence
  and tagged tests progressively by milestone (ADR 0004).
- Toolchain pins (ADR 0003): Java 21, Spring Boot 4.1.1, Python 3.12.14 with uv, Node 24 with
  pnpm, TypeScript 6.0, Maven 3.9.16.
- First domain code with tests: exact money and ISO 4217 minor units (Java, D-43), precision
  ceiling and implied operating points (Python, D-01/D-02), WCAG contrast measurement
  (TypeScript, D-33), out-of-scope term guard (D-47).
- Local `core` compose profile: Kafka 4.3.1 (KRaft), PostgreSQL 16 + TimescaleDB 2.30, PII
  vault, Redis 7.2, SeaweedFS (S3), MLflow 3.16, Mailpit, WireMock.
- CI: lint, strict type checks and unit tests for Java, Python and TypeScript; governance
  checks; Gitleaks.

#### M0 review fixes

- Traceability checks verify what they claim: SRS-derived fields locked by a seed check, evidence
  verified (commit ancestry, successful CI runs of this repository), tags counted only on tests
  enabled unconditionally, benchmark evidence restricted to recorded machines.
- Supply chain: prettier pinned without release-age exceptions; gitleaks launcher pinned and
  re-verified; licence inventory with SPDX expression parsing (ADR 0009); jqwik removed; commit
  message policy enforced; actions and hooks pinned by SHA.
- Container stack proven in CI (`stack` job) with functional smoke test and annotated diagnostics;
  Codespaces devcontainer; Docker-dependent suites skip visibly without Docker and fail in CI when
  Docker is missing; compose memory budgets per profile (ADR 0010).
- Decisions: ADR 0007 (Spring Boot 4.1 compatibility), ADR 0008 (D-47 guard), ADR 0009 (licences),
  ADR 0010 (Docker in CI and Codespaces, benchmark machines).
