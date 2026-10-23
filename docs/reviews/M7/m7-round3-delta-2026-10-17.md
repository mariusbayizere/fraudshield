# Delta re-check: M7 round 3 (`5629e76..6ec6ef3`, branch `m7/staff-auth`)

Reviewer role: Principal Reviewer (fresh, independent; wrote none of the reviewed work)
Date: 2026-10-17 (UTC). Scope: the two round-3 commits (`0f58278`, `6ec6ef3`), against
`m7-round2-2026-10-17.md` and the owner's decisions of 2026-10-17.

**Verdict: CHANGES_REQUIRED** (1 new MAJOR, 4 new MINOR, 4 new NIT)

Of round 2's 13 findings, 12 are closed and 1 (finding 3) is closed in part. The owner's six
decisions are recorded. `ci` run 39054478351 on `6ec6ef3` was green on all 7 jobs, and `stack`
passed. The block is a race in the new stale-if-error code.

## Method

Static review only. Read in full:
- `ApiKeyAuthenticator`, `ApiKeyService.webhookEndpoint`, both API-key filters;
- `RelayedAuditAppender`, `AuditRelayWiring`, `EnvelopeConsumer`, `FailureClass`;
- the ingest configuration, `FakeTime`, ADR 0071 §6.

Every new or changed test was traced against the deletion standard. The analyst-API counts were
recomputed from the contract and the controllers, and the M8 mock claim was checked against
`origin/m8/frontend`.

## New findings

| # | Severity | Location | Finding | Required action |
|---|---|---|---|---|
| 1 | MAJOR | `ApiKeyAuthenticator.credential`, stale branch | The stale renewal put back the record taken before the blocking read without checking it was still the cache's entry. An announcement that evicted the record while the read was blocked (up to Hikari's 2 s) was undone, and the revoked record was served for up to 10 minutes. Failure scenario: an instance's pool is exhausted; a compromised key is revoked elsewhere; the announcement arrives and evicts; the blocked threads put the record back | Renew only if the entry is unchanged, and test an eviction during a blocked read |
| 2 | MINOR | R-13; ADR 0073; the class Javadoc; M7_updates | "No revocation can be committed during the outage" holds only if every instance loses the database. In an outage local to one instance, others commit revocations and only the announcement, which pub/sub drops during reconnects, protects it. "No instance has verified" is per instance | Restate the window per instance and have the owner re-confirm, or close the gap |
| 3 | MINOR | ADR 0071 §6; `worstCaseStaleness()` Javadoc; FR-06-07 | Still state the 2 s bound unconditionally | Amend all three |
| 4 | MINOR | `AuthorisationMatrixTest.apiKeysReachOnlyTheMachinePathsByTheContractFileAlone` | Off `/alerts` and `/admin` it asserted only "not 2xx", which a 404 from an unbuilt endpoint passes; FR-07-05's oracle had no teeth there | Require 401/403 on every checked operation |
| 5 | MINOR | stale branch; ADR 0073 | The renewal was timed from before the read, and the pool timeout equals the TTL, so a blocked read returned an already expired renewal: in practice a blocked read on every request, against the claim | Renew from after the failed read, or correct the claim |
| N1 | NIT | `requirements.yaml` NFR-REL-02, FR-07-05, TEST-04 | The same evidence file listed twice | Merge |
| N2 | NIT | `webhookEndpoint` sort | Deleting it fails the webhook test only about half the time (the fake clock ties creation times and the order falls to random ids) | Make it deterministic or test the sort directly |
| N3 | NIT | `AuditRelayWiring` | The narrowed catch and the missing-`event_id` path are untested | Wiring tests |
| N4 | NIT | M7_updates "Size" | "47" against the table's 48 | Say why |

**Deletion-standard counts (as reviewed).**
- Tests: 9 in scope, 6 proved in full and 3 in part (the webhook sort; the matrix oracle, 32 of 66 operations; the `/api/docs` POST probe, made redundant once CSRF stayed on).
- ADR 0073's decided section: 4 of 6 claims accurate.
- Other documents and backlog rows: accurate, apart from findings 2, 3 and N4.

## Response (round 3, delta fixes)

| # | Response |
|---|---|
| 1 | The renewal uses `cache.replace(keyId, entryReadFrom, renewed)` and otherwise rethrows, so the client gets 503. `ApiKeyLifecycleTest.announcementDuringBlockedReadIsNotUndoneByTheStaleRenewal` blocks a read on a latch, announces, then releases: the blocked request is refused and the record stays evicted |
| 2 | R-13, ADR 0073, the Javadoc and M7_updates state the window per instance. **The owner's confirmation of the wider statement is requested** |
| 3 | ADR 0071 gains an amendment; the `worstCaseStaleness()` Javadoc and FR-06-07's note say the bound holds while the instance can read the database |
| 4 | The oracle requires 401 or 403 on every non-public operation off the machine paths |
| 5 | The renewal runs from a timestamp taken after the failed read; the cap stays anchored to the last successful read |
| N1 | Merged |
| N2 | `ApiKeyService.ACTIVE_FIRST` is unit-tested directly (`WebhookOrderTest`) |
| N3 | The dead-letter wiring test also sends a well-formed payload without its `event_id` header, which must reach the dead-letter topic as `malformed_envelope`. The narrowing itself stays untested: no reachable input raises an `IllegalArgumentException` inside the append's transaction, since `parse` validates every field first and the database constrains the role |
| N4 | Stated: the 48 less the public `/environment` |

## Second delta re-check (`6ec6ef3..0de4d0e`): CHANGES_REQUIRED, and the response

A fresh reviewer re-checked the fixes. Findings 1 to 4, N1, N3 and N4 above are closed; finding 5
is closed in code but was untested; N2 is closed in part (the comparator is proved, not its use in
`webhookEndpoint`). Its new findings:

| # | Severity | Finding | Response |
|---|---|---|---|
| A | MAJOR | The fix for finding 1 refused every request but the first that was blocked on the same read: the others' `replace` failed against the first one's renewal, and they answered 503 through a hanging outage, against D-15 | When `replace` fails, the entry is read again: a renewal of the same read (same last-read time, same record instance) is served, and so is a record a request has read successfully since; only an evicted entry is refused. `requestsBlockedOnTheSameReadAreAllAdmitted` releases two blocked reads at once and requires both to be admitted |
| B | MINOR | The window is not "the 2 s before the outage": an idle key's record read up to 10 minutes before the outage is still served, so an unannounced revocation in those 10 minutes is missed even when every instance is down | R-13, ADR 0073, the Javadoc and M7_updates state that window; **the owner's decision is requested** |
| C | NIT | A pooled connection can block for the 10 s socket timeout, not the pool's 2 s | ADR 0073 says so |
| D | NIT | The post-read renewal timestamp was untested | `failedReadThatBlockedPastTheCacheTtlIsNotRepeatedOnTheNextRequest`: a failed read that advances the clock 2.5 s, then a request a second later must not read again |

## Third delta re-check (`0de4d0e..0bc26fb`): APPROVED_WITH_MINORS, and the response

A fresh reviewer closed A to D, found the stale branch race-free against announcements, and raised
two MINORs:

| # | Finding | Response |
|---|---|---|
| N1 | A successful read begun before a revocation could cache the old record after its announcement had arrived; an outage then served it for up to 10 minutes, outside R-13's "announcement not received" | Closed in code rather than widening the risk: announcements are counted, and a read that overlapped one is not cached (atomically, by `compute` against the announcement's removal). `recordReadWhileAnAnnouncementArrivedIsNotKeptForAnOutage` |
| N2 | The fresher-record branch was untested | `requestBlockedOnFailedReadIsServedTheRecordAnotherReadSince` |

## Fourth re-check (`0bc26fb..6303efe`): APPROVED_WITH_MINORS, and the response

N1 and N2 closed; the counter and `compute` insert were found atomic against an announcement's
removal, keeping `previous` resurrects nothing, and both tests fail on revert and are deterministic.

| # | Finding | Response |
|---|---|---|
| N3 | The global counter stops caching for every in-flight read on any announcement: an availability cost under announcement bursts | Accepted and stated in ADR 0073, with the per-key alternative |
| N4 | R-13's evidence omitted the two new tests | Listed |
| N5 | The class Javadoc did not say an in-flight read is not cached | Says so |
| N6 | ADR 0073's double parenthetical and long line | Merged and re-wrapped |

**CI on `6303efe`.** `stack` and `devcontainer` passed; `ci`'s java job failed in
`CryptoTest.signedTokensVerifyOnceForTheirPurposeAndState`, a test from M7's first build that this
round did not touch. It "tampered" the token by changing its last base64url character, which in an
unpadded string can carry only padding bits; when it did, the bytes decoded unchanged and the token
verified. The test now changes a middle character, which always alters the bytes.
