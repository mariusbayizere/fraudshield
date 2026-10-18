# 0018 — TimescaleDB licence and deployment model

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** D-49, NFR-REL-04, D-32 (compression and retention)
- **Not legal advice.** This ADR records an engineering reading of the licence for this project. An
  operator deploying FraudShield for a bank should have it confirmed by counsel.

## Context

D-49: many managed PostgreSQL services do not offer TimescaleDB, and some Timescale features are under
the Timescale License (TSL) rather than Apache 2.0. The resolution asks for self-hosting and an ADR
confirming that the licence permits this deployment model, listing the features used.

Source read: `tsl/LICENSE-TIMESCALE` at timescaledb tag `2.30.0` ("Posted Date: September 24, 2020",
SHA-256 `8691e074dcdbc2e5be2f0f888d44a65497d73f3813fbb6bf1411ce26ce907e6d`). Image:
`timescale/timescaledb:2.30.0-pg16`, which contains the TSL-licensed components.

## Features used

| Feature | Where | Licence |
|---|---|---|
| Hypertables (`create_hypertable`, `by_range`) | V3, V8 | Apache 2.0 |
| Columnstore compression and `add_compression_policy` | V10 | TSL |
| Continuous aggregates (`account_activity_hourly`, `merchant_activity_15m`) and refresh policies | V10 | TSL |
| Retention policies (`add_retention_policy`) | V10 | TSL (background job; manual `drop_chunks` is Apache 2.0) |

## Decision

1. **Self-host** PostgreSQL 16 + TimescaleDB: Compose locally, Kubernetes for deployment (for example
   CloudNativePG with a TimescaleDB image). Portability to a managed service without TimescaleDB is
   not a goal.
2. **The FraudShield deployment model fits the TSL grants:**
   - **Internal use (2.1(a)).** A bank or processor runs FraudShield for its own fraud operations.
     Only its employees and contractors, through FraudShield's API and UI, use the database. No third
     party gets access to the Timescale data-definition or data-manipulation interfaces.
   - **Value added service (2.1(b), 3.10).** If an operator offers FraudShield to other institutions
     (the multi-tenant model of ADR 0017):
     - The product is fraud detection, not database storage (3.10(i)).
     - It adds substantial value of a different nature (3.10(ii)).
     - Tenants cannot define or modify the schema, which is technically enforced (3.10(iii)): tenant
       users reach the data only through the API, the application roles have no DDL rights, and only
       `fs_migrator` owns objects.
     - The operator must then notify customers that TimescaleDB is subject to the TSL and give them
       the licence or its URL (2.1(b)(1)). This is recorded as a deployment prerequisite in the
       handover documentation.
   - **Prohibited (2.2):** offering the database itself as a service or exposing time-series database
     functions to third parties. FraudShield does neither.
3. **Invariant to keep the licence position:**
   - No tenant or external user gets a database login or SQL access.
   - No application role gets `CREATE` on any schema.
   - The API does not expose raw SQL or query building over the time-series tables.

   A change to any of these needs a new ADR.
4. **Fallback** if the TSL position changes: hypertables and manual `drop_chunks` are Apache 2.0.
   Compression and continuous aggregates would be replaced by plain partitioning, summary tables
   refreshed by application jobs, and a scheduled `drop_chunks`, at a storage and query-latency cost.

## Consequences

- The dependency licence inventory (ADR 0009) covers library dependencies, not container images. This
  ADR is the record for the TimescaleDB image until images are inventoried (backlog PB-6).
- Tests run against the same image (`timescale/timescaledb:2.30.0-pg16`), so the TSL features are
  exercised in CI.
- The TSL notice requirement becomes part of the operator handover checklist (M12).
