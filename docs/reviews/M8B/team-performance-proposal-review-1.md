# Review 1: team performance and what P2's figures mean (ADR 0117, Proposed)

- **Subject:** `docs/adr/0117-team-performance-and-figures.md` (untracked, Proposed, 2026-10-22)
- **Branch / commit:** `m8b/analyst-api` at `11448ce`, with the worktree's uncommitted work read as
  context only
- **Reviewer:** independent; wrote none of this work. No Maven, no Docker, no tests and no network.
  Evidence was read from the contract, migrations V1 to V81, the decision-owner contract, the
  country packs, the register on `m8/frontend-rebase` and the console's generated types there.
  The time-zone claims were checked against the local IANA tzdata with `zdump`.
- **Date:** 2026-10-22

## Verdict: SOUND_WITH_CHANGES

**MAJOR present: YES** (6 MAJOR, 12 MINOR)

The structure holds:
- an institution day from a fixed offset;
- reviews defined against the decision-owner contract's states;
- review time stated as time to decision;
- an honest refusal to call anything "accuracy";
- one operation serving both JSON and CSV.

The day boundaries are correct, and the DST-free claim holds for all five zones and for Kinshasa
(checked with `zdump` over 2000 to 2040: no `isdst=1` transition in Africa/Kigali, Nairobi,
Kampala, Dar_es_Salaam, Lubumbashi or Kinshasa). Kinshasa is +01:00 and Lubumbashi +02:00, as the
ADR says.

**Six points should not be frozen as written:**
1. Two figures are presented as measures they are not:
   - `false_positive_rate` is computed as a false *discovery* rate;
   - `overridden_decisions` counts overrides that agreed with the analyst.
2. Two figures depend on reasons that the proposal itself defeats:
   - `accuracy_rate` keeps its name to spare the console a break that the same proposal already
     forces on it;
   - `average_review_seconds` returns a 0 that a consumer cannot tell apart from "no data".
3. The team table ranks people by a figure that the ADR says "measures the queue more than the
   analyst".
4. By default a senior analyst sees risk officers' personal figures, which the source row does
   not grant.

Each fix is local and cheap. None of them changes the architecture.

## Findings

### 1. MAJOR: `accuracy_rate` keeps a name whose meaning has changed, for a reason that does not hold (§5, §6, §7)

**Evidence:**
- ADR 0117:207-210 keeps the name "because renaming a required field breaks the console".
- The same proposal already breaks the console in lockstep:
  - it adds required fields to `AnalystPerformance` and `DashboardStats`, both
    `additionalProperties: false` (ADR 0117:147-150, 230-233);
  - it changes `analysts_online` to `[integer, "null"]` and says "The console ships in lockstep"
    (ADR 0117:139-140).
- The console's types and zod schemas are generated from the contract
  (`m8/frontend-rebase:frontend/src/api/generated/zod.gen.ts:526,533`,
  `types.gen.ts:461,471`), so the rename costs one regeneration, the same as the changes it
  already makes.
- **`TeamMemberPerformance` is a new schema** (ADR 0117:358-377), so it has no compatibility
  reason at all, yet it also calls the field `accuracy_rate`. Meanwhile the CSV header for the
  same value is `customer_agreement_rate` (ADR 0117:402, 408-409).

**Why it matters:**
- One value would carry two names on one operation.
- A consumer of the frozen contract who reads the field name gets "accuracy". Only the
  description says otherwise, and descriptions do not reach a dashboard tile or a spreadsheet
  pivot.
- The honesty rule: a figure must not be presented as more than its data supports.

**Fix:** rename to `customer_agreement_rate` in `AnalystPerformance`, `TeamMemberPerformance` and
the CSV, in the same lockstep release. Record in the ADR that FR-04-11's "accuracy rate" is met by
this proxy and is not computable in the strict sense (open question 2).

### 2. MAJOR: `false_positive_rate` is a false discovery rate under a name the SRS defines differently (§5, §6)

**Evidence:**
- ADR 0117:212-214 and 257-259 define it as follows: of the analyst's verified CONFIRM_FRAUD
  reviews, the share whose customer answered WAS_ME. That is FP ÷ (FP + TP), the false discovery
  rate (1 − precision).
- The SRS glossary defines FPR as the "proportion of legitimate transactions incorrectly flagged
  as fraud" (`docs/srs/FraudShield_SRS_v1_0.md:452`). With WAS_ME standing in for "legitimate",
  that is FP ÷ (FP + TN).
- The same contract already has a `ModelPerformance.false_positive_rate`
  (`contracts/openapi/fraudshield-api.yaml:3714,3725`), and FR-05-03 uses the term in the
  standard sense. The API would then carry two different formulas under one field name.
- FR-04-11's acceptance asks for the rate "correctly calculated" (`FraudShield_SRS_v1_0.md:148`).

**Fix:** compute the SRS's measure from the same rows: among the analyst's verified reviews whose
customer answered WAS_ME, the share the analyst marked CONFIRM_FRAUD. If the owner wants the share
of fraud confirmations that the customer disputed, keep it as well, but name it for what it is
(for example `disputed_fraud_confirmation_rate`). Keep the "a WAS_ME may come from a compromised
account" caveat on both.

### 3. MAJOR: `overridden_decisions` counts overrides that agreed with the analyst, and "a senior" is a risk officer (§5)

**Evidence:**
- ADR 0117:203-204 says that a committed decision later overridden "is a decision a senior
  disagreed with". That is false.
  - `OverrideRequest.new_decision` may equal the original (`fraudshield-api.yaml:3458-3466`; no
    rule forbids it at 1019-1026).
  - The decision-owner contract makes a same-value override a real outcome: "DECLINE → DECLINE by
    SENIOR_OVERRIDE. The senior's decline is a state even though the value does not change"
    (`docs/architecture/decision-owner-contract.md:427`, and §5.1 item 2 at 478).
- An analyst's CONFIRM_FRAUD that a risk officer re-confirms would therefore count against the
  analyst.
- Only RISK_OFFICER may override (`contracts/openapi/authorisation-matrix.yaml:121-123`;
  `contracts/tests/test_authorisation_matrix.py:104`). "A senior overrode", in ADR 0117:216 and in
  the field description at 260, misnames the actor.

**Fix:**
- Count only overrides whose `override_decision` differs from the original's decision.
  `override_decision` exists (V81:44-47), so this can be computed.
- Name the field `overturned_decisions`, with "by a risk officer" in its description.
- Same-value overrides can be reported separately if the owner wants them.

### 4. MAJOR: `average_review_seconds` is 0 when there is no data, and a consumer cannot detect it (§4, §6)

**Evidence:**
- ADR 0117:131-132 and 172 return 0 when there are no reviews. The stated reason is that null
  "is a type change". The proposal makes exactly such a type change for `analysts_online`
  (finding 1).
- Anomaly reviews count in `reviewed_today` but are excluded from the average (ADR 0117:84-86,
  108-110). So `reviewed_today > 0` with `average_review_seconds = 0` can mean "only anomaly
  reviews today", and nothing in the response tells the console which.
- At the start of every institution day the header shows "Avg Review 0s" (UX-DASH-01), a measured
  value that was never measured.
- The proposal's own `daily` items use null for the same case (ADR 0117:275). The header and the
  series would disagree.

**Fix:** make `average_review_seconds` `[number, "null"]` now, in `DashboardStats` and
`AnalystPerformance`, null when there is no HIGH or MEDIUM review, in the same lockstep release.
That closes open question 6.

### 5. MAJOR: the team table and the CSV compare people on a figure the ADR says measures the queue (§3, §7, §8)

**Evidence:**
- ADR 0117:104-105: "Until assignment is used, this figure measures the queue more than the
  analyst."
- It nevertheless becomes a per-person, sortable column, `average_review_seconds`
  (ADR 0117:372), with no description in the new schema. It is also a CSV column (ADR 0117:401),
  and a CSV carries no description at all.
- An analyst who works the overnight backlog at 08:00 will sort as "slow". Ranking individuals on
  that figure presents it as more than its data supports.

**Fix:**
- In the new schemas, which have no compatibility constraint, name it
  `average_seconds_to_decision` (`TeamMemberPerformance`, `daily`, and the CSV header) and give it
  the queue-waiting caveat as a description.
- Better still, add a companion `median_seconds_to_decision`, or
  `average_seconds_from_assignment` computed only over decisions on assigned entries. That figure
  measures the analyst once ADR 0116 is in use.
- If neither is acceptable, leave the column out of the team table until assignment is in use,
  and record that in the requirements table.

### 6. MAJOR: by default a senior analyst sees risk officers' personal performance figures (§7)

**Evidence:**
- UX-ROLE-SN-04 grants "All analysts: reviews/hour, accuracy rate, avg review time"
  (`m8/frontend-rebase:docs/traceability/requirements.yaml:4433`).
- RISK_OFFICER is a separate, higher role (UX-ROLE-RO-01 at 4462-4469). It is not "an analyst".
- ADR 0117:295-298 includes RISK_OFFICER rows for senior callers as ASSUMED (open question 3).
- An assumption that widens access to staff personal data beyond the source is an authorisation
  leak until the owner grants it. Marking it ASSUMED discloses it but does not make it safe as a
  default.

**Fix:**
- A SENIOR_ANALYST caller gets ANALYST and SENIOR_ANALYST rows.
- A RISK_OFFICER caller gets all three roles.
- Widen access only on the owner's answer.
- State the row filter as an `x-authorisation-rules` entry with `effect: filter`, as `listAlerts`
  does (`fraudshield-api.yaml:821-823`), and add it to the golden matrix's `rules`. "No
  object-level rule" (ADR 0117:388) is then no longer true.

### 7. MINOR: `x-unique-key: [analyst.user_id]` fails the contract's own checks (§7)

**Evidence:**
- `contracts/tests/test_openapi.py:567` requires every key to be a property in the item's
  `required` list. `analyst.user_id` is a path, not a property.
- `contracts/tests/test_openapi_examples.py:166,172` index items with `item[k]`, which raises
  `KeyError` on a dotted key.
- `test_openapi.py:575` pins the number of keyed arrays at 6, so this change and `daily` both
  need that count raised.

**Fix:** either flatten (`user_id` at the row's top level, beside `analyst`), or extend both
checkers to dotted paths with a test that rejects a missing path. Say which in the ADR.

### 8. MINOR: OpenAPI conventions and loose ends (§4, §7, §8)

**Evidence and fixes:**
- **The CSV schema.** It is `{ type: string, maxLength: 1048576 }` (ADR 0117:336). `maxLength`
  counts characters, not bytes, so it does not express "1 MiB". The contract's other alternate
  media type uses `contentMediaType` (`fraudshield-api.yaml:1486-1487`). Use
  `contentMediaType: text/csv`, and state the byte bound in the description.
- **The response's `Content-Type`.** Give it as `text/csv; charset=utf-8; header=present`
  (RFC 4180 §3).
- **More than 1,000 staff answers 422 with `ValidationProblem`** (ADR 0117:416). The request is
  valid. That is a server-side limit, not a field error, and no `ValidationErrorCode` fits it.
  Use a documented problem without a field code, or simply raise the bound.
- **`getDashboardStats`'s summary** still says "The seven header statistics"
  (`fraudshield-api.yaml:785`). There are now eight fields.
- **`schema-examples.yaml:161-166,527-535`** need the new required fields, or the examples test
  fails. The ADR should list these edits.
- **`analyst: allOf [StaffReference, {type: object}]`** (ADR 0117:365-368) only removes the null
  that StaffReference allows (`fraudshield-api.yaml:3482-3483`). Say so in a description, or the
  construct reads as a mistake.

### 9. MINOR: `day_starts_at` cannot carry the offset it is meant to show; the CSV has no zone (§1, §8)

**Evidence:**
- `Timestamp` is UTC only: pattern `Z$` (`fraudshield-api.yaml:2850-2854`). The console must infer
  "+02:00" from the fact that 22:00Z is local midnight. That works only because the day starts at
  00:00 by definition.
- The CSV has `from` and `to` but no zone or offset (ADR 0117:400-402).
- The offset comes from a pack parameter whose documented purpose is "to place local activity
  hours" of *simulated customers* (`dataset/generator/params/countries/CD.yaml:29-40`). Changing
  a dataset parameter would therefore move every production institution's day.

**Fix:**
- Add an explicit `utc_offset` (for example `"+02:00"`) beside `day_starts_at` in all three
  schemas, and a `utc_offset` CSV column.
- Prefer open question 1's zone column (see below).

### 10. MINOR: agreement counts customer answers that came before the decision (§5)

**Evidence:**
- ADR 0117:192-195 count every verified decision, whatever the order of answer and decision.
- `customer_verification_responses.responded_at` exists (V5:70), so the order can be computed.
- An answer given before the decision can change what the analyst sees. A WAS_ME on a
  model-declined transaction approves it before any claim exists
  (decision-owner contract:446-448), and the account history's `outcome` then reads APPROVED
  (`fraudshield-api.yaml:3528`).
- *Inferred, not tested:* whether the console shows that outcome on the alert being reviewed.

Agreement with an answer the analyst could see measures copying, not judgement.

**Fix:** count a decision as verified only when `responded_at` is after the decision's
`created_at`. Alternatively, report "answered before" separately.

### 11. MINOR: the team table's agreement columns are null by default, and disagree with "My performance" (§7)

**Evidence:**
- The team range defaults to today (ADR 0117:300).
- Agreement and false-positive figures use "the definitions above, over the range"
  (ADR 0117:309), with a minimum of 20 verified reviews. In a one-day range that minimum is "rare"
  (ADR 0117:281-282), so UX-ROLE-SN-04's accuracy column is empty by default.
- The same analyst's figure in `getMyPerformance` uses a fixed 30 days (ADR 0117:208-210), so a
  senior and the analyst see different numbers.

**Fix:**
- Compute the agreement columns over the 30 institution days ending at `to`, whatever the range,
  and say so in the column descriptions.
- Alternatively, default the range to the last 30 days.
- Also, `to` alone set to yesterday with `from` defaulting to today answers 422. Default `from` to
  `to`.

### 12. MINOR: review time's start can be moved by the person measured, and the write needs pinning (§3)

**Evidence:**
- **The start can be moved.** The start becomes `assigned_at` when the decider is the assignee
  (ADR 0117:96-97). SENIOR_ANALYST and RISK_OFFICER can assign entries to themselves
  (ADR 0116:241). A self-assignment just before deciding resets their own clock. That is the flaw
  for which ADR 0117:92 rejects a client-supplied start.
- **The write.** `alert_decisions` is append-only (V4:67), and `created_at` is set by trigger with
  `clock_timestamp()` (V81:59).
  - "D1 writes the value … in its own transaction" (ADR 0117:99-101) can only mean at insert, in
    D1's transaction. A later update is impossible.
  - An application-side computation would use a different clock from `created_at`.
- **Existing rows.** Decisions made before P2 have NULL. The ADR does not say they are excluded,
  rather than counted as 0.

**Fix:**
- Use the earliest assignment to the decider, or a self-assignment never moves the start. The
  simplest option is the entry's `created_at` alone.
- Compute the value in the BEFORE INSERT trigger from `NEW.created_at`.
- State "NULL durations are excluded" in the definitions.

### 13. MINOR: `fraud_prevented_today` leaves its day undefined, and its rationale contradicts §5 (§4)

**Evidence:**
- **The day.** "Declined today" (ADR 0117:122) does not say which instant falls in today.
  `transaction_timestamp`? The `decided_at` of the first DECLINE state? Of the latest? A HIGH
  transaction auto-blocked at 23:50 and confirmed by an analyst at 00:10 is "declined" on either
  day.
- **The rationale.** ADR 0117:128-129 implies that a FRAUD label removes false positives. §5
  (ADR 0117:187-188) says labels are mostly the analysts' own decisions and not ground truth.

**Fix:**
- Define the anchor. The first DECLINE state's `decided_at` in `v_decision_states` is natural.
- Describe the field as "declines confirmed as fraud by an analyst, a risk officer or the
  customer", not as excluding false positives.

### 14. MINOR: `analysts_online` and ADR 0115's push trigger disagree (§4)

**Evidence:**
- ADR 0115 §6.3 recomputes `DashboardStats` when a local session connects or disconnects, or when
  an alert event passes.
- Presence here expires 30 s after the last refresh (ADR 0117:135-137). The recompute on
  disconnect therefore still counts the departed user, and nothing recomputes at expiry. In a
  quiet period the pushed count stays wrong until the next alert event.
- Another instance's sessions never trigger this instance's recompute.
- "Has the dashboard open" (ADR 0117:141) is really "holds a real-time session", which is any
  console screen.
- A sorted set scored by expiry also needs `ZREMRANGEBYSCORE`, or it grows.

**Fix:**
- Recompute once more 30 s after a disconnect.
- Say that the figure lags by up to 30 s.
- Word the definition as "has the console open".
- Name the cleanup step.

### 15. MINOR: the proposed index does not serve the institution-wide figures (§9)

**Evidence:**
- `(institution_id, analyst_id, created_at)` (ADR 0117:426-427) serves per-analyst windows.
- `reviewed_today` and `average_review_seconds` in `DashboardStats` are institution-wide over a
  time range. With `analyst_id` second, PostgreSQL scans the institution's whole index range
  (PostgreSQL has no skip scan before 18).
- `auto_blocked_today` has no `(institution_id, blocked_at)` index (V5:14-15).
- `fraud_prevented_today` reads `amount_rwf` from the `transactions` hypertable (V3:28,45)
  through its `v_` view by id. Without a time predicate that probes every chunk.

**Fix:**
- Add `(institution_id, created_at)` on `alert_decisions`, partial on the two review decisions,
  and `(institution_id, blocked_at)` on `auto_block_events`.
- Bound the transaction lookup by `transaction_timestamp`.

### 16. MINOR: the CSV export of staff personal data is not audited, and carries more than the source asks (§8, open question 7)

**Evidence:**
- Names, ids and per-person performance are personal data about employees. A file of them leaves
  the system's control.
- ADR 0117:449-450 reasons that "no D-32 type fits". But D-32's types carry an `action`
  sub-field (`docs/srs/defect_register.md:123`), and `USER_ADMIN` with an action such as
  `TEAM_PERFORMANCE_EXPORTED` fits without a thirteenth type.
- The CSV also carries `user_id`, `escalations`, `overridden_decisions` and the false-positive
  figure. UX-ROLE-SN-04 asks for reviews per hour, accuracy and review time only.

**Fix:**
- Audit the `text/csv` variant, recording the caller, range and row count.
- Justify each extra column against a source, or drop it, `user_id` included.

### 17. MINOR: row membership and roles (§7)

**Evidence:**
- Rows are "ACTIVE or made a review in the range" (ADR 0117:295-297). A LOCKED analyst
  (`users.status`, V2) with no review in the range disappears for 30 minutes, then reappears.
- The row's `role` is the current role, not the role held in the range.

**Fix:** include LOCKED users, and state that `role` is the current role.

### 18. MINOR: unmarked assumptions, and one assumption the contract already settles (whole ADR)

**Unmarked choices** that should be marked ASSUMED, or tied to a source:
- the exclusion of ESCALATE from reviews;
- the exclusion of anomaly reviews from averages;
- the clock-hour granularity of `active_hours`, under which `reviews_per_active_hour` is at least
  1 whenever there is a review, and rewards bursts over steady work (say so in its description);
- the default range of today;
- "1,000 rows comes to well under 1 MiB". Worst case, with 100-code-point names in 4-byte UTF-8,
  is about 0.96 MB, which is under the bound but not well under it. With the BMP letters that
  Kinyarwanda and French use, it is well under.

**An assumption the contract already settles.** ADR 0117:199-202 marks as ASSUMED that a refused
or deferred WAS_ME keeps its response row. The decision-owner contract §6.5 settles it: the
transaction inserts the response first, and a REFUSED outcome is recorded and committed
(`decision-owner-contract.md:666-680`). Cite it rather than assume it, and keep the test.

## The proposal's open questions: recommendations

1. **The institution's time zone.** Add an IANA zone column on `institutions`, by migration,
   defaulted from the country through a fixed map (RW Africa/Kigali, KE Africa/Nairobi, UG
   Africa/Kampala, TZ Africa/Dar_es_Salaam, CD Africa/Kinshasa). Use it for the day. This:
   - decouples production days from a dataset parameter (finding 9);
   - fixes the Kinshasa case;
   - stays correct if a zone ever adopts DST.

   The country pack's offset is acceptable only as a stop-gap, with finding 9's explicit
   `utc_offset`.
2. **"Accuracy".** Accept customer agreement, labelled as such. Rename the JSON field too
   (finding 1). Record FR-04-11's strict "accuracy" as not computable from existing data, and
   leave the row open with that reason (scope §7 point 4).
3. **Team rows.** Senior analysts see ANALYST and SENIOR_ANALYST rows only. Risk officers see all
   three roles (finding 6).
4. **Overrides as work.** Yes: add an `overrides_made` column. Fill it for RISK_OFFICER rows, the
   only role that can override, and make it null for the others, so that a risk officer's work is
   visible without being counted as reviews.
5. **`fraud_prevented_today`.** Keep "confirmed declines", with the anchor and wording of finding
   13. If the owner wants the larger figure, return it as a second field (`declined_value_today`),
   not instead.
6. **`average_review_seconds` with no reviews.** Make it nullable now, in the same lockstep
   release (finding 4).
7. **Exporting staff performance.** Audit it under `USER_ADMIN` with a dedicated action
   (finding 16).
8. **The ASSUMED constants.** Accept them as starting values, with the following conditions:
   - **The 20 minimum.** The response should expose the denominator, as `verified_decisions`
     already does. The console should show the count beside the rate, so a reader can judge it.
   - **The 30-day windows and the 31-day range.** They become one rule under finding 11.
   - **The BOM.** Accept it. RFC 4180 neither requires nor forbids one.
   - **The presence timings.** They need finding 14's extra recompute.
   - **The 1,000 rows.** Accept, with finding 8's error response.
