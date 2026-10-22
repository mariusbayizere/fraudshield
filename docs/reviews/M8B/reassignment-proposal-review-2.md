# Review 2: ADR 0116, alert reassignment (Proposed, revised after review 1)

- **Subject:** `docs/adr/0116-alert-reassignment.md` at `6a45ed2` on the `m8b` worktree (612 lines).
  `0116:N` is a line of the proposal; other references are files in the worktree: `6a45ed2` plus uncommitted P2 edits that were
  present while this review was written (among them `AlertDecisions.java`, `DecisionWorkers.java`,
  `DecisionOwner.java` and `PostgresSink.java`), so line numbers in those files are the working tree's.
- **Reviewer:** independent and adversarial; wrote none of the proposal and none of review 1.
- **Method:** reading only, against `fraudshield-api.yaml`, `schema-examples.yaml`,
  `authorisation-matrix.yaml`, `contracts/tests/*.py`, the decision-owner contract (§3, §5, §9),
  the P2 code (`AlertDecisions.java`, `DecisionOwner.java`), `backend/auth` (account locking,
  `StaffUserJpaRepository`), V2, V4, V81 and `docs/parallel/M8B_scope.md`. The YAML snippets were
  parsed with `python3`/PyYAML (all six parse). No Maven, Docker, tests or network.

## Verdict: SOUND_WITH_CHANGES

**MAJOR present: NO** (0 MAJOR, 4 MINOR).

Both review-1 MAJORs are fixed and hold against the code. The narrowed `analyst_id` is right: I
checked every way an open entry can carry an analyst's decision (D1 and D6 close it, D5 acts on a
closed entry, an undo leaves only an undone row, a RELEASE_FAILED keeps the analyst's closed
status, decision-owner contract `:412`), so "without `outcome`, assigned to them" is true. AN-01
is quoted in full and the scope correction (`M8B_scope.md:208`, the P1 row) is named. The lock
order holds against the code: D1, D2, D5, D6 take the entry `FOR UPDATE` then its advisory lock
(`AlertDecisions.java:54-61`, `:189`), TIMEOUT/RESOLVE take open entries first
(`DecisionOwner.java:89-93`, `:183-185`), and no path holds a `users` row and then waits for an
entry, so levels 1, 2, 6, 7 cannot deadlock with them. The check order matches D1's (lock, key,
level, version). The new findings are local.

## Review 1's findings: do the answers hold?

| # | Sev. (r1) | Answer holds? | Note |
|---|---|---|---|
| 1 | MAJOR | Yes | `analyst_id` = assigned, or a decision with no `alert_decision_undos` row; the open-entry argument checked above; a failing test named |
| 2 | MAJOR | Yes | AN-01 quoted in full (Sources, point 2, table); newly unmet half named with its three causes; `M8B_scope.md:208` lists AN-01 under P1's releases, as the ADR says, and its correction is a stated follow-up |
| 3 | MINOR | Yes | Levels 1, 2, 6, 7; no path in the code reverses them |
| 4 | MINOR | **Partly** | `FOR SHARE` and current-role A3 are right; "ACTIVE" misdescribes LOCKED (new finding 1) |
| 5 | MINOR | Yes | Per-operation scope, D1(b) re-run, refusals record no key |
| 6 | MINOR | **Yes, and already done** | `StateConflict` already lists `idempotency-conflict` at `fraudshield-api.yaml:2672`; the ADR's "whichever lands first" text is stale (finding 3) |
| 7 | MINOR | Yes | Order fixed in point 3 and the operation's description |
| 8 | MINOR | **Partly** | SN-02 deviations recorded; the autocomplete list omits assignees a filter needs (finding 4) |
| 9 | MINOR | Yes | Three ASSUMED marks; `unsupported_value` is 422 in `x-status-by-code` |
| 10 | MINOR | Yes | Purposes, composite FKs, index, append-only, tenant isolation, grants, test |
| 11 | MINOR | Yes | ADR 0117 point 3: start at the entry's creation; from-assignment ignores self-assignments |

## New findings

### 1. MINOR: "ACTIVE" excludes LOCKED users who are signed in and working, and a lock can be triggered by anyone who knows a staff e-mail

**Section:** point 3, A2, A3 and "Where the sources stop" (`0116:127-128`, `0116:155-157`,
`0116:175-177`, `0116:401`); `listAssignableStaff` (`0116:465`).

**Evidence:**
- The ADR says "a user locked after failed sign-ins cannot work an entry until unlocked"
  (`0116:156-157`). That is false in the code:
  - locking after failed sign-ins sets `status = LOCKED` and `locked_until` but does not touch
    `token_version` or refresh tokens (`AuthenticationService.java:207-217`,
    `StaffUserJpaRepository.java:96-103`), so the user's open console keeps working and they can
    still decide alerts;
  - the lock is lifted lazily, only at the next sign-in attempt (`AuthenticationService.java:171-177`)
    or by an unlock, so `status` stays LOCKED after `locked_until` has passed.
- Sign-in takes only an e-mail (V2:34), so any outsider can put any staff account into LOCKED by
  failing its password.
- Consequences under the proposal: a working, signed-in analyst becomes un-assignable (A2) and
  vanishes from `listAssignableStaff`; a risk officer's held entries lose A3's protection, so any
  senior may take them. The effect is bounded (assignment is advisory, the senior is authorised to
  see and decide the entry, and the risk officer can reassign back), so this is not an authorisation
  bypass of consequence, but the ASSUMED rule's outcome is in an unauthenticated party's hands.

**Fix:** define eligibility as `status IN ('ACTIVE', 'LOCKED')` (both may hold a session), or
`ACTIVE`, or `LOCKED` with `locked_until` in the past, and say why; apply the same set to A3 and
`listAssignableStaff`; correct the sentence at `0116:156-157`. ADR 0117 (`0117:430-431`) also calls
LOCKED "temporary … 30 minutes"; it is until the next sign-in.

### 2. MINOR: the `StaleAlertProblem` example is not in the list of examples to change, so the examples test fails as written

**Section:** point 6 "Examples" (`0116:320-321`).

**Evidence:** adding `assigned_analyst` to `AlertSummaryBase.required` makes every AlertSummary-shaped
example invalid until updated. The ADR lists `AlertSummary`, `AlertDetail` and `AlertPage`, but
`schema-examples.yaml:1170-1208` (`StaleAlertProblem`) carries a `current` AlertSummary too, and
`test_example_is_valid_so_the_schema_is_satisfiable[StaleAlertProblem]`
(`test_openapi_examples.py:84-88`) would fail. ADR 0115's new `RealtimeAlertEvent` example needs it
as well.

**Fix:** list `StaleAlertProblem` (and, with ADR 0115, `RealtimeAlertEvent`) among the examples that
gain `assigned_analyst`.

### 3. MINOR: two contract-change statements are stale or ambiguous as snippets

**Section:** point 8 (`0116:443-448`, `0116:520`), and the snippets at `0116:381-440`,
`0116:459-507`.

**Evidence:**
- `StateConflict.x-problem-types` already contains `idempotency-conflict` at `6a45ed2`
  (`fraudshield-api.yaml:2665-2677`, with `decision-contested` and `decision-effect-failed`). The ADR
  still lists it as an addition "whichever lands first".
- The two operation snippets put `AlertAssignmentRequest`, `AssignableStaffList` and
  `AssignableStaff` at four spaces under the path item; parsed as written they become keys of
  `/alerts/{alert_id}/assignment` and `/team/assignees`, not components.
- The 422 `result-too-large` response for `listAssignableStaff` is borrowed from ADR 0117
  ("whichever lands first", `0116:511-514`), so this ADR is not self-contained if 0117 is delayed.

**Fix:** drop the `idempotency-conflict` addition (cite that it is present); split each snippet into
its `paths` and `components/schemas` parts; state that 0116 does not merge before 0117's response,
or carry the response definition here too.

### 4. MINOR: `listAssignableStaff` cannot serve SN-01's "filter by assigned analyst" for every assignee

**Section:** point 8 (`0116:452-456`, `0116:515`) and the SN-01 row (`0116:576`).

**Evidence:** the list returns only ACTIVE users at or below the caller. Entries can be assigned to
users it never lists: a risk officer who self-assigned (seen by a senior), and any user later
demoted, deactivated or locked (point 3, "known limitation"). SN-01's "analyst autocomplete
filter" is a filter, not the assignment dropdown, so it needs those names. The SN-01 row says the
list is "the list behind the autocomplete".

**Fix:** either add a mode for the filter (`purpose=filter`: every decision-role user of the
institution with id, names, role and status, no e-mail), or narrow the SN-01 row to "assignable
users only" and leave the rest open with its reason.

## Owner questions (with recommendations)

1. **Which statuses count as "on duty" (A2, A3, `listAssignableStaff`)?** It is an interpretation of
   SN-02 with an access consequence (finding 1). Recommend **ACTIVE or LOCKED**: both can hold a
   working session; excluding LOCKED hands a lock-out attacker a lever, while DEACTIVATED and
   PENDING_APPROVAL users can never work.
2. **`version + 1` on every assignment** (author's choice). Recommend **keep**: ADR 0115 orders by
   `version` alone; the stated cost near a MEDIUM deadline is real and the one-click re-submit
   mitigates it.
3. **Audit as ANALYST_DECISION / ASSIGNED, UNASSIGNED** (author's choice). Recommend **keep**: no
   thirteenth D-32 type, with the counting consequence stated.
4. **`listAssignableStaff`** (author's choice). Recommend **keep**, with finding 4's filter mode.
5. **The two ASSUMED rank rules** (author's choice). Recommend **keep**, recorded as SN-02 deviations
   as now.
