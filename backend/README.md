# backend

Spring Boot 4.1 / Java 21 services (Maven multi-module, `./mvnw`).

**Purpose.** Transaction ingestion, risk decision engine, rules, thresholds, holds and timers,
auto-block and freeze, staff APIs, WebSocket push, webhooks, SAR drafts and the server-rendered
customer verification page (build prompt C.1).

**Boundaries.** Hexagonal: domain packages import no framework, database or network classes;
ArchUnit tests enforce this per module.

| Module | Status | Milestone |
|---|---|---|
| `common` | Money and ISO 4217 minor units (D-43) | M0 |
| `ingest`, `decision`, `rules` | planned | M6 |
| `audit` | Hash-chained audit writer, tenant transactions, signed anchors, `fraudshield audit verify` | M7 |
| `auth` | Staff sign-in, sessions, lockout, Google sign-in, API keys, contract-driven authorisation | M7 |
| `admin` | Staff accounts, approvals, office IP allowlist, API keys, audit search | M7 |
| `notify`, `sar`, `verify-web` | planned | M6–M8 |

**Test.** `./mvnw -B -ntp verify` runs Checkstyle (Google style), JUnit 5 (property cases from seeded generators; jqwik removed, ADR 0009), SpotBugs with
FindSecBugs and the JaCoCo line-coverage gate (≥ 85%).
