# admin

Administration API (FR-06-01, FR-06-02, FR-06-06, FR-06-07, D-23, D-24, D-26).

**Purpose.**

- Staff accounts: list, create with a welcome email and `Idempotency-Key`, get, and update with a
  version in `ETag`.
- Approvals.
- The office IP allowlist.
- API keys: create, rotate and revoke.
- Audit search.

Every write records a USER_ADMIN or API_KEY_LIFECYCLE audit event with before and after values. A
role or status change ends the account's sessions. An administrator cannot change their own role
or status. The last active administrator is protected against concurrent demotion.

**Boundaries.** Controllers call services from `auth`. All reads and writes run in the
administrator's institution (row-level security), so other institutions' records are not found.

**Test.** `./mvnw -pl admin verify`.

- `requires-docker`: `AuthorisationMatrixTest` is the M7 role × endpoint gate. Every contract
  operation is called by anonymous, each role and each API-key scope.
- `UserAdministrationTest`, `NetworkAndKeysTest` and `AuditSearchTest` cover the rest.
