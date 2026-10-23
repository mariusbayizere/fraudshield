# M7 evidence at 91ee255 (analytic staleness bounds, V70)

- **Commit:** `91ee255` on `m7/staff-auth`, clean tree, not edited during the run. This supersedes
  `m7_evidence_85aff03.md`. Changes since then:
  - `1a0ec54`: anchored session-cache tiers and renumbering to V70;
  - `5b728a1`: the second-session bound test;
  - `91ee255`: ADR 0071 §6, the migration ranges and the lab notebook.
- **Command:** `./mvnw -B -ntp -o -pl common,persistence,audit,auth,admin clean install -fae`,
  run from 14:28:01Z to 14:40:39Z on 2026-09-22. Load average: 4.5 at the start, 7.6 at the end.
- **Machine and stack:** as in `m7_evidence_fe757fa.md`.

## A first run failed, and why it does not count against the code

The first run at this commit used `verify` without `clean`. Four persistence test classes failed
with `column "version" of relation "users" already exists`, and only 25 of 60 tests ran.

The cause: Maven's resource copy never deletes files, so the renamed-away
`V12__staff_identity_and_audit_anchoring.sql` was still in `persistence/target/classes`. The
persistence tests load `classpath:db/migration`, so Flyway applied V12 and then V70. The audit,
auth and admin tests read migrations from the persistence *sources* and were unaffected.

The `clean` run below has no V12 anywhere in the build output. **Any local build from before the
rename needs `mvn clean`**; CI always builds clean.

## Result (`m7_verify_91ee255.txt`)

| Module | Tests | Failures | Checkstyle | SpotBugs | Coverage gate |
|---|---|---|---|---|---|
| common | 1,131 | 0 | 0 | 0 | met |
| persistence (V1-V11, V70) | 60 | 0 | 0 | 0 | met |
| audit | 50 | 0 | 0 | 0 | met |
| auth | 137 | 0 | 0 | 0 | met |
| admin | 28 | 0 | 0 | 0 | met |

## Gate items

| Gate | Analytic bound (ADR 0071 §6), asserted in fake time | Measured at `91ee255` | At `85aff03` |
|---|---|---|---|
| Role × endpoint matrix | — | 756 calls; unlisted callers 69 × 401 and 441 × 403; identical | identical |
| token_version invalidation, announcement and Redis write lost | max(R, L) + σ = **2 s** + clock skew | p50 1,966 ms, max 2,203 ms (20 runs) | p50 2,009 ms, max 4,620 ms |
| same, with pub/sub | — | p50 3 ms, max 10 ms | 7 / 26 ms |
| Revoked API key → 401, announcement lost | T = **2 s** | max 1,893 ms (5 runs) | 1,849 ms |
| same, with pub/sub | — | max 50 ms | 103 ms |

The measured maximum can exceed the bound slightly, because the measurement stops when the first
*refusal* returns. That includes the check path, i.e. the database read of the request that
refuses. The bound itself is asserted to the millisecond, independent of load, by:

- `SessionInvalidationTimingTest.staleAnswersEndWithinTheAnalyticBoundHoweverSlowTheRead`;
- `SessionInvalidationTimingTest.staleVersionCannotOutliveTheBoundThroughAnotherSession`;
- `ApiKeyLifecycleTest.revokedKeyIsRefusedWithinTheAnalyticBoundHoweverSlowTheRead`.

Mutations B1–B5 against the bound code are all killed (ADR 0071 §6).
