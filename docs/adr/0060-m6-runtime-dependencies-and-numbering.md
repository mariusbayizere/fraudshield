# 0060 — M6 runtime dependencies, and the numbers M6 reserves

- **Status:** Accepted
- **Date:** 2026-09-22
- **Requirements affected:** OPS-CI-01, FR-01-01, FR-02-01, FR-03-01
- **Defects referenced:** D-13, D-15, D-16

## Context

M6 adds the first services that run: the ingestion API, the decision engine, its Redis and
Kafka adapters, and the gRPC client of the scorer. ADR 0009 requires every runtime dependency to be
justified and permissively licensed. M5 and M7 are being built at the same time on other branches,
and ADR and Flyway numbers are global.

## Options considered

1. **Spring abstractions for everything** (Spring Kafka, Spring Data Redis, Spring gRPC) — less
   code, but the spool, the Lua scripts and the circuit breaker need the clients' own semantics,
   and the domain must not see Spring (H.1).
2. **The plain clients behind ports** — each adapter uses the client directly; Spring only wires.

## Decision

Option 2. Runtime additions, all managed by Spring Boot 4.1.1's BOM unless stated:

| Dependency | Why | Licence |
|---|---|---|
| `grpc-netty-shaded`, `grpc-protobuf`, `grpc-stub` 1.83.1, `protobuf-java` 4.35.1 | the scoring contract (C.2, D-16) | Apache-2.0, BSD-3-Clause |
| `lettuce-core` 7.5.2 | Redis, with Lua for atomic claims | MIT |
| `kafka-clients` 4.2.1 | idempotent `acks=all` producer (D-15) | Apache-2.0 |
| `resilience4j-circuitbreaker` 2.4.0 (pinned in the parent) | the scorer circuit (C.4) | Apache-2.0 |
| `spring-boot-starter-webmvc`, `-jdbc`, `-actuator`, HikariCP, `micrometer-registry-prometheus` | API, pool, metrics (E.10) | Apache-2.0 |
| `error_prone_annotations` pinned to 2.50.0 | gRPC and Guava disagree; the enforcer requires convergence | Apache-2.0 |

Test-only: `json-schema-validator` 3.0.7 (Apache-2.0, Jackson 3), Testcontainers PostgreSQL and
Kafka, `grpc-inprocess`, Flyway. Proto stubs are generated from `contracts/proto` at build time by
`io.github.ascopes:protobuf-maven-plugin` 5.1.10 (Apache-2.0) and never committed.

**Reserved numbers.** M6 uses ADRs `0060`–`0069` and Flyway versions `V60`–`V69` (V60 account
profiles, V61 FX rates, V62 hold reconciliation, V63 webhook deliveries), because `0027`/`0028`
already exist on `m4/generalisation` and `m5/scoring` and M7 will add identity migrations.

## Consequences

`uv run fs-licences` inventories the new artifacts; it should be re-run when M6 merges. Flyway on
a database that already applied a later-numbered M7 migration would refuse V60–V63 as out of order;
fresh databases are unaffected, and the merge should order the milestones or enable `outOfOrder`
deliberately.
