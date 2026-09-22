# auth

Staff identity and API authentication (FR-07, FR-06-07, D-19, D-23, D-24, D-26, D-27; ADR 0014,
ADR 0070).

**Purpose.**

- Every `/api/v1/auth/*` operation of the contract:
  - email and password sign-in with rate limits and a 5-failure lock;
  - Google sign-in;
  - refresh rotation with reuse detection;
  - sign-out and sign-out everywhere;
  - registration, availability and email verification;
  - password reset by emailed code;
  - password change and unlock;
  - JWKS.
- RS256 access tokens carrying `token_version` and `sid`, checked on every request against
  `SessionStateCache`.
- API-key authentication (HMAC with pepper, 2 s cache, Redis pub/sub revocation) and the key
  lifecycle service used by the admin module.
- The security chain. `ContractPolicy` authorises every request from the packaged contract and
  denies by default. `CsrfDoubleSubmitFilter` protects the cookie-bearing operations.
  `ProblemHandler` answers with RFC 9457 problems.

**Configuration** (`fraudshield.auth.*`, see `config.AuthProperties`). The context refuses to
start without these:

- JWT signing keys;
- `token-key-hex` and `secret-box-key-hex`/`-id`;
- API-key peppers and `current-pepper-version`;
- `console-base-url` and `mail-from`;
- `spring.mail.*`.

Redis (`spring.data.redis.*`) is optional at runtime. Without it, limits apply per instance and
caches fall back to the database.

**Boundaries.** `domain` is framework-free (`ArchitectureTest`). Every database access runs in
`TenantTransactions`, except the SECURITY DEFINER lookups that find an institution before one is
known.

**Test.** `./mvnw -pl auth verify`.

- Unit tests run anywhere.
- `requires-docker` tests need TimescaleDB and Redis (Testcontainers), or
  `FRAUDSHIELD_TEST_POSTGRES_URL` and `FRAUDSHIELD_TEST_REDIS_URL`.
- The HTTP tests run the real application (`testing.AuthTestApplication`) against a local fake of
  Google (`testing.FakeGoogle`).
