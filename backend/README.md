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
| `rules` | Custom-rule DSL (FR-05-05) | M6 |
| `decision` | Decision engine, holds, freeze, breaker, spool, adapters | M6 |
| `ingest` | Ingestion API and composition root | M6 |
| `auth`, `staff-api` | planned | M7 |
| `notify` | Customer SMS, verification, webhooks | M6 |
| `sar`, `verify-web` | planned | M8 |

**Test.** `./mvnw -B -ntp verify` runs Checkstyle (Google style), JUnit 5 (property cases from seeded generators; jqwik removed, ADR 0009), SpotBugs with
FindSecBugs and the JaCoCo line-coverage gate (≥ 85%).
