# Review 2: ADR 0115, the real-time channel (Proposed, revised after review 1)

- **Subject:** `docs/adr/0115-the-real-time-channel.md` at `6a45ed2` on the `m8b` worktree (919
  lines). `0115:N` is a line of the proposal; other references are files in the worktree: `6a45ed2` plus uncommitted P2 edits that were
  present while this review was written (among them `AlertDecisions.java`, `DecisionWorkers.java`,
  `DecisionOwner.java` and `PostgresSink.java`), so line numbers in those files are the working tree's.
- **Reviewer:** independent and adversarial; wrote none of the proposal and none of review 1.
- **Method:** reading only. The proposal was checked against `fraudshield-api.yaml`,
  `contracts/tests/*.py`, `contracts/kafka/` (topics, envelope, alert schema, examples), the
  decision-owner contract, the P2 code (`analyst/decisions/AlertDecisions.java`,
  `decision/owner/*`, `PostgresSink`), `backend/auth` (`SessionStateCache`, `SafeRedis`,
  `AuthAutoConfiguration`, `Access`, `ContractPolicy`), the master build prompt and the SRS.
  The YAML snippets were parsed with `python3`/PyYAML (all five parse). No Maven, Docker, tests or
  network were used.

## Verdict: SOUND_WITH_CHANGES

**MAJOR present: NO** (0 MAJOR, 8 MINOR).

Every review-1 MAJOR is answered by a change that holds against the code. The revocation
arithmetic is right (100 pairs at 1.5 s is 67 checks/s and 133 Redis GETs/s; a pair loads from
PostgreSQL about once per 3 s, so "at most 50 a second" holds), the instance budget closes the
process-level hole, the sink's late close is in the writer table with the right line references
(`DecisionOwner.java:705-710`, `:721-727`, RESOLVE at `:106-110`), and Kafka now carries ids only.
The new findings are about how three mechanisms the revision added attach to code that does not
yet offer the hooks they need, and about records the revision left unstated. None changes the
design.

## Review 1's findings: do the answers hold?

| # | Sev. (r1) | Answer holds? | Note |
|---|---|---|---|
| 1 | MAJOR | **Yes, with new finding 1** | Push closure plus a per-pair backstop with stated rates; Redis-down closes the channel. The limiter and the Redis-down detection need hooks `SessionStateCache`/`SafeRedis` do not have, and the push re-check can read a stale local entry (finding 1). The 3.75 s + skew bound itself is correct |
| 2 | MAJOR | Yes | Shared bytes, 32 MiB budget (≤ heap/8, refuses to start otherwise), largest-queue eviction, 256 frames / 256 KiB from a stated rate. The producer side is outside the budget (finding 6) |
| 3 | MAJOR | **Yes in the table; incomplete in the M6-change list** | The row is correct. The TIMEOUT path that also closes entries lives in the decision module and is missing from "Changes to M6's code" (finding 2) |
| 4 | MAJOR | Yes | Ids only on `fs.alerts.changes`; projection test named. Where the names are resolved needs one rule (finding 3) |
| 5 | MINOR | Yes | Normative frame rules, no broker, CSRF off with a test |
| 6 | MINOR | **Yes in the ADR; frozen text not amended** | `envelope.schema.json:36` and `topics.yaml:12-14` still say "from the authenticated principal" (finding 4) |
| 7 | MINOR | Yes | `token_version`/`exp` replaced, new token must pass `isCurrent`, other `sid` refused |
| 8 | MINOR | Yes | "Second implementation, proven equal"; all eight `AlertStatus` values exist at `fraudshield-api.yaml:2931-2933`; escalator from the parent's ESCALATE |
| 9 | MINOR | Yes | Required fields, fallback `anomaly_score`, `provisional`, read-replaces-at-equal |
| 10a–h | MINOR | Yes | 401 as a Reference Object with its own `description` is valid OAS 3.1; `UpgradeRequired` and `RealtimeError` declare the two new types; filter rule matches the matrix; `AUTO_DECLINED` now present |
| 11 | MINOR | Yes | Dedicated producer, bounded hand-off queue, `max.block.ms` 100, never on request/poller/sink threads |
| 12 | MINOR | **Partly** | Pre-CONNECT caps reserve capacity; the per-IP numbers do not fit staff behind one office address (finding 5) |
| 13 | MINOR | Yes | Presence written, refreshed, expired; 30 s recompute. Role filter on the presence write is missing for P8 (finding 7) |
| 14 | MINOR | Yes | `position()` before ready, earliest offset for new partitions, lag gauge, stalled consumer closes sessions |
| 15 | MINOR | Yes | Window-scoped authority, phantom cards stated, RECEIPT after registration |
| 16 | MINOR | Yes | List complete; `fs.notifications.staff` consumer removed. Its `purpose` text is not (finding 8) |
| 17 | MINOR | Yes | `IN_BAND` kind; same handshake answer for every caller. `Access`'s "exactly one kind" check (`Access.java:19-29`) must accept roles + in-band together, which "a distinct kind" implies |
| 18 | MINOR | Yes | FR-04-10 row says non-connected users learn at the next read |

## New findings

### 1. MINOR: the backstop's limiter, the Redis-down closure and the push re-check need hooks the auth code does not have

**Section:** point 3 (`0115:190-214`).

**Evidence:**
- **The limiter cannot see a database load.** `SessionStateCache.isCurrent`
  (`backend/auth/.../session/SessionStateCache.java:113-122`) decides internally, in the private
  `cached`/`load` (`:134-189`), whether it goes to PostgreSQL. A caller can only rate-limit whole
  checks. A limiter of 50 checks/s against the 67 checks/s the backstop needs at 100 pairs
  (`0115:200`) starves a quarter of the checks, and "a pair whose check cannot complete within two
  backstop periods is treated as revoked" (`0115:206-208`) would then close healthy sessions in
  steady state.
- **"An instance that sees Redis as down" has no signal.** `SafeRedis.get` returns
  `Optional.empty()` both for a missing key and for an unreachable Redis (`SafeRedis.java:13-17`,
  `:41-51`); nothing public reports availability. `BackgroundSubscriber.subscribed()` reports only
  the pub/sub link.
- **The push re-check can read a stale local entry.** The new realtime listener and
  `SessionStateCache.onMessage` are separate listeners on one `RedisMessageListenerContainer`
  (`AuthAutoConfiguration.java:575-588`), with no ordering between them. If the realtime re-check
  runs before `onMessage` removes the local entry, it is answered from the 1 s in-process tier and
  the session stays open until the backstop. D-27's 5 s still holds (the backstop bound covers it),
  but "the delay is the announcement's, well under a second" (`0115:193-194`) does not.

**Fix:**
- Name the `SessionStateCache` change: a load permit passed into the check (or an
  `isCurrent(claims, LoadGate)` overload), so only database loads are limited.
- Name the Redis-availability signal (for example a failure counter or ping in `SafeRedis`) and the
  threshold that closes the channel.
- Make the realtime handler call `SessionStateCache.onMessage` first (one listener that invalidates,
  then re-checks), or re-check bypassing the local tier.

### 2. MINOR: publishing a full `AlertSummary` from the TIMEOUT path and the sink crosses a module boundary, and the TIMEOUT path is missing from the M6-change list

**Section:** point 6.2 table and point 9 "Changes to M6's code" (`0115:383-395`, `0115:776-781`).

**Evidence:**
- The payload's `alert` is "the entry's `AlertSummary` as committed" (`0115:367`), with
  `escalation_target_role` and `escalated_by_user_id` "read in the writing transaction" (`0115:243`).
- The only builder of an `AlertSummary` is `AlertQueries.summary` in the **analyst** module
  (`analyst/alerts/AlertQueries.java:226-236`). `analyst` depends on `decision`
  (`backend/analyst/pom.xml:31`); `decision` does not depend on `analyst`
  (`backend/decision/pom.xml:20-74`).
- Two of the writers are in `decision`: the TIMEOUT request (`OwnerHoldTimeouts.java:33-60`, which
  runs `DecisionOwner.submit`, whose `CLOSE_ON_TIMEOUT` returns only `id, created_at`,
  `DecisionOwner.java:95-104`) and the sink's late close (`PostgresSink.java:433-449`, calling a
  static `DecisionOwner`, `:116`). The resolver is in `analyst` (`DecisionWorkers.java:336`).
- "Changes to M6's code" (`0115:776-781`) lists the sink but not `DecisionOwner`/
  `OwnerHoldTimeouts` (collect the closed entries and their versions, publish after commit).

**Fix:** state the mechanism: a port in `decision` (for example `AlertChangePublisher` taking
`(institution, entry id, version, change)`), implemented in `analyst` with `AlertQueries`, invoked
after commit; and whether the summary is read inside the writing transaction (longer lock hold in
the TIMEOUT and sink transactions, which hold the owner row) or re-read after commit (simpler; the
event then carries the version it read, which G2 tolerates). Add `DecisionOwner`/
`OwnerHoldTimeouts` to the M6-change list.

### 3. MINOR: name resolution can put a database transaction on the single consumer thread

**Section:** points 5 and 6.2 (`0115:291-299`, `0115:374-381`), point 11 (`0115:811`).

**Evidence:** routing step 2 "resolves staff names" before serialising, on the consumer that must
"do nothing else" for the 100 ms leg. A cache miss is a tenant transaction (`0115:376`) with the
runner's 5 s statement timeout; one slow miss stalls every institution's events, and a 30 s stall
closes every local session (`0115:286-289`).

**Fix:** resolve misses off the poll thread (a small executor with a short timeout; the record is
delivered when resolved or dropped and counted), or prefetch per institution, and say which.

### 4. MINOR: the frozen contract still says `institution_id` comes from the principal

**Section:** point 4 (`0115:223-228`).

**Evidence:** `contracts/kafka/schemas/envelope.schema.json:36` ("Set by the producing FraudShield
service from the authenticated principal (API key or staff token)") and `topics.yaml:12-14`
contradict the rule the ADR adopts for `alert.changed`.

**Fix:** list both description changes in point 9: "from the authenticated principal, or, for an
event about a stored row, from that row's `institution_id` read in the writing tenant transaction".

### 5. MINOR: the per-IP admission limits fit attackers, not an office of analysts behind one address

**Section:** point 8 limits (`0115:547-557`).

**Evidence:**
- Staff are expected to sit behind office addresses (`office_ip_allowlist`, `V2:107`). With 30
  handshakes a minute and 5 pending CONNECTs per IP, the mass reconnect the ADR itself causes (the
  Redis-down closure, `0115:209-214`; a consumer stall; a deploy, `0115:565-567`) admits 30 of 200
  analysts a minute; the others receive 429 and back off up to 30 s.
- "The client retries, reaching another instance" (`0115:549`) assumes a load balancer that spreads
  retries; it is not stated as a deployment requirement.

The cost is bounded by the 60 s re-read, so this degrades the feature, it does not break reads.

**Fix:** key the handshake limit on the forwarded address **and** exempt or raise it for addresses
in the institution's allowlist (or count per user after CONNECT instead); state the load-balancer
requirement; add a reconnect-storm case to the CI test.

### 6. MINOR: the producer side has no memory bound in the budget

**Section:** points 6.2 and 8 (`0115:406-414`, `0115:528-541`).

**Evidence:** the hand-off queue (10,000 full `AlertSummary` payloads, about 10 to 20 MiB at the
ADR's 1 KiB+ per frame) and the dedicated producer's `buffer.memory` (Kafka default 32 MiB) are a
second, unbudgeted allocation on the same heap as the 32 MiB outbound budget.

**Fix:** bound the hand-off queue in bytes as well as events, state `buffer.memory`, and include
both in the "at most one eighth of the heap" start-up check.

### 7. MINOR: the presence write is not role-filtered, so P8's ADMIN sessions would count as analysts online

**Section:** point 2 "Presence" (`0115:134-138`), point 10 (`0115:785-787`).

**Evidence:** "On an accepted CONNECT the instance adds the user"; P8 adds ADMIN to the channel;
ADR 0117 defines `analysts_online` over ANALYST, SENIOR_ANALYST and RISK_OFFICER only
(`0117:167`).

**Fix:** write presence only for those three roles, and list it among the rules P8 must keep.

### 8. MINOR: the new topic extends the master prompt's catalogue and breaks two Kafka tests the ADR does not name; the staff topic's purpose still says WebSocket

**Section:** point 9 (`0115:741-760`).

**Evidence:**
- The master prompt's C.3 lists exactly thirteen topics
  (`docs/prompts/FraudShield_Master_Build_Prompt.md:283`); `topics.yaml:1` cites it.
  `test_catalogue_lists_exactly_the_c3_topics_with_key_retention_and_dlq` asserts
  `listed == C3_TOPICS` (`contracts/tests/test_events.py:43-57`, `:66-68`) and
  `test_topic_examples_validate` needs `contracts/kafka/examples/fs.alerts.changes.json`
  (`test_events.py:61-62`, `:136`). The ADR lists the OpenAPI tests it changes, and the baseline,
  but not these.
- `fs.notifications.staff`'s purpose still reads "Staff email, push and WebSocket notifications"
  (`topics.yaml:114`) after its WebSocket consumer is removed.
- The catalogue defaults give every topic a `.dlq`; the fan-out "never dead-letters"
  (`0115:292-293`), so the DLQ is created and unused. Harmless; say so.

**Fix:** record the C.3 extension as a deviation from the master prompt (the owner's decision of
2026-10-22 on ids-only changes implies acceptance of the topic, but the record should say so);
update `C3_TOPICS`, add the example, amend the staff topic's purpose.

## Owner questions (with recommendations)

1. **Accept the fourteenth topic as a recorded deviation from the master prompt's C.3?** Recommend
   **yes**: `fs.decisions.final` cannot carry entry changes (`0115:35-37`), and the reads stay
   authoritative.
2. **`merchant` and `top_reasons` on `alert.created`** (author's choice). Recommend **accept**:
   optional additions are backward compatible under ADR 0012 point 5, and `merchant_name` is already
   on `fs.transactions.raw`.
3. **Close the whole channel while Redis is down** (author's choice). Recommend **accept**: it fails
   closed, the console polls anyway, and the alternative is idle sockets competing with decisions
   for the pool.
4. **The ASSUMED numbers** (author's choice). Recommend **accept as starting values**, except the
   per-IP handshake limits, which should be re-derived for office NAT before release (finding 5).
