# 0115: The real-time channel

- **Status:** Proposed (M8B, 2026-10-22), revised after review 1
  (`docs/reviews/M8B/realtime-channel-proposal-review-1.md`) and review 2
  (`docs/reviews/M8B/realtime-channel-proposal-review-2.md`), for re-review before any code. It
  changes the frozen contract (`contracts/openapi/fraudshield-api.yaml`,
  `contracts/openapi/authorisation-matrix.yaml`, `contracts/kafka/topics.yaml`,
  `contracts/kafka/schemas/envelope.schema.json`'s `institution_id` description, one new and one
  changed Kafka payload schema), it adds a fourteenth topic to the master prompt's C.3 catalogue (a
  recorded deviation, point 9), and it is a concurrency contract, so under
  `docs/parallel/M8B_scope.md` §5 it is reviewed on its own before P2's code relies on it.
- **Date:** 2026-10-22
- **Requirements affected:** FR-04-02, UX-DASH-01, NFR-PERF-05, FR-04-10, MOB-DEV-03, FR-06-04
  (P8 reuses the channel), D-27
- **ADRs referenced:** 0012, 0014, 0064, 0070, 0071, 0081, 0110, 0112, 0114, 0116, 0117
- **Sources:** M8B scope P2-f and §7 point 3; `M8B_updates.md` item 2; SRS FR-04-02, FR-04-10, the
  latency table's row 13 ("Kafka consumer -> WebSocket push to all connected analysts: < 50ms") and
  the network table (3G: "WebSocket with 60s heartbeat"; 2G: "no real-time WebSocket; 60-second
  polling fallback"); the master build prompt's stack row ("WebSocket (STOMP) push"); the register's
  NFR-PERF-05, UX-DASH-01 and MOB-DEV-03; `topics.yaml` (the `websocket-fanout` consumer);
  `docs/architecture/decision-fact-ordering.md` §3 (N1 to N3); the decision-owner contract §3, §5,
  §6.2, §6.3, §9, §10 and §11; `SessionStateCache` (`backend/auth/.../session/`).

## Context

FR-04-02 asks for a "real-time alert feed via WebSocket" in which a new alert reaches "all connected
dashboards within 1 second". UX-DASH-01 asks for real-time header figures. NFR-PERF-05 measures "< 1
second from Kafka event to browser DOM update". Nothing in `contracts/` describes a real-time
channel, so until one exists the console re-reads every 60 s and FR-04-02 and UX-DASH-01 are
BLOCKED.

What exists today:
- **Raised alerts are on Kafka.** `KafkaSink` publishes each `AlertRaised` to `fs.alerts.high`,
  `.medium` or `.anomaly` (`alert.created`, key `account_token`), and `topics.yaml` names
  `websocket-fanout` as a consumer of all three.
- **Nothing else about an alert is on Kafka.** Deciding, undoing, escalating, overriding, timing out,
  resolving, and the sink closing a late entry change `alert_queue_entries` in PostgreSQL only
  (decision-owner contract §6.2 and §9). `fs.decisions.final` carries the transaction's state, which
  is not the queue entry's: a decision that is still PENDING_COMMIT, an undo and an escalation never
  appear there.
- **Kafka and PostgreSQL are not ordered against each other** (decision-fact-ordering N1). An
  `alert.created` can reach Kafka before, or long after, its row reaches PostgreSQL. If the sink
  skipped the record as a duplicate decision, the row never appears (N3).
- **Staff authenticate with a 15-minute RS256 access token in the `Authorization` header.**
  `SessionStateCache` decides whether a token's session is current. It writes every change through
  to Redis and announces it on the pub/sub channel `fs:auth:sessions` (messages `tv:<user id>` and
  `sid:<session id>`); its worst-case staleness, if both are lost, is `worstCaseStaleness()` (2 s by
  default) plus clock skew (ADR 0070 point 2, ADR 0071 §6). A browser cannot set headers on a
  WebSocket handshake.

## Decision

### 1. The protocol: STOMP 1.2 over WebSocket

**Chosen: one WebSocket endpoint, `GET /api/v1/realtime`, speaking STOMP 1.2.**
- **The sources name it.** The SRS names WebSocket in FR-04-02, FR-04-10, UX-DASH-01 and MOB-DEV-03,
  and the master prompt names "WebSocket (STOMP) push". The master prompt governs.
- **It can re-authenticate without reconnecting** (point 3). Server-sent events carry their
  credentials in the request, so a new token means a new request, and every event in the gap is
  lost.
- **STOMP supplies what a raw socket would need us to invent:** a CONNECT frame that carries headers
  (the access token), named subscriptions, negotiated heart-beats, receipts and ERROR frames.

**Rejected:**
- **Server-sent events.** They are simpler, and plain HTTP. They lose the in-band
  re-authentication, and the SRS and the master prompt both name WebSocket.
- **A raw WebSocket with a home-made framing.** It would be a protocol only FraudShield speaks.
- **SockJS.** It adds HTTP fallback transports under `/realtime/**`, each of which would need the
  same rules. The endpoint is the exact path only, and every sub-path stays closed by the
  deny-by-default policy (ADR 0070 point 1).
- **An AsyncAPI document.** The repository has no AsyncAPI tooling, and the console generates its
  types from the OpenAPI document. The frames are therefore OpenAPI component schemas (point 9), and
  the channel's rules are in its operation's description.

**What the channel is for.** It is a latency improvement, not the source of truth.
- **The source of truth stays the reads:** `listAlerts`, `getAlert` and `getDashboardStats`.
- **The console keeps its 60-second re-read while the socket is open,** until M10 measures the
  channel's loss (decided by the owner, 2026-10-22). It is the only repair for every loss case
  (points 6 and 7) and the bound every freshness claim here rests on. Lengthening it needs M10's
  measurements (`fs_realtime_publish_failed_total`, `fs_realtime_publish_dropped_total`, overflow
  closes, consumer lag) and a restatement of these bounds against the new interval.
- **What the console's freshness becomes.** Never staler than today's polling, but the push can
  briefly show cards that the next re-read removes: a phantom card for a duplicate decision that
  never reaches PostgreSQL (N3), or a card whose row is not yet written (N1). Today's polling never
  shows those. Under point 7's resume rule they last at most two re-read periods.
- **This is how the contract can stay small.** Every loss case in point 6 is repaired by the next
  re-read. The channel promises neither completeness nor replay.

### 2. Who may connect, and the handshake

**The handshake carries no credentials.**
- `GET /api/v1/realtime`, the exact path, with `Upgrade: websocket` and the subprotocol
  `v12.stomp`.
- **Origin check.** The `Origin` header must be one of `fraudshield.realtime.allowed-origins`, or
  the server answers 403 before upgrading. Its default is the API's own origin. That default is
  ASSUMED, because the contract's server URL is relative (`/api/v1`) and no CORS configuration
  exists, which suggests the console is served from the API's origin.
- **A request that is not a valid WebSocket upgrade** answers 426 `upgrade-required` with a problem
  document. The framework's own answers (400 for a missing `Upgrade` header, 426 for an unsupported
  WebSocket version) are replaced, so every refusal is a problem document from the catalogue.
- **Cookies are ignored on this endpoint.** The browser will send `fs_csrf`, whose path is `/`; it
  will not send `fs_refresh`, whose path is `/api/v1/auth/refresh`. The server reads neither.
- **The handshake answers every caller alike.** It never answers 401, or 403 on role, because it
  authenticates no one. Roles are checked at CONNECT.

**Authentication is the STOMP CONNECT frame's `Authorization: Bearer <access token>` header.**
- **The token is validated exactly as on HTTP:** RS256, the same validators and clock-skew
  allowance, and `SessionStateCache.isCurrent` for `token_version` and `sid`.
- **Roles:** ANALYST, SENIOR_ANALYST and RISK_OFFICER, the roles of `listAlerts` and
  `getDashboardStats`. ADMIN is not admitted by this proposal: P8 adds it with its own destination
  (point 10).
- **A CONNECT must arrive within 5 s of the upgrade,** or the server closes the socket. The 5 s is
  ASSUMED. It bounds how long an unauthenticated socket can hold a slot.
- **The session is bound, at CONNECT, to the token's `sub`, `institution_id`, `role`, `sid`,
  `token_version` and `exp`.** `sub`, `institution_id`, `role` and `sid` never change for the life
  of the socket; `token_version` and `exp` change only by re-authentication (point 3).

**Normative rules for the frames, not left to the framework:**
- **Before an accepted CONNECT, any other frame** (SUBSCRIBE, SEND, UNSUBSCRIBE, a STOMP frame of an
  unknown command) receives `ERROR` (`unauthorized`) and the socket is closed.
- **A second CONNECT on an authenticated socket** receives `ERROR` (`forbidden`) and the socket is
  closed, whatever token it carries.
- **The server writes each frame directly to its session.** There is no broker relay, no simple
  broker, and no shared destination. Spring's user-destination translation is not used, so no
  per-session destination such as `/queue/alerts-user<session>` exists to be subscribed to.
- **Only the destinations of point 6 may be subscribed, and only `/app/session.reauth` may be sent
  to.** Any other SUBSCRIBE (including any `/topic/**` or translated destination) or SEND receives
  `ERROR` (`forbidden`) and the socket is closed.
- **Spring Security's CSRF check on CONNECT is turned off by design,** for the reasons below. It is
  configured off explicitly, with a comment citing this ADR, and a test asserts that a CONNECT with
  no CSRF token and a valid bearer token is accepted.
- **Tests** (the STOMP integration test): SUBSCRIBE before CONNECT is refused and closes; SUBSCRIBE
  to `/topic/alerts` and to a translated user destination is refused and closes; a second CONNECT
  carrying another institution's token is refused and closes, and that socket never receives the
  other institution's events.

**Presence, for ADR 0117's `analysts_online`.** On an accepted CONNECT **by a session whose role is
ANALYST, SENIOR_ANALYST or RISK_OFFICER** the instance adds the user to its institution's Redis
presence set (ADR 0117 point 4), and refreshes it every 10 s while the session lives. Sessions of
any other role (ADMIN, once P8 admits it, point 10) are never written to the presence set, because
ADR 0117 counts only those three roles as analysts online. When the user's last local session ends
(close, expiry, revocation, overflow or shutdown), the instance stops refreshing it, and the entry
expires within 30 s (both timings ASSUMED, ADR 0117). A user is counted while any instance
refreshes them. A unit test of the presence writer asserts that a session bound to any other role
leaves the presence set unchanged, so the rule holds before P8 admits ADMIN.

**Rejected ways to carry the token:**
- **The access token in the query string.** It would land in access logs and proxies, and the
  contract says staff use the bearer header.
- **A short-lived single-use ticket from a new endpoint.** It would add an operation, and a shared
  store of tickets, for a credential that the CONNECT frame already carries safely. A stateless
  signed ticket is not single-use.
- **An access-token cookie.** None exists, and adding one would change D-27's model and bring CSRF
  into play.

**Why CSRF does not apply.**
- **The credential is never ambient.** It is a frame header that only the console's script can
  write, so a cross-site page that opens a socket cannot authenticate it.
- **So cross-site WebSocket hijacking has no credential to borrow.** The Origin check is kept as a
  second layer.
- **The CONNECT frame, and the SEND frame of point 3, need no `X-CSRF-Token`.**
- **The refresh that obtains a new token is unchanged:** `POST /auth/refresh`, with its cookie and
  its CSRF header (D-27).

### 3. Expiry, re-authentication and revocation

**The access token expires after 15 minutes. The socket outlives it only by re-authenticating in
band.**
- **The re-auth frame.** The console sends `SEND` to `/app/session.reauth` with
  `Authorization: Bearer <new token>` and a `receipt` header. The server validates it as in point 2.
- **On success:** the session's `token_version` and `exp` are **replaced** by the new token's, and
  the new token must itself pass `isCurrent`. The revocation checks below then run on the new
  claims. The server answers with a `RECEIPT`. The socket and its subscriptions are untouched, so
  **no event is dropped by a re-authentication.**
- **A different `sid` is refused.** A token with the same `sub` but another `sid` comes from another
  sign-in, which is another session. The server sends `ERROR` (`forbidden`, detail "session
  changed") and closes; the console reconnects with the new token and re-reads (point 7).
- **On any other failure:** the server sends `ERROR` and closes, as STOMP 1.2 requires. A failure is
  an invalid token, or a different `sub`, `institution_id` or `role`. The `ERROR` frame's body is a
  Problem (point 9): `unauthorized`, or `forbidden` for a changed identity.
- **When the console re-authenticates.** It refreshes and re-authenticates at the latest 60 s before
  `exp`. That margin is ASSUMED, chosen to cover a slow refresh on 3G (SRS network table: 100 to
  200 ms of round trip).

**When the session's token expires without re-authentication:**
- the server stops sending at once;
- it sends `ERROR` (`unauthorized`, detail "access token expired") and closes;
- nothing is queued for the session.

The console then refreshes, reconnects and resumes (point 7). Events in the gap are not replayed;
the re-read repairs them.

**Revocation.**
- **What ends a session:** sign-out, sign-out everywhere, a role change, a lock by an administrator,
  deactivation and a password change. Each ends the session's `sid` or increments `token_version`
  (ADR 0070 point 2).
- **Closure is driven by push.** The channel adds **no second listener** on `fs:auth:sessions`.
  `SessionStateCache.onMessage` stays the only one, and gains a hook: after it has removed the local
  entry that a `tv:<user>` or `sid:<sid>` message names, it calls the registered session-change
  listeners with that user or session id (a `SessionStateCache.addChangeListener` method, a change
  to `backend/auth`, point 9). The real-time module registers one, which re-checks the matching
  local sessions and closes those no longer current with `ERROR` (`unauthorized`, detail "session
  ended"). Because the call comes after the invalidation, the re-check can never be answered from
  the stale 1 s in-process entry: it reads Redis, or PostgreSQL. The listener only hands the ids to
  the real-time module's own thread, so it never blocks `onMessage`. The delay is the
  announcement's, well under a second in normal operation. (Two listeners on one
  `RedisMessageListenerContainer`, `AuthAutoConfiguration.java:575-588`, have no order between
  them, which is why the first revision's separate listener could read the stale local entry.)
- **A polling backstop covers a lost announcement.** The instance re-checks each **distinct
  (`user`, `sid`) pair** it holds, not each socket, every 1.5 s with ±0.25 s of jitter (both
  ASSUMED). The bound when the announcement is lost is `worstCaseStaleness()` (2 s) + clock skew +
  the backstop period (at most 1.75 s) + the close, about 3.75 s plus skew, inside D-27's 5 s.
- **What the backstop costs, at the instance's cap of 100 distinct pairs (point 8):**
  - about 67 checks a second; each misses the 1 s in-process entry and reads two Redis keys, so
    about 133 Redis GETs a second;
  - a database load (one tenant transaction, two queries) only when the shared Redis value has
    passed its 2 s deadline. Redis is shared, so across the deployment that is at most one load per
    pair per 2 s: at most 50 tenant transactions a second for one instance's 100 pairs;
  - a limiter caps the backstop's **database loads**, not its checks, at 50 a second per instance
    (ASSUMED, matching the cap). A caller of today's `SessionStateCache.isCurrent` cannot see
    whether a check goes to PostgreSQL (the decision is made inside the private `cached`/`load`,
    `SessionStateCache.java:113-189`), and limiting whole checks to 50 a second against the 67 the
    backstop needs would starve a quarter of them in steady state. So `SessionStateCache` gains an
    overload, `isCurrent(claims, LoadGate gate)` (a change to `backend/auth`, point 9): it consults
    `gate.tryAcquire()` only immediately before `load`, and when the gate refuses it answers
    **unknown** instead of reading PostgreSQL. The overload returns one of `CURRENT`,
    `NOT_CURRENT` and `UNKNOWN`; the existing `isCurrent(claims)` is unchanged for HTTP. Checks
    answered from the in-process tier or from Redis never touch the gate, so in steady state (at
    most 50 loads a second for 100 pairs, above) the gate never refuses;
  - an `UNKNOWN` pair is checked again at the next backstop period. A pair whose check has not
    returned `CURRENT` or `NOT_CURRENT` within two backstop periods is treated as revoked: its
    sessions are closed with `ERROR` (`service-unavailable`), and the console reconnects and
    re-reads. The channel fails closed, never open.
- **While Redis is unavailable, the channel is closed** (the author's choice on review 2's
  recommendation; the owner may overrule). `SessionStateCache` would otherwise send every check to
  PostgreSQL, about one tenant transaction per pair per check, competing with the decision path for
  the same pool. Instead, an instance that sees Redis as down closes every session with `ERROR`
  (`service-unavailable`) and answers new handshakes 503 with `Retry-After` until Redis is back. The
  console falls back to its 60-second re-read, which it runs anyway. HTTP authentication is
  unchanged: it already reads PostgreSQL when Redis is down.
- **How an instance "sees Redis as down".** Today nothing reports it: `SafeRedis.get` answers
  `Optional.empty()` both for a missing key and for an unreachable Redis (`SafeRedis.java:13-17`,
  `:41-51`), and `BackgroundSubscriber.subscribed()` reports only the pub/sub link. So `SafeRedis`
  gains an availability signal (a change to `backend/auth`, point 9): it counts **consecutive
  failed operations** (an exception caught, never a missing key) and resets the count on any
  success, and exposes `available()`, false once the count reaches 3 (ASSUMED). The real-time
  module also sends a `PING` through `SafeRedis` every 1 s (ASSUMED), so the signal moves even when
  no session is being checked. The channel closes when `available()` is false, and reopens after
  two consecutive successful `PING`s 1 s apart (ASSUMED). The window before detection (at most
  about 3 s of failed operations) is covered by the load gate above: checks that fall through to
  PostgreSQL in that window are capped at 50 a second per instance.
- **A role change always ends the session,** because it increments `token_version`. Role filtering
  (point 4) therefore never runs on a stale role.

### 4. Tenant isolation and role filtering

**Every event belongs to one institution.**
- **Where its institution comes from:** its Kafka envelope's `institution_id`. It is never read
  from the payload.
- **How the envelope's `institution_id` is set.** For `alert.created`, M6 sets it as today. For
  `alert.changed`, most writers have no staff principal (the TIMEOUT poller, its local fallback and
  the overdue sweep, the resolver, and the sink closing a late entry; decision-owner contract §6.2,
  §6.3). So the publisher sets it to **the changed row's `institution_id`, read in the same
  row-level-security tenant transaction that wrote the row,** for every writer. The writer passes it
  to the publisher with the entry's id (point 6.2), and the publisher's re-read runs in that
  institution's tenant transaction, so row-level security refuses a row of any other institution. It
  is never taken from a principal or a request. The frozen descriptions that say otherwise are
  amended (point 9).
- **A test per writer** asserts that the event of a change made in institution A carries A, and that
  a session of institution B never receives it.
- **Who receives it:** only sessions bound to that `institution_id`.
- **Destinations carry no institution.** The server writes each session's own copy (point 2), so a
  client cannot name another institution, and nothing is broadcast on a shared topic.

**An event about an alert is delivered only to sessions that `listAlerts` would show the entry to:**
- **an entry with no `escalated_from_alert_id`:** every connected role;
- **an escalated entry:** sessions whose role ranks at or above its `escalation_target_role`, plus
  the user who escalated it. This is the same rule as `listAlerts`' `queue=escalated` (ranks
  ANALYST < SENIOR_ANALYST < RISK_OFFICER, E.8).
- **Who "the user who escalated it" is.** `escalated_by_user_id` is the `analyst_id` of the ESCALATE
  decision on the **parent** entry (`escalated_from_alert_id`), as `listAlerts` reads it. It is not
  the actor of the current change: a DECIDED on an escalated entry routes to the escalator, not to
  the decider. The publisher reads it, with the rest of the summary, in its re-read after the
  commit (point 6.2), and puts it in the Kafka payload.

**A second implementation, proven equal by test.** `listAlerts` applies its rule in SQL, with an
`EXISTS` on `alert_decisions` for the escalator. The push evaluates payload fields in memory. It is
therefore a second implementation of the same rule, and a test proves them equal:
- for every seeded entry, every role, and every user (the escalator and others), "would be pushed"
  equals "is listed by `queue=all` with `outcome` set to every `AlertStatus`" (all eight, including
  AUTO_DECLINED; without `outcome`, `queue=all` lists only open entries);
- the seed includes escalation chains of depth 2.

**`getAlert` applies no escalation filter** (`AlertQueries.detail`), so the role hiding is a list
rule only. The push follows `listAlerts`, the stricter of the two.

If the owner later restricts an ANALYST to their own assigned alerts (UX-ROLE-AN-01; ADR 0116 point
2), this filter changes with that rule.

### 5. Fan-out across instances

**Every instance must see every event,** because a console may be connected to any instance.

**Each instance runs one Kafka consumer, with manual assignment and no consumer group.**
- **Topics.** It is `assign()`ed every partition of `fs.alerts.high`, `fs.alerts.medium`,
  `fs.alerts.anomaly` and `fs.alerts.changes` (point 6). It seeks to the end at start, and commits
  no offsets.
- **Why no group.** One group for all instances would split the partitions between them, so each
  instance would see only part of the events. A group per instance would leave a group behind for
  every restart. Manual assignment gives "every instance reads everything" without either.
- **Ready only when positioned.** `seekToEnd` is lazy. The instance calls `position()` for every
  assigned partition before it reports ready, and until then it answers handshakes 503 with
  `Retry-After`. Otherwise an instance could accept a SUBSCRIBE, and the console re-read, before
  the consumer's position was fixed, and skip records committed after the read.
- **Its ACL.** `topics.yaml` lists `websocket-fanout` as a consumer of the three alert topics, of
  `fs.ml.retrain` and of `fs.notifications.staff`, and the ACLs are derived from it. This ADR
  removes `websocket-fanout` from `fs.notifications.staff`'s consumers (point 9), since the channel
  deliberately does not consume it. Its Read on `fs.ml.retrain` stays, unused until P8 (point 10).
  It gains Read on `fs.alerts.changes`.
- **A restarted instance starts at the end of each partition.** Its consoles reconnect and re-read
  (point 7), so it never needs history.
- **New partitions.** It re-reads partition metadata every 5 minutes (ASSUMED interval) and
  assigns any new partition from its **earliest** offset, which is at most 5 minutes of data. From
  the end, the first minutes of a new partition's events would be skipped silently.
- **Lag is exported** per partition as `fs_realtime_consumer_lag` (end offset minus position),
  because with no group, Kafka's own lag tooling sees nothing.
- **A failed consumer closes its sessions.** If the consumer thread dies or cannot poll for 30 s
  (ASSUMED), the instance closes its local sessions with `ERROR` (`service-unavailable`) and stops
  reporting ready, so consoles reconnect to another instance and re-read. Its sessions are never left
  open and silent.

**How it routes a record.** For each record, the consumer:
1. validates the envelope and payload against their schemas. An invalid record is dropped and
   counted (`fs_realtime_invalid_total`), and never dead-lettered: this reader owns nothing;
2. resolves staff names from the cache (point 6.2) and serialises the frame **once** per record: the
   frame is the same for every recipient. A record whose name is not in the cache is handed to the
   name resolver (point 6.2) and leaves the poll thread; it re-enters at step 3 when resolved. The
   poll thread never opens a database transaction;
3. finds the local sessions of the envelope's institution;
4. applies point 4's filter;
5. offers the shared, immutable frame bytes to each matching session's outbound queue, without
   blocking (point 8).

**There is no local shortcut.** The instance that made a change receives it back from Kafka like
every other instance. There is one path, at the cost of one Kafka round trip.

### 6. What is pushed, and from where

**Two destinations.**

| Destination | Frame body | Source |
|---|---|---|
| `/user/queue/alerts` | `RealtimeAlertEvent`: the alert's full `AlertSummary` after the change, and the change's kind | `fs.alerts.*` (raised) and `fs.alerts.changes` (every later change) |
| `/user/queue/figures` | `RealtimeFiguresEvent`: a `DashboardStats` snapshot for the institution | computed by the instance (point 6.3) |

The names are STOMP destinations the server writes to directly (point 2); the `/user/` prefix is
kept only as a name and implies no broker translation.

#### 6.1 Raised alerts: from `fs.alerts.*`

- **What is pushed.** The fan-out maps the `alert.created` payload to an `AlertSummary`: version 0,
  status PENDING, `escalated_from_alert_id` null, `assigned_analyst` null (ADR 0116), and
  `masked_account` null (ADR 0110 point 3: always null today).
- **Every field `AlertSummaryBase` requires is present.** `merchant` and `top_reasons` are required
  on every card. When the payload lacks them, the frame carries `merchant: null` and
  `top_reasons: []`.
- **Two card fields are missing from the payload today:** `merchant` and `top_reasons`. Adding them
  is the author's choice on the review's recommendation; the owner may overrule.
  - **The change.** `alert.schema.json` gains both as optional properties: `merchant`, and
    `top_reasons` as up to three `{feature, value, shap}`. The tolerant reader
    (`contracts/src/fraudshield_contracts/events.py`) and the backward-compatibility check accept an
    added optional property.
  - **Who sets them.** `DecisionService` sets them on `AlertRaised`. The decision path already
    carries the scorer's SHAP to the sink (ADR 0112 point 2), so the values exist at decision time.
    The fan-out derives `direction` and `template_key` exactly as the reads do.
  - **Why not read the card from PostgreSQL instead.** That would fail exactly in N1's window, the
    one the push exists for: the row may not be written yet.
  - **This is a change to M6's code,** listed with the others in point 9.
- **`anomaly_score` follows the read's rule.** The read returns null when the decision was made by
  the rule-based fallback (`ml_unavailable_fallback`); the Kafka payload today includes the score
  whenever it is present. M6's `AlertRaised` therefore omits `anomaly_score` under the fallback
  (an M6 change, point 9), so the pushed card equals the read card.
- **`provisional`** means **"the payload lacked fields the read may have"**: `true` exactly when the
  payload has no `merchant` or no `top_reasons` property (a spool record written before the change).
  A card whose `top_reasons` is genuinely empty (a score below 0.60, the rule-based fallback, or a
  score from before V80; ADR 0112) carries the payload's empty list and `provisional: false`,
  because a re-read would return nothing more.
- **The equality test.** For one seeded entry of each kind (with SHAP, without SHAP, under the
  fallback), the fan-out's mapped `AlertSummary` equals `listAlerts`' item field by field, including
  the null `anomaly_score` under the fallback.
- **N1's consequence, stated.** A raised alert can be pushed before its row is in PostgreSQL.
  `getAlert` may then answer 404 for a while, and the console treats that 404 as "not yet",
  retrying when the drawer is opened.
- **N3's consequence, stated.** A duplicate decision's alert can be pushed and never reach
  PostgreSQL. The next re-read, which is authoritative for the window it covers, removes it.

#### 6.2 Every later change: a new topic, `fs.alerts.changes`

**The topic.**
- **Its catalogue entry:** `event_type` `alert.changed`, key `transaction_id`, so every entry of one
  transaction (an escalation chain) shares a partition. That it has 12 partitions and P1D retention
  is ASSUMED, sized like the alert topics; retention is short because nothing replays it.
- **Its producer and consumer:** `fraudshield-api` produces it (the analyst operations, the TIMEOUT
  and RESOLVE paths, and the sink's late closes all run there, as the alert topics' producer does),
  and `websocket-fanout` reads it.

**The payload carries staff ids, never names (decided by the owner, 2026-10-22).** ADR 0012 point 4
is "No personal data or credentials in events", with no restriction to customers, and `topics.yaml`
says "No message carries raw personal data". The payload is:
- `alert`: the entry's `AlertSummary` as read by the publisher after the commit (below), with
  `assigned_analyst` (a `StaffReference`)
  replaced by **`assigned_analyst_id`** (a user id or null);
- `change`;
- `actor_user_id` (null when no person acted);
- `escalation_target_role` and `escalated_by_user_id` (point 4), which the routing filter needs and
  which are not pushed to the console.

**Names are resolved in the fan-out.** Before it serialises a frame, the fan-out turns
`assigned_analyst_id` into the `StaffReference` the console expects, from a per-institution cache
of `(user_id → first name, last name, role)` read in a tenant transaction.
- **The cache's entries live 60 s** (ASSUMED), so a renamed user's card may show the old name for up
  to a minute; the next read shows the new one.
- **A cache miss is resolved off the poll thread.** The consumer thread must do nothing but poll and
  route (point 11's 100 ms leg), and a miss is a tenant transaction with the runner's 5 s statement
  timeout: on the poll thread, one slow miss would stall every institution's events, and a 30 s
  stall closes every local session (point 5). So a record with a missing name is handed to a **name
  resolver**: a small executor of 2 threads (ASSUMED) with a bounded queue of 1,000 records
  (ASSUMED). The resolver loads the name in a tenant transaction with a 500 ms timeout (ASSUMED),
  fills the cache, and returns the record to routing (point 5, step 3). G2 makes the record's
  later arrival harmless: the console orders by `version`, not by arrival.
- **A name that cannot be resolved** (the database unavailable or slower than the timeout, or the
  resolver's queue full) drops the event, counted as `fs_realtime_unresolved_total`. The next
  re-read repairs it. The frame is never sent with a wrong or missing assignee, since
  `assigned_analyst: null` means "in the pool". A RAISED event has no assignee and never needs the
  resolver. A resolved miss's extra latency is inside `fs_realtime_push_seconds` (point 11) and is
  not excluded from the budget; misses are rare with a 60 s cache, and are counted
  (`fs_realtime_name_cache_miss_total`).

**Who publishes.** The transaction that changed the entry publishes, **after its commit**, through
one port (below):

| `change` | Written by (decision-owner contract) | Entry after |
|---|---|---|
| `DECIDED` | D1, the CLAIM request | CONFIRMED_FRAUD or MARKED_LEGITIMATE, decision PENDING_COMMIT, `version + 1` |
| `UNDONE` | D2, the RELEASE request | its `prior_status`, `version + 1` |
| `ESCALATED` | D6, the original entry | ESCALATED, `version + 1` |
| `ESCALATION_CREATED` | D6, the new entry | PENDING, version 0, `escalated_from_alert_id` set |
| `OVERRIDDEN` | D5, the OVERRIDE request | OVERRIDDEN, `version + 1` |
| `TIMED_OUT` | the TIMEOUT request, for each entry it closes | AUTO_RELEASED or AUTO_DECLINED, `version + 1` |
| `RESOLVED` | the resolver's RESOLVE request, for each entry it closes | OVERRIDDEN, `version + 1` (V81's `DecisionOwner` increments it, `DecisionOwner.java:107-110`) |
| `TIMED_OUT` / `RESOLVED` | **the sink, closing a late entry** (§6.2): in the transaction that writes a MEDIUM `AlertRaised` after its transaction's TIMEOUT or RESOLVE, `PostgresSink` closes the new entry at once (`DecisionOwner.java:705-710`, `:721-727`) | AUTO_RELEASED or AUTO_DECLINED (after a TIMEOUT), or OVERRIDDEN (after a RESOLVE), `version + 1`. It publishes after the sink's batch commits |
| `ASSIGNED` | `assignAlert` (ADR 0116) | status unchanged, `version + 1` |

`AUTO_DECLINED` is not added by this ADR: it is one of the decision-owner contract's §11 contract
additions, and this ADR depends on it.

**How a writer outside `analyst` publishes: a port, and a re-read after the commit.**
- **The module boundary.** The only builder of an `AlertSummary` is `AlertQueries.summary` in the
  `analyst` module (`analyst/alerts/AlertQueries.java:226-236`). `analyst` depends on `decision`
  (`backend/analyst/pom.xml:31`); `decision` does not depend on `analyst`. Two writers are in
  `decision`: the TIMEOUT request (`OwnerHoldTimeouts.java:33-60`, through `DecisionOwner.submit`,
  whose `CLOSE_ON_TIMEOUT` today returns only `id, created_at`, `DecisionOwner.java:95-104`) and the
  sink's late close (`PostgresSink.java:433-449`). The resolver is in `analyst`
  (`DecisionWorkers.java:336`).
- **The port.** `decision` gains a port, `AlertChangePublisher`, with one method
  `offer(institutionId, alertId, version, change, actorUserId)`. Every writer, in either module,
  calls it after its commit. `analyst` implements it; a no-op implementation is wired when the
  `analyst` module is absent. The implementation only offers the tuple to the hand-off queue
  (below), so the port never blocks.
- **What each `decision` writer must collect.** `DecisionOwner`'s TIMEOUT and RESOLVE paths return
  the ids and new `version`s of the entries they closed (today `CLOSE_ON_TIMEOUT` returns only
  `id, created_at`), and `OwnerHoldTimeouts` and the resolver offer them after commit; the sink
  collects the late closes of its batch and offers them after the batch commits. These are changes
  to M6's code (point 9).
- **The summary is re-read after the commit, not in the writing transaction** (the simpler of
  review 2's two options). The publisher thread reads the entry's summary with `AlertQueries`, with
  `escalation_target_role` and `escalated_by_user_id`, in one short read-only tenant transaction of
  the offered institution. So the TIMEOUT and sink transactions, which hold the owner row, are not
  lengthened by a summary read.
- **What that costs, stated.** The re-read may see a **later** version than the one offered, when
  another change committed in between. The event then carries the version and the snapshot it read,
  and its `event_id` is derived from that version (below), so the two publications of that snapshot
  share an `event_id`. G2 tolerates it: the console applies the highest version. The event's
  `change` names the change that caused the publication; it may be older than the snapshot, and the
  console uses it only as a hint, never for ordering. A re-read that fails (the database unavailable)
  drops the event, counted in `fs_realtime_publish_failed_total`; the next re-read of the console
  repairs it.

**What is not pushed, deliberately.** None of these changes the queue entry's status or `version`:
- **D3's commit.** The deciding console already holds `undo_until`, and a commit changes nothing a
  card shows.
- **D4's effect outcomes**, and RELEASE_FAILED.
- **A contest** and the CUSTOMER_* requests, which change the owner row only.

**How the publication is made: best effort, never blocking (decided by the owner, 2026-10-22).**
- **A dedicated producer.** The publisher has its own Kafka producer instance (idempotent,
  `acks=all`), not the spool's, so it never competes with the decision path's buffer.
- **A bounded hand-off queue.** After the commit, the writing thread only **offers** the port's
  tuple (institution, entry id, version, change, actor: ids, an enum and a number, never a
  summary) to an in-memory queue of 10,000 entries (ASSUMED); the offer never blocks. When the queue
  is full, the event is dropped and counted (`fs_realtime_publish_dropped_total`). Because the queue
  holds fixed-size tuples, its count bounds its bytes: about 128 bytes per entry (estimated, not
  measured), so about 1.25 MiB, and the summaries exist only one at a time on the publisher thread.
- **Its own thread.** One publisher thread drains the queue, re-reads the summary (above), and calls
  `send`. `send` can block for up to `max.block.ms` (on a missing metadata fetch or a full buffer),
  so `max.block.ms` is small, 100 ms (ASSUMED), and only this thread ever waits on it.
- **The producer's buffer is bounded and budgeted.** The dedicated producer's `buffer.memory` is set
  to 8 MiB (ASSUMED), not Kafka's 32 MiB default. It and the hand-off queue are counted in point
  8's start-up check with the outbound budget.
- **`send` is never called on a request thread, the TIMEOUT poller, or the sink.** So a Kafka
  problem never stalls `decideAlert` or `undoAlertDecision` past FR-04-04's 2 s, never delays a
  TIMEOUT, and never slows the sink.
- **A failed send is counted** (`fs_realtime_publish_failed_total`) and not retried beyond the
  producer's own delivery timeout.
- **The envelope `event_id`** is the name-based UUID of `alert_changed|<alert_id>|<version>`, so a
  repeated publication repeats it.
- **Why best effort.** A lost change is repaired by the console's next re-read. A durable path (the
  spool, or V9's unused outbox with a relay) would add a writer to every transaction and a worker,
  and would buy a guarantee the console does not rely on.

**Coverage.** Every writer that changes an entry's `status`, `version` or assignment must publish.
- **The test.** It lists every `UPDATE alert_queue_entries` and `INSERT INTO alert_queue_entries`
  in main code, and fails when one has no test asserting its event.
- **The one exception is the sink's INSERT** of a raised entry, whose event is the `fs.alerts.*`
  message. The sink's UPDATE that closes a late entry is not excepted: it publishes (table above).

That this listing can be written as a source scan, like the inspector's "every analyst endpoint", is
ASSUMED.

#### 6.3 Figures

- **What is sent.** `DashboardStats` is institution-wide and the same for every role, so an instance
  computes it once per institution, not once per session.
- **When it is computed.** The instance recomputes it for an institution at most once every 5 s
  (ASSUMED interval), and only while it has at least one session of that institution, when any of
  these holds:
  - an alert event for that institution has passed through the instance since the last
    computation;
  - a session of that institution connected or disconnected on this instance;
  - 30 s have passed since a local disconnect, so the departed user's presence entry has expired
    (ADR 0117 point 4);
  - 30 s have passed since the last computation (ASSUMED), which picks up presence changes made on
    other instances.
- **The staleness accepted.** `analysts_online` can lag by up to 30 s, from presence expiry and from
  other instances' changes. ADR 0117 states the same lag.
- **Who receives it.** The result is sent to every local session of the institution, with `as_of`.
- **Consistency.** Two instances may send snapshots a few seconds apart. The console keeps the one
  with the later `as_of`.
- **What the figures mean** (the institution's day, `analysts_online`) is ADR 0117's.
- **`getMyPerformance` is not pushed.** It is per user, and the console already knows when its own
  user decides, so it re-reads it then. FR-04-11's "update in real time" is met that way, or not at
  all; this ADR does not claim it.

### 7. Ordering, duplicates and resuming

**Delivery guarantees, precisely:**
- **G1. Isolation.** An event is never delivered to a session of another institution, nor to a
  session that `listAlerts` would not show the entry to (point 4).
- **G2. Snapshots.** Every alert event carries the whole `AlertSummary` at its `version`. Applying,
  for each `alert_id`, the event with the highest `version` received is correct whatever the arrival
  order and however many duplicates arrive.
- **G3. Kafka.** Each change reaches Kafka at most once per publication and is re-published only
  with the same `event_id`. Raised alerts reach Kafka at least once (decision-fact-ordering G4).
- **G4. Per socket.** Each frame is written to a socket at most once.

**Not guaranteed:**
- **No guarantee of delivery to the browser.** An event is lost when:
  - the publisher crashes between its commit and its send;
  - the hand-off queue is full (point 6.2);
  - Kafka refuses the send within the producer's delivery timeout;
  - a staff name cannot be resolved (point 6.2);
  - the socket is closed or overflows (point 8);
  - the session is between connections.
- **No order across alerts, partitions, topics or instances,** and **none within one alert by
  arrival.** Publishers on different instances send after their own commits, so `version` 3 can
  arrive before `version` 2.
- **No replay and no cursor.**

**What the console does with them** (normative for the console; M8's to build):
- **Events.** Keep the highest `version` seen per `alert_id`. Ignore an **event** whose `version` is
  equal (a duplicate) or lower (stale).
- **Reads replace at an equal version.** A card from `listAlerts` or `getAlert` replaces the held
  card when its `version` is **equal or higher**. A read is never treated like an event, so a
  provisional version-0 card is completed by the next read of version 0.
- **Membership.** An entry whose status is not PENDING or IN_REVIEW leaves the open views.
- **Its own filters.** The console applies its own filters (queue, tier, search) to pushed
  summaries; the server does not filter by them.

**Resuming after a dropped connection: "subscribe, then re-read".**
1. **Reconnect with back-off.** Use 1 s, 2 s, 4 s and so on, up to 30 s, with full jitter (ASSUMED
   schedule). Send CONNECT, then SUBSCRIBE to both destinations, **each with a `receipt` header.**
2. **The server sends each RECEIPT only after the session is registered with the router** for that
   destination, so an event routed after the RECEIPT reaches the session.
3. **Only after both RECEIPTs,** re-read `listAlerts` for the views on screen, and
   `getDashboardStats`.
4. **Merge by `version`, per alert, within the window read.** The re-read is authoritative only
   for the key range and filters it actually covered: the pages read, under the view's filters. An
   alert in that window that the read does not list is removed, with one exception: an alert whose
   event arrived after the re-read request was sent is kept, since it may be newer than the read's
   snapshot. Cards outside the pages read are untouched.

Subscribing first means no change can fall between the read and the subscription, given point 5's
rule that an instance is ready only when its consumer is positioned. A server-side cursor was
rejected:
- it would need a gap-free, per-institution sequence across concurrent committers on every
  instance;
- it would need a replay store.

Both are mechanisms the re-read makes unnecessary.

### 8. Back-pressure, memory and limits

**Back-pressure.** The Kafka consumer never blocks on a socket.
- **Each session has a bounded outbound queue** of 256 frames or 256 KiB, whichever is reached
  first. The size is ASSUMED, as 60 s (one re-read period) of one institution's peak alert-event
  rate, itself ASSUMED at 4 events a second (the SRS states 10,000 TPS of transactions but no alert
  rate), at up to 1 KiB per frame. A reader further behind than one re-read period gains nothing
  from the queue that the re-read would not give it.
- **On overflow** the server sends `ERROR` with the problem `realtime-overflow` and closes. The
  console reconnects and re-reads (point 7). A slow phone therefore costs itself a re-read, and
  never delays other sessions or the consumer.

**The instance has a memory budget too.** The per-session bound protects other sessions from one
slow reader; it does not protect the process.
- **Frames are shared.** Each record is serialised once (point 5), and every queue holds a
  reference to the same immutable bytes.
- **An instance-wide outbound budget of 32 MiB** (ASSUMED) bounds the sum of the frame sizes queued
  across all sessions, counted per queue as if unshared, which over-counts.
- **The start-up check covers the channel's every allocation, not only the outbound budget.** The
  sum of the outbound budget (32 MiB), the change producer's `buffer.memory` (8 MiB, point 6.2), the
  hand-off queue's bound (about 1.25 MiB, point 6.2) and the name resolver's queue (1,000 records,
  point 6.2, counted at the ASSUMED 1 KiB a record, about 1 MiB), about 42 MiB with the ASSUMED
  values, must be at most one eighth of the API instance's maximum heap; the instance refuses to
  start if configured above that.
- **When the budget is exceeded,** the instance closes the sessions with the largest queues, with
  `realtime-overflow`, until the total is below 75 % of the budget. A burst (a TIMEOUT sweep closing
  many entries, or a restart while many phones sit on stalled 3G links) therefore costs the slowest
  readers a re-read, not the process its heap.
- **Without the budget,** 500 sessions × 256 KiB would be 125 MiB per instance, and the first
  draft's 2,000 × 1 MiB would have been about 2 GiB.

**Limits** (every number ASSUMED, and configurable):

| Limit | Value | When exceeded |
|---|---|---|
| Authenticated sessions per user | 5 (tabs and a phone), **counted per instance**: with N instances a user can hold 5N. Accepted, because the cost is bounded by the per-instance caps below | the oldest session is closed with `ERROR` `rate-limited` |
| Authenticated sessions per instance | 500 | the handshake answers 503 with `Retry-After`, so the client or the load balancer tries another instance |
| Distinct (`user`, `sid`) pairs per instance | 100, so the revocation backstop's rates of point 3 hold | a CONNECT that would add a pair receives `ERROR` `service-unavailable` and is closed; the client retries, and reaches another instance only under the load-balancer requirement below |
| Sockets awaiting CONNECT, per instance | 50, counted apart from the authenticated sessions, so unauthenticated sockets can never take authenticated capacity | the handshake answers 503 with `Retry-After` |
| Sockets awaiting CONNECT, per client IP, at once | 200, so a whole office behind one address can be mid-handshake at once (derivation below) | the handshake answers 429 |
| Handshakes per client IP | 1,000 a minute (derivation below) | 429 |
| Accepted CONNECTs per user, per instance | 10 a minute, counted after authentication | `ERROR` `rate-limited`, close |
| Time from upgrade to CONNECT | 5 s | close |
| Inbound frame size | 8 KiB. The console only sends CONNECT, SUBSCRIBE, the re-auth SEND and heart-beats | `ERROR` `validation`, close |
| Destinations | the two of point 6; any other SUBSCRIBE or SEND | `ERROR` `forbidden`, close |

**"Client IP"** is the forwarded address, resolved as `Requests` already documents
(`backend/auth/.../web/Requests.java`), not the proxy's address.

**The per-IP limits are derived for an office behind one address** (re-derived after review 2, on
its recommendation, before release; the author's choice on review 2's recommendation; the owner may
overrule).
- **Why the first values were wrong.** Staff are expected to sit behind office addresses
  (`office_ip_allowlist`, `V2:107`). With 30 handshakes a minute and 5 pending CONNECTs per address,
  a mass reconnect that this ADR itself causes (the Redis-down closure, point 3; a stalled consumer,
  point 5; a deploy, below) would admit 30 of 200 analysts a minute, and send the rest into back-off
  of up to 30 s.
- **The derivation.** The largest office behind one address is ASSUMED at 200 staff, the CI test's
  session count (point 11). In a mass reconnect each client succeeds at its first or second attempt
  unless an instance is full; allowing 5 handshakes per person per minute covers retries after a
  503 from a full or not-yet-ready instance, so 200 × 5 = 1,000 a minute. All 200 may be between
  upgrade and CONNECT at the same moment, so 200 at once. Both are ASSUMED.
- **What still protects capacity.** The per-instance cap of 50 sockets awaiting CONNECT, counted
  apart from authenticated sessions, and the 5 s to CONNECT. One address can now hold one
  instance's 50 pending slots for at most 5 s at a time; that costs other clients a 503 and a retry
  on another instance, never authenticated capacity. After authentication, a user is limited to 10
  accepted CONNECTs a minute per instance (ASSUMED), which bounds a single credential's churn
  whatever its address.
- **Why not exempt the institution's allowlisted addresses,** the other remedy review 2 offered.
  The handshake carries no credentials, so the institution, and therefore its allowlist, is unknown
  until CONNECT; exempting the union of every institution's allowlist would need a cross-tenant read
  of `office_ip_allowlist` outside row-level security.
- **A deployment requirement.** The load balancer in front of the API must spread new WebSocket
  connections across ready instances (round robin or least connections, with no source-address
  affinity), so that a client retrying after a 503 can reach another instance. This is a
  requirement on the deployment, recorded here and listed in "Required follow-ups".
- **The CI test** (point 11) gains a reconnect-storm case: 200 sessions from one address dropped at
  once (100 users with two tabs each, so within the cap of 100 pairs on the one test instance) all
  reconnect and subscribe within 60 s, and none receives 429.

**Heart-beats.**
- **The server offers 25,000 ms each way** (ASSUMED, below common proxy idle timeouts). It accepts
  a client's value up to 60,000 ms, the SRS's 3G heart-beat.
- **On 2G the SRS says no WebSocket** (60-second polling). That choice is the console's.

**Shutdown.** A stopping instance sends `ERROR` (`service-unavailable`, detail "server shutting
down") to each session and closes. Clients reconnect elsewhere. No sticky sessions are needed,
because every instance carries every event.

### 9. The contract additions

**OpenAPI path** (`fraudshield-api.yaml`, under "Analyst console"):

```yaml
  /realtime:
    get:
      tags: [alerts]
      operationId: openRealtimeChannel
      summary: Real-time alert feed and header figures, STOMP 1.2 over WebSocket (FR-04-02, UX-DASH-01)
      description: |
        Upgrade to WebSocket with subprotocol `v12.stomp`, at this exact path (no SockJS). The
        handshake carries no credentials and the server ignores cookies here; `Origin` must be an
        allowed origin (403 otherwise); a request that is not a valid upgrade answers 426. The
        handshake answers every caller alike: it never answers 401, or 403 on role.
        The STOMP CONNECT frame must arrive within 5 s and carry `Authorization: Bearer <access
        token>`, validated as on every staff operation; the roles below are enforced there. Any
        frame before an accepted CONNECT, a second CONNECT, or a frame to another destination
        receives ERROR and the socket is closed. Before the token's `exp`, the client
        re-authenticates with SEND to `/app/session.reauth` carrying the new token, with the same
        `sub`, `sid`, `institution_id` and `role`. A session whose token expires, or whose session
        is revoked, receives ERROR and is closed (within 5 s of revocation, D-27).
        Destinations (SUBSCRIBE, each with a `receipt` header): `/user/queue/alerts`
        (RealtimeAlertEvent) and `/user/queue/figures` (RealtimeFiguresEvent). Events are
        snapshots: apply the highest `version` per `alert_id`; a read replaces a held card at an
        equal or higher `version`. Delivery is not guaranteed and nothing is replayed: after
        (re)connecting, subscribe, wait for both RECEIPTs, then re-read listAlerts and
        getDashboardStats. Only entries listAlerts would show the caller are pushed. ERROR frame
        bodies are Problem documents (the RealtimeError response).
      security: []
      x-auth-in-band: stomp-connect
      x-required-roles: [ANALYST, SENIOR_ANALYST, RISK_OFFICER]
      x-authorisation-rules:
        - rule: Pushes an alert only to sessions listAlerts would show it to (queue=all, every outcome) - escalated entries only to roles at or above the target role, plus the user who escalated them
          effect: filter
      x-realtime:
        protocol: stomp-1.2
        subscribe:
          /user/queue/alerts: { $ref: "#/components/schemas/RealtimeAlertEvent" }
          /user/queue/figures: { $ref: "#/components/schemas/RealtimeFiguresEvent" }
        send:
          /app/session.reauth: { headers: [Authorization, receipt] }
        error: { $ref: "#/components/responses/RealtimeError" }
      responses:
        "101": { description: Switching protocols to WebSocket (STOMP 1.2) }
        "401":
          $ref: "#/components/responses/Unauthorized"
          description: >-
            Never sent by the handshake, which authenticates no one. Documented because the
            operation is role-protected: an unauthenticated or expired CONNECT is refused with a
            STOMP ERROR frame whose body is this problem (unauthorized), and the socket is closed.
        "403": { $ref: "#/components/responses/Forbidden" }
        "426": { $ref: "#/components/responses/UpgradeRequired" }
        "429": { $ref: "#/components/responses/TooManyRequests" }
        "503": { $ref: "#/components/responses/ServiceUnavailable" }
        default: { $ref: "#/components/responses/UnexpectedProblem" }
```

- **401 is present,** so `test_secured_operations_document_401_and_403_and_json_ops_have_problem_default`
  holds unchanged. OpenAPI 3.1 lets a Reference Object carry its own `description`, which overrides
  the referenced one; it states truthfully that the handshake never sends it.
- **403** is the Origin refusal (`forbidden`).
- **The `x-authorisation-rules` filter entry** matches the matrix's `{ effect: filter }`, so
  `test_document_matches_the_reviewed_matrix_exactly` holds. Its wording follows `listAlerts`'.

**Two new responses** (`components/responses`):

```yaml
    UpgradeRequired:
      description: Not a valid WebSocket upgrade request for the real-time channel (ADR 0115)
      x-problem-types: ["urn:fraudshield:problem:upgrade-required"]
      content:
        application/problem+json:
          schema: { $ref: "#/components/schemas/Problem" }
    RealtimeError:
      description: >-
        Not an HTTP response: the body of a STOMP ERROR frame on the real-time channel (ADR 0115).
        The socket is closed after it. A `validation` problem here has no `errors` member, since a
        frame has no fields.
      x-problem-types:
        - "urn:fraudshield:problem:unauthorized"
        - "urn:fraudshield:problem:forbidden"
        - "urn:fraudshield:problem:validation"
        - "urn:fraudshield:problem:rate-limited"
        - "urn:fraudshield:problem:realtime-overflow"
        - "urn:fraudshield:problem:service-unavailable"
      content:
        application/problem+json:
          schema: { $ref: "#/components/schemas/Problem" }
```

- **Every new problem type is declared by a response,** as `test_problem_types_come_from_the_catalogue`
  requires: `upgrade-required` by `UpgradeRequired`, `realtime-overflow` by `RealtimeError`.
- **`ProblemType` gains two values:** `urn:fraudshield:problem:realtime-overflow` and
  `urn:fraudshield:problem:upgrade-required`.

**The access forms in the header.** `info.description`'s list of access forms gains the fifth:
"`x-auth-in-band: stomp-connect` with `x-required-roles` (the real-time channel: roles enforced at
the STOMP CONNECT, ADR 0115)".

**`x-auth-in-band` is new.**
- **What it means.** HTTP authentication is skipped for this one operation, and the roles in
  `x-required-roles` are enforced at the STOMP CONNECT.
- **The contract tests.** `test_every_operation_declares_exactly_one_authorisation_class` learns
  that an operation with `x-auth-in-band` has `security: []` and `x-required-roles`, and that only
  `openRealtimeChannel` may have it. `test_authorisation_matrix.py` learns the matrix's `in-band`
  key.
- **The `$ref`s inside `x-realtime` are checked.** Standard validators do not resolve references in
  extensions, so a new test resolves every `$ref` under `x-realtime` (the two schemas and the
  response) and fails on one that does not resolve.
- **The Java authorisation layer.** `ContractPolicy` reads access from the matrix (`roles`,
  `public`, `refresh-cookie`); it gains a distinct `Access` kind, `IN_BAND`, that is never treated
  as public anywhere else. `ContractAuthorizationManager` permits the handshake of an `IN_BAND`
  operation without authentication, and denies every other path by default as today, so
  `/realtime/**` sub-paths stay closed. The handler carries `@ContractOperation("openRealtimeChannel")`.
- **`AuthorisationMatrixTest`** (ADR 0070 point 1: every operation exercised by anonymous, the four
  roles and API keys) expects the **same** handshake answer for every caller: 101 for a valid
  upgrade from an allowed Origin, 426 otherwise, never 401 or 403 on role. The roles are tested at
  CONNECT by the STOMP integration test: ADMIN, an API key and no token are refused with ERROR; the
  three decision roles are accepted.

**Schemas** (`components/schemas`):

```yaml
    RealtimeAlertEvent:
      type: object
      required: [event_id, occurred_at, change, actor_user_id, provisional, alert]
      additionalProperties: false
      properties:
        event_id:
          type: string
          format: uuid
          description: The Kafka envelope's event_id; a repeated publication repeats it
        occurred_at: { $ref: "#/components/schemas/Timestamp" }
        change:
          type: string
          enum: [RAISED, DECIDED, UNDONE, ESCALATED, ESCALATION_CREATED, OVERRIDDEN, TIMED_OUT, RESOLVED, ASSIGNED]
        actor_user_id:
          type: [string, "null"]
          format: uuid
          description: The person who made the change; null for RAISED, TIMED_OUT and RESOLVED, and for a late entry closed by the sink
        provisional:
          type: boolean
          description: >-
            True when the Kafka payload lacked fields the read may have (merchant, top_reasons);
            the card then carries merchant null and top_reasons empty, and a read completes it.
            False when top_reasons is genuinely empty.
        alert: { $ref: "#/components/schemas/AlertSummary" }
    RealtimeFiguresEvent:
      type: object
      required: [as_of, stats]
      additionalProperties: false
      properties:
        as_of: { $ref: "#/components/schemas/Timestamp" }
        stats: { $ref: "#/components/schemas/DashboardStats" }
```

**Examples.** `schema-examples.yaml` gains examples of `RealtimeAlertEvent` and
`RealtimeFiguresEvent`, so the examples test validates them (they are reached only through
`x-realtime`, not through an operation's responses). The `RealtimeAlertEvent` example's `alert`
carries `assigned_analyst`, which ADR 0116 makes required on every `AlertSummary`.

**The authorisation matrix:**

```yaml
  openRealtimeChannel:
    roles: [ANALYST, SENIOR_ANALYST, RISK_OFFICER]
    in-band: stomp-connect
    ref: "FR-04-02, UX-DASH-01, FR-04-10; roles enforced at STOMP CONNECT; pushes only what listAlerts shows the caller (ADR 0115)"
    rules:
      - { effect: filter }
```

**Kafka** (`topics.yaml`), a new topic:

```yaml
  - name: fs.alerts.changes
    event_type: alert.changed
    schema_version: 1
    purpose: >-
      Every change to an alert queue entry after it is raised (decided, undone, escalated, overridden,
      timed out, resolved, closed late by the sink, assigned), as the entry's AlertSummary after the
      change with staff as ids only, for the real-time channel (ADR 0115). Published after commit,
      best effort; the reads are authoritative
    key: transaction_id
    partitions: 12
    retention: P1D
    payload_schema: schemas/alert-change.schema.json
    producers: [fraudshield-api]
    consumers: [websocket-fanout]
```

**A recorded deviation from the master prompt's C.3** (the author's choice on review 2's
recommendation; the owner may overrule). C.3 lists exactly thirteen topics
(`docs/prompts/FraudShield_Master_Build_Prompt.md:283`), and `topics.yaml:1` cites it.
`fs.alerts.changes` is a fourteenth. The reason: `fs.decisions.final` carries the transaction's
state, not the queue entry's, so it cannot carry an undo, an escalation or a PENDING_COMMIT
decision (Context), and the reads stay authoritative. The owner's decision of 2026-10-22 on ids-only
changes was made about this topic's payload, which implies the topic, but it did not record the
deviation; this does. Its consequences:
- **`contracts/tests/test_events.py`:** `C3_TOPICS` (`:43-57`) gains `fs.alerts.changes`, so
  `test_catalogue_lists_exactly_the_c3_topics_with_key_retention_and_dlq` (`:66-68`) holds; the
  constant keeps its name, and a comment cites this ADR as the deviation.
- **`contracts/kafka/examples/fs.alerts.changes.json`** is added, so `test_topic_examples_validate`
  (`:61-62`, `:136`) finds and validates it. Its `alert` carries `assigned_analyst_id`, and no
  property carries a name.
- **`topics.yaml:1`**'s header comment says the catalogue is C.3's plus this ADR's deviation.
- **Its DLQ.** The catalogue's defaults give every topic a `.dlq`, so `fs.alerts.changes.dlq` is
  created. Nothing writes to it: the fan-out never dead-letters (point 5). It is created and unused,
  which is harmless, and is kept so the catalogue's every-topic-has-a-DLQ rule needs no exception.

**`fs.notifications.staff`** loses `websocket-fanout` from its consumers (point 5), and its
`purpose` (`topics.yaml:114`) changes from "Staff email, push and WebSocket notifications (freeze,
escalation, unlock, approvals)" to "Staff email and push notifications (freeze, escalation, unlock,
approvals)".

**Where `institution_id` comes from, in the frozen text.** Two descriptions say it comes only from
the authenticated principal, which point 4 contradicts for `alert.changed`. Both change to "from the
authenticated principal, or, for an event about a stored row, from that row's `institution_id` read
in the writing tenant transaction":
- `contracts/kafka/schemas/envelope.schema.json:36`, the `institution_id` property's
  `description` (a description change, which the compatibility check accepts);
- `contracts/kafka/topics.yaml:12-14`, the header comment's last sentence on tenant isolation.

**Its payload schema,** `schemas/alert-change.schema.json`:
- **Required:** `alert_id`, `version`, `transaction_id`, `change`, `actor_user_id`,
  `escalation_target_role`, `escalated_by_user_id` and `alert`.
- **`alert`** is the OpenAPI `AlertSummary` **with `assigned_analyst` replaced by
  `assigned_analyst_id`** (`["string", "null"]`, a UUID). A contract test compares that projection
  with `AlertSummary` after resolving references, as `decision-final.schema.json` is compared with
  `FinalDecision`. No property of the payload carries a person's name.
- **Its baseline** is added under `kafka/baseline/`.

**`alert.schema.json`** gains two optional properties, which the compatibility test accepts:
- `merchant`: `["string", "null"]`, at most 100 characters;
- `top_reasons`: at most 3 items of `{feature, value, shap}`, with the OpenAPI `ShapContribution`'s
  types for those three fields.

**Changes to M6's code** (all made with P2's code, each with its test):
- `AlertRaised`, its spool codec and `KafkaMessages` carry `merchant` and `top_reasons`
  (point 6.1);
- `AlertRaised` omits `anomaly_score` under the rule-based fallback (point 6.1);
- `PostgresSink` publishes `TIMED_OUT` or `RESOLVED` after its batch commits when it closes a late
  entry (point 6.2), through the non-blocking publisher;
- `decision` gains the `AlertChangePublisher` port (point 6.2), with a no-op default;
- `DecisionOwner`'s TIMEOUT (`CLOSE_ON_TIMEOUT`, `DecisionOwner.java:95-104`) and RESOLVE paths
  return the ids and new `version`s of the entries they close, and `OwnerHoldTimeouts`
  (`OwnerHoldTimeouts.java:33-60`) and the resolver (`DecisionWorkers.java:336`) offer them to the
  port after commit;
- `PostgresSink` collects its batch's late closes for the same purpose.

**Changes to `backend/auth`** (made with P2's code, each with its test):
- `SessionStateCache.addChangeListener`, called by `onMessage` after it removes the local entry
  (point 3), with a test that the listener runs after the invalidation;
- `SessionStateCache.isCurrent(claims, LoadGate)`, answering `CURRENT`, `NOT_CURRENT` or `UNKNOWN`,
  consulting the gate only before a database load (point 3), with a test that a refused gate never
  reaches PostgreSQL and that a Redis-answered check never consults the gate;
- `SafeRedis.available()`, from a count of consecutive failed operations that a missing key never
  increments (point 3), with a test of both;
- `Access`'s check that an operation has exactly one access kind (`Access.java:19-29`) accepts
  `roles` together with `IN_BAND`, which point 9's "distinct kind" implies, and still refuses
  `IN_BAND` together with scopes, `public` or `refresh-cookie`, and `IN_BAND` without roles.

### 10. P8's retraining stream reuses this channel

`startRetraining` promises progress "streamed over WebSocket". P8's own proposal adds:
- ADMIN to `openRealtimeChannel`'s roles;
- a destination `/user/queue/retraining-jobs` fed from `fs.ml.retrain` by the same consumer.

It must keep this ADR's rules:
- CONNECT authentication and in-band re-authentication;
- the institution filter;
- presence written only for ANALYST, SENIOR_ANALYST and RISK_OFFICER sessions (point 2), so ADMIN
  sessions never count as analysts online;
- snapshot frames, a `RetrainingJob` with a field that only increases (`progress_percent` within a
  `state`);
- no replay. On reconnect the console re-reads `getRetrainingJob`.

Whether `fs.ml.retrain` envelopes are per institution, or global to the deployment, is P8's
question. This ADR does not answer it.

### 11. The one-second budget, and what can be measured where

**What the budget covers.** FR-04-02's "within 1 second" is read as NFR-PERF-05 measures it: **from
the Kafka record to the DOM.** Neither the payment's decision nor the spool's drain to Kafka is
inside it.
- **The drain is unbounded** (decision-fact-ordering N1). This channel cannot bound "from payment
  to dashboard", and does not claim to.

**The split** (ASSUMED except where cited):

| Leg | Budget | How it is held |
|---|---|---|
| Kafka record → fan-out poll returns | ≤ 100 ms (ASSUMED) | `fetch.max.wait.ms` 50 (ASSUMED); the poll loop does nothing else |
| Routing, filter, enqueue, socket write | ≤ 50 ms | SRS latency table, row 13 ("< 50ms") |
| Network to the browser, and render | ≤ 850 ms (ASSUMED, the remainder) | the console's; 3G round trips are 100 to 200 ms (SRS network table) |

**Measured on the server:** `fs_realtime_push_seconds`, a histogram from the Kafka record's
`CreateTime` to the frame's socket write, per destination.

**What can be measured here, and what cannot:**
- **On this machine: only unit tests** of the router, the filter, the queue's overflow and budget,
  and the re-auth rules. Kafka, the application and a browser together do not fit in its memory
  (the resource rules).
- **In CI:** an integration test with Kafka (Testcontainers) and real STOMP clients asserts the
  server leg, from record to socket write, under a stated load: 200 sessions across 2 institutions
  (100 users with two tabs each, within point 8's cap of 100 distinct pairs per instance),
  4 alert events a second per institution for 60 s, with p99 of the server leg at or under 50 ms
  (the load is ASSUMED, matching point 8's peak rate). It also asserts the isolation and filter
  tests (G1), re-authentication without loss, revocation within 5 s by push and by the backstop,
  per-session overflow, and the instance budget closing the largest queues first.
- **In M10:** the browser leg, by Playwright with millisecond timestamps (NFR-PERF-05's own method),
  and the loss rate that decides whether the 60-second re-read can be lengthened (point 1).
- **Nowhere yet:** latency over real mobile networks, and a fan-out at production volume across
  several instances.

## Requirements: closed, and left open

| Row | What this does | What stays open |
|---|---|---|
| FR-04-02 | Gives the feed its channel, which removes the BLOCKED reason | Releasing the row needs the code, the console against it (the mock rule), and M10's measurement. The "10,000 rows" criterion is the console's |
| UX-DASH-01 | Real-time figures (point 6.3) | Figure definitions are ADR 0117's. `analysts_online` lags by up to 30 s |
| NFR-PERF-05 | Defines the start point and the server's leg, and gives it a metric | M10 measures it end to end |
| FR-04-10 | "Target role analyst notified via WebSocket": ESCALATION_CREATED reaches every **connected** session at or above the target role | A target-role user who is not connected learns of the escalation only at their next read. Push to a device that is not connected (email, push) is the notification service's, not this channel's |
| MOB-DEV-03 | "WebSocket reconnects after tab switch": point 7 defines reconnect and resume | M10 tests it |
| FR-06-04 | Fixes the rules P8 reuses | P8's proposal |
| D-27 | Revocation closes a socket within 5 s, by push or by the backstop (point 3) | The CI test of point 11 |
| ADR 0081, question 2 | Input: this server cannot know whether a given console's socket is up, so REALTIME_PAUSED as "my socket is down" is the console's own state. A stalled consumer now closes its sessions (point 5), which the console sees as a dropped socket | Whether the server also reports a stalled fan-out consumer elsewhere is P3's decision |

## Deliberately left out

- **Replay, cursors and `Last-Event-ID`** (point 7).
- **Server-side filters by queue, tier or search.** The console filters what it receives.
- **`fs.notifications.staff` on the socket.** No code produces `ALERT_ESCALATED` today, and
  ESCALATION_CREATED already tells the target roles. The notification service keeps email and push,
  and `websocket-fanout` is removed from that topic's consumers (point 9).
- **D3 commits, D4 effects, contests and customer answers** (point 6.2).
- **Per-user figures** (`getMyPerformance`) on the socket.
- **ADMIN**, until P8.
- **SockJS** and any HTTP fallback transport (point 1).
- **A durable publication path** for changes (point 6.2).
- **Staff names on Kafka** (point 6.2).

## Open questions for the owner

None remain open from the first draft. Questions 1, 4 and 5 were decided by the owner on
2026-10-22; questions 2 and 3 are answered by the author on the review's recommendations, marked in
the text as "the author's choice on the review's recommendation; the owner may overrule". Review 2
repeated questions 2 and 3, and added two (6 and 7); all four are answered on review 2's
recommendations, marked "the author's choice on review 2's recommendation; the owner may
overrule". None of them is an owner decision:
- **1. Best effort for changes** (decided): accepted, with a dedicated producer and a bounded
  hand-off queue, so publishing never blocks a decision, the TIMEOUT poller or the sink (point 6.2).
- **2. `merchant` and `top_reasons` on `alert.created`** (author's choice on review 2's
  recommendation; the owner may overrule): added, with the card-equality test, and the M6 changes
  listed (points 6.1 and 9). Optional additions are backward compatible under ADR 0012 point 5,
  and `merchant_name` is already on `fs.transactions.raw`.
- **3. The ASSUMED numbers** (author's choice on review 2's recommendation; the owner may
  overrule): accepted as starting values, to be revised by M10, after the changes of reviews 1 and
  2, except the per-IP handshake limits, which were re-derived for office NAT before release (point
  8). The complete list: 5 s to CONNECT; the 60 s re-auth margin; 5 sessions per user (per
  instance); 500 sessions, 100 distinct (`user`, `sid`) pairs and 50 pre-CONNECT sockets per
  instance; the office of 200 staff behind one address, and from it 200 pre-CONNECT sockets per IP
  at once and 1,000 handshakes a minute per IP (5 per person); 10 accepted CONNECTs a minute per
  user per instance; the 8 KiB inbound frame; 256 frames or 256 KiB per session queue, from a peak of 4 alert
  events a second per institution and 1 KiB frames; the 32 MiB instance budget (at most one eighth
  of the heap, together with the 8 MiB producer `buffer.memory` and the 1 KiB per resolver record)
  and its 75 % target; the 1.5 s ± 0.25 s revocation backstop and its limit of 50 database loads a
  second; Redis "down" after 3 consecutive failed operations, the 1 s `PING`, and "back" after two
  successful `PING`s 1 s apart; the 25 s heart-beat; the 5 s figure interval and the 30 s figure
  refresh; the 5-minute partition refresh; the 30 s consumer-stall limit; `fetch.max.wait.ms` 50 and
  the 100/50/850 ms split; the hand-off queue of 10,000 entries and `max.block.ms` 100; the 60 s
  name cache; the name resolver's 2 threads, 1,000-record queue and 500 ms timeout; 12 partitions and P1D for `fs.alerts.changes`; the back-off schedule; the allowed
  origin default; and the CI test's load (200 sessions, 2 institutions, 4 events a second each for
  60 s).
- **4. Staff names on Kafka** (decided): ids only; names are resolved in the fan-out (point 6.2).
- **5. The 60-second re-read** (decided): kept while the socket is open, until M10 measures loss
  (point 1).
- **6. The fourteenth topic as a deviation from C.3** (author's choice on review 2's
  recommendation; the owner may overrule): accepted and recorded (point 9).
- **7. Closing the whole channel while Redis is down** (author's choice on review 2's
  recommendation; the owner may overrule): accepted (point 3). It fails closed, the console polls
  anyway, and the alternative is idle sockets competing with decisions for the pool.

## Required follow-ups

Made outside this ADR, by whoever changes the named file; none is made by this proposal:
- **The deployment's load balancer** must spread new WebSocket connections across ready instances
  with no source-address affinity (point 8). The deployment configuration and its documentation
  must state it before FR-04-02 is released.
- **The master prompt's C.3 deviation** (point 9) is recorded here; if the owner overrules it, the
  channel has no source for later changes and points 6.2 and 9 fall.
- **The frozen-text amendments of point 9** (`envelope.schema.json:36`, `topics.yaml:1`,
  `topics.yaml:12-14`, `topics.yaml:114`, `C3_TOPICS`, the new Kafka example) are made in the same
  contract change as the topic, not later.

## Review 1's findings and their answers

| # | Severity | Finding | Answer |
|---|---|---|---|
| 1 | MAJOR | The once-a-second revocation re-check was not cheap, and with Redis down it was a database transaction per session per second | Fixed in point 3: closure is driven by the `fs:auth:sessions` push; a backstop per distinct (`user`, `sid`) every 1.5 s ± 0.25 s keeps D-27's 5 s, with its Redis and database rates stated at the cap of 100 pairs and a limiter on database loads that fails closed; with Redis down, every session is closed and handshakes answer 503, and the console polls |
| 2 | MAJOR | No instance-wide memory bound | Fixed in point 8: one serialisation per record with shared bytes; a 32 MiB instance budget (ASSUMED, at most one eighth of the heap) that closes the largest queues; the per-session cap lowered to 256 frames or 256 KiB from a stated peak rate; both in open question 3 and the CI test |
| 3 | MAJOR | The writer table omitted the sink's late-entry close | Fixed in point 6.2: a row for the sink's `TIMED_OUT` / `RESOLVED`, publishing after its batch commits through the non-blocking publisher; the coverage exception narrowed to the INSERT; the sink change listed under M6's changes (point 9); RESOLVE's increment cited to `DecisionOwner.java` instead of ASSUMED |
| 4 | MAJOR | Staff names on `fs.alerts.changes` contradicted ADR 0012 point 4 | Fixed by the owner's decision (2026-10-22): ids only on Kafka (`assigned_analyst_id`, `actor_user_id`, `escalated_by_user_id`); names resolved in the fan-out from a per-institution cache; the contract test compares the id projection (points 6.2, 9) |
| 5 | MINOR | Frames before CONNECT, routing and CONNECT's CSRF left to the framework | Fixed in point 2: normative rules for frames before CONNECT and a second CONNECT, direct writes with no broker or user-destination translation, CSRF on CONNECT off by design, and the three tests |
| 6 | MINOR | The envelope's `institution_id` could not come from a principal for most writers | Fixed in point 4: it is the changed row's `institution_id`, read in the writing tenant transaction, tested per writer |
| 7 | MINOR | Re-authentication replaced only the expiry | Fixed in point 3: `token_version` and `exp` are replaced and must pass `isCurrent`; a different `sid` is refused; the backstop binds to the new claims |
| 8 | MINOR | "The same code as `listAlerts`" impossible; the equivalence test too narrow; the escalator's source unstated | Fixed in point 4: "a second implementation, proven equal by test", over every role, user, all eight statuses via `outcome`, and chains of depth 2; `escalated_by_user_id` is the parent entry's ESCALATE `analyst_id`. The `getAlert` observation is recorded |
| 9 | MINOR | The pushed card must equal the read card; `provisional` and the merge under-specified | Fixed in points 6.1 and 7: required fields present (`merchant: null`, `top_reasons: []`); `anomaly_score` nulled under the fallback by an M6 change; `provisional` defined as "the payload lacked fields the read may have"; a read replaces at an equal version; the field-by-field equality test |
| 10a | MINOR | No 401 | Fixed in point 9: 401 documented through a Reference Object whose own description says the handshake never sends it and the ERROR frame carries it; the test holds unchanged |
| 10b | MINOR | `realtime-overflow` declared by no response | Fixed in point 9: the `RealtimeError` response declares it, and is referenced from `x-realtime` |
| 10c | MINOR | The inline 426 named no problem type | Fixed in point 9: `UpgradeRequired` response with the new type `upgrade-required` |
| 10d | MINOR | Matrix and document disagreed on rules | Fixed in point 9: the operation declares the filter rule, worded like `listAlerts`' |
| 10e | MINOR | `$ref`s inside `x-realtime` unchecked | Fixed in point 9: a new test resolves them; examples of both frame schemas are added |
| 10f | MINOR | `actor_user_id` optional on the frame | Fixed in point 9: required and nullable |
| 10g | MINOR | `info.description` lists four access forms | Fixed in point 9: the fifth is named |
| 10h | MINOR | `AUTO_DECLINED` not in `AlertStatus` | Fixed in point 6.2: cited as a dependency on the decision-owner contract's §11 additions |
| 11 | MINOR | "Asynchronously after the commit" had no mechanism; `send` blocks | Fixed by the owner's decision (2026-10-22), in point 6.2: a dedicated producer, a bounded non-blocking hand-off queue drained by its own thread (drop and count on full), `max.block.ms` 100, and `send` never on request, poller or sink threads |
| 12 | MINOR | Unauthenticated sockets could take every slot; the per-user cap was per instance | Fixed in point 8: separate caps on pre-CONNECT sockets per instance and per IP, so authenticated capacity is reserved; the per-user cap is stated as per instance and accepted; "client IP" is the forwarded address resolved as `Requests` documents |
| 13 | MINOR | `analysts_online` presence not in this ADR; figures triggered locally only | Fixed in points 2 and 6.3: presence written at CONNECT, refreshed, and left to expire when the last local session ends (close, expiry, revocation, overflow, shutdown); recomputes 30 s after a disconnect and every 30 s; the 30 s staleness accepted |
| 14 | MINOR | "Subscribe, then re-read" false while unpositioned and for new partitions; a stalled consumer invisible | Fixed in point 5: ready only after `position()` for every partition (503 until then); new partitions from the earliest offset; lag exported; a failed consumer closes its sessions and stops being ready |
| 15 | MINOR | The resume merge ambiguous under paging; phantom cards omitted; acknowledgement without receipts | Fixed in points 1 and 7: read authority scoped to the window read; the freshness claim restated with phantom and premature cards; `receipt` on SUBSCRIBE, and RECEIPT only after registration |
| 16 | MINOR | ASSUMED values missing from open question 3; the ACL sentence contradicted `topics.yaml` | Fixed: the list in open question 3 is complete, including the 1.5 s backstop (now marked ASSUMED) and the CI test's load; `websocket-fanout` is removed from `fs.notifications.staff`'s consumers and its `fs.ml.retrain` Read is stated as unused until P8 (point 5) |
| 17 | MINOR | The Java authorisation layer not addressed | Fixed in point 9: an `IN_BAND` access kind, never public; `AuthorisationMatrixTest` expects the same handshake answer for every caller, with roles tested at CONNECT; no SockJS, exact path only (point 1) |
| 18 | MINOR | FR-04-10's row should say non-connected users learn at their next read | Fixed in the requirements table |
| Q1 | | Best effort | Decided by the owner (2026-10-22): accepted, with the dedicated producer and bounded queue (finding 11) |
| Q2 | | `merchant` and `top_reasons` on `alert.created` | Added, with the equality test and the M6 changes listed; author's choice on the review's recommendation |
| Q3 | | The ASSUMED numbers | Accepted as starting values after findings 1, 2 and 16; author's choice on the review's recommendation |
| Q4 | | Staff names on Kafka | Decided by the owner (2026-10-22): ids only (finding 4) |
| Q5 | | The 60-second re-read | Decided by the owner (2026-10-22): kept until M10 measures loss (point 1) |

## Review 2's findings and their answers

Review 2 (`docs/reviews/M8B/realtime-channel-proposal-review-2.md`): SOUND_WITH_CHANGES, no MAJOR,
eight MINOR findings and four owner questions.

| # | Severity | Finding | Answer |
|---|---|---|---|
| 1 | MINOR | The backstop's limiter, the Redis-down closure and the push re-check need hooks the auth code does not have | Fixed in point 3 and the `backend/auth` changes of point 9: `isCurrent(claims, LoadGate)` limits database loads only and answers `UNKNOWN` when refused, so the 67 checks a second are never starved; `SafeRedis.available()` from consecutive failed operations (3, ASSUMED) plus a 1 s `PING` (ASSUMED), with the reopening rule; no second pub/sub listener: `SessionStateCache.onMessage` calls the real-time module's change listener after it has invalidated the local entry, so the re-check never reads the stale 1 s tier |
| 2 | MINOR | Publishing a full `AlertSummary` from the TIMEOUT path and the sink crosses a module boundary; the TIMEOUT path is missing from the M6-change list | Fixed in point 6.2: an `AlertChangePublisher` port in `decision`, implemented in `analyst` with `AlertQueries`; the writer offers ids and version after commit, and the publisher re-reads the summary after the commit (not in the writing transaction), with the consequence for the version and `event_id` stated. `DecisionOwner`, `OwnerHoldTimeouts`, the resolver and the sink added to "Changes to M6's code"; point 4 updated |
| 3 | MINOR | Name resolution can put a database transaction on the single consumer thread | Fixed in points 5 and 6.2: a cache miss goes to a name resolver of 2 threads with a 1,000-record queue and a 500 ms timeout (all ASSUMED); the poll thread never opens a transaction; an unresolved record is dropped and counted |
| 4 | MINOR | The frozen contract still says `institution_id` comes from the principal | Fixed in point 9: `envelope.schema.json:36` and `topics.yaml:12-14` gain "or, for an event about a stored row, from that row's `institution_id` read in the writing tenant transaction"; listed in "Required follow-ups" as part of the same contract change |
| 5 | MINOR | The per-IP admission limits fit attackers, not an office behind one address | Fixed in point 8: re-derived from an office of 200 staff (ASSUMED) to 200 pending CONNECTs and 1,000 handshakes a minute per address, with a per-user limit of 10 accepted CONNECTs a minute after authentication; the per-instance pre-CONNECT cap still reserves authenticated capacity; why the allowlist exemption was not chosen (the institution is unknown before CONNECT); the load-balancer requirement stated and listed as a follow-up; a reconnect-storm case added to the CI test |
| 6 | MINOR | The producer side has no memory bound in the budget | Fixed in points 6.2 and 8: the hand-off queue holds fixed-size id tuples (about 1.25 MiB at 10,000), `buffer.memory` is 8 MiB (ASSUMED), and both, with the resolver's queue, are in the one-eighth-of-heap start-up check (about 42 MiB) |
| 7 | MINOR | The presence write is not role-filtered | Fixed in point 2: presence only for ANALYST, SENIOR_ANALYST and RISK_OFFICER sessions, with a unit test; added to the rules P8 must keep (point 10) |
| 8 | MINOR | The new topic extends C.3 and breaks two Kafka tests; the staff topic's purpose still says WebSocket; the DLQ is unused | Fixed in point 9: the fourteenth topic recorded as a deviation from C.3; `C3_TOPICS` and the new example `fs.alerts.changes.json` named; `topics.yaml:1`'s header updated; the staff topic's purpose amended; the unused DLQ stated as harmless |
| Q1 | | Accept the fourteenth topic as a recorded deviation from C.3? | Accepted: the author's choice on review 2's recommendation; the owner may overrule (point 9, open question 6) |
| Q2 | | `merchant` and `top_reasons` on `alert.created` | Accepted: the author's choice on review 2's recommendation; the owner may overrule (point 6.1, open question 2) |
| Q3 | | Close the whole channel while Redis is down | Accepted: the author's choice on review 2's recommendation; the owner may overrule (point 3, open question 7) |
| Q4 | | The ASSUMED numbers | Accepted as starting values, except the per-IP limits, re-derived for office NAT (finding 5): the author's choice on review 2's recommendation; the owner may overrule (open question 3) |
| r1-17 note | | `Access`'s "exactly one kind" check must accept roles with in-band | Fixed in point 9's `backend/auth` changes |
