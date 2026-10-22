# 0116: Alert reassignment

- **Status:** Proposed (M8B, 2026-10-22), revised after review 1
  (`docs/reviews/M8B/reassignment-proposal-review-1.md`) and review 2
  (`docs/reviews/M8B/reassignment-proposal-review-2.md`), for re-review before any code. It adds two
  operations to the frozen contract (`assignAlert` and `listAssignableStaff`) and changes
  `AlertSummary`, `AlertDetail` and `listAlerts`.
- **Date:** 2026-10-22
- **Requirements affected:** UX-ROLE-SN-02, UX-ROLE-SN-01, UX-ROLE-AN-01 (left open, and newly unmet),
  FR-04-10, D-29
- **ADRs referenced:** 0014, 0070, 0110, 0112, 0114, 0115, 0117
- **Sources:** M8B scope P2-g and §7 point 3; `M8B_updates.md` item 3; the register's rows as on
  `m8/frontend-rebase`, quoted in full where this ADR relies on them:
  - UX-ROLE-SN-02: "Additional to ANALYST: Reassign any alert to any on-duty analyst; self-assign
    from unassigned pool; Cannot Do: Cannot override RISK_OFFICER decisions; Key MUI Components: MUI
    Select (analyst dropdown) inline in alert card";
  - UX-ROLE-SN-01: "Additional to ANALYST: View every alert regardless of assignment; see unassigned
    pool; filter by assigned analyst; Cannot Do: Cannot configure thresholds or model versions; Key
    MUI Components: MUI DataGrid with 'Assigned To' column; analyst autocomplete filter";
  - UX-ROLE-AN-01: "Can Do: View own assigned alerts; filter by risk tier, date, status; real-time
    updates; Cannot Do: Cannot see alerts assigned to other analysts; cannot see unassigned pool; Key
    MUI Components: MUI DataGrid (virtualised); MUI Chip (risk tier); MUI CircularProgress (score)";
  - UX-ROLE-RO-01: "Additional to SENIOR_ANALYST";

  V4's `alert_queue_entries.assigned_analyst_id` and `assigned_at`; the decision-owner contract §3,
  §5, §9 and §10; ADR 0112 point 5 (`analyst_id`); D-32 (`docs/srs/defect_register.md`).

## Context

**What the SRS asks for.** UX-ROLE-SN-02 lets a senior analyst reassign an alert to an analyst, and
take one from the unassigned pool. No contract operation does either.

**What the schema has.** V4 gave every queue entry `assigned_analyst_id` and `assigned_at`, with a
composite foreign key to a user of the same institution. Nothing writes them:
- no code assigns an alert when it is raised;
- the status `IN_REVIEW` is never set;
- P1's `listAlerts` matches `analyst_id` against "assigned to, or decided by" (ADR 0112 point 5). It
  matches **any** `alert_decisions` row with that `analyst_id` (`AlertQueries.java`), an undone one
  included, so in practice it means "decided by, ever".

**What the SRS implies, and why it cannot work today.** UX-ROLE-AN-01 says an ANALYST sees only
their own assigned alerts, never alerts assigned to other analysts, and never the pool. Read with
SN-02, work reaches an analyst only by being assigned.
- **No auto-assignment rule exists,** and none is derivable from the sources: no shift roster, no
  capacity model (D-10's is not written).
- **Assignment as an access rule would therefore hide every new alert from every ANALYST** until a
  senior assigned it by hand.
- **MEDIUM holds would then time out unseen.** This proposal does not take that step (point 2).

## Decision

### 1. One write operation: `assignAlert`

`PUT /api/v1/alerts/{alert_id}/assignment`, with `Idempotency-Key`. The body is:

```json
{ "assignee_user_id": "6b1f…", "alert_version": 4 }
```

- **`assignee_user_id`** is the user to assign, which may be the caller (self-assignment from the
  pool), or `null` to return the entry to the unassigned pool.
- **The answer is 200** with the entry's `AlertSummary` as it now stands, carrying the new
  `assigned_analyst` and `version`.

**One operation covers all three of SN-02's actions:** reassigning, self-assigning and unassigning.
Unassigning is not in the SRS. It is included because it is the same write with a null, and because
without it a mistaken assignment can only be moved to someone else.

### 2. What an assignment means: advisory, not an access rule (decided by the owner, 2026-10-22)

**The owner decided on 2026-10-22 that assignment is ADVISORY.** An assignment records who is
expected to work the entry. It:
- shows on every card (`assigned_analyst`, point 6);
- drives `listAlerts`' assignment filters (point 7);
- feeds ADR 0117's `average_seconds_from_assignment` (ADR 0117 point 3).

**It does not change who may see or decide the entry:**
- `listAlerts`, `getAlert` and the real-time channel (ADR 0115 point 4) show the entry to exactly the
  callers they show it to today;
- `decideAlert`, `escalateAlert` and `overrideAlertDecision` keep their rules. An analyst who is not
  the assignee can still decide.

**Why advisory.**
- **Binding is not safe today.** As an access rule, an assignment would need an auto-assignment
  rule (Context), or HELD MEDIUM entries time out unseen. It would also need a new `decideAlert`
  refusal, `not-assignee`, which is a change to an existing operation's rules. Neither is derivable
  from the sources.
- **Advisory delivers what can be delivered now.** It meets SN-02 and most of SN-01 (the
  requirements table says what stays open).

**What advisory costs: UX-ROLE-AN-01's restriction stays OPEN, and this change makes it newly
unmet.** AN-01's Cannot-Do is, in full: "Cannot see alerts assigned to other analysts; cannot see
unassigned pool".
- **Today the first half is vacuous:** no entry is assigned, so no ANALYST can see an alert
  assigned to someone else. The second half is already unmet, since every ANALYST sees every open
  entry, and all of them are in the pool.
- **This change makes the first half real and unmet.** Every ANALYST will see:
  - `assigned_analyst` on every card, so a colleague's assignment is visible;
  - `listAlerts?analyst_id=<colleague>`, which lists a colleague's assigned alerts;
  - `listAlerts?assignment=unassigned`, which lists the pool by name.
- **The reason it stays open** is the reason above: enforcing it needs an auto-assignment rule that
  no source gives, and without one every new alert would be hidden from every ANALYST.
- **What it means for the register.** AN-01 cannot leave IN_PROGRESS while assignment is advisory.
  Its note already says that "'own assigned alerts' only … is the server's to enforce in
  listAlerts (M8B)", and this ADR does not enforce it.

**Required follow-up, not made by this ADR:** `docs/parallel/M8B_scope.md` §3's table (line 208, the
P1 row) lists UX-ROLE-AN-01 among the IN_PROGRESS rows **P1 releases**. That is no longer possible
under advisory assignment. The row must be corrected, by removing AN-01 from P1's releases and
recording it as open with this ADR's reason, so that the scope and the register agree. This ADR
does not edit the scope document; the correction is listed in point 10 and must land with this
ADR's acceptance.

### 3. Who may assign, and to whom

**Roles:** SENIOR_ANALYST and RISK_OFFICER.
- **Seniors:** UX-ROLE-SN-02.
- **Risk officers:** "Additional to SENIOR_ANALYST" (UX-ROLE-RO-01).
- **Not ANALYST.** SN-02's abilities are "additional to ANALYST", so an analyst cannot self-assign.
- **Not ADMIN.** No alert operation admits ADMIN in the authorisation matrix today
  (`test_admin_never_decides_alerts` checks every `/alerts` path).

**Object-level rules.** Ranks are ANALYST < SENIOR_ANALYST < RISK_OFFICER (E.8):

| # | Rule | Problem |
|---|---|---|
| A1 | An escalated entry can be assigned only by a caller whose role is at or above its target role, the same rule as deciding it | 403 `escalation-level-required` |
| A2 | The assignee must be an **on-duty** user (status ACTIVE or LOCKED, below) of the caller's institution with role ANALYST, SENIOR_ANALYST or RISK_OFFICER, ranking **at or below the caller**, and, for an escalated entry, **at or above its target role** | 422 `validation`, code `assignee_not_eligible` |
| A3 | An entry assigned to an on-duty user (ACTIVE or LOCKED) whose **current** role ranks above the caller cannot be reassigned or unassigned by that caller | 403 `assignment-held-by-higher-role` |
| A4 | The entry must be open (PENDING or IN_REVIEW) | 409 `stale-alert`, with the current alert |
| A5 | `alert_version` must equal the entry's `version` | 409 `stale-alert`, with the current alert |
| A6 | Assigning the user already assigned (or null to an unassigned entry) | 422 `validation`, code `no_change` |

**The order of checks is fixed,** so that the status does not depend on the implementation when
several rules fail. After the request body is validated (400):
1. 404 when the entry is not in the caller's institution;
2. idempotency (point 5): a replay answers 200, a reused key with another fingerprint 409
   `idempotency-conflict`;
3. A1 (403);
4. A3 (403);
5. A4, then A5 (409 `stale-alert` with the current alert);
6. A2 (422);
7. A6 (422).

An escalation-level refusal therefore comes before any 409 that carries `current`, as in
`decideAlert` (decision-owner contract D1(a) to (d)). A later tightening of `getAlert`'s visibility
can rely on that order.

**Where the sources stop and assumption begins.**
- **A1 is derived:** it is `decideAlert`'s rule, applied to the same entries.
- **A2's upper bound, "at or below the caller", is ASSUMED.** Moving work to a higher role is what
  escalation is for (FR-04-10), and escalation records a reason; an assignment would move work up
  without one.
- **A3 is ASSUMED,** by analogy with SN-02's "cannot override RISK_OFFICER decisions": a senior
  should not take back work a risk officer has taken.
- **That `status IN ('ACTIVE', 'LOCKED')` stands in for "on-duty" is ASSUMED** (the author's choice
  on review 2's recommendation; the owner may overrule). No roster or shift data exists, so
  "on-duty" (SN-02) cannot be checked. The set is the one that can hold a working session:
  `SessionService.canHoldSession` (`SessionService.java:248-250`) admits exactly ACTIVE and LOCKED.
  - **Why LOCKED is included.** The first revision said "a user locked after failed sign-ins cannot
    work an entry until unlocked". That is false in the code: a lock after failed sign-ins sets
    `status = LOCKED` and `locked_until`, but does not touch `token_version` or the refresh tokens
    (`AuthenticationService.java:207-217`, `StaffUserJpaRepository.java:96-103`), so the user's
    open console keeps working and they can still decide alerts. The status also stays LOCKED after
    `locked_until` has passed, until the next sign-in attempt or an unlock
    (`AuthenticationService.java:171-177`). And sign-in takes only an e-mail (V2:34), so anyone who
    knows a staff e-mail can put the account into LOCKED by failing its password (D-26). Excluding
    LOCKED would make a working analyst un-assignable, and would strip A3's protection from a risk
    officer's entries, at the will of an unauthenticated party.
  - **What including it costs, stated.** An administrator's lock also sets LOCKED, and it revokes
    the user's sessions. Such a user stays eligible under A2 and keeps A3's protection although they
    cannot work until unlocked. Assignment is advisory, so the entry stays visible and decidable by
    everyone who could decide it before; a senior or risk officer at or above the assignee's rank
    can reassign it.
  - **DEACTIVATED and PENDING_APPROVAL users are never on duty:** neither can hold a session.
  - **What the console may show instead.** It may mark which eligible users are online. That is the
    presence of ADR 0117 point 4, which this operation does not consult.
- **That "any on-duty analyst" includes SENIOR_ANALYST and RISK_OFFICER assignees is ASSUMED.**
  SN-02 says "analyst"; the ranks above an analyst can do everything an analyst can
  ("additional to"), so they can work an assigned entry too.
- **These two rank rules narrow SN-02's "any alert" and "any analyst".** They are recorded as
  deviations in SN-02's requirements row (below), not only here. Accepting both is the author's
  choice on the review's recommendation; the owner may overrule.

**Role and status changes during and after an assignment.**
- **A2 reads the assignee's `users` row `FOR SHARE`** (lock level 6, point 4). An administrator's
  concurrent role change or deactivation either commits first, and A2 sees it, or waits until the
  assignment commits. An entry is therefore never assigned to a user who was ineligible at commit.
  (A failure lock is a concurrent change too, but it does not change eligibility, since LOCKED is
  on duty.)
- **A later demotion or deactivation does not undo an assignment.** Assignment is advisory, so the
  entry stays assigned to that user until someone reassigns it. This is accepted, and stated: an
  ANALYST can be left holding an entry escalated to SENIOR_ANALYST, or a DEACTIVATED user can hold
  an entry.
- **A3 uses the assignee's current role and status.** An assignee who is no longer on duty
  (DEACTIVATED) does not hold the entry against A3, so any senior or risk officer can free it.
  Without that rule, a deactivated risk officer's entries could be freed only by another risk
  officer. A LOCKED risk officer still holds their entries, so a failure lock caused by an outsider
  hands no senior a lever over them.
- **Known limitation.** An entry assigned to a user who is no longer on duty is not in
  `assignment=unassigned`. The console can find such entries by the assignee's name on the card, or
  by `listAlerts?analyst_id=` with an id from `listAssignableStaff?purpose=filter` (point 8), which
  lists them with their status; no filter selects all of them at once. That filter is left out
  (point 9).

**Users of another institution are invisible.** Row-level security hides them, and V4's composite
foreign key `(assigned_analyst_id, institution_id)` refuses them even if the check in code is
wrong. A request for one is answered by A2 as `assignee_not_eligible`, never a different status.
The same answer is given for an id that is unknown, in another institution, not on duty or
ineligible, so the answer reveals nothing about other institutions.

### 4. The decision-owner contract's claim, and PENDING_COMMIT

**Reassignment never touches the owner row.** It does not take or release a claim, and it does not
change a hold's deadline. It writes only the entry's assignment columns and `version`, its history
row and its audit event.

**Locks.** It takes, in this order, levels 1, 2, 6 and 7 of the contract's lock order (§3), and
never levels 3 to 5:
- level 1, the entry `FOR UPDATE`;
- level 2, the entry's advisory lock;
- level 6, the assignee's `users` row `FOR SHARE` (A2), and the foreign-key share locks on `users`
  from the entry's and the history row's foreign keys;
- level 7, the audit chain head, for its audit event.

The order is ascending and nothing reverses it.
- **It serialises with every operation on the same entry:** D1, D2, D5 and D6, and a TIMEOUT or a
  RESOLVE closing it.
- **It cannot deadlock with them.** It never waits for a lower level while holding a higher one,
  and it never takes levels 3 to 5.

**An entry whose decision is PENDING_COMMIT is not open.** D1 has already set its status to
CONFIRMED_FRAUD or MARKED_LEGITIMATE (§9 D1(f)). The rules give:
- **Assigning it** answers 409 `stale-alert` with the current alert (A4). The analyst's claim is
  untouched.
- **If the decision is undone** (D2), the entry is open again with its previous status, a new
  `version`, and the assignment it had. It can then be reassigned.
- **If the decision commits,** the entry stays closed and can never be reassigned.

**Every other closed entry answers A4 the same way.** That covers ESCALATED (its successor is a new,
unassigned entry, which can be assigned), OVERRIDDEN, AUTO_RELEASED and AUTO_DECLINED.

**A held MEDIUM entry past its deadline,** before the poller's TIMEOUT has closed it, is still open
and can be assigned. That is harmless: the TIMEOUT closes it whatever its assignment.

**A contested transaction's entries** can be assigned. A contest changes the owner, not the entry.

### 5. `version`, idempotency, audit and history

**`version + 1` on every assignment.** This is the author's choice on the review's recommendation;
the owner may overrule.
- **Why.** `version` is how a client knows the entry it acted on is the entry it saw (D-29), and the
  real-time channel orders snapshots by `version` alone (ADR 0115 G2). Without an increment, an
  `ASSIGNED` event would be discarded as a duplicate, or the channel would need a second counter.
- **The consequence.** An analyst whose decision races a reassignment gets 409 `stale-alert` with the
  current alert, which shows the new assignee. Because assignment is advisory, they may re-submit
  with the new `version`.
- **The cost, near a MEDIUM deadline.** A reassignment shortly before a MEDIUM hold's
  `review_deadline_at` can make an analyst's in-flight decision fail with 409. If the analyst does
  not re-submit before the deadline, the TIMEOUT closes the entry and the analyst's decision is
  lost. The console must therefore show the new assignee and offer an immediate one-click
  re-submit with the new `version` on that 409; a senior reassigning an entry close to its deadline
  should expect this.
- **The alternative,** not incrementing `version`, would let the decision through, but would need a
  second ordering counter for the channel.

**Idempotency** follows D1(b) of the decision-owner contract, with these rules:
- **The same key, caller, alert and request fingerprint** return 200 with the entry as it now
  stands, whatever has happened since. This is D6's rule for its 201 (decision-owner contract D6).
- **The same key with anything else** is 409 `idempotency-conflict`.
- **A replay of a successful request** therefore never trips A5 or A6.
- **The key's scope is this operation.** Assignment keys live in `alert_assignments`, unique per
  institution. Decide, escalate and override keep theirs in `alert_decisions`. A key reused across
  `decideAlert` and `assignAlert` is therefore not detected: each operation sees only its own keys.
  The contract's `IdempotencyKey` already requires a key "unique per logical action", so reusing one
  across two actions is a client error that the server does not catch. Checking across both tables
  was rejected, because it would read `alert_decisions` under a lock order this operation does not
  otherwise need.
- **A concurrent request with the same key on a different alert.** The second insert fails on the
  unique key; the transaction rolls back and re-runs once, as D1(b) does. The re-run finds the key
  with a different fingerprint and answers 409 `idempotency-conflict`.
- **A refusal records no key.** A request refused with 403, 404, 409 or 422 writes nothing, so a
  later request with the same key is evaluated afresh.

**History: a new append-only, tenant-isolated table, `alert_assignments`.**
- **Why a table, when the audit event records the same before and after.** It is needed for two
  things the audit log cannot serve:
  - it stores the idempotency key and the request fingerprint, which the replay rule above reads;
  - it is a queryable assignment history, which ADR 0117 reads for
    `average_seconds_from_assignment` (the earliest assignment of an entry to the decider by
    another user).
- **One row per applied assignment:**
  - the entry, the previous assignee (nullable), the new assignee (nullable) and the caller;
  - `assigned_at` (DB time, `clock_timestamp()` in a BEFORE INSERT trigger);
  - `idempotency_key`, with `UNIQUE (institution_id, idempotency_key)`, and the request
    fingerprint.
- **Constraints, as V4 does it:** composite foreign keys `(…, institution_id)` to `users` for the
  previous assignee, the new assignee and the caller, and
  `(alert_queue_entry_id, institution_id)` to `alert_queue_entries`; an index on
  `(institution_id, alert_queue_entry_id, assigned_at)` for ADR 0117's lookup.
- **Grants and isolation:** `CALL make_append_only('alert_assignments')`,
  `CALL enable_tenant_isolation('alert_assignments')`, and `GRANT SELECT, INSERT` to `fs_app` only,
  as V11 grants its append-only tables. `DatabaseSecurityTest` gains a case: `fs_app` cannot UPDATE
  or DELETE a row, and cannot read or write another institution's rows.
- **The migration.** The entry's `assigned_analyst_id` and `assigned_at` are updated in the same
  transaction. The migration number is claimed at implementation from M8B's range (V80 to V89; V81
  and V82 are taken), so it is written here as V8x, claimed in `M8B_updates.md` before use (X-1).

**Audit: `ANALYST_DECISION`, with action `ASSIGNED` or `UNASSIGNED`.** One audit event per applied
assignment, in the same transaction. This is the author's choice on the review's recommendation;
the owner may overrule.
- **Its fields:** `event_type` ANALYST_DECISION, `action` `ASSIGNED` (or `UNASSIGNED` for a null
  assignee), `entity_type` `alert_queue_entry`, `before_value` and `after_value` holding the assignee
  ids.
- **Why not a thirteenth type.** D-32 fixes exactly twelve types
  (`docs/srs/defect_register.md`), V8's CHECK lists twelve, and the contract's `AuditEventType`
  says "The twelve audit event types". A thirteenth would need a D-32 amendment. D-32 already
  groups sub-actions under one type ("incl. escalation, undo").
- **The consequence for counting.** A count of decisions taken from the audit log must now filter
  by `action`, because ANALYST_DECISION also carries assignments. FR-06-06's assertions, and any
  other test or report that counts ANALYST_DECISION events, must filter out `ASSIGNED` and
  `UNASSIGNED`.

**The real-time channel.** After the commit, the change is published as `ASSIGNED` on
`fs.alerts.changes` (ADR 0115 point 6.2), with ids only (ADR 0115 point 9).

### 6. `AlertSummary` gains `assigned_analyst`

```yaml
    AlertSummaryBase:
      required: [..., assigned_analyst]       # added to the existing list
      properties:
        assigned_analyst:
          description: >-
            The staff member expected to work this entry, or null for the unassigned pool
            (ADR 0116). Advisory: it does not restrict who may see or decide the entry.
          $ref: "#/components/schemas/StaffReference"   # already [object, "null"]
```

**Lockstep.** `assigned_analyst: true` is added to the closed property lists of `AlertSummary` and
`AlertDetail`. Adding a required response field breaks the console's generated client until it is
regenerated, so the console and the server ship in lockstep (as ADR 0110's consequences).

**Examples.** Every example that carries an `AlertSummary`-shaped value gains `assigned_analyst`
(one null, one a `StaffReference`), or the examples test fails:
- `schema-examples.yaml`'s `AlertSummary`, `AlertDetail` and `AlertPage`;
- `StaleAlertProblem` (`schema-examples.yaml:1170-1208`), whose `current` is an `AlertSummary`;
  without it `test_example_is_valid_so_the_schema_is_satisfiable[StaleAlertProblem]`
  (`test_openapi_examples.py:84-88`) fails;
- with ADR 0115, the new `RealtimeAlertEvent` example's `alert` (ADR 0115 point 9).

The implementer checks every example whose schema reaches `AlertSummaryBase`, rather than relying on
this list alone.

**NFR-SEC-03.** The inspector already accepts `StaffReference` in `DecisionRecord.analyst`. It must
accept it here too: staff names in an HTTP response are not customer personal data. This concerns
HTTP responses only. Kafka events carry staff ids, never names (ADR 0012 point 4; ADR 0115 point 9).

### 7. What `listAlerts`' `analyst_id` means, and the pool

**`analyst_id` is narrowed to match decisions that are not undone.** It selects entries **currently
assigned to** that user, **or** with a decision by that user that has **not been undone** (no
`alert_decision_undos` row).
- **Why the change.** P1's query matches any decision row, an undone one included. So after X
  decides an entry and undoes it, and a senior reassigns it to Y, `listAlerts?analyst_id=X` would
  still list it, though it is not assigned to X (review 1, finding 1).
- **Without `outcome`, only open entries are listed.** An open entry has no decision that is not
  undone: a committed or PENDING_COMMIT decision closes it, an escalation closes it, and an override
  acts on a closed entry. So, without `outcome`, the filter means exactly "assigned to them".
- **Decisions later overridden still match.** The review's option (a) would also exclude them. They
  are kept, because an overridden entry is closed and so never appears without `outcome`, and a
  search for X's overridden work (`outcome=OVERRIDDEN&analyst_id=X`) should find it.
- **This is a change to P1's query** (`AlertQueries`), made with this ADR's code. A test seeds a
  decision, undoes it, reassigns the entry to another user, and asserts that the first user's
  `analyst_id` filter does not list it; it fails against P1's current query.

**A new optional parameter, `assignment`,** selects the pool (UX-ROLE-SN-01):

```yaml
        - name: assignment
          in: query
          description: >-
            `unassigned`: entries with no assigned analyst (the pool). `assigned`: entries with one.
            Omitted: both. With `analyst_id`, only `assigned` is accepted; `unassigned` answers 422
            `validation`, code `unsupported_value`, field `assignment`.
          schema: { type: string, enum: [assigned, unassigned] }
```

- **That `assignment` combined with `analyst_id` is refused except as `assigned` is ASSUMED.** The
  combination `unassigned` + `analyst_id` could only list entries that user decided, which is a
  confusing query; refusing it is simpler than defining it. The refusal uses the existing code
  `unsupported_value`, which is already a 422 in `x-status-by-code`.

**The `analyst_id` description** becomes:

```yaml
        - name: analyst_id
          in: query
          description: >-
            Entries currently assigned to this staff user, or carrying a decision by them that has
            not been undone. Without `outcome` a queue lists open entries only, and an open entry has
            no such decision, so this is "assigned to them".
          schema: { type: string, format: uuid }
```

**What does not change:** visibility (point 2), and the ANALYST's access to the whole feed.

### 8. The operations

**`assignAlert`** (the snippet is split into its `paths` and `components/schemas` parts, so it
parses as it will be merged):

```yaml
paths:
  /alerts/{alert_id}/assignment:
    put:
      tags: [alerts]
      operationId: assignAlert
      summary: Assign an alert to an analyst, take it from the pool, or return it to the pool (UX-ROLE-SN-02)
      description: |
        Advisory: sets who is expected to work the entry; it does not change who may see or decide it.
        Only open entries (PENDING, IN_REVIEW) can be assigned; an entry whose decision is
        PENDING_COMMIT is not open, and an undo returns it open with its previous assignment. Never
        touches the transaction's decision owner or an analyst's claim. Increments `version`.
        Checks run in this order: 404; idempotency; escalation level; assignment held by a higher
        role; open and current (409 stale-alert with the current alert); assignee eligibility (422);
        no change (422). Replays with the same Idempotency-Key, caller, alert and fingerprint return
        200 with the entry as it now stands; the same key with anything else is 409
        idempotency-conflict. Keys are scoped to this operation. A refused request records no key.
      x-required-roles: [SENIOR_ANALYST, RISK_OFFICER]
      x-authorisation-rules:
        - rule: Callers rank ANALYST < SENIOR_ANALYST < RISK_OFFICER (E.8). An escalated entry can be assigned only by a caller whose role is at or above its target_role
          problem: urn:fraudshield:problem:escalation-level-required
          status: 403
        - rule: An entry assigned to an ACTIVE or LOCKED user whose current role ranks above the caller cannot be reassigned or unassigned by the caller
          problem: urn:fraudshield:problem:assignment-held-by-higher-role
          status: 403
        - rule: The assignee must be an ACTIVE or LOCKED ANALYST, SENIOR_ANALYST or RISK_OFFICER of the caller's institution, ranking at or below the caller and, for an escalated entry, at or above its target_role
          problem: urn:fraudshield:problem:validation
          code: assignee_not_eligible
          status: 422
        - rule: The entry must be open and alert_version current; otherwise the current alert is returned
          problem: urn:fraudshield:problem:stale-alert
          status: 409
      parameters:
        - $ref: "#/components/parameters/AlertId"
        - $ref: "#/components/parameters/IdempotencyKey"
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/AlertAssignmentRequest" }
      responses:
        "200":
          description: Assigned; the entry as it now stands
          content:
            application/json:
              schema: { $ref: "#/components/schemas/AlertSummary" }
        "400": { $ref: "#/components/responses/ValidationProblem" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/AuthorisationRuleViolation" }
        "404": { $ref: "#/components/responses/NotFound" }
        "409": { $ref: "#/components/responses/StateConflict" }
        "422": { $ref: "#/components/responses/ValidationProblem" }
        "503": { $ref: "#/components/responses/ServiceUnavailable" }
        default: { $ref: "#/components/responses/UnexpectedProblem" }
components:
  schemas:
    AlertAssignmentRequest:
      type: object
      required: [assignee_user_id, alert_version]
      additionalProperties: false
      properties:
        assignee_user_id: { type: [string, "null"], format: uuid, description: Null returns the entry to the pool }
        alert_version: { type: integer, minimum: 0 }
```

**`409` covers more than `stale-alert`.** It also carries `idempotency-conflict`, which
`StateConflict.x-problem-types` **already declares** at `6a45ed2` (`fraudshield-api.yaml:2673`,
beside `decision-contested` and `decision-effect-failed`, added with the decision-owner contract's
§11). This ADR therefore adds nothing to `StateConflict`; the first revision's "whichever lands
first" was stale. `IdempotencyConflict` (the response) is not reused, because its description is
specific to FR-01-03's `transaction_id`.

**503** is the runner's lock or statement timeout (decision-owner contract §2).

**`listAssignableStaff`: the assignee list for the dropdown, and the people list for the filter.**
Keeping it is the author's choice on review 2's recommendation, with review 2's filter mode; the
owner may overrule. Without it, SN-02's "analyst dropdown" cannot be built honestly: the only staff
list today is `listUsers`, which is ADMIN-only and returns the full `StaffUser`, e-mail included.
The names the console has already seen (in `AlertDetail.decisions` and `assigned_analyst`) are not
the list of eligible users.

**Two modes, because the dropdown and the filter need different people.**
- **`purpose=assign`** (the default): the users the caller may assign, under A2: on-duty (ACTIVE or
  LOCKED) users with a decision role, ranking at or below the caller; with `alert_id`, only those A2
  admits for that entry.
- **`purpose=filter`**: every user of the caller's institution whose **current** role is ANALYST,
  SENIOR_ANALYST or RISK_OFFICER, **whatever their status and rank**, with their `status`. SN-01's
  "analyst autocomplete filter" is a filter, not the assignment dropdown, so it needs the names of
  everyone an entry can be assigned to: a risk officer who self-assigned (seen by a senior), and a
  user later deactivated (point 3), whom the assign mode never lists. `alert_id` is refused in this
  mode (422 `validation`, code `unsupported_value`, field `alert_id`).
- **Neither mode returns e-mail.** `status` is returned only in the filter mode, where the console
  needs it to mark users who can no longer work an entry; the assign mode lists only on-duty users,
  and does not say which of them is LOCKED.
- **What the filter mode still misses, stated.** A user whose current role is no longer a decision
  role (made ADMIN after an assignment) is not listed. Such an entry is found by the assignee's name
  on its card. This is rare (an ADMIN is not a decision role) and is left open.

```yaml
paths:
  /team/assignees:
    get:
      tags: [alerts]
      operationId: listAssignableStaff
      summary: Staff the caller may assign alerts to, or may filter alerts by (UX-ROLE-SN-02, UX-ROLE-SN-01)
      description: |
        purpose=assign (default): users of the caller's institution with status ACTIVE or LOCKED
        and role ANALYST, SENIOR_ANALYST or RISK_OFFICER, ranking at or below the caller (ADR 0116
        rule A2). With `alert_id`, only the users A2 admits for that entry (for an escalated entry,
        also at or above its target role). Returns id, names and role; never e-mail or status.
        purpose=filter: every user of the caller's institution whose current role is ANALYST,
        SENIOR_ANALYST or RISK_OFFICER, whatever their status or rank, with id, names, role and
        status, for the analyst filter; `alert_id` is refused (422 validation, unsupported_value).
        Never e-mail. Ordered by last name, then first name, then user_id.
      x-required-roles: [SENIOR_ANALYST, RISK_OFFICER]
      x-authorisation-rules:
        - rule: With purpose=assign, lists only ACTIVE or LOCKED users whose role ranks at or below the caller's role; with purpose=filter, lists users of the caller's institution with a decision role only
          effect: filter
      parameters:
        - { name: alert_id, in: query, schema: { type: string, format: uuid } }
        - name: purpose
          in: query
          description: "`assign` (default): the users the caller may assign. `filter`: every decision-role user, with status, for the analyst filter"
          schema: { type: string, enum: [assign, filter], default: assign }
      responses:
        "200":
          description: The users the caller may assign, or may filter by
          content:
            application/json:
              schema: { $ref: "#/components/schemas/AssignableStaffList" }
        "400": { $ref: "#/components/responses/ValidationProblem" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }
        "422": { $ref: "#/components/responses/ValidationOrLimitProblem" }
        default: { $ref: "#/components/responses/UnexpectedProblem" }
components:
  schemas:
    AssignableStaffList:
      type: object
      required: [items]
      additionalProperties: false
      properties:
        items:
          type: array
          maxItems: 1000
          description: Each `user_id` appears once (`x-unique-key`)
          x-unique-key: [user_id]
          items: { $ref: "#/components/schemas/AssignableStaff" }
    AssignableStaff:
      type: object
      required: [user_id, first_name, last_name, role]
      additionalProperties: false
      properties:
        user_id: { type: string, format: uuid }
        first_name: { $ref: "#/components/schemas/PersonName" }
        last_name: { $ref: "#/components/schemas/PersonName" }
        role: { type: string, enum: [ANALYST, SENIOR_ANALYST, RISK_OFFICER] }
        status:
          type: string
          enum: [PENDING_APPROVAL, ACTIVE, LOCKED, DEACTIVATED]
          description: Present only with purpose=filter; absent with purpose=assign
```

- **The path is under `/team`, not `/alerts`,** so it cannot collide with `/alerts/{alert_id}`.
- **The 1,000 bound** matches ADR 0117's team table, and that 1,000 is far above any institution's
  team is ASSUMED, as there. An institution with more matching staff is answered, not truncated,
  with 422 `result-too-large` through the response `ValidationOrLimitProblem`.
- **That response is ADR 0117's** (point 8 there: `ValidationOrLimitProblem`,
  `ResultTooLargeProblem` and the `result-too-large` value of `ProblemType`). So that this ADR does
  not depend on 0117's timing: **0116's contract change merges with or after 0117's**; if 0117 is
  delayed, 0116 carries those three definitions verbatim from ADR 0117 point 8, and 0117 then adds
  nothing. They are never defined twice in different words.
- **The filter mode serves SN-01's "analyst autocomplete filter",** for the roles that hold SN-01;
  the assign mode serves SN-02's dropdown.

**Contract catalogue additions:**
- `ProblemType` and `AuthorisationRuleViolation.x-problem-types` gain
  `urn:fraudshield:problem:assignment-held-by-higher-role`;
- nothing for `StateConflict`: `idempotency-conflict` is already declared there (above);
- `ValidationErrorCode` gains `assignee_not_eligible`, with status 422 in `x-status-by-code`;
- `schema-examples.yaml` gains `AlertAssignmentRequest` and `AssignableStaffList` examples (the
  examples test requires one for every object schema an operation uses); the `AssignableStaffList`
  example has one item without `status` (assign mode) and is accompanied by a second example in the
  filter mode's shape, with `status`, so both shapes are validated;
- `test_every_staff_name_field_uses_the_person_name_rule` gains `AssignableStaff`;
- `test_unique_keys_name_properties_of_their_items` counts one more keyed array (`items`). At
  `11448ce` the count is 6; ADR 0117 adds two, so with both ADRs it becomes 9.

**The authorisation matrix:**

```yaml
  assignAlert:
    roles: [SENIOR_ANALYST, RISK_OFFICER]
    ref: "UX-ROLE-SN-02 (reassign, self-assign from the pool); RISK_OFFICER per UX-ROLE-RO-01 'additional to SENIOR_ANALYST'; advisory (ADR 0116)"
    rules:
      - { problem: escalation-level-required, status: 403 }
      - { problem: assignment-held-by-higher-role, status: 403 }
      - { problem: validation, status: 422, code: assignee_not_eligible }
      - { problem: stale-alert, status: 409 }
  listAssignableStaff:
    roles: [SENIOR_ANALYST, RISK_OFFICER]
    ref: "UX-ROLE-SN-02 analyst dropdown (purpose=assign), UX-ROLE-SN-01 analyst autocomplete filter (purpose=filter); RISK_OFFICER per UX-ROLE-RO-01; no e-mail (ADR 0116)"
    rules:
      - { effect: filter }
```

`test_object_level_rules_are_declared_with_documented_problems` gains `assignAlert`: each rule's
problem is in the catalogue, its status is documented, and the response it points to declares the
problem.

### 9. Deliberately left out

- **Assignment as an access rule** (point 2).
- **Auto-assignment** (round robin, by load, by skill). No rule is derivable from the sources.
- **The `IN_REVIEW` status.** Assignment does not set it, and its meaning stays undefined, as today.
  Coupling "assigned" to IN_REVIEW would make an undo's `prior_status` carry assignment state.
- **A reason for reassignment.** The SRS asks for none, and the audit event records who, whom and
  when.
- **Bulk reassignment.**
- **A filter for entries assigned to users who are no longer on duty** (point 3's known
  limitation).
- **Notifying the assignee,** beyond the real-time `ASSIGNED` event, which reaches them if they are
  connected.

### 10. Required follow-ups outside this ADR

- **`docs/parallel/M8B_scope.md` §3, line 208 (the P1 row):** remove UX-ROLE-AN-01 from "IN_PROGRESS
  rows it releases", and record that it stays open under advisory assignment (point 2). This ADR
  does not edit that file.
- **The register's UX-ROLE-AN-01 note** (on `m8/frontend-rebase`): record that the restriction is
  left open by the owner's decision of 2026-10-22, with this ADR's reason.
- **Merge order with ADR 0117:** this ADR's contract change merges with or after ADR 0117's
  `ValidationOrLimitProblem`, `ResultTooLargeProblem` and `result-too-large`, or carries them
  verbatim (point 8).
- **ADR 0117's description of LOCKED** (`0117:430-431`, "temporary … 30 minutes") is corrected in
  ADR 0117 itself: a failure lock lasts until the next sign-in attempt or an unlock (point 3).

## Requirements: closed, and left open

| Row | What this does | What stays open |
|---|---|---|
| UX-ROLE-SN-02 | Reassign, self-assign and unassign (`assignAlert`), and the dropdown's list (`listAssignableStaff`, `purpose=assign`) | **Deviations from "any alert to any on-duty analyst":** A2's upper bound (no assigning to a role above the caller's) and A3 (no taking work from a higher role), both ASSUMED; "on-duty" is read as status ACTIVE or LOCKED, the statuses that can hold a session (ASSUMED; no roster). The code and the console against it (the mock rule) |
| UX-ROLE-SN-01 | The "Assigned To" column (`assigned_analyst`), the pool (`assignment=unassigned`), filtering by assigned analyst (`analyst_id`, narrowed in point 7) and the list behind the autocomplete (`listAssignableStaff`, `purpose=filter`: every decision-role user of the institution, any status or rank) | The analyst autocomplete filter itself, in the console. An assignee whose current role is no longer a decision role is not in the filter list (point 8). The code and the console against it |
| UX-ROLE-AN-01 | Nothing | **Not met, and newly unmet.** Its Cannot-Do, in full, is "Cannot see alerts assigned to other analysts; cannot see unassigned pool". Under advisory assignment (the owner's decision, 2026-10-22) this ADR adds `assigned_analyst` on every card and two filters (`analyst_id` for a colleague, `assignment=unassigned`) that let an ANALYST see colleagues' assignments and the pool, which AN-01 forbids. The reason it stays open: enforcing it needs an auto-assignment rule that no source gives. AN-01 cannot leave IN_PROGRESS while assignment is advisory, and the scope table's P1 claim must be corrected (point 10) |
| FR-04-10 | An escalated entry can be assigned only to someone at or above its target role, by someone at or above it | none new |

## Open questions for the owner

None remain open from the first draft. Question 1 (advisory or binding) was decided by the owner on
2026-10-22: advisory. Questions 2 to 5 are answered by the author on the review's recommendations,
each marked in the text as "the author's choice on the review's recommendation; the owner may
overrule":
- **2. `version` increments on assignment** (point 5), with its cost near a MEDIUM deadline named.
- **3. The audit type is ANALYST_DECISION with action ASSIGNED or UNASSIGNED** (point 5); no
  thirteenth type.
- **4. A minimal eligible-assignees read is added** (`listAssignableStaff`, point 8): id, names and
  role, no e-mail, for SENIOR_ANALYST and RISK_OFFICER. Review 2 recommended keeping it with a
  filter mode; it is kept, with `purpose=filter` (the author's choice on review 2's
  recommendation; the owner may overrule).
- **5. The two ASSUMED rank rules are accepted** (point 3), recorded as deviations in SN-02's row.
- **6. Which statuses count as "on duty"** (review 2's question 1): ACTIVE or LOCKED, for A2, A3 and
  `listAssignableStaff` (point 3; the author's choice on review 2's recommendation; the owner may
  overrule). This is not an owner decision.

Review 2 also recommended keeping questions 2, 3 and 5's answers; they are kept, and remain the
author's choices, not owner decisions.

## Review 1's findings and their answers

| # | Severity | Finding | Answer |
|---|---|---|---|
| 1 | MAJOR | The new `analyst_id` text claimed "assigned to them" without `outcome`, which P1's query (any decision row, undone included) does not give | Fixed in point 7: `analyst_id` now matches assignment, or a decision **not undone**; the description says so and explains why it means "assigned to them" without `outcome`. Overridden decisions still match, with the reason (a narrower reading than the review's option (a)). The P1 query change and a test that fails when undone decisions match are named |
| 2 | MAJOR | AN-01 quoted with half its restriction missing; the proposal makes the missing half real | Fixed: AN-01 is quoted in full in Sources, point 2 and the requirements table; point 2 and the table say which restriction is newly unmet and by what (the field and the two filters); point 2 and point 10 record that AN-01 cannot leave IN_PROGRESS and that `M8B_scope.md` line 208 must be corrected, as a required follow-up this ADR does not make |
| 3 | MINOR | The lock paragraph omitted levels 6 and 7 | Fixed in point 4: "levels 1, 2, 6 and 7, in order; never 3 to 5", with what each is |
| 4 | MINOR | The assignee check was a plain read; A3's role and inactive assignees undefined | Fixed in point 3: A2 reads the assignee `FOR SHARE`; later demotion or deactivation is accepted and stated; A3 uses the current role and status, and an assignee no longer ACTIVE does not hold the entry; the entries such users hold are a named limitation. (Review 2, finding 1, widened ACTIVE to ACTIVE or LOCKED) |
| 5 | MINOR | Idempotency key scope, concurrent collisions and refusals unspecified | Fixed in point 5: keys are scoped to this operation, with the reason; a concurrent same-key collision re-runs once as D1(b) and answers 409; refusals record no key. The replay body is unchanged, as the review accepted |
| 6 | MINOR | `idempotency-conflict` emitted but not declared | Fixed in point 8: `StateConflict.x-problem-types` gains the type, named as a contract change, coordinated with the decision-owner contract's §11; `IdempotencyConflict` is not reused, with the reason |
| 7 | MINOR | The order of A1 to A6 was not fixed | Fixed in point 3 and the operation's description: 404, idempotency, A1, A3, A4/A5, A2, A6 |
| 8 | MINOR | SN-01 and SN-02 rows overclaimed; §8's dropdown sentence contradicted the next | Fixed: SN-01's row lists the autocomplete as open; SN-02's row lists A2's upper bound and A3 as deviations; the contradictory sentence is removed and the dropdown is served by `listAssignableStaff` |
| 9 | MINOR | Three interpretations not marked ASSUMED; the 422 had no code | Fixed in points 3 and 7: ACTIVE for "on-duty" (also excluding LOCKED), senior and risk-officer assignees, and the `assignment` + `analyst_id` refusal are each marked ASSUMED; the refusal uses `unsupported_value`, field `assignment`. The reading of "on-duty" is superseded by review 2, finding 1: ACTIVE or LOCKED |
| 10 | MINOR | The history table's purpose, constraints and grants underspecified | Fixed in point 5: its two purposes (the idempotency record and ADR 0117's history), composite foreign keys as V4, an index, `make_append_only`, `enable_tenant_isolation`, SELECT and INSERT only, and a `DatabaseSecurityTest` case |
| 11 | MINOR | A senior could reset their own measured review time by unassigning and self-assigning | Fixed with ADR 0117 (its point 3): time to decision starts at the entry's creation alone, which no one can move, and the separate `average_seconds_from_assignment` uses the earliest assignment of the entry to the decider **by another user**, so self-assignments never start it |
| Q1 | | Advisory or binding | Decided by the owner (2026-10-22): advisory, with AN-01 open (finding 2) |
| Q2 | | `version` on assignment | Yes, with the cost near a MEDIUM deadline named (point 5); author's choice on the review's recommendation |
| Q3 | | Audit type | ANALYST_DECISION with ASSIGNED/UNASSIGNED, and the counting consequence for FR-06-06 (point 5); author's choice on the review's recommendation |
| Q4 | | Assignee dropdown | `listAssignableStaff` added (point 8); author's choice on the review's recommendation |
| Q5 | | The two ASSUMED rank rules | Accepted, and recorded as SN-02 deviations (point 3, requirements table); author's choice on the review's recommendation |

## Review 2's findings and their answers

Review 2 (`docs/reviews/M8B/reassignment-proposal-review-2.md`): SOUND_WITH_CHANGES, no MAJOR, four
MINOR findings and five owner questions.

| # | Severity | Finding | Answer |
|---|---|---|---|
| 1 | MINOR | "ACTIVE" excludes LOCKED users who are signed in and working, and anyone who knows a staff e-mail can cause a lock | Fixed in point 3: "on duty" is `status IN ('ACTIVE', 'LOCKED')`, the set `SessionService.canHoldSession` admits, ASSUMED and marked as the author's choice on review 2's recommendation; applied to A2, A3 (table, operation rules) and `listAssignableStaff`; the false sentence about locked users is replaced by what the code does (`AuthenticationService.java:171-177`, `:207-217`); the cost (an administrator-locked user stays eligible) is stated; ADR 0117's "30 minutes" is corrected there (point 10) |
| 2 | MINOR | The `StaleAlertProblem` example is not in the list of examples to change | Fixed in point 6: `StaleAlertProblem` and, with ADR 0115, `RealtimeAlertEvent` are listed, with the test each would fail |
| 3 | MINOR | `idempotency-conflict` already in `StateConflict`; snippets not split into `paths` and `components/schemas`; the 422 response borrowed from ADR 0117 | Fixed in point 8: the `StateConflict` addition is dropped, citing `fraudshield-api.yaml:2673`; both snippets are split into `paths` and `components/schemas` parts; the merge order with ADR 0117 is stated (with or after 0117, or carrying 0117's three definitions verbatim), and listed in point 10 |
| 4 | MINOR | `listAssignableStaff` cannot serve SN-01's filter for every assignee | Fixed in point 8: `purpose=filter` lists every decision-role user of the institution, any status or rank, with `status` and no e-mail; `alert_id` refused in that mode; the residual (an assignee now ADMIN) stated; the SN-01 and SN-02 rows name the mode each uses |
| Q1 | | Which statuses count as "on duty" | ACTIVE or LOCKED (finding 1): the author's choice on review 2's recommendation; the owner may overrule |
| Q2 | | `version + 1` on every assignment | Kept (point 5): the author's choice on review 2's recommendation; the owner may overrule |
| Q3 | | Audit as ANALYST_DECISION / ASSIGNED, UNASSIGNED | Kept (point 5): the author's choice on review 2's recommendation; the owner may overrule |
| Q4 | | `listAssignableStaff` | Kept, with the filter mode (finding 4): the author's choice on review 2's recommendation; the owner may overrule |
| Q5 | | The two ASSUMED rank rules | Kept, recorded as SN-02 deviations (point 3): the author's choice on review 2's recommendation; the owner may overrule |
