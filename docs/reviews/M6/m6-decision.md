REVIEW MODE: M6 m6/decision

# Review: M6 ingestion and decision engine (`m6/decision`)            Reviewer role: Principal Reviewer
Date: 2026-09-22 (UTC; review run 10:26–10:59 UTC)   Branch/commit: `665b37f` (`m6/decision`)   Requirements: FR-01-01…07, FR-03-01…08, FR-05-05 (DSL), NFR-REL-01, NFR-REL-02   Defects: D-06, D-10, D-12, D-13, D-14, D-15, D-17, D-18, D-25
Verdict: **CHANGES_REQUIRED**

Scope reviewed: the 13 M6 commits `5250db1`…`665b37f` (on top of `f8885d6`) restricted to
`backend/{rules,decision,notify,ingest}`, `backend/persistence/.../migration/V60`–`V63`,
`backend/pom.xml`, `docs/adr/0060`–`0067`, `docs/parallel/M6_updates.md` and
`docs/benchmarks/2026-09-22-M6-decision-latency.json`. I reviewed from the diff, the requirement
text (Part C.2, C.4, D.3 M6, E.1, E.6, E.7, B D-10/12/13/14/15/18/25) and the tests. I did not
write this code.

**What the verdict means.** No BLOCKER was found: nothing claimed done in the branch is
fabricated. The gate numbers in `M6_updates.md` match the committed benchmark file row for row,
and the declared gaps are real and correctly described. There are, however, seven MAJOR findings
on the code and tests (1–7), and one MAJOR on the gate (15, below).
Two are defects in idempotency (FR-01-03) under failure (one reproduced against Redis). Two are
mutations the test suite does not catch on critical paths (D-15 commit ordering, FR-01-03
fingerprint). One is a proposed DONE status that the code cannot reach in production (FR-03-05).
One is a partial-failure inconsistency on the verification page, and one is a consumer with no
poison-record handling or DLQ. Under I.1 rule 6 every MAJOR must be fixed and re-reviewed before
**merge**.

Separately, **the M6 gate is not met**: the end-to-end p95 is not below 50 ms at the largest
achievable load (declared by the author, confirmed here, and not reproducible even at 50 req/s on
this host today). So even after the MAJORs are fixed, `m6-complete` **must not be tagged**. The
author declared the latency result honestly as not met and did not claim it done, so it is not a
BLOCKER under rule 5. A milestone gate that is not met cannot give an APPROVED milestone verdict
(I.3 step 6). Once the MAJORs are fixed and re-reviewed, the branch may merge with the gate
recorded as NOT MET. The tag waits for a passing measurement on a host that can support it, or
for an owner decision recorded as an ADR.

## Checks re-run (command → result)

| # | Command (cwd) | Result |
|---|---|---|
| 1 | `uptime` (start) | `load average: 8.15, 9.82, 11.64`; `nproc` = 4 |
| 2 | `./mvnw -B -ntp verify -pl rules,decision,notify,ingest -am` (`backend/`) | **BUILD SUCCESS**, EXIT=0, `real 8m0.486s`. Tests: common 1131, rules 42, decision 101, notify 52, ingest 46 — all `Failures: 0, Errors: 0, Skipped: 0` (Testcontainers suites ran; none skipped). Load at start 8.22 / end 13.24 |
| 3 | `uv run fs-traceability render` (repo root) | `wrote docs/traceability/requirements_matrix.md (258 rows, 782 tagged tests)`; `git diff --stat -- docs/traceability` → **empty** |
| 4 | `uv run fs-migration-guard` | `migration-guard: 11 merged migrations, 0 changed` (also 0 changed `--against origin/m5/scoring`); V60–V63 are new, no number collision with `origin/m5/scoring` |
| 5 | `git diff --stat f8885d6..665b37f -- ml frontend contracts requirements.yaml docs/backlog lab_notebook.md SESSION_STATE.md` | **empty**: the M6 commits touch none of these paths. (The three-dot diff against `m0/bootstrap` is not empty, 107 files under `ml/`. Those files come from M1–M3 history that `m0/bootstrap` does not contain yet, not from M6.) |
| 6 | `git diff --stat f8885d6..665b37f` outside the four modules | only the files `M6_updates.md` declares: `backend/README.md`, `backend/pom.xml`, V60–V63, ADR 0060–0067, the benchmark JSON, `M6_updates.md` and the regenerated matrix |
| 7 | `git log m0/bootstrap..HEAD --format=%B \| grep -ci co-authored` | `0`; the 13 M6 subjects are Conventional Commits, one author identity |
| 8 | Redis reproduction of the idempotency scripts (verbatim copy of `RedisIdempotency.CLAIM`/`COMPLETE`, throwaway key, lease shortened to 100 ms) | `CLAIMED` → lease expires (`EXISTS` 0) → `COMPLETE` → hash `state DONE response …` **without `fp`** → identical replay claim returns **`CONFLICT`** (finding 1) |
| 9 | Short latency benchmark, `-Dfs.benchmark.rates=50 -Dfs.benchmark.seconds=20` (`backend/`), then `git checkout -- docs/benchmarks/` | EXIT=0; see Evidence. Load average before 24.54 / after 25.99 |

## Mutation spot checks (what was broken → which test failed)

Each mutation was applied to main code alone, the narrowest test classes were run with
`./mvnw -B -ntp -pl <module> -am test -Djacoco.skip=true -Dtest=<classes> -Dsurefire.failIfNoSpecifiedTests=false`,
and the file was restored with `git checkout`. `git status` was clean before the next mutation.

| # | Requirement | Mutation | Tests run | Result |
|---|---|---|---|---|
| M1 | E.6 / FR-05-07 half-open HIGH boundary | `Tiering.tierOf`: `compareTo(high) >= 0` → `> 0` | `DecisionEngineTest` | **Killed**: `perChannelThresholdsApply`, `tiersAreHalfOpenWithNoGap[5]` |
| M2 | E.6 order: frozen → DECLINE first | `DecisionEngine`: `if (in.accountFrozen())` → `if (in.accountFrozen() && tier == RiskTier.LOW)` | `DecisionEngineTest,DecisionServiceTest` | **Killed**, but only by the generative `propertyTierIsMonotoneInScoreAndOutcomesAreConsistent`. No example test covers a frozen account with a MEDIUM/HIGH score |
| M3 | FR-03-02 / D-18 hold claim vs analyst atomicity | `RedisHoldSchedule.CANCEL`: `if not member then return 0` → `return 1` (analyst "wins" a hold the poller already claimed) | `RedisAdaptersTest,HoldTimeoutServiceTest,TransitionsAndBreakerMonitorTest` | **Killed**: `RedisAdaptersTest.holdsAreClaimedOrCancelledExactlyOnceAndOneInstanceLeads` |
| M4 | FR-03-06 freeze on the 3rd HIGH in 60 min (production Lua) | `RedisFreezes.RECORD_HIGH`: `count >= ARGV[4]` → `count > ARGV[4]` | `RedisAdaptersTest,RedisOutageTest,FreezeAndBreakerTest,DecisionServiceTest` | **Killed** only by `RedisOutageTest.decisionsContinueOnFallbacksWhileRedisIsDownAndResumeAfter` (expected DECLINE, was APPROVE). **Survived** `RedisAdaptersTest.exactlyOneOfManyConcurrentThirdHighsFreezes` and `FreezeAndBreakerTest` (finding 8) |
| M5 | FR-03-07 / D-18 breaker "> 5.0%" | `MccCircuitBreaker.breaches`: `> 0` → `>= 0` | `FreezeAndBreakerTest,TransitionsAndBreakerMonitorTest` | **Killed**: `theBreakerOpensAboveFivePercentOnlyWithEnoughVolume` |
| M6 | D-15 spool at-least-once: commit only after the sink accepts | `SpoolDrainer.run`: `spool.commit(...)` moved **before** `sink.accept(batch)` | `DurableSpoolTest,KafkaSpoolChaosTest,PostgresAdaptersTest` (decision); then `ResilienceApiTest` (ingest) | **SURVIVED** all four (finding 3) |
| M7 | FR-02-09 distinct senders exclude the account itself | `RedisAccountState.read`: `senders - (own within window ? 1 : 0)` → `senders - 0` | `RedisAdaptersTest` | **Killed**: `counterpartyAndDeviceSetsCountDistinctOtherAccountsInOpenWindows` |
| M8 | Webhook HMAC (`t + "." + body`) | `WebhookSignatures.sign`: `"."` → `":"` | `WebhookSignaturesTest,WebhookDeliveryTest` | **Killed**: 7 failures in `WebhookSignaturesTest` (shared vectors, `whatWeSignVerifiesAndMatchesTheValidVector`); `WebhookDeliveryTest` did not notice (it signs and verifies with the same code) |
| M9 | FR-01-03 fingerprint covers every request value | `Fingerprints.of`: removed `fields.add(r.counterpartyToken())` | `RequestValidatorTest,IngestApiTest` | **SURVIVED** (finding 4). The one `IngestApiTest` failure in this run, `highRiskTransactionsAreDeclinedBlockedAndTheCustomerIsTexted` ("SMS within 5 s": 6.30 s), is a timing failure unrelated to the mutation (finding 14) |

Score: 7 of 9 killed. The two survivors are both on critical paths.

## Findings

| # | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| 1 | MAJOR | `ingest/.../idempotency/RedisIdempotency.java` (`COMPLETE`, `CLAIM`), `IngestService.decide` | A decision that completes after the 10 s in-flight lease has expired writes `state=DONE,response` into a **fresh hash with no `fp`**. For the next 24 h every identical replay then returns **409 `idempotency-conflict`**, and each replay records a spurious `IDEMPOTENCY_CONFLICT` audit event. Reproduced against Redis (check 8). If a duplicate re-claimed in between, both requests are scored and the first COMPLETE overwrites the second's INFLIGHT, so clients receive different decisions for one transaction id. The lease can be exceeded: the JDBC fallbacks on the path have no hot-path budget (finding 10: Hikari 2 s + `socketTimeout` 10 s), and the spool wait (1 s) can time out and still fsync later, which releases the claim for a record that becomes durable. That is a second "decided twice" route beyond the crash case the `DecisionService` javadoc declares. | Make COMPLETE conditional: it succeeds only while this claim still holds the key (claim token / fp match), and it always writes `fp`. Bound the whole decision below the lease, or renew the lease. Add Redis-level tests for "complete after expiry" and "re-claim after expiry". | Open |
| 2 | MAJOR | `ingest/.../idempotency/ResilientIdempotency.java`; ADR 0067 point 3 | Decisions made while Redis is down are persisted only through the spool to PostgreSQL. `complete()` writes only to Redis and fails silently. After Redis recovers, `claim()` asks Redis only, finds nothing, returns `Claimed`, and **re-scores and re-decides** a transaction already decided during the outage. The client can get a different answer from the one PostgreSQL kept. This breaks FR-01-03 ("identical responses") across an outage boundary, and ADR 0067 does not declare it (it declares only two first submissions racing *while* Redis is down). | On a Redis `Claimed` during or after a degraded window, consult `transaction_ids` (or backfill Redis from PostgreSQL on recovery) before deciding. Otherwise record the gap explicitly in ADR 0067 and in `M6_updates.md`. Test: decide with Redis down, restore Redis, resubmit → replay. | Open |
| 3 | MAJOR | `decision/.../spool/SpoolDrainer.java`; `KafkaSpoolChaosTest`, `ResilienceApiTest.withPostgresDown…`, `DurableSpoolTest` | Mutation M6 survived: committing the consumer position **before** the sink accepts (data loss on any sink failure) fails no test. The sink-failure and retry path is never exercised. The Kafka "chaos" closes the producer after unpause, and `KafkaProducer.close()` flushes the buffered records. The PostgreSQL pause is shorter than the JDBC timeouts, so the calls block and then succeed. `DurableSpoolTest` does not cover `SpoolDrainer`. D-15's zero-loss claim therefore rests on untested ordering. | Add a `SpoolDrainer` test with a sink that throws N times and then accepts: every record is delivered, in order, and nothing is committed past a refused batch. Make the chaos tests produce real sink failures (a pause longer than the delivery/socket timeouts). | Open |
| 4 | MAJOR | `ingest/.../request/Fingerprints.java`; `RequestValidatorTest` (fingerprint test), `IngestApiTest.changedRequestsUnderTheSameIdAreAuditedConflicts` | Mutation M9 survived: removing `counterparty_id` from the fingerprint fails no test. Only `amount` is ever varied (both tests). So a changed counterparty, channel, MCC, device, coordinates or timestamp under the same id could be silently replayed as a duplicate (FR-01-03's 409 rule) without any test noticing. | Add a table-driven test that changes each fingerprinted field on its own and asserts the fingerprint changes, plus the documented normalisations (trailing zeros, fractional seconds) that must not change it. | Open |
| 5 | MAJOR | `docs/parallel/M6_updates.md` (FR-03-05 → DONE); `notify/.../sms/SelfServicePolicy.java`; `ResilienceApiTest.theVerificationPageIsSmallScriptFreeAndLiftsTheBlock` | FR-03-05 ("Yes" lifts the block within 10 s) is proposed **DONE**, but it cannot happen in production. `SelfServicePolicy` refuses a link whenever `daysSinceSimSwap` is null, and nothing in the API sets it (no MNO adapter). No SMS can be sent either (no PII vault). The test reaches the page by calling `verifications.issue(...)` directly, bypassing the D-25 gate. Applying DONE would record an acceptance criterion as met while it is unmet in the product, which would be a BLOCKER at merge. | Propose FR-03-05 as DONE_WITH_DEVIATION (or BLOCKED on the MNO and PII-vault backlog items), naming the bypass, in `M6_updates.md`. Keep the page tests. | Open |
| 6 | MAJOR | `notify/.../verification/VerificationService.answer`; `ingest/.../web/VerificationPageController.answer` | The customer's answer and the `unblock_events` row are committed **before** `DecisionTransitionService.customerAnswered` runs. If that call throws a runtime failure (spool full or timed out, Redis `latest` failing), the controller returns 500. The token is already USED, `unblock_events` says CUSTOMER_VERIFICATION unblocked, and the decision stays DECLINE with no webhook and no label. Nothing reconciles this and the customer cannot retry. | Make the transition part of the unit of work, or reconcile: a sweep finds `unblock_events` without an APPROVE state. Catch the failure and show the customer a retryable page. Test with the recorder failing. | Open |
| 7 | MAJOR | `notify/.../kafka/EnvelopeConsumer.java` | Any record whose handling throws (malformed JSON, missing `institution_id`, a non-transient `SQLException`) is re-sought and retried **forever, every ~100 ms poll with no backoff**, and logs a WARN each time. That stalls customer SMS or webhook delivery for the whole partition. C.3 requires a DLQ (`<topic>.dlq`) per topic. The PostgreSQL spool sink already dead-letters non-transient failures, but this consumer has no equivalent. | Separate transient from permanent failures. Send permanent ones to `<topic>.dlq` (or a dead-letter table) and commit past them, with bounded exponential backoff for transient ones. Test with a poison record followed by a good one. | Open |
| 8 | MINOR | `decision/.../domain/FreezePolicy.freezes`, `FreezeAndBreakerTest.theThirdHighWithinSixtyMinutesFreezesAtExactlyThree`, `RedisAdaptersTest.exactlyOneOfManyConcurrentThirdHighsFreezes` | The FR-03-06 "exactly three" unit test exercises `FreezePolicy.freezes`, which is **not called by any main code**: production uses the Lua in `RedisFreezes` and the SQL in `JdbcFreezes`. The Redis race test asserts that "exactly one freezes" but not that the third HIGH is the one that freezes, so M4 survived it. Only `RedisOutageTest` caught M4. | Assert in `RedisAdaptersTest` that the 1st and 2nd HIGH do not freeze and the 3rd does, including the exact 60-minute edge. Delete `FreezePolicy.freezes` or make the adapters use it. | Open |
| 9 | MINOR | `decision/.../application/DecisionService.decide`; ADR 0061 point 1 | E.6 puts "account frozen? → DECLINE" before "(3) ML or fallback score", and ADR 0061 says "Order exactly as E.6". The service scores **every** transaction first, frozen ones included: a scorer call on the hot path and a persisted scoring record. With the scorer down, a frozen account's decline also carries `ML_UNAVAILABLE`, a reason that played no part in that decline. The outcome is the same DECLINE. | Short-circuit frozen accounts before scoring, or amend ADR 0061 to say they are scored for the record and drop `ML_UNAVAILABLE` from frozen declines. | Open |
| 10 | MINOR | `decision/.../jdbc/JdbcFreezes.java`, `ingest/.../idempotency/JdbcIdempotency.java` (C.4 fallbacks) | Commit `20f8893` bounded only the account-profile lookup (50 ms). The other PostgreSQL fallbacks used on the hot path when Redis is down rely on Hikari `connection-timeout` 2 s and `socketTimeout` 10 s. In a Redis + PostgreSQL double fault one decision can wait for seconds, which feeds finding 1. | Give each hot-path fallback an explicit budget (as `RedisAccountState.durable` does) and fail the request as 503 when it is exceeded. | Open |
| 11 | MINOR | `decision/.../kafka/KafkaSpoolChaosTest` | D-15 asks for a test that **kills** Kafka and the API pod. The test's "process death" is an in-JVM `close()`: the writer thread is joined and `KafkaSink.close()` flushes. It does not show recovery after SIGKILL of a separate process. | Run the spool writer in a forked JVM and `destroyForcibly()` it mid-backlog (or document the limitation in ADR 0064). | Open |
| 12 | MINOR | `ingest/.../application/BatchJobs.java` | Batch items live only in memory. If the process dies, a job answered 202 stays `RUNNING` forever with nothing to finish or fail it. One item that stays `Unavailable` stops the job and leaves the rest unrecorded. An unparseable `cursor` reaches `Integer.parseInt` without validation. | Mark stale RUNNING jobs FAILED on start-up (or persist the items), and validate `cursor` (400). | Open |
| 13 | MINOR | `ingest/.../web/IngestController.principal`, `refuse`; `ApiKeyFilter` | The filter matches `getRequestURI()` by prefix, and the controllers dereference the principal without a null check. Any path variant that reaches a controller without passing through the filter becomes an NPE (500) instead of 401. It fails closed, but with the wrong status. With a chunked body (no Content-Length) larger than the limit, the body is truncated at `MAX_BODY` and answered 400 malformed instead of 413. | Return 401 when the principal is absent. Read one byte past the limit and answer 413. | Open |
| 14 | MINOR | `ingest/.../api/IngestApiTest.highRiskTransactionsAreDeclinedBlockedAndTheCustomerIsTexted` | The FR-03-04 "SMS within 5 s" assertion failed once in this review (6.30 s at load ≈ 20) and passed in the full verify run: a timing test that is flaky on this host. | Measure from the auto-block timestamp with the provider double's receive time, or record the host-load dependency in the test. | Open |
| 15 | MAJOR (blocks the `m6-complete` tag; not a merge blocker, see verdict) | M6 gate; `docs/benchmarks/2026-09-22-M6-decision-latency.json`; `M6_updates.md` "Proposed status VERIFIED_AT_REDUCED_SCALE" | End-to-end p95 < 50 ms at the largest achievable load: **not met** (claimed not met; confirmed). The only passing figure, 42.1 ms at 50 req/s, **could not be reproduced**: my 50 req/s × 20 s run measured client p95 **325.3 ms** at load average ≈ 25. The model is excluded (scorer double), so M5 inference is not in any figure. | Do not tag `m6-complete`. Record the gate as NOT MET, with both measurements and host loads. Re-measure on a quiet or dedicated host (with the M5 scorer when available) before re-requesting the gate. Do not apply VERIFIED_AT_REDUCED_SCALE to FR-01-01/03-01/03-03 on the strength of the 50 req/s point alone. | Open |
| 16 | MINOR | `decision/.../redis/RedisCircuitBreakers.countConfirmedFraud`, `counts` | Confirmed fraud increments `f` in the *confirmation* minute's bucket without `n`, and `counts` silently clamps `min(f, n)`. When M7 confirms an already auto-blocked HIGH, the fraud may be counted twice (D-18 numerator). The port contract does not say. | State in the port javadoc and ADR 0061 that callers count only non-auto-blocked confirmations and bucket them by transaction time, or dedupe by transaction id. | Open |
| 17 | MINOR | `decision/.../spool/DurableSpool.openSegment` | A new segment file is created and its data fsynced, but its parent directory is never fsynced. After a crash, a freshly rolled segment's directory entry (and with it acknowledged records) can be lost on some filesystems. | fsync the spool directory after creating a segment and after the checkpoint rename. | Open |
| 18 | MINOR | `notify/.../webhook/HttpWebhookTransport.PUBLIC_HTTPS` | The SSRF filter misses 100.64.0.0/10 (CGNAT) and applies the IPv6 `fc00::/7` test to the first octet of IPv4 addresses. DNS rebinding is declared (ADR 0066). | Use an explicit deny-list of IPv4 and IPv6 special-purpose ranges, with tests. | Open |
| 19 | MINOR | `decision/.../resilience/ResilientPorts.LocalFallbackHolds` | Holds scheduled locally during a Redis outage are timed out only while this instance leads. After recovery, if another instance holds the lease, they wait for the reconciliation sweep (deadline + 10 s), outside D-18's ±500 ms. | Drain local holds into Redis on recovery, or let every instance time out its own local holds. | Open |

### Declared gaps: are they honest and correctly classified?

- **Latency gate not met**: honest. The numbers in `M6_updates.md` equal the JSON file. The host
  caveat is stated, and so is the exclusion of the model. Classification is wrong in one respect:
  VERIFIED_AT_REDUCED_SCALE should not rest on a figure that does not reproduce (finding 15).
- **PII vault absent / no real SMS**: honest. FR-03-04 is correctly DONE_WITH_DEVIATION.
  FR-03-05 is not (finding 5).
- **MNO SIM-swap signal absent**: honest in the backlog note ("D-25 refuses self-service for every
  block"). The consequence for FR-03-05 is not carried into the proposed status (finding 5).
- **FR-01-07 `/api/docs`**: NOT_STARTED is correct.
- **No 429**: declared in the backlog. It should also appear in the FR-01-02/E.1 row evidence as an
  unmet response code.
- **No Java/Python feature-parity test**: honest and correctly left open.
- **Not declared**: findings 1, 2, 6, 7 (behaviour); 3, 4, 8 (tests without teeth); 11, 12, 17
  (durability details).

## Evidence reproduced (claimed vs measured)

| Claim | Claimed | Measured by reviewer |
|---|---|---|
| M6 modules build and all tests pass | green | `verify` BUILD SUCCESS, 1,372 tests (1131 + 42 + 101 + 52 + 46), 0 failures/errors/skips, 8 m 0 s |
| 100 identical → 1 scored, 1 body, 99 `Idempotent-Replayed`, TTL 24 h | `IngestApiTest` | passes in the verify run (not independently re-derived beyond the test) |
| 10,000-duplicate storm scored once | `ResilienceApiTest` | passes in the verify run |
| Chaos: ML, Kafka, Redis, PostgreSQL | `GrpcScorerTest`, `KafkaSpoolChaosTest`, `RedisOutageTest`, `ResilienceApiTest` | all pass, but the Kafka and PostgreSQL chaos never drive the sink-failure path (finding 3, mutation M6) |
| Traceability matrix is generated | render clean | `258 rows, 782 tagged tests`, no diff |
| Merged migrations unchanged | guard passes | `11 merged migrations, 0 changed` |
| Gate p95 < 50 ms at largest load | **not met**; 42.1 ms at 50 req/s, 69.1–108.6 ms at 100–200 req/s, 615 ms at 300 req/s (load avg 23) | 50 req/s × 20 s (1,000 requests, 0 errors): client p50 **110.1** / p95 **325.3** / p99 **612.3** ms; server decision p50 49 / p95 162 / p99 240 ms; load average 24.54 before, 25.99 after (1 min); `p95_under_50_ms: false`. The claimed 42.1 ms is **not reproduced** on this host at this load. File restored with `git checkout -- docs/benchmarks/` |

## Residual risks

- **Latency on real hardware is unknown.** Every measurement so far comes from an oversubscribed
  4-core host with the model mocked. The per-stage medians (about 6 ms per single Redis `EVAL`)
  point at CPU starvation, but that is a hypothesis, not a measurement. The FR-03-01 "1,000 HIGH
  events/s" load was not run.
- **Idempotency across failures** (findings 1, 2) is the most consequential open risk. A duplicate
  that is decided twice can produce two different customer-visible outcomes for one payment.
- **Production cannot start yet.** `ApiKeyAuthenticator` and `WebhookEndpoints` are M7 ports
  without implementations, and the API refuses to start without the first. Staff-path 403s,
  key rotation and revocation are unverified until M7.
- **D-25 self-service and customer SMS are dark in production** until the MNO adapter and the PII
  vault exist.
- **Out-of-order arrivals** under-count distinct senders and devices (ADR 0061 point 7, declared),
  and a payment that follows its predecessor on the same account within milliseconds can miss it
  in the velocity features (the feature-store update runs after the response). Both matter most
  for historical batch ingestion and bursts.
- **No cross-language parity test.** Nothing checks the Java `AccountContext` against the Python
  online features, so a silent training/serving skew is possible.
