# ingest

**Purpose.** The ingestion API (E.1) and the API process's composition root: `POST
/api/v1/transactions/ingest` and `/ingest/batch`, `GET /api/v1/jobs/{id}`, `GET
/api/v1/decisions/{id}`, the customer verification page `/verify/{token}` and `GET /api/v1/health`.
Request validation (ADR 0011, the shared vectors), idempotency (ADR 0067), FX normalisation
(ADR 0063), problem details, metrics and the background schedules.

**Boundaries.** Controllers translate HTTP only; `ingest.request` is pure. API keys are verified by
an `ApiKeyAuthenticator` the API-key module (M7) provides; the application does not start without
one.

**Run.** `FraudShieldApiApplication` with `FRAUDSHIELD_DB_URL`, `FRAUDSHIELD_DB_APP_PASSWORD`,
`FRAUDSHIELD_REDIS_URI`, `FRAUDSHIELD_KAFKA_BOOTSTRAP`, `FRAUDSHIELD_SPOOL_DIR` and the scorer's
mTLS material (`application.yml`).

**Test.** `../mvnw -pl ingest verify`. `IngestApiTest` and `ResilienceApiTest` run the whole API
against containers; `DecisionLatencyBenchmark` runs only with `-Dfs.benchmark=true`.
