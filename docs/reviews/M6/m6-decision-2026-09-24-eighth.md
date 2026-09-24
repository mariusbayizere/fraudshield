> Recorded by the author on 2026-09-24. The MAJOR finding and the two surviving mutations were
> fixed; `docs/parallel/M6_updates.md` maps them to the fix.

# REVIEW 8: FraudShield M6, commit b830cce (the per-key budget charged in transactions)

Reviewer: independent Principal Reviewer (fresh; has not seen earlier reviews). Worktree `/home/marius/fraudshield-m6-review8`, detached at b830cce. Addendum: tag-only commit 503b38f (see the end).

## Verdict: CHANGES_REQUIRED

One MAJOR finding, introduced by this fix. No BLOCKER.

The core change is sound. The Lua script and `LocalRateLimiter` charge all or nothing, atomically: the charge happens inside one EVAL, or under the bucket's monitor. `chargeableUnits` matches `BatchJobs.submit`'s envelope checks exactly. The filter and the controller never both charge the exact batch path. The 429 problem body, `Retry-After` ≥ 1 and the `RateLimit-*` headers are correct on every batch path I read. The old property name and the 200/s default appear nowhere outside historical ADR/M6 text. The API test does catch a batch charged as one request.

## Checks run

| Command | Result |
|---|---|
| `git show b830cce` and reads of RateLimiter, Local/Redis/ResilientRateLimiter, RateLimitFilter, ApiKeyFilter, IngestController, BatchJobs, FraudShieldProperties, DecisionWiring, tests, ADR 0058, threat model, M6_updates | read |
| `git show b830cce^:.../RateLimitFilter.java` | before the fix, the filter charged every protected path, batch included, **before** the body was read |
| `git grep` for `requests-per-second` / `requestsPerSecond` / `RATE_LIMIT` in code, config, charts and env files | none remaining (only historical mentions in ADR 0058 and M6_updates) |
| `./mvnw -B -ntp -pl ingest -am test -Dtest='TransactionBudgetTest,RateLimitApiTest'` (spotless, checkstyle, jacoco and spotbugs skipped) plus a temporary probe test | BUILD SUCCESS: TransactionBudgetTest 6/6, RateLimitApiTest 5/5 + probe 1/1 |
| Probe output (reproduction of finding 1) | `exhaust: 202 remaining=0`; `single endpoint, over budget, stalled body: HTTP/1.1 429 after 70 ms`; `batch endpoint, over budget, stalled body: TIMEOUT after 4007 ms`; `30 full batches from an exhausted key: refused=30 in 570 ms` |
| `git status` after every run | clean. The probe and all mutations were restored with `git checkout --`. |

## Mutations

| # | Mutation | Tests | Result |
|---|---|---|---|
| M1 | `IngestController`: `int units = 1;` (a batch charged as one request) | RateLimitApiTest | **killed**: 4/5 fail, including `batchCallersCannotExceedTheTransactionBudget` |
| M4 | `IngestController.charged(...)` returns `answer` without charging (refused, oversized or malformed batch envelopes become free) | RateLimitApiTest, TransactionBudgetTest, IngestApiTest | **survived**. The two rate-limit classes pass. IngestApiTest had 2 unrelated failures under load 13.8 (single-endpoint 503s and an SMS await timeout, on code M4 does not touch). |
| M5 | `FraudShieldProperties.RateLimit`: the burst ≥ 1,000 startup check disabled | same run | **survived**: no test covers the startup check |

M4 and M5 are gaps in coverage, not wrong behaviour; the code does what ADR 0058 says. I'm not raising them as findings. M4 is folded into the required action for finding 1, which changes exactly that code.

## Findings

### 1. MAJOR: an exhausted key can make the server read and parse unlimited batch bodies of up to 4 MiB. The flood control on the batch endpoint regressed.

- **Where:**
  - `backend/ingest/src/main/java/io/github/mariusbayizere/fraudshield/ingest/web/RateLimitFilter.java:61-63`: the batch POST is now exempt from the filter.
  - `backend/ingest/src/main/java/io/github/mariusbayizere/fraudshield/ingest/web/IngestController.java:138-156`: the body is read (`readNBytes(MAX_BATCH_BODY + 1)`, `MAX_BATCH_BODY` = 4 MiB at :45) and parsed (`JSON.readTree`) before the first `limiter.take`.
  - `IngestController.java:187-203` (`charged`), `LocalRateLimiter.java:62-67` and the Lua script: a refused charge takes nothing.
- **Failure scenario:** a key spends its budget, or only its last 999 units. From then on:
  - Each batch request, valid or malformed, up to 4 MiB, is read in full and turned into a Jackson tree on a Tomcat thread before it is refused with 429.
  - Because a refusal takes nothing, the key's bucket never goes lower, and nothing limits how often it can do this.
  - A slow or stalled body holds a request thread for as long as the client likes, and the key is not charged for it.

  Before b830cce the filter refused an over-budget key at one unit, before the body was read, so neither was possible. The per-key budget was the only per-caller protection of this path (threat model, "Ingestion API (1) — D: one key floods …"). Now the costliest pre-decision work on the ingest service, 4 MiB reads and parses, is unlimited for exactly the keys the budget has cut off.
- **Contradicts the docs:**
  - ADR 0058 point 2 says "charging only accepted batches would make the parser free to exercise". For an over-budget key the parser is free.
  - `RateLimitFilter`'s class Javadoc and M6_updates.md describe the batch path as held to the budget.
- **How verified:** read and **reproduced**. A temporary test in RateLimitApiTest (Redis up, budget 5/s, burst 1,000):
  - The key was emptied with one 1,000-item batch (202, `RateLimit-Remaining: 0`).
  - The same key then sent headers plus a partial body and stalled. On `/api/v1/transactions/ingest` it got **429 after 70 ms**: the filter refused it before reading. On `/api/v1/transactions/ingest/batch` there was **no answer after 4,007 ms**: the server sat in `readNBytes` for an over-budget key.
  - 30 full 1,000-item batches from the exhausted key were all read, parsed and refused (30 × 429 in 570 ms). None was charged, so the loop can go on without limit.
- **Required action:**
  1. Refuse an over-budget key before its batch body is read. For example, keep an admission charge of 1 unit in the filter for the batch POST, then charge the item count in the controller. That overcharges by one; alternatively, have the controller charge `items − 1` with an all-or-nothing script that accepts a pre-reserved unit. Either way, an exhausted key gets 429 without the server reading its body.
  2. Correct ADR 0058 point 2, the filter Javadoc and M6_updates to match whatever is chosen.
  3. Add a test like the probe above: an exhausted key's stalled batch request is answered 429 promptly.
  4. Add a test that a refused or malformed batch envelope costs one unit. That would kill mutation M4, which survives today.

## Things I checked and did not raise (not BLOCKER or MAJOR)

- **Encoded or matrix-parameter variants of the batch path:** `getRequestURI` is not equal to `BATCH_PATH`, so the filter charges 1 and the controller charges again. That is an overcharge of one unit, not a bypass.
- **An exact `POST /api/v1/transactions/ingest/batch` always reaches a charge:** no `consumes` or `produces` on the mapping, so Spring cannot answer 415 before the controller; a 401 happens in `ApiKeyFilter` first.
- **Redis timeout after the script ran:** the fallback charges the local bucket again. That is a double charge, not an excess.
- **Switching to the fallback** grants each instance a fresh local burst. This is the documented degraded trade-off and was there before this commit.
- **A `SQLException` in `submit` after the charge** spends the units without recording a job. That is an overcharge during an outage only.
- **`LocalRateLimiter` reads `now` outside the monitor**, so under contention the refill window can be counted twice. This predates the commit and the effect is small.
- **The test bounds** (`BURST + LIMIT*(seconds+1)`, refill counted from the send) are safe on a loaded host. The run passed at load average 5 to 14.
- **Retry-After:** `ceil((units − tokens)/limit)`, never below 1. It is always reachable, because burst ≥ `MAX_ITEMS` ≥ units, which the startup check enforces.
- **`RateLimit-Remaining` on a refusal** reports what is still in the bucket, as ADR 0058 point 6 says.

## Addendum: 503b38f (coordinator's added scope)

`git diff b830cce 503b38f -- backend/decision/src/test` changes only three lines, each adding `@Tag("TEST-03")`: to `HoldTimeoutServiceTest`, `DecisionEngineTest` and `FreezeAndBreakerTest`. `org.junit.jupiter.api.Tag` is already imported in each file.

The only tag-based test selection in the build is `excludedGroups=requires-docker` (`backend/decision/pom.xml`, profile `without-docker-tests`), so the new tag excludes nothing and changes no test run. The matrix row TEST-03 cites these three files at the new tag lines.

**No BLOCKER or MAJOR in the tag change.** Verified by read, not run. The merge of main at m5-complete and the documentation commits in that range were out of scope and not reviewed.
