# 0003 — Toolchain and stack versions

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** all implementation rows; OPS-CI-01, OPS-CI-02
- **Defects referenced:** D-35, D-49

## Context

Build prompt A.3 rule 10: use the SRS stack, pin exact versions, and if a named major
version is out of upstream support, keep it only if still patched, otherwise switch with an
ADR. Support status was checked on 2026-09-17 against endoflife.date, the npm registry,
Maven Central, PyPI, Docker Hub and GHCR; the commands are listed under "Evidence" below.
Amended during the M0 review, before first merge to `main` (findings 13 and owner finding 4).

| SRS names | Upstream status on 2026-09-17 | Decision |
|---|---|---|
| Java 21 | LTS, supported; Adoptium lists 21.0.12+8 and its respin 21.0.12.1+1 as the latest GA builds (2026-07) | **Java 21.0.12** — CI pins the *patch release* `21.0.12` (the Temurin build within it, including respins, is resolved by `actions/setup-java` and shown in the job log); the devcontainer uses SDKMAN `21.0.12-tem`; the build machine runs Ubuntu OpenJDK 21.0.12; the Maven enforcer accepts `[21,22)` locally |
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
| Redis 7 | 7.4 is RSALv2/SSPLv1 only; Redis 8.x is tri-licensed RSALv2 / SSPLv1 / AGPLv3 (repository LICENSE.txt); 7.2 is the last BSD-3 line and is patched (7.2.16, 2026-08-25, EOL 2029-12); Valkey 9.1.2 (2026-09-01) is the BSD-3 fork | **Redis 7.2.16** (see options below) |
| Apache Kafka | 4.3.1 current stable (KRaft only) | **apache/kafka:4.3.1** |
| MLflow | 3.16.0 (2026-09-04); stages deprecated in favour of aliases | **ghcr.io/mlflow/mlflow:v3.16.0-full** (aliases, D-50) |
| MinIO (prompt) | repository archived 2026 | replaced, see ADR 0005 |

## Options considered

For Redis specifically:

- **Redis 8 under AGPLv3** — OSI-approved, but strong copyleft for a network service; an
  institution deploying a modified server would take on source-disclosure obligations. Rejected
  for a product meant to be adopted by banks without legal review of copyleft.
- **Valkey 9** — BSD-3, actively maintained, protocol-compatible; not named by the SRS. Preferred
  successor when 7.2 approaches end of life.
- **Redis 7.2** — BSD-3, named major version of the SRS, still patched. Chosen for now.

For the stack as a whole:

1. Keep every SRS-named major version — ships unpatched frameworks (Spring Framework 6.2,
   React 18, MUI 5) in a payments security product. Rejected.
2. Move to the newest release of everything, including pre-1.0 or RC lines (Maven 4 RC,
   TypeScript 7) — breaks tooling support. Rejected.
3. Newest **supported** release line whose surrounding tooling also supports it.

## Decision

Option 3, as tabulated. What is pinned where, precisely:

- **Pinned now:** toolchains in `.tool-versions`, `.python-version`, `.nvmrc`,
  `.mvn/wrapper/maven-wrapper.properties` (with distribution SHA-256) and CI `env`; Python
  dependencies in `uv.lock`; front-end build tooling in `frontend/pnpm-lock.yaml`; Maven
  dependencies through the Spring Boot 4.1.1 BOM plus explicit plugin versions; container images
  by tag in `docker-compose.yml`; GitHub Actions by commit SHA.
- **Decided but not yet installed:** React 19.3, MUI 9.4, `@mui/x-data-grid` 9.x, Tailwind 4.3
  (M8), springdoc-openapi and Resilience4j (ADR 0007). They are pinned in lockfiles in the
  milestone that first uses them, after re-checking support status on that date.
- **Deferred:** container image digests are pinned when images are first pulled and verified in
  CI (M9 supply-chain work).

## Evidence

Commands run on 2026-09-17 (outputs summarised in the table):

```bash
curl -s https://endoflife.date/api/spring-boot.json         # 3.5 eol 2026-06-30; 4.1 eol 2027-07-31
curl -s https://endoflife.date/api/spring-framework.json    # 6.2 eol 2026-06-30, last 2026-06-08
curl -s https://endoflife.date/api/react.json               # 18: support ended 2024-12-05
curl -s https://endoflife.date/api/redis.json               # 7.2.16 released 2026-08-17/25, eol 2029-12-01
curl -s https://endoflife.date/api/nodejs.json              # 24 LTS, eol 2028-04-30
curl -s https://endoflife.date/api/python.json              # 3.12 eol 2028-10-31
curl -s https://registry.npmjs.org/@mui/material            # latest-v5 5.18.0 (2025-07-08); latest 9.4.0
curl -s https://registry.npmjs.org/typescript-eslint/latest # peer typescript ">=4.8.4 <6.1.0"
curl -s https://api.github.com/repos/redis/redis/license    # tri-licence text for Redis 8
curl -s https://api.github.com/repos/minio/minio            # archived: true (ADR 0005)
curl -s "https://api.adoptium.net/v3/info/release_versions?release_type=ga&version=%5B21%2C22%29"
```

## Consequences

- Every document that says "Spring Boot 3", "React 18", "TypeScript 5" or "MUI v5" refers to
  the successor recorded here; the SRS text itself is not edited.
- Spring Boot 4 and MUI 9 differ from the APIs described in SRS examples (e.g. Jackson 3,
  MUI Grid v2); implementations follow the current APIs.
- Redis 7.2 support ends 2029-12; revisit before then (Valkey is the BSD-licensed successor).
