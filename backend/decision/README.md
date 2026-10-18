# decision

**Purpose.** The risk decision path (E.6, C.2, C.4): tiers from per-channel thresholds, rules,
the MCC circuit breaker, auto-block and account freeze, MEDIUM holds and their deadline scheduler,
the rule-based fallback, the durable spool drained to Kafka and PostgreSQL (D-15), the Redis
feature store and the gRPC client of the scorer.

**Boundaries.** `domain` is pure (no framework or I/O); `application` holds the services and the
ports; `adapter.*` implements the ports (`spool`, `events`, `kafka`, `jdbc`, `redis`, `grpc`,
`resilience`). `ArchitectureTest` enforces the layering. Decisions: ADRs 0061, 0062, 0064.

**Test.** `../mvnw -pl decision verify`. Tests tagged `requires-docker` start TimescaleDB, Redis and
Kafka containers (ADR 0010). The test sources are also published as a test-jar used by `notify` and
`ingest`. Property tests draw a fresh seed each run; reproduce with `-Dfs.property.seed=<seed>`.
