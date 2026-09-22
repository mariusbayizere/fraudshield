# Review: M7 staff identity, authorisation, administration and audit   Reviewer role: Principal Reviewer

Date: 2026-09-22 (UTC)   Branch: `m7/staff-auth`

- **Commits:** reviewed `c280402`; re-reviewed the fixes `c280402..fe757fa`; final `2dc718c`
- **Requirements:** FR-06-01, FR-06-02, FR-06-06, FR-06-07, FR-07-01 … FR-07-09
- **Defects:** D-19, D-23, D-24, D-26, D-27, D-32

Verdict: **APPROVED_WITH_MINORS** (re-review of the fixes). The first review, on `c280402`, was
CHANGES_REQUIRED: 1 BLOCKER, 4 MAJOR, 11 MINOR, 1 NIT.

## How the review was done

An independent reviewer agent reviewed the tree. It had no part in writing the code, and it worked
from:

- the diff;
- the requirements: build prompt Part B, D.3, E.8, H and I.2; SRS FR-06 and FR-07; ADR 0011, 0014
  and 0017;
- the frozen contract and the schema.

The reviewer ran no builds. The laptop has about 1 GB free, and a second concurrent Maven build
would starve both. That deviates from I.1 rule 3 ("re-run, don't re-read"). The author re-ran every
check on a clean, committed tree and every mutation spot check the reviewer proposed (below), and
the reviewer then re-reviewed the fix diff.

## Findings

| # | Severity | Location | Finding | Resolution | Status |
|---|---|---|---|---|---|
| 1 | BLOCKER | `SessionService.rotate` | A refresh racing a revocation (logout, logout-all, password change, admin edit) could leave a live token in a revoked family: the revocation's `UPDATE` never saw the newly inserted row | Every token-writing path locks the account row first (account → token); a revoked, never-rotated token is refused without a reuse alarm | Fixed `7be13e7`; `RefreshRaceTest`; mutation M12 killed |
| 2 | MAJOR | `ApiKeyAuthenticator` | Unknown key IDs were cached without bound: memory exhaustion | Misses are not cached; the cache is capped; failed keys are rate-limited per address (429) | Partly fixed `7be13e7`: the limit is checked after the lookup (residual, ADR 0070) |
| 3 | MAJOR | `AuditChainVerifier` | Deleting the latest anchors and rewriting the tail verified | Rows of every due day must be covered by an anchor dated up to that day (V12 `audit_chain_last_seq_before`, 25 h grace) | Fixed `7be13e7`; `rowsOfDueDaysMustBeCovered…`; mutation M10 killed |
| 4 | MAJOR | `AuthenticationService`, `PasswordChangeService`, `JdbcAuditLog` | bcrypt ran inside the tenant transaction while it held the chain head; one chain partition for all | bcrypt outside the transaction, stored hash re-checked in the write; partitions spread by thread unless configured | Fixed `7be13e7`; `concurrentSignInsAllSucceed` |
| 5 | MAJOR | `UserAdministrationService`, security chain | Administrator refusals and wrong-role 403s were not audited | Refusals are returned as outcomes and audited (`USER_CHANGE_REFUSED`); 403s write `AUTH/ACCESS_DENIED` | Fixed `7be13e7`, `fe757fa`; `wrongRoleRefusalsAreAudited` |
| 6 | MINOR | `PasswordHasher` | A password over 72 bytes skipped bcrypt (timing oracle) | Spends one bcrypt | Fixed; `overlongPasswordStillCostsOneBcrypt` |
| 7 | MINOR | lockout counters | The real failure counter never decays; the unknown-email counter does | Accepted: no stronger than the accepted availability channel | Residual, ADR 0070 |
| 8 | MINOR | `SessionService` | A user agent over 1,024 characters failed sign-in | Truncated | Fixed; `overlongUserAgentDoesNotBreakSignIn` |
| 9 | MINOR | `PasswordResetService.verify` | Timing oracle; SRS says 400 | Padded to the minimum duration; 422 per ADR 0011, recorded as a deviation | Fixed |
| 10 | MINOR | `GoogleSignInService` | Problem type outside the contract for that operation | `forbidden` | Fixed |
| 11 | MINOR | `IdempotencyStore` | Read-then-write race | Atomic claim (SET NX / `putIfAbsent`) | Fixed; the unbounded local fallback map is a NIT, open |
| 12 | MINOR | `UserAdministrationService` | Locking a failure-locked account kept the 30-minute end | Converted to an administrator's lock | Fixed `7be13e7`; regression N1 found and fixed in `e0b9391` and `2dc718c` |
| 13 | MINOR | `PasswordChangeService` | Unthrottled guessing of the current password with a stolen token | The fifth wrong guess in 15 minutes ends every session | Fixed; `guessingTheCurrentPasswordEndsEverySession` |
| 14 | MINOR | temporary passwords | Never expire or force a change | Accepted: needs a contract flag (M8) | Residual, ADR 0070 §9, threat model R-5 |
| 15 | MINOR | reset codes | No cumulative per-account cap | 20 verification attempts per email per day | Fixed |
| 16 | MINOR | Google tokens | Revoked only on single logout | Every "all sessions end" path revokes them | Fixed; `signOutEverywhereRevokesTheGoogleTokenToo`; holding limits are residual |
| 17 | NIT | `Cidr`, `OfficeIpAllowlist` | `0.0.0.0/0` accepted; cache evicted before commit | IPv4 ranges /16 or narrower, IPv6 /32 or narrower; eviction after commit | Fixed |
| N1 | MINOR (re-review) | `UserAdministrationService` | The fix for 12 turned *any* edit of a failure-locked account into an administrator's lock | Only an explicit LOCKED converts it; the lock end is otherwise kept | Fixed `e0b9391` and `2dc718c`; `profileEditOfFailureLockedAccountKeepsTheFailureLock` |
| N2 | MINOR (re-review) | `AccessDeniedAudit` | Unbounded `ACCESS_DENIED` audit rows from a misconfigured client | One record per caller and operation per minute | Fixed `e0b9391`; `repeatedDenialsAreAuditedOncePerMinute` |
| N3 | NIT (re-review) | `ApiKeyAuthenticationFilter` | Failure limiter keyed on `getRemoteAddr()` | Needs the deployment's trusted forwarded-header configuration | Residual, ADR 0070 |

## Checks re-run (command → result)

All checks ran on a clean committed tree:

- local PostgreSQL 16.15 with TimescaleDB 2.30.0 (the Compose image version) and Redis 7.0.15;
- `FRAUDSHIELD_TEST_POSTGRES_URL` and `FRAUDSHIELD_TEST_REDIS_URL` set.

Results:

- `./mvnw -o -pl common,persistence,audit,auth,admin verify` at `fe757fa` → BUILD SUCCESS:
  - tests: common 1,131, persistence 60, audit 50, auth 124, admin 22, with 0 failures;
  - Checkstyle 0 and SpotBugs 0 in every module;
  - all coverage gates met.

  Record: `docs/benchmarks/m7_verify_fe757fa.txt`.
- `-pl auth,admin verify` at `e0b9391` → auth 124/0 with its gates met; admin 1 failure, the new N1
  test, which caught the incomplete fix.
- `-pl admin verify` at `2dc718c` → 24 tests, 0 failures, gates met.
- `uv run fs-traceability check` → 0 errors.
- `fs-licences` → only the three pre-existing Python flags remain.

CI on GitHub runs the same suites with Testcontainers (ADR 0010). The author cannot read the CI
results without `gh` authentication.

## Mutation spot checks (what was broken → which test failed)

Each check applied one change, ran the named test, then restored the file with `git checkout`. The
tree was verified clean after the batch.

| # | Mutation | Test | Result |
|---|---|---|---|
| M1 | Rotation not recorded (`markRotated` removed) | `SessionTest.refreshRotatesTheTokenAndReuseRevokesTheWholeFamily` | killed |
| M2 | `token_version` ignored in the session check | `SessionTest.passwordChangeEndsEverySessionAndOldJwtReturns401` | killed |
| M3 | API-key scope ignored (grant any authenticated key) | `AuthorisationMatrixTest.everyCallerOutsideTheMatrixIsRefusedOnEveryOperation` | killed. The first run wrongly reported "survived": it tested the installed, unmutated auth jar. Re-run with `-pl auth,admin`, it failed with unlisted callers answered 404. |
| M4 | Lock on the sixth failure instead of the fifth | `LoginTest.fifthFailureLocksTheAccountAndEmailsAnUnlockLinkThatWorksOnce` | killed |
| M5 | Rotating keys never expire | `ApiKeyLifecycleTest.rotationKeepsTheOldKeyForTwentyFourHours` | killed |
| M6 | Signed-token purpose not checked | `CryptoTest.signedTokensVerifyOnceForTheirPurposeAndState` | killed |
| M7 | OTP attempts unbounded | `PasswordResetTest.fiveWrongAttemptsExhaustTheCode` | killed |
| M8 | CSRF double-submit check off | `SessionTest.refreshNeedsTheDoubleSubmitCsrfToken` | killed |
| M9 | Anchors not recomputed | `AuditAnchoringTest.editedRowWithRecomputedHashIsCaughtByTheSignedAnchor` | killed |
| M10 | Anchor coverage not checked | `AuditAnchoringTest.rowsOfDueDaysMustBeCoveredSoDeletingTheLatestAnchorsIsDetected` | killed |
| M11 | Role change keeps sessions | `UserAdministrationTest.roleChangeEndsTheOldTokenAndStaleVersionsConflict` | killed |
| M12 | Rotation without the account lock | `RefreshRaceTest.signOutEverywhereRacingRotationLeavesNoLiveToken` | killed |

## Evidence reproduced (claimed vs measured)

All figures are from `docs/benchmarks/m7_evidence_fe757fa.md`.

| Gate | Claimed | Measured at `fe757fa` |
|---|---|---|
| Role × endpoint matrix | every unlisted caller is refused | 756 calls; unlisted callers got 69 × 401 (anonymous) and 441 × 403; no listed caller was refused, except the refresh operation called without its cookie (9 × 401, by design) |
| bcrypt > 100 ms per hash | cost 12 | `PasswordHasherTest`: the minimum of 5 hashes was above 100 ms |
| token_version invalidation < 5 s | pub/sub, with a TTL bound when it is lost | p50 3 ms, max 5 ms; with the announcement lost, max 2,005 ms (20 runs) |
| Revoked key → 401 < 5 s | pub/sub, with a TTL bound when it is lost | max 20 ms; without pub/sub, max 1,937 ms (5 runs) |

## Residual risks

See ADR 0070, "Residual risks accepted after the principal review", and threat model R-2 and R-5 …
R-8. In short:

- enumeration through the difference in counter decay;
- temporary passwords that neither expire nor force a change;
- trust in the database's own re-hash function until anchors are exported off-host;
- PB-11 (forged anchors);
- per-instance limits while Redis is down;
- the client IP depends on the proxy configuration;
- only logout-all is raced against rotation in a test.
