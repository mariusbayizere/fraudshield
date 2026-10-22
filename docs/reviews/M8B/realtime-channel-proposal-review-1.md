# Review 1: ADR 0115, the real-time channel (Proposed)

Subject: `docs/adr/0115-the-real-time-channel.md` (untracked in the worktree on `m8b/analyst-api` at
`11448ce`, content sha256 `9b85a137…`). Reviewer: independent; wrote none of it. Method: reading
only. No build, test or container was run, and nothing was sent over the network. `0115:N` is a line
of the proposal. Other lines are at `11448ce` plus the uncommitted V81 / decision-owner work in the
worktree, which is read only as context.

## Verdict: SOUND_WITH_CHANGES

**MAJOR present: YES** (4 MAJOR, 14 MINOR).

The overall shape is right. The proposal keeps the reads as the source of truth, sends snapshots
carrying `version`, guarantees nothing about delivery, and re-reads after subscribing. Because of
that, most loss cases are bounded by the 60-second re-read, and the proposal says so. The tenant
and role filter is in the right place, and the credential is never ambient. The four MAJORs are
local. None needs a different architecture, but each must be fixed, and the proposal re-reviewed,
before P2 writes code against it:

- revocation polling costs a database or Redis round trip per session per second, not "cheap" (1);
- no instance-wide memory bound: 2,000 × 1 MiB of queues (2);
- the coverage table leaves out a writer that the approved decision-owner contract defines (3);
- staff names on Kafka contradict ADR 0012 point 4 and `topics.yaml` (4).

## Findings

### 1. MAJOR: the once-a-second revocation re-check is not cheap, and with Redis down it is a database transaction per session per second

**Section:** §3 "How often it is checked" (`0115:136-139`).

**Claim:** "Most checks hit the cache's 1-second in-process entry, so they are cheap."

**Evidence:**
- `SessionStateCache.isCurrent` (`backend/auth/.../session/SessionStateCache.java:113-121`) reads two
  keys, the user's version and the `sid`. An in-process entry lives at most `localTtl`, 1 s by
  default (`:21`, `:138`, `:157`).
- A check every 1 s against a 1 s entry therefore misses almost every time and goes to Redis (`:141`).
- A Redis value is trusted only until its 2 s deadline (`:144-155`). After that, `load` runs a tenant
  transaction with two queries (`:164-172`).
- ADR 0070 point 2 (`docs/adr/0070-staff-identity-and-audit-implementation.md:42-43`): "When Redis is
  down, the database answers".
- At the proposal's 2,000 sessions per instance (`0115:355`) the steady state is about 4,000 Redis
  GETs a second, plus a database load roughly every 2 s per distinct user and `sid`.
- With Redis down, every check goes to PostgreSQL: up to about 2,000 tenant transactions a second per
  instance, from idle sockets. They compete for the same pool as the decision path. This is a
  server-exhaustion path, and the proposal says the opposite.

**Fix:**
- **Drive closure by push.** Hook the existing `fs:auth:sessions` pub/sub (`onMessage`, `:235`):
  when an invalidation for a user or `sid` arrives, close the matching local sessions at once.
- **Keep a polling backstop, sized and stated.** Check per distinct (`user`, `sid`), not per socket,
  every P with jitter. Choose P so that `worstCaseStaleness()` (2 s) + clock skew + P + the close
  stays within D-27's 5 s, for example P = 2 s. Give the resulting Redis and DB rates for 2,000
  sessions.
- **State the Redis-down behaviour.** Either the DB rate is accepted with a stated bound and a
  limiter, or every socket is closed with `service-unavailable` and the console falls back to
  polling.

### 2. MAJOR: per-session queues are bounded but the instance is not; worst case ≈ 2 GiB of queued frames

**Section:** §8 back-pressure and limits (`0115:344-359`).

**Evidence:**
- **The arithmetic.** 500 frames or 1 MiB per session (`0115:344-345`) × 2,000 sessions per
  instance (`0115:355`) is about 2 GiB of outbound queue per instance.
- **No budget.** The proposal states no heap or memory budget for the API instance, and the
  repository has none for it (`docker-compose.yml` sets limits only for infrastructure services).
- **How it happens.** A burst raises the queues and slow readers hold them until heart-beat expiry
  (25 to 60 s, then about 2 more intervals). A TIMEOUT sweep closing many entries is one such burst,
  and so is a fan-out restart while thousands of phones sit on stalled 3G links.
- **The per-session bound protects other sessions from one slow reader.** It does not protect the
  process.

**Fix:**
- Serialise each event once per (institution, visibility class) and enqueue shared immutable bytes.
- Add an **instance-wide outbound byte budget** (ASSUMED value, stated against the heap). When it is
  exceeded, close the sessions with the largest queues using `realtime-overflow`.
- Lower the per-session cap to what 60 s of one institution's peak event rate needs, and state that
  rate.
- Add the budget to open question 3 and to the CI overflow test (`0115:555`).

### 3. MAJOR: the writer table omits the late-entry close, which the approved decision-owner contract defines and V81 implements

**Section:** §6.2 "Who publishes" and "Coverage" (`0115:239-275`).

**Evidence:**
- **What the contract defines.** Decision-owner contract §6.2 (`docs/architecture/decision-owner-contract.md:610-624`):
  in the transaction that writes a MEDIUM `AlertRaised`, `PostgresSink` "closes the new entry at
  once". After a TIMEOUT it closes it with the TIMEOUT's outcome, and after a RESOLVE as OVERRIDDEN.
- **What V81 implements.**
  `backend/decision/.../owner/DecisionOwner.java:705-710` (`AUTO_DECLINED`/`AUTO_RELEASED`,
  `version + 1`) and `:721-727` (OVERRIDDEN, `version + 1`), called from the sink's batch.
- **What the proposal says.** The table lists only the analyst operations, TIMEOUT, RESOLVE and
  ASSIGNED. The only exception it allows is "the sink's insert, whose event is the `fs.alerts.*`
  message" (`0115:272`). The sink's UPDATE is neither.
- **Consequence 1: the coverage rule is false as written.** "Every writer that changes an entry's
  `status`, `version` … must publish" (`0115:269`) does not hold for this writer.
- **Consequence 2: the console shows a closed entry as open.** The `alert.created` record is mapped
  to PENDING v0 (`0115:205-207`), but the entry is already AUTO_* or OVERRIDDEN at v1 in PostgreSQL.
  The console shows it as open until a re-read. In the resume rule's exception case (`0115:330-331`)
  that lasts two re-read periods.
- **Consequence 3: an unstated change to M6/V81.** Publishing from the sink's transaction changes
  M6/V81's sink path, and the proposal does not say so.

**Fix:**
- Add a row: `TIMED_OUT` / `RESOLVED`, written by "the sink, closing a late entry (decision-owner
  contract §6.2)". It publishes after the sink's batch commits, through the same non-blocking
  publisher (finding 11).
- Narrow the coverage exception to the INSERT only.
- List the sink change under "changes to M6's code" next to open question 2.
- **RESOLVE needs no ASSUMED.** It already increments `version` (`DecisionOwner.java:107-110`),
  so the "ASSUMED here" at `0115:249` can cite the code.

### 4. MAJOR: staff names on `fs.alerts.changes` contradict ADR 0012 point 4 and the topic catalogue

**Section:** §9 "Staff names" (`0115:505-507`), open question 4.

**Evidence:**
- **The proposal's reading.** "ADR 0012's rule forbids customer personal data and says nothing of
  staff."
- **What ADR 0012 says.** Point 4 is headed **"No personal data or credentials in events"**
  (`docs/adr/0012-event-and-scoring-contracts.md:45`), with no restriction to customers.
- **What the catalogue says.** The frozen header says "No message carries raw personal data"
  (`contracts/kafka/topics.yaml:5`).
- **What the existing schemas do.** No Kafka schema carries a person's name today. Staff are
  identified by id.
- **ADR 0116.** Its "staff names are not customer personal data"
  (`docs/adr/0116-alert-reassignment.md:192-193`) concerns HTTP responses (NFR-SEC-03's inspector),
  not events.
- **Conclusion.** As written, the change contradicts the frozen contract. Marking it ASSUMED does
  not make it consistent.

**Fix:**
- Carry ids only on Kafka: `assigned_analyst_id`, `actor_user_id`, `escalated_by_user_id`.
- The fan-out resolves names through a per-institution cache before it writes the frame, or the
  frame carries ids and the console resolves them.
- The Kafka `alert` object is then "AlertSummary with `StaffReference` replaced by an id". The
  contract test compares that projection, not identity (`0115:502-503`).

### 5. MINOR: what happens before CONNECT, and how routing works, must be stated, not left to the framework

**Section:** §2 and §8 (`0115:85-88`, `0115:149-151`, `0115:358-359`).

**Evidence:**
- **Frames before CONNECT.** The proposal limits destinations "after" connect. It does not say that
  any frame other than CONNECT on an unauthenticated socket (SUBSCRIBE, SEND, a second CONNECT) is
  refused and closes the socket.
- **Spring's user-destination pitfall.** With Spring's user destinations on a simple broker,
  `/user/queue/alerts` is translated to a per-session destination (`/queue/alerts-user<session>`)
  that is itself subscribable unless blocked.
- **Spring Security messaging and CSRF.** By default it demands a CSRF token on CONNECT. The proposal
  says CONNECT needs none (`0115:104`), so that default must be disabled deliberately, not
  accidentally.

**Fix:** state these as normative:
- frames before an accepted CONNECT close the socket;
- the server writes frames directly to its session, with no broker relay or shared destination;
- the CONNECT CSRF interceptor is off by design.

Add tests for each: SUBSCRIBE before CONNECT, SUBSCRIBE to a translated or `/topic/` destination,
and a second CONNECT with another institution's token.

### 6. MINOR: the envelope's `institution_id` for changes cannot come from "the authenticated principal" for most writers

**Section:** §4 (`0115:146-147`).

**Evidence:**
- **Writers with no staff principal.** TIMEOUT (the poller, the local fallback, the overdue sweep),
  RESOLVE (the resolver) and the sink's late close (finding 3) run without one. See
  `decision-owner-contract.md:403` and §6.3.
- **What the catalogue's wording assumes.** It speaks of an API key or a staff token
  (`contracts/kafka/schemas/envelope.schema.json:36`).
- **Why it matters.** Isolation (G1) rests entirely on this field.

**Fix:**
- State that `institution_id` is the changed row's `institution_id`, read in the same RLS tenant
  transaction that wrote it.
- Test it per writer: the event of a change made in tenant A carries A.

### 7. MINOR: re-authentication must replace the bound claims, not only the expiry

**Section:** §3 (`0115:112-119`).

"The session's expiry becomes the new token's" leaves the revocation check running on the old
token's `sid` and `token_version`.

**Fix:**
- On success, replace `sid`, `token_version` and `exp` with the new token's. The new token must pass
  `isCurrent`.
- Decide whether a different `sid` for the same user is accepted (another sign-in) or refused.
- Bind the revocation backstop of finding 1 to the new claims.

### 8. MINOR: "the same code as `listAlerts`" is not possible as specified; the equivalence test needs every status and the escalator

**Section:** §4 (`0115:153-161`).

**Evidence:**
- **The read's rule is SQL.** It uses an `EXISTS` on `alert_decisions` for the escalator
  (`backend/analyst/.../alerts/AlertQueries.java:130-137`, `:356-360`).
- **The push filter is different code.** It evaluates payload fields in memory, so it is a second
  implementation. That is fine, but it must be described as such.
- **`queue=all` without `outcome` lists only PENDING and IN_REVIEW.** See
  `contracts/openapi/fraudshield-api.yaml:813-816`. "Is listed by `queue=all`" therefore misses
  decided, escalated, timed-out and overridden events.
- **`escalated_by_user_id` must be read from the right row.** It is the ESCALATE decision's
  `analyst_id` on the parent entry (as the read does), not the current change's actor. Otherwise a
  DECIDED on an escalated entry routes to the decider instead of the escalator.

**Fix:**
- Call it "a second implementation, proven equal by test".
- The test enumerates every role, every user (escalator or not), every status (`outcome` = all
  seven) and escalation chains of depth 2.
- Specify where `escalated_by_user_id` comes from.

**Observation, not a finding against this ADR.** `getAlert` (`AlertQueries.detail`, `:231`) applies
no escalation filter, so the role hiding is a list filter only. The push matches `listAlerts`,
which is the stricter of the two.

### 9. MINOR: the pushed card must equal the read card; `provisional` and the equal-version merge are under-specified

**Section:** §6.1, §7 (`0115:205-217`, `0115:318-331`).

**Evidence:**
- **Fields required on every card.** `AlertSummaryBase` requires `merchant` and `top_reasons`
  (`fraudshield-api.yaml:3363-3366`). A provisional frame must therefore carry `merchant: null` and
  `top_reasons: []`, and the proposal does not say so.
- **`anomaly_score` differs between push and read.** The read nulls it when
  `ml_unavailable_fallback` (`AlertQueries.java:109-110`). The Kafka payload includes it whenever it
  is present (`KafkaMessages.java:467-469`).
- **Some cards are "provisional" forever.** Scores without SHAP (below 0.60, the fallback, before
  V80; ADR 0112) have `top_reasons` empty in the read too, so `provisional: true` invites a re-read
  that returns nothing more.
- **The merge rule cannot complete a card.** The console rule "ignore an event whose version is
  equal" (`0115:318-319`) and "merge by version" (`0115:329`) do not say that the read replaces a
  pushed card at an equal version. If the console treats a read like an event, a provisional v0
  card is never completed.

**Fix:**
- Define `provisional` as "the payload lacked fields the read may have".
- Normatively, a read replaces at an equal version.
- Add a test: for one seeded entry, the fan-out's mapped `AlertSummary` equals `listAlerts`' item,
  field by field, including the null `anomaly_score` under fallback.

### 10. MINOR: the OpenAPI, matrix and AsyncAPI-substitute snippets fail the contract tests, or depart from the conventions, as written

**Section:** §9 (`0115:372-512`).

These are separate from what the proposal says the tests "learn". The marker test and `security: []`
are acknowledged at `0115:423-430`.

- **a. No 401 response.** `x-required-roles` operations must document 401 and 403
  (`contracts/tests/test_openapi.py:84-90`). The snippet has no 401. Either add the exemption to the
  test explicitly, or document why the handshake never answers 401.
- **b. `realtime-overflow` is declared by no response.** Every `ProblemType` must be declared by some
  `components/responses` `x-problem-types` (`test_openapi.py:476-490`, `declared == set(catalogue)`).
  The snippet adds the type but no response that declares it.
- **c. The 426 response breaks the pattern.** It is inline and names no problem type. Every other
  response is a `$ref` to a `components/responses` entry that lists its types.
- **d. The matrix and the document disagree on rules.** The matrix row has
  `rules: [{effect: filter}]` (`0115:475-476`), but the operation declares no
  `x-authorisation-rules`, so `test_document_matches_the_reviewed_matrix_exactly`
  (`contracts/tests/test_authorisation_matrix.py:56-57`; `contracts/src/fraudshield_contracts/authorisation.py:94`)
  fails. Add the rule to the operation, worded like `listAlerts`' (`fraudshield-api.yaml:821-823`).
- **e. The `$ref`s inside `x-realtime` are not checked.** Standard validators do not resolve
  references inside extensions. Add a test that resolves them.
- **f. `actor_user_id` is optional on the frame.** It is not required in `RealtimeAlertEvent`, but it
  is required in the Kafka payload (`0115:500-501`). Make it required and nullable, as the
  description already implies.
- **g. The access forms are listed in the header too.** The `info.description` convention
  (`fraudshield-api.yaml:15-16`) lists the four access forms and must name the fifth.
- **h. `AUTO_DECLINED` is not in `AlertStatus` yet.** `TIMED_OUT` → `AUTO_DECLINED` (`0115:248`)
  depends on the decision-owner contract adding it (`fraudshield-api.yaml:2908-2910` lacks it;
  `decision-owner-contract.md:1139`). Cite the dependency.

### 11. MINOR: "asynchronously after the commit, the HTTP response never waits" needs a mechanism; `KafkaProducer.send` blocks

**Section:** §6.2 (`0115:259-262`).

**Evidence:**
- `send()` blocks for up to `max.block.ms` on a missing metadata fetch or a full buffer. M6's own
  producer sets it from its send timeout (`backend/decision/.../adapter/kafka/KafkaSink.java:82`).
- If the publisher is called on the request thread, a Kafka problem stalls `decideAlert` and
  `undoAlertDecision` past FR-04-04's 2 s.
- If it is called on the TIMEOUT poller or the sink, it stalls them too.
- If it shares the spool's producer, it competes with the decision path's buffer.

**Fix:**
- A dedicated producer instance.
- A bounded hand-off queue drained by its own thread: drop and count on full.
- A small `max.block.ms`.
- Never call `send` on request, poller or sink threads.

### 12. MINOR: unauthenticated sockets can take every slot; the per-user cap is per instance

**Section:** §8 limits (`0115:354-357`).

**Evidence:**
- **Unauthenticated sockets share the cap.** Nothing separates pre-CONNECT sockets from the 2,000
  per instance. At 30 handshakes a minute per IP and 5 s each, about 800 source addresses hold all
  2,000 slots of an instance, and real consoles get 503 everywhere. The damage is bounded by the
  60 s polling fallback, but it is a cheap denial of the feature.
- **"5 sessions per user" is counted per instance**, unless it is shared. With N instances a user
  can hold 5N.

**Fix:**
- Add a separate, small cap on pre-CONNECT sockets per instance, and per IP concurrently.
- Reserve authenticated capacity.
- Either state the per-user cap as per instance, or count it in the shared presence of finding 13.
- Say that "client IP" is the forwarded address resolved as `Requests` already documents
  (`backend/auth/.../web/Requests.java:13-14`).

### 13. MINOR: `analysts_online` presence is ADR 0117's dependency on this channel and is not in this ADR

**Section:** §6.3 (`0115:279-291`).

**Evidence:**
- **The dependency.** ADR 0117 defines `analysts_online` as users holding an authenticated real-time
  session, kept in a per-institution Redis sorted set that each instance refreshes
  (`docs/adr/0117-team-performance-and-figures.md:124`, `:134-139`).
- **What 0115 does.** It only says "what the figures mean … is ADR 0117's". It does not make the
  session lifecycle write, refresh and remove that presence on close, expiry or revocation.
- **Figures are triggered locally only.** The recompute trigger is "a session of that institution
  connected or disconnected" on this instance. A connect on another instance changes the figure
  without triggering a push here.

**Fix:**
- State the presence writes in §2, §3 and §8 (close and revocation remove it).
- Either trigger recomputation on presence changes or accept the 5 s/60 s staleness explicitly.

### 14. MINOR: "subscribe, then re-read" is false while the consumer is not positioned, and for new partitions; a stalled consumer is invisible

**Section:** §5, §7 (`0115:171-182`, `0115:333`).

**Evidence:**
- **The consumer may not be positioned yet.** `seekToEnd` is lazy: the position resolves at the
  first `poll`/`position`. An instance that accepts SUBSCRIBE before every partition's position is
  resolved can skip records committed after the console's read. That breaks "no change can fall
  between the read and the subscription".
- **New partitions lose events.** They are assigned "from its end" after up to 5 minutes, so their
  first minutes of events are skipped silently.
- **The lag is invisible.** With no group, Kafka-side lag tooling sees nothing.
- **A dead consumer thread is not noticed.** Its sessions stay open and quiet.

**Fix:**
- Refuse CONNECT (503) until `position()` is resolved for every assigned partition. Make that the
  readiness signal.
- Assign newly discovered partitions from the **earliest** offset, which is at most 5 minutes of
  data.
- Export `end offset − position` per partition.
- On consumer failure, close local sessions with `service-unavailable`, so consoles reconnect
  elsewhere and re-read.

### 15. MINOR: the resume merge is ambiguous under paging, and "never staler than today" omits phantom cards

**Section:** §1, §7 (`0115:62-66`, `0115:324-331`).

**Evidence:**
- **Paging.** `listAlerts` is paged (Cursor, Limit; `fraudshield-api.yaml:849-850`). "The re-read is
  authoritative for alerts it does not list" would drop valid cards beyond the pages read. It must
  be scoped to the key range and filter the read covered.
- **Phantom and premature cards.** N3 phantom cards and N1 cards whose row is not yet written are
  shown by the push. Today's polling never shows them. Under the exception rule they can persist for
  up to two re-read periods.
- **No acknowledgement without a receipt.** "Only after the subscriptions are acknowledged" assumes
  an acknowledgement that STOMP gives only for SUBSCRIBE with a `receipt` header.

**Fix:**
- Scope read authority to the window read.
- Restate the freshness claim: never staler, but it may briefly show cards that the next re-read
  removes.
- Require `receipt` on SUBSCRIBE, and a RECEIPT only after the session is registered with the
  router.

### 16. MINOR: the ASSUMED values are marked in the text but missing from open question 3, and the ACL sentence contradicts `topics.yaml`

**Sections:** §5, §8, §11, open question 3.

**Evidence:**
- **Missing from open question 3's list:**
  - 30 handshakes a minute per IP;
  - 8 KiB inbound frame;
  - the 5-minute partition refresh;
  - the 1 s revocation re-check (not marked ASSUMED at all);
  - `fetch.max.wait.ms` 50 and the 100/50/850 ms split;
  - the CI test's "stated load", which is not stated anywhere.
- **The ACL sentence.** "Its ACL needs only Read on the four topics" (`0115:177`), but
  `topics.yaml` still lists `websocket-fanout` as a consumer of `fs.notifications.staff` and
  `fs.ml.retrain` (`contracts/kafka/topics.yaml:111-140`), which the ACLs are derived from.

**Fix:**
- Complete the list.
- Either remove `websocket-fanout` from `fs.notifications.staff`'s consumers (it is deliberately left
  out, `0115:576-578`), or say that the ACL covers it unused until P8.

### 17. MINOR: the Java authorisation layer is not addressed

**Section:** §9 "`x-auth-in-band` is new" (`0115:423-430`).

**Evidence:**
- **How ContractPolicy reads access.** It takes access from the matrix (`roles`, `public`,
  `refresh-cookie`; `backend/auth/.../security/ContractPolicy.java:158-175`), so `in-band` must
  become a distinct `Access` kind that is never treated as public elsewhere.
- **What ADR 0070 point 1 requires.** Every contract operation is exercised over HTTP by anonymous,
  the four roles and API keys, and every handler carries `@PreAuthorize`/`@ContractOperation`
  (`docs/adr/0070-…md:27-30`). A WebSocket handler has neither, and the proposal does not say how
  `AuthorisationMatrixTest` treats it.
- **SockJS.** The proposal does not say SockJS is excluded. Deny-by-default keeps `/realtime/**`
  sub-paths closed, which is correct, but should be stated.

**Fix:**
- Specify the `Access` kind.
- State that `AuthorisationMatrixTest` expects the same handshake answer for every caller (101 or
  426, never 401 or 403 on role), and that roles are tested at CONNECT in the STOMP integration test.
- Say "no SockJS; exact path only".

### 18. MINOR: requirement closure is stated honestly; one wording nit

**Section:** "Requirements: closed, and left open" (`0115:560-570`).

- **What is honest.** FR-04-02 and NFR-PERF-05 are correctly said to be only unblocked and partly
  specified. The 1 s is read from the Kafka record, as NFR-PERF-05's own text says
  (`docs/traceability/requirements.yaml:1384`). The unbounded drain is excluded and named.
- **The nit.** FR-04-10's "notified via WebSocket" is met only for connected sessions, which the row
  says. The row should also say that a target-role user who is not connected learns of the
  escalation only at their next read.

## The proposal's open questions: recommendations (not decisions)

1. **Best effort for changes.** Accept, provided the coverage table is complete (finding 3) and the
   publisher cannot block writers (finding 11). The reads are authoritative, and the repair is
   bounded by the re-read. An outbox would buy a guarantee nothing relies on.
2. **`merchant` and `top_reasons` on `alert.created` (an M6 change).** Accept.
   - **Why.** The tolerant reader (`contracts/src/fraudshield_contracts/events.py:75-87`) and the
     backward check accept an optional addition to the closed schema. Reading the card from
     PostgreSQL instead would fail exactly in the N1 window the push exists for.
   - **Conditions.** Finding 9's equality test, and the sink change of finding 3 listed with it as
     M6 changes.
3. **The ASSUMED numbers.** Accept as starting values after these changes:
   - the per-session queue cap is reduced and an instance budget is added (finding 2);
   - the revocation re-check is replaced by push plus a stated backstop (finding 1);
   - the list is completed (finding 16).

   M10 revises them.
4. **Staff names on Kafka.** Ids only. Finding 4 shows that carrying names contradicts ADR 0012 point
   4 and `topics.yaml:5`. Resolve names in the fan-out from a per-institution cache, or in the
   console.
5. **60-second re-read while the socket is healthy.** Keep 60 s for now. It is the only repair for
   every loss case (findings 3, 14, 15) and the bound every freshness claim rests on. Lengthen it
   only after M10 measures the actual loss rate (`fs_realtime_publish_failed_total`, overflow
   closes, lag) and the proposal restates its bounds against the new interval.
