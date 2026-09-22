# M7 evidence at fe757fa

- **Commit:** `fe757fa` on `m7/staff-auth`. The tree was clean (`git status --porcelain` empty) and
  was not edited during the run.
- **Machine:** `dev-laptop-01` (see `hardware.md`): Intel Core i5-6200U, 4 logical CPUs, 7.6 GiB RAM,
  shared with the owner's other work. Load average was 3.1 at the start of the run and 5.1 at the
  end.
- **Stack:**
  - OpenJDK 21.0.12.
  - PostgreSQL 16.15 with TimescaleDB 2.30.0, the image version of `docker-compose.yml`, run
    without Docker (ADR 0010: CI remains authoritative).
  - Redis 7.0.15 locally; Compose pins 7.2.16.
- **Command:** `./mvnw -B -ntp -o -pl common,persistence,audit,auth,admin verify -fae` with
  `FRAUDSHIELD_TEST_POSTGRES_URL` and `FRAUDSHIELD_TEST_REDIS_URL` set. Run from 06:20:41Z to
  06:27:26Z on 2026-09-22.

## Result (`m7_verify_fe757fa.txt`)

| Module | Tests | Failures | Checkstyle | SpotBugs | Coverage gate |
|---|---|---|---|---|---|
| common | 1,131 | 0 | 0 | 0 | met |
| persistence (V1-V12) | 60 | 0 | 0 | 0 | met |
| audit | 50 | 0 | 0 | 0 | met |
| auth | 124 | 0 | 0 | 0 | met |
| admin | 22 | 0 | 0 | 0 | met |

## M7 gate items

| Gate (build prompt D.3) | Measured | File |
|---|---|---|
| Role × endpoint matrix proves 403s | 84 operations × 9 callers = 756 calls. Callers the matrix does not list: 69 × 401 (anonymous) and 441 × 403, with no exceptions. Callers it lists: never 401 or 403, except the refresh operation called without its refresh cookie (9 × 401, the designed credential) | `m7_authorisation_matrix_fe757fa.json` |
| bcrypt timing > 100 ms | `PasswordHasherTest`: the minimum of 5 cost-12 hashes is > 100 ms (assertion passed) | test source |
| token_version invalidation < 5 s | Between two independent instances, 20 runs. With pub/sub: p50 3 ms, max 5 ms. With the announcement and the Redis write both lost: p50 2,000 ms, max 2,005 ms | `m7_token_version_invalidation_fe757fa.json` |
| Revoked API key → 401 within 5 s (FR-06-07) | 5 runs. With pub/sub: max 20 ms. Without it: max 1,937 ms | `m7_api_key_revocation_fe757fa.json` |

These timings come from in-process instances sharing only Redis and the database, on a loaded
laptop. They show the mechanism works well inside its bound. They are not a statement about a
production cluster.
