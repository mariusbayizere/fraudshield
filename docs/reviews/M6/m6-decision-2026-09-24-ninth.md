> Recorded by the author on 2026-09-24. Both MAJOR findings were fixed; `docs/parallel/M6_updates.md`
> maps them to the fix.

# M6 review 9: commit 2f92169 (admission unit restored for the batch)

Reviewer: fresh, independent Principal Reviewer. Tree: `/home/marius/fraudshield-m6-review9`, detached at `2f92169`.
Scope: BLOCKER and MAJOR only.

## Verdict: CHANGES_REQUIRED (2 MAJOR, 0 BLOCKER)

The fix does what it set out to do. On every protected path an exhausted key is now refused in the filter before any body byte is read. Accepted batches cost exactly their item count: 1 on admission plus `items - 1` in the controller, and 1 for a single-item batch. The response headers are correct. Two problems remain. The regression test that guards review 8's MAJOR depends on timing, and fails on a slow host with the same symptom as the defect it guards. The controller's new partial charge also changed what a budget-refused batch costs and what its `Retry-After` means, and ADR 0058 still describes the old behaviour.

## Checks run

| Command / probe | Result |
|---|---|
| `git show 2f92169`, `git show b830cce`; read `RateLimitFilter`, `ApiKeyFilter`, `IngestController`, `BatchJobs.chargeableUnits`, `ResilientRateLimiter`, `LocalRateLimiter`, `RedisRateLimiter`, `DecisionWiring` filter registration, ADR 0058, `M6_updates.md` | read |
| Path variants: both filters use the same `PROTECTED` prefix test on `getRequestURI()`. If a variant (`;x=1` in a segment, percent-encoded segment) skips the rate filter, it also skips `ApiKeyFilter`, so there is no principal and the controller returns 401 (`scope()`, IngestController.java:314). | No path is authenticated but uncharged, and none is charged twice. (`ApiKeyFilter` strips the context path and `RateLimitFilter` does not. No context path is configured, so this is latent only.) |
| Header merge: Spring 7.0.9 `ServletServerHttpResponse` probe (scratch `H.java`). The filter calls `setHeader("RateLimit-Remaining","997")`, then the entity header is `988`. | Servlet ends with `[988]`: replaced, not duplicated. The controller's second-charge headers win. With no second charge (single item, invalid envelope), the filter's admission headers stand. Correct. |
| `./mvnw -B -ntp -pl ingest -am test ... -Dtest='RateLimitApiTest,RateLimitConfigurationTest'` (timing print added to the stalled-body test) | 10/10 pass. `REVIEW9 batch roundtrip ms=163.4`, load avg ≈ 2.5 |
| Same reactor, `-Dtest='RateLimitApiTest,IngestApiTest'`, with mutations M1+M2 below | IngestApiTest 12/12. RateLimitApiTest 1 error (M1). Roundtrip 122.9 ms |
| `LocalRateLimiter` scenario (scratch `R.java` against `ingest/target/classes`, fake clock): filter + controller sequence for a 20-item batch, client honouring `Retry-After` | Reproduces finding 2 (output below) |

## Mutations

| # | Mutation | Result |
|---|---|---|
| M1 | `Thread.sleep(250)` between the draining 202 and the stalled socket (RateLimitApiTest, simulating a slower host) | Test fails: `exhaustedKeysAreRefusedBeforeTheirBatchBodyIsRead:249 » SocketTimeout Read timed out`. This is finding 1: timing alone produces the defect's symptom. |
| M2 | IngestController.java:156 `units > 1` → `units > 2` (a 2-item batch is charged 1 unit) | **Survived.** All other RateLimitApiTest cases and IngestApiTest 12/12 pass. This is a boundary gap and is not raised as MAJOR. |

All mutations were restored with `git checkout -- backend`, and `git status --short` is empty. The untracked `REVIEW9-OUT.md` and ignored build output remain. `ingest/target/classes` was last compiled from the M2-mutated source, so rebuild before reusing this worktree's target.

## Findings

### 1. MAJOR: the stalled-body regression test has a ~200 ms timing window and fails on a slow host like the defect it guards

- **Where:** `backend/ingest/src/test/java/io/github/mariusbayizere/fraudshield/ingest/api/RateLimitApiTest.java:222-249` (`exhaustedKeysAreRefusedBeforeTheirBatchBodyIsRead`). The drain is at :227 and the `readLine` with a 3 s `SoTimeout` is at :245.
- **Scenario:** the test drains `BUDGET_KEY_SIX` with a 1,000-item batch. The controller's 999-unit charge leaves exactly 0 tokens. The test then opens the stalled request. At `LIMIT = 5` the bucket refills continuously (`LocalRateLimiter`:58 and the Redis script both use fractional tokens), so one unit is back after 200 ms. The window runs from the controller's charge to the stalled request's admission, and covers `batches.submit` (a DB insert and job start), the 202 response, the client's return and the socket connect. If that window passes 200 ms, the admission is **allowed**, the controller blocks in `readNBytes` on the stalled body, and `readLine` times out after 3 s. That is exactly the failure the pre-fix code produced. Measured roundtrips on this laptop at load ≈ 2.5 were 163 ms and 123 ms, which leaves tens of milliseconds of margin. This host has recently run at load 8–15 (PB-74), and CI runners are shared. On a loaded host the test fails with a false failure that looks like a regression. The likely next step is to retry or disable it, and then review 8's MAJOR has no guard.
- **How verified:** run (roundtrip timings above) and mutation M1: a 250 ms host delay turns it into `SocketTimeout`.
- **Required action:** make the guard independent of timing. For example, add a deterministic filter-level test: `RateLimitFilter` with a limiter that refuses, a request whose input stream throws if read, and assertions of 429, chain not invoked, and body not read. The API test can keep the stalled socket, but only if its outcome cannot depend on refill: for example, open N stalled connections at once after draining and require at least `N - ceil(elapsed·LIMIT)` prompt 429s. Alternatively, run it against a key or config whose refill is negligible over the test's duration.

### 2. MAJOR: a budget-refused batch is now billed the admission unit and given a `Retry-After` one unit short, and ADR 0058 still says a refusal takes nothing

- **Where:** `backend/ingest/src/main/java/io/github/mariusbayizere/fraudshield/ingest/web/IngestController.java:156-164`. `docs/adr/0058-the-ingest-budget-counts-transactions.md` point 1 (lines 24-28: "if fewer units remain than the batch holds, **nothing is taken**"), point 6 (lines 52-54: "`Retry-After` is … until enough units have refilled for the refused charge … a refusal takes nothing"), and point 2, whose list of one-unit outcomes leaves out the budget refusal.
- **Scenario:** since 2f92169 the filter has already taken 1 unit when the controller finds a valid batch of `units` items does not fit. The controller refuses `take(units - 1)` and returns 429. The admission unit is kept, and `Retry-After` is computed as `ceil((units-1 - tokens)/limit)`. A retry costs `1 + (units-1) = units`, so a client that waits exactly `Retry-After` can be refused again, and pays another admission unit each time. Reproduced with `LocalRateLimiter` (rate 5, burst 1,000, 20-item batch, client honouring `Retry-After`):
  ```
  t=2020 admission allowed remaining=9 ; rest(19) refused retryAfter=2
  t=4020 admission allowed remaining=18; rest(19) refused retryAfter=1   <- honoured Retry-After, refused again
  t=5020 admission allowed remaining=22; rest(19) allowed
  ```
  Under b830cce the same sequence charges 20 at once, refuses with `Retry-After` 2, and is accepted at t=4020. So this is a regression introduced by the fix. The problem body also states "this request costs 20 transactions" (`refusal(permit, units, …)`), while `Retry-After` was computed for 19. At the default 2,000/s the extra refusal is rare, because the whole-second ceiling usually absorbs one unit. At low configured rates it is routine, and the extra unit per refusal happens at every rate. M10's campaign classifies 429s against this ADR, and the ADR now misstates both the cost of a refusal and the meaning of `Retry-After`.
- **How verified:** read the code and ADR, and reproduced with the limiter scenario above. No existing test covers a controller-level budget refusal's `Retry-After` or its cost.
- **Required action:** choose one of these and test it:
  - (a) make the controller's `Retry-After` cover the full retry cost (`units` from the current tokens, for example via a limiter method that reports the wait for N units). Then document in ADR 0058 points 1, 2 and 6 that a budget-refused batch costs the admission unit and that `RateLimit-Remaining` reflects it.
  - (b) refund the admission unit on the controller's refusal, so that "a refusal takes nothing" holds again. This needs care in the Redis path.

  Either way, add an API test for a batch that passes admission but is refused at the second charge, asserting `Retry-After`, `RateLimit-Remaining` and that the retry after `Retry-After` is accepted.

## Considered and not raised (below MAJOR, for the record)

- **Exhausted key and Tomcat swallow:** after the filter's 429, Tomcat still swallows up to `maxSwallowSize` (2 MiB) of the unread body on the worker thread. An unauthenticated 401 gets the same treatment, so this is not a budget bypass.
- **Parse work per unit on a key with budget left:** an invalid envelope or a padded 1-item batch of up to 4 MiB costs 1 unit, against 1,000 for a full valid batch. This predates 2f92169 (ADR 0058 point 2 design, and before that everything cost 1). Worth a tracked item, not a finding against this commit.
- **Redis between the two charges:** if Redis fails after admission, the rest is charged to the local bucket and marked degraded, which is the documented outage behaviour. The reverse case (admission degraded, rest shared) leaves the filter's `RateLimit-Degraded: true` on the answer. This is cosmetic.
- **Test gap:** M2 survived (no test submits a 2-item batch). Adding one is recommended.
- **`RateLimitConfigurationTest`:** it checks the default and the burst check; this was read, not mutated.
