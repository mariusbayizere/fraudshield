# Review 2: ADR 0117, team performance and what P2's figures mean (Proposed, revised after review 1)

- **Subject:** `docs/adr/0117-team-performance-and-figures.md` at `6a45ed2` on the `m8b` worktree
  (820 lines). `0117:N` is a line of the proposal; other references are files in the worktree: `6a45ed2` plus uncommitted P2 edits that were
  present while this review was written (among them `AlertDecisions.java`, `DecisionWorkers.java`,
  `DecisionOwner.java` and `PostgresSink.java`), so line numbers in those files are the working tree's.
- **Reviewer:** independent and adversarial; wrote none of the proposal and none of review 1.
- **Method:** reading only, against `fraudshield-api.yaml`, `schema-examples.yaml`,
  `contracts/tests/test_openapi.py` and `test_openapi_examples.py`, the decision-owner contract
  (§4, §5, §6.5), V1, V2, V4, V5, V11, V81, the P2 code (`AlertDecisions.java`, `DecisionOwner.java`,
  `PostgresSink.java`), `docs/parallel/M8B_scope.md`, the master build prompt and the SRS. The YAML
  snippets were parsed with `python3`/PyYAML (all five parse). No Maven, Docker, tests or network.

## Verdict: SOUND_WITH_CHANGES

**MAJOR present: NO** (0 MAJOR, 6 MINOR).

All six review-1 MAJORs are fixed, and the fixes hold: the rename to `customer_agreement_rate`,
FPR as FP ÷ (FP + TN) with its denominator, `overturned_decisions` over `override_decision`
(V81:44-47), nullable averages, the queue-honest per-person names with
`average_seconds_from_assignment`, and the senior row filter declared in both the document and the
matrix. The contract snippets pass the tests I could check by reading: the flattened row satisfies
`test_unique_keys_name_properties_of_their_items` (`test_openapi.py:552-575`; 6 → 8, or 9 with
0116), `format: date` does not disturb `test_timestamps_are_utc`, `ValidationOrLimitProblem`'s two
alternatives fix disjoint `type` constants, and `ResultTooLargeProblem` stays out of the closed-schema
count (`test_openapi_examples.py:112-130`). The new findings concern a clock the duration trigger
mixes, two records the revision leaves unmade, and the honesty of two rates' populations.

## Review 1's findings: do the answers hold?

| # | Sev. (r1) | Answer holds? | Note |
|---|---|---|---|
| 1 | MAJOR | Yes | Renamed in all three places; strict accuracy left open in FR-04-11's row. The scope still says P2 releases FR-04-11 (finding 2); `getMyPerformance`'s summary still says "accuracy" (finding 6) |
| 2 | MAJOR | Yes (formula) | FP ÷ (FP + TN) with `verified_legitimate_decisions`. Its population needs one more caveat (finding 4) |
| 3 | MAJOR | Yes | Only differing overrides count; `override_decision` exists (V81:44-47); actor is the risk officer |
| 4 | MAJOR | Yes | `[number, "null"]` in both schemas |
| 5 | MAJOR | Yes, one question left | New schemas use `average_seconds_to_decision`; the two existing fields keep the old name (owner question 1) |
| 6 | MAJOR | Yes | Senior callers get ANALYST and SENIOR_ANALYST rows; filter rule in the operation and the matrix |
| 7 | MINOR | Yes | Flat row, `x-unique-key: [user_id]`, counts stated |
| 8 | MINOR | Yes | `contentMediaType`, `Content-Type` with `header=present`, `result-too-large`, dashboard summary, examples, no `allOf` |
| 9 | MINOR | Yes, with finding 3 | IANA column and explicit `utc_offset`; the zone name itself and DRC's second zone are not addressed |
| 10 | MINOR | Yes | Only answers after the decision verify it |
| 11 | MINOR | Yes | Agreement over the 30 days ending at `to`; `from` defaults to `to` |
| 12 | MINOR | **Yes, with finding 1** | Start fixed at the entry's `created_at`; computed in a BEFORE INSERT trigger. The start's clock and the CHECK are not addressed |
| 13 | MINOR | Yes, with finding 5 | Anchored at the first DECLINE's `decided_at`; "current state" should be read the contract's way |
| 14 | MINOR | Yes | 30 s recompute, stated lag, "console open", `ZREMRANGEBYSCORE` |
| 15 | MINOR | Yes | Partial `(institution_id, created_at)`, `(institution_id, blocked_at)`, time-bounded lookup |
| 16 | MINOR | Yes | Export audited before the first byte; every CSV column justified |
| 17 | MINOR | Yes | LOCKED included; `role` is current. LOCKED is not "30 minutes": it persists until the next sign-in (`AuthenticationService.java:171-177`); harmless here since LOCKED is included |
| 18 | MINOR | Yes | Assumptions marked; §6.5 cited |

## New findings

### 1. MINOR: the duration subtracts an application-clock start from a DB-clock end, and the existing CHECK turns a negative result into a failed decision

**Section:** point 3 (`0117:121-134`).

**Evidence:**
- The end is DB time (`V81:59`). The start, the entry's `created_at`, is **not**: the sink writes it
  from `AlertRaised.createdAt()`, the deciding instance's clock (`PostgresSink.java:121-126`,
  `:443`); an escalated entry gets DB `now()` at D6 (`V4:21`, `AlertDecisions.java:393-405`).
- `decision_duration_seconds` has `CHECK (decision_duration_seconds >= 0)` (`V4:50`). A deciding
  instance clock ahead of the database by more than the time to decision makes the trigger's value
  negative, and the D1 INSERT fails, so `decideAlert` answers 500. The window is small (the sink's
  drain plus a human decision), but it is a correctness hazard introduced by a metric.
- For an escalated entry the figure runs from the escalation, not from the alert's raise; not stated.

**Fix:** compute `greatest(0, …)` (or NULL when negative, counted); state that the start is the raise
time on the deciding instance's clock (or the escalation time for an escalated entry); add both to
the field descriptions.

### 2. MINOR: the scope still says P2 releases FR-04-11

**Section:** requirements table (`0117:750`).

**Evidence:** `docs/parallel/M8B_scope.md:209` lists "FR-04-11 (item 11)" among the IN_PROGRESS rows
P2 **releases**. The ADR now leaves FR-04-11's strict "accuracy rate" open with a reason. This is the
same inconsistency ADR 0116 found and recorded for UX-ROLE-AN-01 (`0116:106-111`); 0117 does not
record it.

**Fix:** add a "required follow-up" like 0116 point 10: correct the P2 row so FR-04-11 is released
only with its strict-accuracy part recorded as open (or not released), and note it in the register.

### 3. MINOR: responses carry an offset but not the zone, and every DR Congo institution is put on Kinshasa time

**Section:** point 1 (`0117:53-61`, `0117:73-79`).

**Evidence:**
- The master prompt (which governs) says "DRC spans two zones (Kinshasa UTC+1, Lubumbashi UTC+2)"
  and "The UI always shows the zone abbreviation"
  (`docs/prompts/FraudShield_Master_Build_Prompt.md:210`). The responses give only `utc_offset`, so
  the console cannot show CAT/EAT/WAT from the API; it would have to map offsets itself.
- The migration and trigger map CD to `Africa/Kinshasa` with no way to say otherwise except a later
  migration (`0117:62-63`). A Lubumbashi institution would get a day that starts an hour late, the
  very error review 1 raised against the first draft, mirrored.
- The trigger validates the zone against `pg_timezone_names` on INSERT only; the migrations that are
  the only way to change it are unchecked.

**Fix:** return `time_zone` (the IANA name) beside `utc_offset` (additive, same lockstep release);
make the zone an explicit input when an institution is created, with the country map only as the
backfill for existing rows (or require a CD institution to name its zone); validate on UPDATE too.

### 4. MINOR: the agreement and false-positive populations are self-selected, which the descriptions do not say

**Section:** point 5 (`0117:275-310`), schema descriptions (`0117:351-369`, `0117:583-589`).

**Evidence (inferred, not tested):**
- Only reviews whose customer answered **after** the decision count. Whether a customer answers
  depends on the decision: after CONFIRM_FRAUD the payment stays blocked and a genuine customer has
  every reason to answer WAS_ME; after MARK_LEGITIMATE the payment goes through and the customer has
  little reason to answer at all.
- So among WAS_ME answers, CONFIRM_FRAUD decisions are over-represented, and the FPR is biased
  upward; agreement is biased in its own way. The description calls it "the SRS's FPR" with only the
  compromised-account caveat.

**Fix:** add to both descriptions: "over reviews whose customer chose to answer after the decision;
customers whose payment stayed blocked answer more often, so this overstates the share of genuine
customers declined". Keep the name (it is the SRS's formula), but do not present it as comparable to
`ModelPerformance.false_positive_rate`.

### 5. MINOR: `fraud_prevented_today` reads "the owner's current state" instead of the contract's reader

**Section:** point 4 (`0117:165`).

**Evidence:** the decision-owner contract makes `v_decision_states` what readers read
(`decision-owner-contract.md:373-387`). Not every transaction has a `decision_owners` row: none is
created for a HIGH decline that no request ever touched, and a NOT_ME received before the
sequence-1 row is visible records its FRAUD label without one (`DecisionOwner.java:193-195`,
`labelWithoutOwner`). Reading `decision_owners` would silently drop such transactions.

**Fix:** "whose latest state in `v_decision_states` is still DECLINE".

### 6. MINOR: loose ends in the contract text

**Section:** points 4, 6 and 10.

**Evidence and fixes:**
- `getMyPerformance`'s summary still says "accuracy from customer verifications"
  (`fraudshield-api.yaml:1081`). After the rename it should say "customer agreement".
- `0117:192-193` says ADR 0115 "points 2, 3 and 8" define presence; only 0115 point 2 (and 6.3) do.
- The snippets put `DashboardStats`, `TeamPerformance` and `TeamMemberPerformance` at four spaces
  under their path items (`0117:207-253`, `0117:468-589`); parsed as written they are keys of the
  path item. Split each into its `paths` and `components/schemas` parts.

## Owner questions (with recommendations)

1. **Rename `average_review_seconds` to `average_seconds_to_decision` in `DashboardStats` and
   `AnalystPerformance` too?** Recommend **yes**. The release already breaks the generated client
   (nullable type, renamed `accuracy_rate`), so the rename is free; one quantity then has one name
   across the header, "My performance", the team table and the CSV; and a field name, unlike a
   description, reaches every tile and pivot. The SRS's "Avg Review" is a UI label, which the console
   can keep with a tooltip saying "time to decision".
2. **FR-04-11 in the scope's P2 releases** (finding 2). Recommend correcting the scope: release
   FR-04-11 only as "met except strict accuracy (not computable)".
3. **How a DR Congo institution outside Kinshasa gets its zone** (finding 3). Recommend an explicit
   zone at institution creation, with the country map only for the backfill.
4. **Seniors see other seniors' rows** (author's choice). Recommend **accept**: SN-04's "all analysts"
   reasonably includes senior analysts; risk officers stay excluded.
5. **Export audit under USER_ADMIN / TEAM_PERFORMANCE_EXPORTED** (author's choice). Recommend
   **accept**: it is the nearest of the twelve types and avoids a D-32 amendment.
6. **The "after the decision" rule** (owner's decision). Not reopened; note that with HIGH alerts,
   where the SMS goes out at the block, most answers will precede the review, so the rates will be
   null for most people for some time. The console should show the count, as the ADR requires.
