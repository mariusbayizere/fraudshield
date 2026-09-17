# 0019 — Demo seeding and the synthetic-data banner

- **Status:** Accepted (repository owner decision, 2026-09-17)
- **Date:** 2026-09-17
- **Requirements affected:** D-21 (synthetic data only outside production, UI banner), FR-05-07 and
  ADR 0014 (two risk officers for dual control), G.6 (no committed secrets)

## Context

The repository is public. A demo needs staff accounts for every role, including two RISK_OFFICER
accounts because configuration changes need a second officer (ADR 0014), and an API key for
ingestion. Committed demo passwords would be public credentials, and seeded accounts in a production
database would be a backdoor. D-21 requires development and demo deployments to use synthetic data
only and to show "SYNTHETIC DATA — NOT FOR PRODUCTION" in the UI.

## Decision

1. **Profiles.** Demo seeding (`fraudshield.demo-seed.enabled=true`) runs only when every active Spring
   profile is `dev` or `demo`. `DemoSeedGuard` is registered in `META-INF/spring.factories` and runs
   when the environment is prepared, before any bean or database connection. It stops startup if
   seeding is enabled with no profile or with any other profile (`prod`, `staging`, `default`,
   `dev,prod`, …). The seeder bean is also `@Profile({"dev","demo"})` and checks again.
2. **No committed secrets.**
   - `make seed-demo` (`fs-seed-demo`) generates the credentials on the developer's machine: a random
     24-character password per account (every character class present), a development API key
     `fsk_dev_…` and a 32-byte API-key pepper.
   - It writes them to the git-ignored `.demo-credentials` (created with mode 600, never overwritten)
     and prints them once. Later runs reuse the file without printing.
   - The Java tool receives the values through its environment, never its command line. The database
     stores bcrypt (cost 12) password hashes and an HMAC-SHA256 of the key secret. A test proves no
     plaintext is stored.
   - The seeder accepts only `fsk_dev_` keys.
3. **What is seeded.**
   - The synthetic institution `demo-bank` with five accounts:
     - `admin.demo@example.com` (ADMIN)
     - `analyst.demo@example.com` (ANALYST)
     - `senior.analyst.demo@example.com` (SENIOR_ANALYST)
     - `risk.officer.a.demo@example.com` (RISK_OFFICER)
     - `risk.officer.b.demo@example.com` (RISK_OFFICER)
   - One ingestion API key, version 1 of the channel thresholds and circuit-breaker settings, and a
     `DEMO_DATA_SEEDED` audit event.
   - Names are synthetic and addresses use RFC 2606 `example.com`. Seeding is idempotent on the
     institution code.
4. **Banner.**
   - The public `GET /api/v1/environment` returns exactly `{"synthetic_data": boolean}`. It is public
     because the banner must show on the login page, and it discloses nothing else.
   - `synthetic_data` is true when the database holds demo data, demo seeding is enabled, or a `dev`
     or `demo` profile is active (`SyntheticDataFlag`).
   - The front end shows `SYNTHETIC DATA — NOT FOR PRODUCTION` whenever the flag is true
     (`frontend/src/lib/environment/syntheticDataBanner.ts`), and rejects a malformed body. The React
     shell that renders it on every page arrives in M8, and its component test must use this module.
5. **The database remembers (review MAJOR-3).** Seeding runs in a one-shot process, so a check of
   that process's profiles alone would not stop a later `prod` service from serving the seeded
   database.
   - The seeder marks its institution `institutions.synthetic = true`; only `fs_migrator` can write
     it.
   - `deployment_has_synthetic_data()` (`SECURITY DEFINER`, one boolean, executable by every
     application role without a tenant) reads the marker.
   - `SyntheticDataAutoConfiguration` creates `SyntheticDataStatus` in every Spring Boot application
     with a FraudShield database, after Flyway. It fails startup when the marker is set and the
     active profiles are not only `dev`/`demo`, and it supplies the banner flag.
   - `SyntheticDataStatusTest` starts the real database tool against a seeded database: it refuses
     `prod` and no profile, starts under `demo` with the flag on, and starts under `prod` against a
     clean database with the flag off.
6. **Local secret scanning.**
   - `make secrets-scan` scans committed history (`gitleaks git`) and every committable
     working-tree file: tracked files plus untracked files that are not ignored
     (`tools/bin/gitleaks-worktree`).
   - Git-ignored local secret files (`.env`, `.demo-credentials`) are not scanned, otherwise
     `make ci` would fail after `make env` or `make seed-demo`.
   - A force-added ignored file is still caught by the pre-commit hook (staged changes) and the CI
     history scan.

## Consequences

- Deleting `.demo-credentials` regenerates credentials, but the stored hashes then no longer match;
  reset the database volume first. The file header says so.
- The CI stack job runs `make seed-demo` on the Compose stack with throw-away credentials. It filters
  the account and key lines out of the log, then runs `check-seeded-stack.sh` (file mode, git-ignore,
  migrations, fs_app password login, tenant isolation of the seeded users).
- Tests: `DemoSeedGuardTest` (startup fails under `prod`, `production`, `staging`, `default`,
  `dev,prod`, `demo,staging` and no profile; succeeds under `dev`, `demo`; `application.yml` keeps
  seeding off), `DemoDataSeederTest`, `SyntheticDataFlagTest`, `tools/tests/test_seed_demo.py`, the
  contract test for `getDeploymentEnvironment` and the front-end banner test.
