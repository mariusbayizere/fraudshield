# Changelog

All notable changes are recorded here, grouped by milestone. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow Semantic Versioning.

## [Unreleased]

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
