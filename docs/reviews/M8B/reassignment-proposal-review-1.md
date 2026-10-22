# Review 1 of ADR 0116, alert reassignment

- **Subject:** `docs/adr/0116-alert-reassignment.md` (Proposed), the uncommitted working-tree
  version of 2026-10-22 (358 lines). Line numbers `:n` without a file are the proposal's.
- **Reviewer:** an independent, adversarial reviewer who wrote none of the proposal.
- **Method:** the proposal was read against the frozen contract
  (`contracts/openapi/fraudshield-api.yaml`, `contracts/openapi/authorisation-matrix.yaml`), the
  approved decision-owner contract (`docs/architecture/decision-owner-contract.md`), V4, V8 and V11,
  P1's code (`AlertQueries`, `AlertController`), `StaffRole`, ADR 0110, 0112, 0115 and 0117,
  `docs/parallel/M8B_scope.md` and the register rows on `m8/frontend-rebase`
  (`docs/traceability/requirements.yaml`). No build, tests, Docker or network were used. The other
  uncommitted work in the worktree (V81, `decision/owner/*`) was read as context only.

## Verdict: SOUND_WITH_CHANGES

**MAJOR present: YES** (two, both fixed by changing the text; neither needs a change of design).

**What holds:**
- **Races.** Reassignment cannot break the owner's claim, the version rule or the lock order. It
  takes the entry row and its advisory lock first, as D1, D2, D5 and D6 do (decision-owner contract
  :951, :967, :1013, :1037). So it serialises with every write to the entry.
  - Racing a decide: the loser gets 409 `stale-alert`, either by version (A5) or because the entry
    is closed (A4).
  - Racing an undo: the entry is closed until D2 commits, so A4 refuses it.
  - Racing an escalation: A4 or A5 refuses it.
  - Racing a TIMEOUT or RESOLVE: the entry is closed by the time the lock is granted.
  - It never touches the owner row (levels 3 to 5), so no claim or deadline can change.
- **Tenants.** No other institution's users leak. `users` is under row-level security
  (V2:38). V4's foreign key is composite, `(assigned_analyst_id, institution_id)` (V4:23), so the
  database refuses another institution's user even if the check in code is wrong. A2 gives the
  same answer for an id that is unknown, in another institution, inactive or ineligible.
- **Lower roles.** A lower role cannot take work from a higher one (A3). It cannot assign above
  itself (A2), and it cannot touch an escalation above its rank (A1).
- **Hidden escalated entries.** An escalated entry above the caller's rank is not newly exposed.
  `getAlert` already returns any entry of the institution, whatever its escalation target
  (`AlertQueries.java:231-247`, `AlertController.java:149-158`). The 409's `current` body adds
  nothing to that. Finding 7 still asks for a fixed order of checks.
- **Closed entries.** PENDING_COMMIT, ESCALATED, OVERRIDDEN, AUTO_* and resolved entries are all
  handled correctly by A4 (:123-135).
- **OpenAPI conventions.** The snippets follow the contract's conventions:
  - the closed `AlertSummary` and `AlertDetail` property lists;
  - `StaffReference` already admitting null;
  - an existing PUT that carries a `version` in its body (`setRuleState`, yaml:1374);
  - the matrix's `rules` shape;
  - `no_change` already in `ValidationErrorCode`.

---

## Findings

### 1. MAJOR: the new `analyst_id` text states a property P1's query does not have (§7, :197-201, :214-222)

- **The claim.** The proposal writes into the contract that, without `outcome`, `analyst_id` means
  "assigned to them": "An open entry has no live decision".
- **The code.** P1's filter matches **any** `alert_decisions` row with that `analyst_id`
  (`AlertQueries.java:193-199`). An undone decision stays in that table, because undo is a
  separate row (V4; decision-owner contract D2).
- **The counterexample.** X decides an entry, then undoes it. The entry is open again (D2(d)),
  and a senior reassigns it to Y. `listAlerts?analyst_id=X` with no `outcome` still lists it,
  though it is not assigned to X.
- **Why it is MAJOR.** The contract text would be false against the server. SN-01's "filter by
  assigned analyst" would return entries that are not assigned to that analyst.
- **Fix.** Pick one:
  - (a) match only live decisions, those neither undone nor overridden, and say so;
  - (b) give `assignment` a third value, or a separate parameter, for "assigned to this user only",
    and drop "so this is 'assigned to them'".

  Either way, add a test that fails when undone decisions match.

### 2. MAJOR: AN-01 is quoted with half its restriction left out, and the proposal makes the omitted half real (:11, :28-30, :73-74, :328, :345-348)

- **The full row.** UX-ROLE-AN-01's Cannot-Do is "Cannot see alerts **assigned to other analysts**;
  cannot see unassigned pool" (`requirements.yaml:4263-4270` on `m8/frontend-rebase`).
- **What the proposal quotes.** It quotes the row as "View own assigned alerts … cannot see
  unassigned pool", and its requirements row as "cannot see … the unassigned pool". Both elide
  the first restriction.
- **Why the elided half matters now.** Today no entry is assigned, so the restriction is vacuous.
  The proposal makes it real. Every ANALYST will see:
  - `assigned_analyst` on every card;
  - `listAlerts?analyst_id=<colleague>`, which lists a colleague's alerts;
  - `assignment=unassigned`, which lists the pool.
- **What is unstated.** The proposal leaves a role restriction unmet, which is a defensible choice
  (open question 1). But it does not say which restriction, and it does not say that the change
  activates it.
- **What else is unrecorded.** `M8B_scope.md:208` lists AN-01 among the rows **P1 releases**.
  AN-01's own note says "'own assigned alerts' only … is the server's to enforce in listAlerts
  (M8B)". The proposal does not record that P1 can no longer release it.
- **Fix.** Make three text changes:
  - quote AN-01 in full;
  - in the requirements table, state that the operation adds a field and two filters that let an
    ANALYST see colleagues' assignments and the pool, which AN-01 forbids;
  - record that AN-01 cannot leave IN_PROGRESS under advisory assignment, and amend the scope
    table's P1 row, or file the item, so the register and the scope agree.

### 3. MINOR: the lock-order paragraph understates the locks taken (§4, :117-121)

- **The text.** "Nothing deeper" and "It never waits for level 3 or below while holding level 1".
- **What the operation takes.** It takes level 6, the foreign-key share lock on `users`, from both
  the entry's and the history row's foreign keys. It takes level 7, the audit chain head, for its
  audit event (decision-owner contract :155-156).
- **The conclusion still holds.** The order is still ascending (1, 2, 6, 7), and nothing reverses
  it (:178).
- **Fix.** Say "levels 1, 2, 6 and 7, in order; never 3 to 5".

### 4. MINOR: the assignee check is a read, with no rule for later role or status changes (§3, A2/A3, :86-93)

- **Concurrent changes.** A2 reads the assignee's role and status. An administrator's demotion
  or deactivation can commit concurrently, so an entry can end up assigned to an ineligible user.
  Examples: an ANALYST holding an entry escalated to SENIOR_ANALYST, or a DEACTIVATED user.
- **A3 is ambiguous.** It does not say whether "ranks above" uses the assignee's current role or
  their role at assignment.
- **The consequence.** A DEACTIVATED risk officer's entries can then only be freed by another risk
  officer. They also drop out of `assignment=unassigned`.
- **Fix.** Make three changes:
  - read the assignee `FOR SHARE` (level 6), or accept the race in words;
  - state that A3 uses the assignee's current role;
  - state that an assignee who is no longer ACTIVE does not hold the entry against A3, or name the
    stranded-entry case as a known limitation.

### 5. MINOR: the idempotency key's scope and collisions are unspecified (§5, :151-161)

- **Key scope.** Decide, escalate and override keep their keys in `alert_decisions`
  (`UNIQUE (institution_id, idempotency_key)`, V4). Assignment keeps its keys in
  `alert_assignments`. A key reused across `decideAlert` and `assignAlert` is therefore not
  detected. `IdempotencyKey` says "unique per logical action" (yaml:2515-2520).
- **Concurrent collisions.** Nothing says what a concurrent same-key request on a **different**
  alert gets. D1(b) re-runs once on a unique-key collision (contract :956).
- **Replay after a failure.** Nothing says that a refused request (403, 409, 422) records no key,
  so a later request with that key is evaluated afresh.
- **Fix.** Make three changes:
  - state that the key is scoped per operation, or check it across both tables;
  - adopt D1(b)'s re-run on collision;
  - state that refusals record no key.

  The replay's body, the entry as it now stands, matches D6 (contract :1037) and is stated in
  the operation description (:240). It is acceptable.

### 6. MINOR: `idempotency-conflict` is emitted but not declared (§8, :288-290)

- **The gap.** `StateConflict.x-problem-types` (yaml:2645-2653) lacks
  `urn:fraudshield:problem:idempotency-conflict`. The catalogue requires each response to list the
  types it may carry (yaml:2934-2940). "In whatever form that proposal takes" leaves the frozen
  contract inconsistent if §11's addition lands in a different form or later.
- **Fix.** Name the change: add the type to `StateConflict.x-problem-types`, with the
  decision-owner contract's §11. `IdempotencyConflict`'s description is specific to FR-01-03, so
  it cannot be reused.

### 7. MINOR: the order of A1 to A6 is not fixed (§3, :86-93; §8, :242-255)

- **The gap.** When several rules fail, the status depends on the order of checks: A1 or A3 (403),
  A4 or A5 (409), A2 or A6 (422). The order is not stated.
- **Why order matters.** For consistency with decideAlert (D1(a) to (d)), an escalation-level
  refusal should come before a 409 that carries `current`. Any later tightening of `getAlert`'s
  visibility will rely on it.
- **Fix.** State the order: 404, idempotency, A1, A3, A4/A5, A2, A6.

### 8. MINOR: the SN-01 and SN-02 rows overclaim (Requirements table, :326-327)

- **SN-01.** The row closes "filter by assigned analyst" and leaves only code and console open.
  - Finding 1 makes the filter not purely "assigned".
  - SN-01 also requires an "analyst autocomplete filter" (`requirements.yaml:4372-4378`). That
    needs the same staff list as open question 4, and today the analyst is a raw UUID
    (`docs/backlog/product.md:1518` on `m8/frontend-rebase`).
- **SN-02.** The row says "Reassign any alert". A1, A3 and A2's upper bound narrow "any alert" and
  "any analyst". Only A1 is derived.
- **Fix.** Make three changes:
  - list the autocomplete under SN-01's open items;
  - list A2's upper bound and A3 as deviations in SN-02's row, not only as ASSUMED rules in §3;
  - correct §8's "the console [can] build the dropdown from data it can already see" (:315-316),
    which the next bullet contradicts (:317-320).

### 9. MINOR: assumptions are not all marked ASSUMED (§3, :81, :89, :102-103)

- **What is not marked.** Three choices are interpretations of the SRS, but the proposal does not
  mark them ASSUMED:
  - ACTIVE as the stand-in for "on-duty", which also excludes LOCKED users (V2: `status`);
  - "any on-duty analyst" read as including SENIOR_ANALYST and RISK_OFFICER assignees;
  - `assignment` combined with `analyst_id` being 422 except as `assigned`.
- **The missing code.** The 422 in the last item has no `ValidationErrorCode`.
- **Fix.** Mark the three as ASSUMED. Name the code: `unsupported_value` fits, with
  `field: assignment`.

### 10. MINOR: the history table's purpose, constraints and grants are underspecified (§5, :157-164)

- **Its justification.** The table duplicates the audit event's before and after values. Its real
  justification is twofold, and the proposal should say so:
  - it stores the idempotency key and fingerprint;
  - it is a queryable assignment history, which ADR 0117 needs (finding 11).
- **What is missing.** Composite foreign keys `(… , institution_id)` to `users` for the previous
  assignee, the new assignee and the caller, and to `alert_queue_entries`, as V4 does. Also the
  grants and the tests: SELECT and INSERT only, in V11's append-only list (V11:20-27);
  `enable_tenant_isolation`; and a `DatabaseSecurityTest` case.
- **Fix.** Specify them.

### 11. MINOR: a senior can reset their own measured review time (interaction with ADR 0117, :60)

- **The rule.** ADR 0117 starts review time at `assigned_at` when the decider is the assignee
  (0117:96-97). It rejects a start "the person measured could set" (0117:89-94).
- **The loophole.** A SENIOR_ANALYST or RISK_OFFICER can unassign and then self-assign. That moves
  `assigned_at` to now and shortens their own figure. A6 blocks only the one-step version.
- **Fix.** Pick one:
  - ADR 0117 uses the earliest `alert_assignments` row assigning the entry to the decider, which
    is one more reason for the table;
  - or ADR 0117 ignores self-assignments.

---

## The proposal's open questions

1. **Advisory or binding?** **Accept advisory now,** on two conditions:
   - finding 2's text, so that AN-01's unmet restriction is stated in full;
   - the scope table's P1 claim on AN-01 corrected.

   Binding assignment needs an auto-assignment rule, or HELD MEDIUM entries time out unseen. It
   also needs a `not-assignee` refusal on `decideAlert`. Neither is derivable from the sources.
2. **Should an assignment change `version`?** **Yes, as proposed.**
   - ADR 0115's G2 orders snapshots by `version` alone (0115:299-301). Without an increment, an
     `ASSIGNED` event would be discarded or need a second counter.
   - The proposal must name a cost it omits. A reassignment near a MEDIUM hold's deadline can make
     an analyst's decide fail with 409 and lose the deadline to the TIMEOUT. The console should
     show the new assignee and offer an immediate re-submit.
3. **The audit type.** **Keep ANALYST_DECISION with action `ASSIGNED` or `UNASSIGNED`.** A
   thirteenth type contradicts D-32's "exactly 12" (`docs/srs/defect_register.md:123`), V8's CHECK
   (V8:23-26) and `AuditEventType`'s "The twelve audit event types" (yaml:2911-2917). It would need
   a D-32 amendment.
   - D-32 already groups sub-actions under a type ("incl. escalation, undo").
   - Any count of decisions from the audit log must then filter by `action`. Say so for FR-06-06's
     assertions.
4. **The assignee dropdown.** **Yes: add the minimal read, in this ADR, not later.**
   - **What it returns:** id, first and last names, and role, with no e-mail.
   - **Who may call it:** SENIOR_ANALYST and RISK_OFFICER.
   - **What it lists:** A2's eligible set (ACTIVE, at or below the caller).
   - **Why it is needed:** without it, SN-02's dropdown cannot be built honestly, and SN-02 cannot
     leave NOT_STARTED.
   - **What else it serves:** SN-01's autocomplete (finding 8).
5. **The two ASSUMED rank rules.** **Accept both.**
   - **A2's upper bound** keeps upward movement on the escalation path, which records a reason
     (FR-04-10).
   - **A3** keeps a lower role from taking work a higher role has taken.
   - **Both narrow SN-02's "any alert / any analyst",** so record them as deviations in the row
     (finding 8).
