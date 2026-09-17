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
