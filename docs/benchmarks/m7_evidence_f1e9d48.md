# M7 evidence at f1e9d48 (hybrid persistence, ADR 0071)

- **Commit:** `f1e9d48` on `m7/staff-auth`. The tree was clean (`git status --porcelain` empty) and
  was not edited during the run. This supersedes `m7_evidence_fe757fa.md` for the code as it now
  stands: the staff CRUD domain on Spring Data JPA, plus the two review fixes of `24016f9`.
- **Machine and stack:** the same as `m7_evidence_fe757fa.md` (`dev-laptop-01`; PostgreSQL 16.15
  with TimescaleDB 2.30.0 without Docker; Redis 7.0.15; OpenJDK 21.0.12). Load average was 4.4 at
  the start of the run and 5.6 at the end.
- **Command:** `./mvnw -B -ntp -o -pl common,persistence,audit,auth,admin verify -fae` with
  `FRAUDSHIELD_TEST_POSTGRES_URL` and `FRAUDSHIELD_TEST_REDIS_URL` set. Run from 09:53:24Z to
  10:02:30Z on 2026-09-22.

## Result (`m7_verify_f1e9d48.txt`)

| Module | Tests | Failures | Checkstyle | SpotBugs | Coverage gate |
|---|---|---|---|---|---|
| common | 1,131 | 0 | 0 | 0 | met |
| persistence (V1-V12) | 60 | 0 | 0 | 0 | met |
| audit | 50 | 0 | 0 | 0 | met |
| auth | 133 | 0 | 0 | 0 | met |
| admin | 27 | 0 | 0 | 0 | met |

Auth went from 124 to 133 tests and admin from 22 to 27. The additions are:

- the hybrid-persistence tests (tenant isolation through Hibernate, `@Version`, bulk updates, schema
  validation, status versioning, locked-read reload);
- the persistence-settings tests;
- the admin tests added in `e0b9391`/`2dc718c`;
- the Hibernate-statistics `QueryCountTest`.

## M7 gate items, re-measured

| Gate | Measured at `f1e9d48` | At `fe757fa` | File |
|---|---|---|---|
| Role × endpoint matrix | 756 calls. Unlisted callers: 69 × 401 and 441 × 403, with no exceptions. Listed callers: never 401 or 403, except the refresh operation without its cookie (9 × 401, by design) | identical | `m7_authorisation_matrix_f1e9d48.json` |
| token_version invalidation < 5 s | With pub/sub: p50 4 ms, max 6 ms. With the announcement and the Redis write lost: p50 1,997 ms, max 2,004 ms (20 runs) | 3/5 ms; 2,000/2,005 ms | `m7_token_version_invalidation_f1e9d48.json` |
| Revoked API key → 401 < 5 s | With pub/sub: max 39 ms. Without it: max 1,905 ms (5 runs) | 20 ms; 1,937 ms | `m7_api_key_revocation_f1e9d48.json` |
| bcrypt > 100 ms | `PasswordHasherTest` passed | passed | test source |
| No N + 1 (ADR 0071) | `QueryCountTest`: one statement per listing whatever the rows: 1 range and 12 ranges by 12 different creators, and users, approvals and keys before and after 15 more; 0 entity and 0 collection fetches | new | test source |
| RLS under JPA | `HybridPersistenceTest.jpaQueriesAreTenantIsolatedAndFailClosedWithoutTenant`, and the persistence isolation suite (60) | new | test source |

As before, these timings come from in-process instances on a loaded laptop. CI (Testcontainers)
remains authoritative.
