# 0007 — Spring Boot 4.1 ecosystem compatibility

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** FR-01-01 … FR-01-07, FR-03-*, FR-05-*, FR-06-*, FR-07-*, NFR-REL-01,
  OPS-CI-03
- **Defects referenced:** D-13, D-15, D-19, D-27, D-49

## Context

ADR 0003 moved the backend from the SRS-named Spring Boot 3 line (open-source support ended
2026-06-30) to Spring Boot 4.1.1. The build prompt names libraries whose Spring Boot 4 support must
be verified before M1 relies on them: springdoc-openapi (C.1, FR-01-07), Resilience4j (C.1, C.4),
Testcontainers (H.2, M1 gate), Flyway (M1), Spring Security (FR-07), and Jackson, which moves to a
new major version in Spring Boot 4.

## Verified versions

Checked on 2026-09-17 against Maven Central: the `spring-boot-dependencies` 4.1.1 BOM, and each
library's published POM for the Spring Boot version it is built against.

| Library | Version | Source of version | Spring Boot 4.1 compatibility evidence | Status |
|---|---|---|---|---|
| Spring Framework | 7.0.9 | Boot 4.1.1 BOM | managed by the BOM | compatible |
| Spring Security | 7.1.1 | Boot 4.1.1 BOM | managed by the BOM | compatible |
| Jackson (databind 3) | 3.1.5 (`jackson-bom`); 2.21.5 still managed as `jackson-2-bom` | Boot 4.1.1 BOM | managed; Boot 4 auto-configures Jackson 3 | compatible — migration impact below |
| Flyway | 12.4.0 (`flyway-core`, `flyway-database-postgresql` present on Central) | Boot 4.1.1 BOM | managed by the BOM | compatible |
| Testcontainers | 2.0.5 (`testcontainers`, `testcontainers-postgresql`) | Boot 4.1.1 BOM | managed; 2.x renames modules (`testcontainers-postgresql` instead of `postgresql`) | compatible |
| Apache Kafka clients | 4.2.1; Spring for Apache Kafka 4.1.1 | Boot 4.1.1 BOM | managed; clients are protocol-compatible with the 4.3.1 broker in compose | compatible |
| PostgreSQL JDBC | 42.7.13 | Boot 4.1.1 BOM | managed | compatible |
| Lettuce (Redis) | 7.5.2.RELEASE | Boot 4.1.1 BOM | managed | compatible |
| Micrometer | 1.17.1 | Boot 4.1.1 BOM | managed | compatible |
| Thymeleaf | 3.1.5.RELEASE | Boot 4.1.1 BOM | managed (verification page, E.7) | compatible |
| JUnit Jupiter | 6.0.3 | Boot 4.1.1 BOM | managed | compatible |
| springdoc-openapi (`springdoc-openapi-starter-webmvc-ui`) | 3.1.1 (published 2026-09) | not in BOM; pin explicitly | `springdoc-openapi` 3.1.1 parent POM is `spring-boot-starter-parent` **4.1.0**; bundles swagger-core 2.2.55 | compatible |
| Resilience4j (`resilience4j-spring-boot4`) | 2.4.0 (published 2026-03) | not in BOM; pin explicitly | depends on `spring-boot-autoconfigure` **4.0.0**, not 4.1 | **risk** — see consequences |

## Jackson 3 migration impact

- Package root changes from `com.fasterxml.jackson.databind` to `tools.jackson.databind`
  (annotations stay in `com.fasterxml.jackson.annotation`). No FraudShield code exists yet, so there
  is nothing to migrate; new code targets Jackson 3 only.
- `JsonProcessingException` handling becomes unchecked `JacksonException`; RFC 9457 error mapping
  (H.1) is written against the new hierarchy.
- Money must never pass through floating point: `DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS`
  is enabled and amounts travel as JSON strings (H.1), verified by an ingestion contract test in M1.
- Libraries still on Jackson 2 (springdoc via swagger-core) run with `jackson-2-bom` side by side;
  the two do not share `ObjectMapper` configuration, so OpenAPI examples are checked against the
  runtime serializer in the M1 OpenAPI test.

## Decision

Adopt Spring Boot 4.1.1 with the BOM-managed versions above; pin springdoc-openapi 3.1.1 and
resilience4j-spring-boot4 2.4.0 explicitly when the first module uses them (M1 and M6).

## Consequences

- **Resilience4j risk.** Its Spring Boot 4 starter was built against Boot 4.0.0. Mitigation: the M6
  circuit-breaker integration tests (ML scorer down → fallback within 5 s, NFR-REL-01) run against
  the Boot 4.1 context and are the compatibility proof. If the auto-configuration breaks, the
  fallback is Resilience4j core (`resilience4j-circuitbreaker`, framework-independent) with explicit
  bean configuration, which removes the starter dependency entirely.
- springdoc on Jackson 2 and the application on Jackson 3 must produce identical JSON for money and
  timestamps; the OpenAPI example validation in M1 asserts this.
- Versions are re-checked when Spring Boot 4.1.x patch releases land; any library whose Boot 4
  support lags becomes an ADR update, not a silent downgrade.
