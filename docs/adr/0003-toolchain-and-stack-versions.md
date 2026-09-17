# 0003 — Toolchain and stack versions

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** all implementation rows; OPS-CI-01, OPS-CI-02
- **Defects referenced:** D-35, D-49

## Context

Build prompt A.3 rule 10: use the SRS stack, pin exact versions, and if a named major
version is out of upstream support, keep it only if still patched, otherwise switch with an
ADR. Support status was checked on 2026-09-17 against endoflife.date, the npm registry,
Maven Central, PyPI, Docker Hub and GHCR (commands in the M0 walkthrough).

| SRS names | Upstream status on 2026-09-17 | Decision |
|---|---|---|
| Java 21 | LTS, supported | **Java 21** (OpenJDK 21.0.12 on build machine) |
| Spring Boot 3 | 3.5 OSS support ended 2026-06-30; Spring Framework 6.2 EOL 2026-06-30, last release 2026-06-08 | **Spring Boot 4.1.1** (Spring Framework 7.0; supported to 2027-07-31) |
| Python (unversioned; prompt: 3.12) | 3.12 security-only until 2028-10, still patched | **Python 3.12.14** (uv-managed) |
| React 18 | active support ended 2024-12 | **React 19.3** |
| TypeScript 5 (strict) | only the latest release line is patched; typescript-eslint 8.70 supports `<6.1.0` | **TypeScript 6.0.3** (7.0 rejected until lint tooling supports it) |
| MUI v5 | last v5 release 2025-07-08 | **MUI 9.4** and free `@mui/x-data-grid` 9.x (MIT; no Pro/Premium, D-35) |
| Tailwind CSS (unversioned) | 4.3 current | **Tailwind 4.3** (Preflight not imported, D-37) |
| Vite (unversioned) | 8.3 current | **Vite 8.3** |
| Node (prompt: LTS) | 24 is LTS (EOL 2028-04) | **Node 24.21.0**, **pnpm 12.4.2** |
| Maven (prompt: Gradle or Maven) | 3.9.16 current stable; 4.0 still RC | **Maven 3.9.16** via Maven Wrapper |
| PostgreSQL 16 | supported until 2028-11 | **PostgreSQL 16.15** |
| TimescaleDB | 2.30.0 for pg16 | **timescale/timescaledb:2.30.0-pg16** (licence: ADR in M1, D-49) |
| Redis 7 | 7.4+ is RSALv2/SSPL; 7.2 is the last BSD-3 line, patched (7.2.16, 2026-08-25, EOL 2029-12) | **Redis 7.2.16** (BSD-3; satisfies F.3 licence rule) |
| Apache Kafka | 4.3.1 current stable (KRaft only) | **apache/kafka:4.3.1** |
| MLflow | 3.16.0 (2026-09-04); stages deprecated in favour of aliases | **ghcr.io/mlflow/mlflow:v3.16.0-full** (aliases, D-50) |
| MinIO (prompt) | repository archived 2026 | replaced, see ADR 0005 |

## Options considered

1. Keep every SRS-named major version — ships unpatched frameworks (Spring Framework 6.2,
   React 18, MUI 5) in a payments security product. Rejected.
2. Move to the newest release of everything, including pre-1.0 or RC lines (Maven 4 RC,
   TypeScript 7) — breaks tooling support. Rejected.
3. Newest **supported** release line whose surrounding tooling also supports it.

## Decision

Option 3, as tabulated. Versions are pinned in lockfiles (`uv.lock`, `pnpm-lock.yaml`,
Maven dependency management via the Spring Boot BOM) and in `.tool-versions`,
`.python-version`, `.nvmrc`, `.mvn/wrapper/maven-wrapper.properties`, and image tags in
`docker-compose.yml`. Container images are pinned by tag at M0; digests are pinned when the
images are first pulled and verified (M9 supply-chain work).

## Consequences

- Every document that says "Spring Boot 3", "React 18", "TypeScript 5" or "MUI v5" refers to
  the successor recorded here; the SRS text itself is not edited.
- Spring Boot 4 and MUI 9 differ from the APIs described in SRS examples (e.g. Jackson 3,
  MUI Grid v2); implementations follow the current APIs.
- Redis 7.2 support ends 2029-12; revisit before then (Valkey is the BSD-licensed successor).
