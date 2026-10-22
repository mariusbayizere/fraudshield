# 0117: Team performance, and what P2's figures mean

- **Status:** Proposed (M8B, 2026-10-22), revised after review 1
  (`docs/reviews/M8B/team-performance-proposal-review-1.md`), for re-review before any code. It adds
  one operation to the frozen contract, changes `DashboardStats` and `AnalystPerformance`, adds a
  time-zone column to `institutions`, and adds one problem type and one response.
- **Date:** 2026-10-22
- **Requirements affected:** UX-ROLE-SN-04, FR-04-11, UX-ROLE-AN-04, UX-DASH-01, FR-04-01
- **ADRs referenced:** 0013, 0110, 0112, 0114, 0115, 0116
- **Sources:** M8B scope P2-e, P2-g and §7 point 3; `M8B_updates.md` items 4, 9 and 11; SRS FR-04-11
  ("accuracy rate from customer verifications … false positive rate correctly calculated from
  customer_verifications table") and the glossary's FPR ("proportion of legitimate transactions
  incorrectly flagged as fraud"); the register's UX-ROLE-SN-04 ("Additional to ANALYST: All
  analysts: reviews/hour, accuracy rate, avg review time; sortable; CSV export; Cannot Do: Cannot
  modify analyst accounts"), UX-ROLE-AN-04 ("Own: … Cannot view team performance … Recharts
  LineChart (daily trend)") and UX-ROLE-RO-01 ("Additional to SENIOR_ANALYST"), as on
  `m8/frontend-rebase`; migrations V1 (`institutions`), V4 (`alert_queue_entries`,
  `alert_decisions`), V5 (`auto_block_events`, `customer_verifications`,
  `customer_verification_responses`, `label_events`) and V81 (`override_decision`, the decision
  timing trigger); the decision-owner contract §4.7, §5, §6.5 and §9; ADR 0116's `alert_assignments`.

## Context

**Only `getMyPerformance` exists, and only today's figures.** It returns:
- `reviewed_today`;
- `average_review_seconds`;
- `backlog`;
- `accuracy_rate`, "null until customer verifications exist";
- `verified_decisions`.

`getDashboardStats` returns seven numbers. **None of the figures is defined beyond its name:**
- **"Today" has no zone.** `institutions` has a country and no time zone.
- **Review time has no definition.** `alert_decisions.decision_duration_seconds` exists, is
  nullable, and nothing defines or writes it.
- **"Accuracy" has no ground truth behind it.** The only outside signals are these:
  - **A customer's answer** to a verification: WAS_ME or NOT_ME. Verifications exist only for
    auto-blocked (HIGH) transactions, and only when a link was allowed (V5).
  - **A risk officer's override** of the decision.
  - **Labels.** Their CHARGEBACK source has no producer.
- **Analysts online has no definition.**
- **Anomaly reviews.** Item 9 asks whether `reviewed_today` counts reviews of anomaly alerts.
- **A daily trend** needs a daily series (item 11; UX-ROLE-AN-04).
- **Team figures.** UX-ROLE-SN-04 asks for every analyst's figures, sortable, with a CSV export.

## Decision

### 1. Whose "today": the institution's day, in its own IANA time zone (decided by the owner, 2026-10-22)

**The owner decided on 2026-10-22:** "today" uses a new IANA time-zone column on `institutions`,
filled from the country, and every response carries an explicit `utc_offset` beside
`day_starts_at`.

**The column.** `institutions.time_zone text NOT NULL`, an IANA zone name.
- **How it is filled.** The migration fills every existing row from its `country` through a fixed
  map: RW Africa/Kigali, KE Africa/Nairobi, UG Africa/Kampala, TZ Africa/Dar_es_Salaam, and CD
  (DR Congo) Africa/Kinshasa. A BEFORE INSERT trigger fills it the same way when an insert gives no
  zone, so demo seeding and tests keep working.
- **A country outside the map** fails the migration, or the insert, with a message naming the
  country. No silent default: a wrong day boundary is worse than a refused row.
- **A zone the database does not know** is refused by the same trigger (it must appear in
  `pg_timezone_names`).
- **Who can change it.** `fs_app` holds SELECT only on `institutions` (V11), and no API
  administers institutions, so only a migration changes it.
- **The migration number** is claimed at implementation from M8B's range (V80 to V89; V81 and V82
  are taken), so it is written here as V8x, claimed in `M8B_updates.md` before use. The indexes of
  point 9 go in the same migration.

**The day.** A day runs from local 00:00 in the institution's zone, inclusive, to the next local
00:00, exclusive. In a zone that observed daylight saving, a day could last 23 or 25 hours; none of
the five zones does (review 1 checked Africa/Kigali, Nairobi, Kampala, Dar_es_Salaam, Lubumbashi
and Kinshasa with `zdump` over 2000 to 2040), but the definition does not depend on it.

**Every response carries the day explicitly:**
- `day_starts_at`, a UTC `Timestamp` (the contract's timestamps are UTC only, pattern `Z$`);
- `utc_offset`, the offset in force at `day_starts_at`, as `"+02:00"` (pattern
  `^[+-][0-9]{2}:[0-9]{2}$`), so the console can label "since 00:00, UTC+02:00" without inferring
  the offset from the timestamp.

The CSV carries a `utc_offset` column (point 8).

**Rejected:**
- **UTC.** A Kigali dashboard would reset at 02:00 local time, and "today" would include 22:00 to
  24:00 of the previous local evening.
- **The analyst's own zone.** Figures for one institution would then disagree between analysts, and
  the team table would mix days.
- **The country pack's `utc_offset_hours`** (the first draft's choice). Its documented purpose is
  to place simulated customers' activity hours (`dataset/generator/params/countries/CD.yaml`), so
  changing a dataset parameter would have moved every production institution's day. It also put an
  institution in Kinshasa (+01:00) on Lubumbashi's +02:00.

**This differs from P1's hours, deliberately.** P1's `hourly_counts` place each transaction in its
currency's country (ADR 0110 point 5), because they describe a customer. "Today" describes an
institution's working day.

### 2. What a review is, and which reviews count

**A review is an analyst decision: CONFIRM_FRAUD or MARK_LEGITIMATE, by a user, not undone.**
- **A PENDING_COMMIT decision counts.** If it is undone within its 5 s, it stops counting.
- **Its day** is the institution day of its `alert_decisions.created_at`.

**Not reviews:**
- **ESCALATE.** That it is not a review is ASSUMED: it hands the review on, and the decision on the
  new entry is the review, so counting both would count one alert twice. Escalations are reported
  separately in the team table.
- **SENIOR_OVERRIDE.** It acts on an alert already reviewed. A risk officer's overrides are shown
  in their own team-table column, `overrides_made` (point 7), not as reviews.
- **AUTO_RELEASED and AUTO_BLOCKED.** No person made them.

**Anomaly reviews count (item 9).**
- **Where.** A decision on an ANOMALY entry is a review, in `reviewed_today`, in the daily series and
  in the team table. It is work an analyst did.
- **One exception, ASSUMED:** anomaly reviews are excluded from every average time to decision
  (point 3).

### 3. Review time: time to decision, said plainly

**The server cannot see when an analyst starts looking at an alert.**
- **The contract has no "opened" signal,** and `AlertDecisionRequest` carries no duration.
- **A client-supplied start time** would be a figure the person measured could set. It is rejected.

**So "review time" is defined as time to decision, from the entry's creation.**
- **The start** is the entry's `created_at`, always. No one can move it: not the decider, and not a
  self-assignment (ADR 0116, review 1 of 0116 finding 11, and this ADR's review finding 12). The
  first draft's start at `assigned_at` is withdrawn.
- **The end** is the decision's `created_at`, which V81's `alert_decisions_timing` trigger sets to
  DB time (`clock_timestamp()`).
- **Where it is stored.** `alert_decisions.decision_duration_seconds`, the existing column, is
  computed **at insert, in a BEFORE INSERT trigger,** from `NEW.created_at` minus the entry's
  `created_at`, for CONFIRM_FRAUD and MARK_LEGITIMATE. The trigger runs after V81's timing trigger
  (its name sorts after it, or V81's function computes it), so `NEW.created_at` is already DB time.
  `alert_decisions` is append-only (V4), so the value cannot be written later, and an application
  computation would use a different clock.
- **Decisions made before P2 have NULL** in that column. **NULL durations are excluded** from every
  average, never counted as 0.

**What it includes:**
- **Queue waiting time.** An unassigned alert decided an hour after it was raised counts an hour.
- **The real limit.** This figure measures the queue more than the analyst. An analyst who works the
  overnight backlog at 08:00 looks slow. Every field description says so, and the per-person
  columns are named for what they are (point 7).

**A second figure that measures the analyst once assignment is used: time from assignment.** For
the team table only, `average_seconds_from_assignment` averages, over a person's HIGH and MEDIUM
reviews of entries that **another user** assigned to them before the decision, the time from the
**earliest** such assignment (in ADR 0116's `alert_assignments`) to the decision.
- **Self-assignments never start it,** so a senior or risk officer cannot shorten their own figure
  by self-assigning just before deciding, or by unassigning and self-assigning again.
- **It is null** when the person has no such review in the window. Until assignment is in use, it
  is null for everyone.

**ANOMALY entries are excluded from the averages; that is ASSUMED.** They hold nothing and have no
timer (D-10), so they wait by design. Including them would measure the anomaly queue's policy, not
the analyst's speed. Averages are over HIGH and MEDIUM entries, escalated ones included.

### 4. `getDashboardStats`: each figure defined

All figures are institution-wide and the same for every caller. The definitions go into each
property's `description`.

| Field | Definition |
|---|---|
| `high_open`, `medium_open` | Open entries (PENDING or IN_REVIEW) of that tier in the **standard** queue, so that clicking the badge (UX-DASH-01) lists exactly that many for every role. Escalated entries are not counted here |
| `auto_blocked_today` | `auto_block_events` whose `blocked_at` is in today |
| `reviewed_today` | Reviews (point 2) in today, by anyone in the institution, anomaly reviews included |
| `fraud_prevented_today` | The sum in RWF of the amounts of transactions **whose first DECLINE state** (`decided_at` of the lowest-sequence DECLINE in `v_decision_states`, decision-owner contract §4.7) **falls in today**, whose owner's current state is still DECLINE, and whose latest label is FRAUD. The currency is always RWF |
| `average_review_seconds` | Mean of `decision_duration_seconds` (time to decision, point 3) over today's reviews of HIGH and MEDIUM entries; **null when there are none** |
| `analysts_online` | Distinct users of the institution with role ANALYST, SENIOR_ANALYST or RISK_OFFICER who hold an authenticated real-time session now (ADR 0115), that is, who **have the console open**; null when unknown |
| `day_starts_at`, `utc_offset` (new) | Point 1 |

**`fraud_prevented_today`: confirmed declines.** This is the author's choice on the review's
recommendation; the owner may overrule.
- **The anchor** is the first DECLINE state's `decided_at`. A HIGH transaction auto-blocked at 23:50
  and confirmed by an analyst at 00:10 counts on the day of the block.
- **What it is:** declines confirmed as fraud by a FRAUD label, from an analyst, a risk officer or
  the customer (`label_events.source` ANALYST, SENIOR_OVERRIDE or CUSTOMER; CHARGEBACK has no
  producer). It is **not** "declines without false positives": labels mostly come from the
  analysts' own decisions (point 5), so it is not ground truth either.
- **It rises as labels arrive** and can lag the blocks.
- **The transaction lookup is bounded by time.** Amounts are read from the `transactions`
  hypertable through its view, with `transaction_timestamp` no earlier than 30 days before today's
  start, so the query does not probe every chunk. That 30-day bound is ASSUMED; a transaction whose
  first DECLINE comes more than 30 days after it was made is not counted, and that is stated in the
  field's description.

**`average_review_seconds` is nullable now** (`[number, "null"]`), null when there is no HIGH or
MEDIUM review today. A 0 would be a measured value that was never measured, and a consumer could not
tell "only anomaly reviews today" from "instant reviews". This is a response type change, shipped in
the same lockstep release as the other changes (point 10). The name is kept; see point 10.

**`analysts_online` needs presence shared by every instance.**
- **How it is kept.** Each instance keeps its sessions' users in a Redis sorted set per institution,
  scored by expiry, refreshed every 10 s with a 30 s lifetime (both ASSUMED). ADR 0115 points 2, 3
  and 8 define when the real-time channel writes, refreshes and removes a user's presence.
- **How it is read.** `ZREMRANGEBYSCORE` removes entries whose expiry has passed, then `ZCOUNT` counts
  the rest. Without the removal the set would grow. That a Redis sorted set is the mechanism is
  ASSUMED: it is the smallest shared store the deployment already has.
- **It lags by up to 30 s.** A user who closes the console is counted until their entry expires.
  ADR 0115 point 6.3 therefore recomputes the figures once more 30 s after a local disconnect.
- **While Redis is unavailable the count is unknown,** so the field is nullable:
  `type: [integer, "null"]`. ADR 0115 also closes every real-time session while Redis is down.
- **The definition is "has the console open"** (any console screen holding a real-time session). It
  is not "on duty", which no data records.

**The contract change:**

```yaml
  /dashboard/stats:
    get:
      summary: The header statistics (SRS 5.4, UX-DASH-01), each defined in its description (ADR 0117)

    DashboardStats:
      type: object
      required:
        [high_open, medium_open, auto_blocked_today, reviewed_today, fraud_prevented_today,
         average_review_seconds, analysts_online, day_starts_at, utc_offset]
      additionalProperties: false
      description: >-
        Institution-wide figures, the same for every caller (ADR 0117). "Today" is the institution's
        day in its own time zone: from day_starts_at (UTC) to the next local midnight; utc_offset
        is the offset in force at day_starts_at.
      properties:
        high_open: { type: integer, minimum: 0, description: Open HIGH entries in the standard queue }
        medium_open: { type: integer, minimum: 0, description: Open MEDIUM entries in the standard queue }
        auto_blocked_today: { type: integer, minimum: 0, description: Auto-blocks whose blocked_at is today }
        reviewed_today:
          type: integer
          minimum: 0
          description: CONFIRM_FRAUD and MARK_LEGITIMATE decisions made today and not undone, anomaly reviews included
        fraud_prevented_today:
          description: >-
            Amount (RWF) of transactions whose first DECLINE state is today, still declined, and
            confirmed as fraud by a FRAUD label (from an analyst, a risk officer or the customer).
            Not ground truth. Transactions made more than 30 days before today are not counted.
          $ref: "#/components/schemas/Money"
        average_review_seconds:
          type: [number, "null"]
          minimum: 0
          description: >-
            Mean time to decision of today's reviews of HIGH and MEDIUM entries, from the entry's
            creation to the decision. It includes time waiting in the queue, so it measures the
            queue as much as the analysts; the server cannot observe when a review starts. Null
            when there is no such review today.
        analysts_online:
          type: [integer, "null"]
          minimum: 0
          description: >-
            Staff with a decision role who have the console open (hold a real-time session, ADR
            0115) now; lags by up to 30 s; null when unknown
        day_starts_at: { $ref: "#/components/schemas/Timestamp" }
        utc_offset:
          type: string
          pattern: "^[+-][0-9]{2}:[0-9]{2}$"
          description: The institution's UTC offset in force at day_starts_at, for example +02:00
```

**Examples.** `schema-examples.yaml`'s `DashboardStats` example gains `day_starts_at` and
`utc_offset`, or the examples test fails.

### 5. Agreement with customers: what can be computed honestly, and what is returned

**Accuracy cannot be computed from the data that exists.** Accuracy needs to know whether the
transaction was really fraud, and nothing records that:
- **Chargebacks would come closest.** The `CHARGEBACK` label source has no producer.
- **A customer's answer is not ground truth.** The decision-owner contract records that a
  compromised account sends exactly the "yes" a genuine customer would (§5, CUSTOMER_YES step 2).
- **Labels are mostly the analysts' own decisions,** so comparing a decision with its label compares
  it with itself.

**FR-04-11's "accuracy rate" in the strict sense therefore stays an open requirement** (requirements
table), with that reason. What is returned instead is named for what it is.

**`customer_agreement_rate` replaces `accuracy_rate` everywhere (decided by the owner,
2026-10-22).** The rename applies to `AnalystPerformance`, `TeamMemberPerformance` and the CSV
header. The console changes in the same release (point 10). Its definition:
- **Verified.** A review (point 2) of an auto-blocked transaction whose customer answered the
  verification **after the decision**: `customer_verification_responses.responded_at` later than the
  decision's `created_at`.
  - **Answers given before the decision are not counted** (review finding 10). An answer the analyst
    could see measures copying, not judgement: a WAS_ME on a model-declined transaction approves it
    before any claim exists (decision-owner contract §5), and the account history then shows the
    outcome.
- **Agree.** CONFIRM_FRAUD with NOT_ME, and MARK_LEGITIMATE with WAS_ME. The other two pairs
  disagree.
- **The rate** is agreeing ÷ verified, over the person's reviews of the **30 institution days**
  ending today (`getMyPerformance`) or ending at `to` (the team table, point 7).
- **Null while fewer than 20 of them are verified.** The 30-day window and the minimum of 20 are
  ASSUMED, chosen so that one or two answers cannot swing the rate.
- **The verified count is always given beside it** (`verified_decisions`), so a reader can judge a
  rate by its denominator, and the console shows the count next to the rate.
- **Where the answer is read.** From `customer_verification_responses`, through
  `customer_verifications` and `auto_block_events`, to the transaction. Whether the owner applied,
  deferred or refused the answer does not matter here: the row records what the customer said. The
  decision-owner contract §6.5 settles that a refused or deferred answer still has its row: the
  transaction inserts the response first, and a REFUSED outcome is recorded and committed with it.
  P2's tests keep a case that pins it.

**`false_positive_rate` is the SRS's measure, FP ÷ (FP + TN).** This is the author's choice on the
review's recommendation; the owner may overrule.
- **The SRS's definition.** The glossary defines FPR as the "proportion of legitimate transactions
  incorrectly flagged as fraud", and `ModelPerformance.false_positive_rate` already uses it in that
  sense. The first draft's formula was a false discovery rate, FP ÷ (FP + TP), under the same name.
- **Here, with WAS_ME standing in for "legitimate":** among the person's verified reviews whose
  customer answered WAS_ME after the decision, the share the person marked CONFIRM_FRAUD.
- **Its denominator is given beside it,** as `verified_legitimate_decisions`, over the same 30-day
  window, and the rate is null while that count is below 20 (ASSUMED, as above).
- **Its caveat, in its description:** a WAS_ME may come from a compromised account, so a "false
  positive" here may be a fraud the customer's account confirmed.
- **The share of fraud confirmations that customers disputed** (the first draft's figure) is not
  returned. If the owner wants it, it would be a separate field named
  `disputed_fraud_confirmation_rate`.

**`overturned_decisions`** replaces the first draft's `overridden_decisions`. This is the author's
choice on the review's recommendation; the owner may overrule.
- **What it counts:** the person's reviews in the window that a **risk officer** overrode with a
  **different** decision: a SENIOR_OVERRIDE whose `override_of_decision_id` is the review and whose
  `override_decision` (V81) differs from the review's decision.
- **Why only different ones.** `OverrideRequest.new_decision` may equal the original, and the
  decision-owner contract makes a same-value override a real outcome (DECLINE → DECLINE). A risk
  officer who re-confirms an analyst's fraud decision has not disagreed with it.
- **"A senior" was wrong.** Only RISK_OFFICER may override (the authorisation matrix).

**What these numbers cannot show:**
- **MEDIUM and ANOMALY alerts are never verified.** Verification follows an auto-block, which only
  HIGH decisions make. An analyst who works only MEDIUM holds has a null agreement rate for ever.
- **The rates move after the fact.** They change as customers answer after the decision.

### 6. `getMyPerformance`: defined, with a daily series

```yaml
    AnalystPerformance:
      type: object
      required:
        [reviewed_today, average_review_seconds, backlog, customer_agreement_rate,
         verified_decisions, false_positive_rate, verified_legitimate_decisions,
         overturned_decisions, day_starts_at, utc_offset, daily]
      additionalProperties: false
      description: The caller's own figures (ADR 0117); "today" is the institution's day in its time zone.
      properties:
        reviewed_today: { type: integer, minimum: 0, description: The caller's reviews today, anomaly reviews included }
        average_review_seconds:
          type: [number, "null"]
          minimum: 0
          description: >-
            Mean time to decision of the caller's reviews of HIGH and MEDIUM entries today, from the
            entry's creation; includes queue waiting time. Null when there is none.
        backlog: { type: integer, minimum: 0, description: Open entries assigned to the caller (ADR 0116) }
        customer_agreement_rate:
          type: [number, "null"]
          minimum: 0
          maximum: 1
          description: >-
            Customer agreement, not accuracy: of the caller's reviews in the last 30 institution days
            whose auto-blocked transaction's customer answered the verification after the decision,
            the share where the answer agreed (CONFIRM_FRAUD with NOT_ME, MARK_LEGITIMATE with
            WAS_ME). Null while verified_decisions is below 20. No ground truth exists to compute
            accuracy (ADR 0117).
        verified_decisions:
          type: integer
          minimum: 0
          description: The denominator of customer_agreement_rate, over the same 30 days
        false_positive_rate:
          type: [number, "null"]
          minimum: 0
          maximum: 1
          description: >-
            Of the caller's reviews in the last 30 institution days whose customer answered WAS_ME
            after the decision, the share the caller marked CONFIRM_FRAUD (FP / (FP + TN), the SRS's
            FPR, FR-04-11). A WAS_ME may come from a compromised account. Null while
            verified_legitimate_decisions is below 20.
        verified_legitimate_decisions:
          type: integer
          minimum: 0
          description: The denominator of false_positive_rate, over the same 30 days
        overturned_decisions:
          type: integer
          minimum: 0
          description: The caller's reviews in the last 30 institution days that a risk officer overrode with a different decision
        day_starts_at: { $ref: "#/components/schemas/Timestamp" }
        utc_offset: { type: string, pattern: "^[+-][0-9]{2}:[0-9]{2}$" }
        daily:
          type: array
          minItems: 30
          maxItems: 30
          description: The last 30 institution days, oldest first, today last; days with no review included
          x-unique-key: [date]
          items:
            type: object
            required: [date, reviews, average_seconds_to_decision]
            additionalProperties: false
            properties:
              date: { type: string, format: date }
              reviews: { type: integer, minimum: 0 }
              average_seconds_to_decision:
                type: [number, "null"]
                minimum: 0
                description: Mean time to decision (from the entry's creation, queue waiting included) of that day's HIGH and MEDIUM reviews; null on a day with none
```

**`backlog`'s meaning is new.** It counts open entries assigned to the caller, so until assignment
is used (ADR 0116) it is 0 for everyone.

**The daily series** answers item 11. A daily agreement rate is left out, because 20 verified
reviews in one day is rare.

**Examples.** `schema-examples.yaml`'s `AnalystPerformance` example is rewritten with the new and
renamed fields and a 30-item `daily`, or the examples test fails.

**`getMyPerformance` is not pushed** over the real-time channel (ADR 0115 point 6.3). The console
re-reads it after its own decisions.

### 7. Team performance: `getTeamPerformance`

**Who may call it: SENIOR_ANALYST and RISK_OFFICER.**
- **Seniors:** UX-ROLE-SN-04.
- **Risk officers:** "Additional to SENIOR_ANALYST" (UX-ROLE-RO-01).
- **Not ANALYST:** UX-ROLE-AN-04 says "Cannot view team performance".
- **Not ADMIN:** no source grants it.

**Which rows a caller sees: seniors see analyst and senior rows only.** This is the author's
choice on the review's recommendation; the owner may overrule.
- **A SENIOR_ANALYST** gets ANALYST and SENIOR_ANALYST rows. SN-04 grants "All analysts", and a
  risk officer is a separate, higher role (UX-ROLE-RO-01), not "an analyst". Showing a senior the
  risk officers' personal figures would widen access to staff personal data beyond the source.
- **A RISK_OFFICER** gets all three roles.
- **It is declared** as an `x-authorisation-rules` entry with `effect: filter`, as `listAlerts`
  declares its queue rule, and as a `{ effect: filter }` rule in the golden matrix.

**Row membership.** One row per user of the caller's institution, among the roles the caller may
see, who either is ACTIVE or LOCKED now, or made a review, an escalation or an override in the
range. A deactivated user's work in the range therefore still shows, and a LOCKED user (V2's
`users.status`, a temporary lock after failed sign-ins) does not disappear for 30 minutes.
- **`role` is the user's current role,** not the role held during the range. A user promoted
  within the range appears once, under the new role, with all their work in it.

**Range.** `from` and `to` are institution days, inclusive.
- **Defaults, ASSUMED:** `to` defaults to today and `from` defaults to `to`. So `to` alone, set to
  yesterday, gives yesterday, rather than the first draft's 422.
- **Bounds:** `to` after today, `from` after `to`, or more than 31 days (`to − from` above 30 days)
  answers 422 `validation`, code `out_of_range`. The 31-day bound is ASSUMED, chosen to keep the
  query cost fixed.
- **The agreement columns ignore the range's length.** `verified_decisions`,
  `customer_agreement_rate`, `verified_legitimate_decisions` and `false_positive_rate` are always
  computed over the **30 institution days ending at `to`**, whatever `from` is. A one-day range would
  otherwise leave them null by default (the minimum is 20 verified), and a senior and the analyst
  would see different numbers for the same person. With `to` today, the team row equals the
  analyst's own `getMyPerformance`. `TeamPerformance.agreement_from` names the window's first day.

**Columns:**
- **Reviews per hour is per active hour.** No shift data exists, so "per hour worked" cannot be
  computed. The figure is reviews ÷ **active hours**, the number of distinct institution
  clock-hours in the range in which the person made at least one review. The clock-hour granularity
  is ASSUMED. Its description says what follows from it: the figure is at least 1 whenever there is
  a review, and it rewards bursts over steady work. It is a proxy, and its name says so:
  `reviews_per_active_hour`.
- **Time to decision is named for what it is.** The per-person column is
  `average_seconds_to_decision`, with the queue-waiting caveat in its description. A person who
  works the overnight backlog sorts as slow, and the description says so. Beside it,
  `average_seconds_from_assignment` (point 3) measures the person once assignment is in use.
- **`escalations`**: the person's ESCALATE decisions in the range.
- **`overturned_decisions`**: point 5, over the range.
- **`overrides_made`**: the risk officer's SENIOR_OVERRIDE decisions in the range, null on ANALYST
  and SENIOR_ANALYST rows, since only RISK_OFFICER may override. A risk officer's work is then
  visible without being counted as reviews. This is the author's choice on the review's
  recommendation; the owner may overrule.
- **The agreement columns**, over the 30 days ending at `to` (above).

```yaml
  /team/performance:
    get:
      tags: [alerts]
      operationId: getTeamPerformance
      summary: Every analyst's reviews, time to decision and customer agreement over a range of days, as JSON or CSV (UX-ROLE-SN-04)
      description: |
        Days are institution days in the institution's time zone (ADR 0117), inclusive; at most 31
        days; `to` defaults to today and `from` to `to`. Review counts and times are over the range;
        the agreement figures are always over the 30 institution days ending at `to`. A
        SENIOR_ANALYST sees ANALYST and SENIOR_ANALYST rows; a RISK_OFFICER sees all three roles.
        Rows are unsorted; sorting is the client's. `Accept: text/csv` returns a subset of the
        columns as RFC 4180 CSV (UTF-8 with a byte-order mark, CRLF, one header row), with
        `Content-Type: text/csv; charset=utf-8; header=present`, at most 1 MiB (1,048,576 bytes);
        each CSV export is audited (USER_ADMIN, action TEAM_PERFORMANCE_EXPORTED). More than 1,000
        rows is answered with 422 result-too-large, never truncated.
      x-required-roles: [SENIOR_ANALYST, RISK_OFFICER]
      x-authorisation-rules:
        - rule: A SENIOR_ANALYST caller gets ANALYST and SENIOR_ANALYST rows only; a RISK_OFFICER caller gets all three roles
          effect: filter
      parameters:
        - { name: from, in: query, schema: { type: string, format: date } }
        - { name: to, in: query, schema: { type: string, format: date } }
      responses:
        "200":
          description: The team's figures
          headers:
            Content-Disposition:
              description: "For text/csv: attachment; filename=team-performance_<from>_<to>.csv"
              schema: { type: string }
          content:
            application/json:
              schema: { $ref: "#/components/schemas/TeamPerformance" }
            text/csv:
              schema: { type: string, contentMediaType: text/csv }
        "400": { $ref: "#/components/responses/ValidationProblem" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "406": { $ref: "#/components/responses/NotAcceptable" }
        "422": { $ref: "#/components/responses/ValidationOrLimitProblem" }
        "503": { $ref: "#/components/responses/ServiceUnavailable" }
        default: { $ref: "#/components/responses/UnexpectedProblem" }

    TeamPerformance:
      type: object
      required: [from, to, day_starts_at, utc_offset, agreement_from, generated_at, analysts]
      additionalProperties: false
      properties:
        from: { type: string, format: date }
        to: { type: string, format: date }
        day_starts_at: { $ref: "#/components/schemas/Timestamp", description: Start of the from day, in UTC }
        utc_offset:
          type: string
          pattern: "^[+-][0-9]{2}:[0-9]{2}$"
          description: The institution's UTC offset in force at day_starts_at
        agreement_from:
          type: string
          format: date
          description: First day of the 30 institution days ending at `to`, over which the agreement columns are computed
        generated_at: { $ref: "#/components/schemas/Timestamp" }
        analysts:
          type: array
          maxItems: 1000
          description: One row per user; each `user_id` appears once (`x-unique-key`)
          x-unique-key: [user_id]
          items: { $ref: "#/components/schemas/TeamMemberPerformance" }
    TeamMemberPerformance:
      type: object
      required:
        [user_id, first_name, last_name, role, reviews, active_hours, reviews_per_active_hour,
         average_seconds_to_decision, average_seconds_from_assignment, escalations,
         overturned_decisions, overrides_made, verified_decisions, customer_agreement_rate,
         verified_legitimate_decisions, false_positive_rate]
      additionalProperties: false
      properties:
        user_id: { type: string, format: uuid }
        first_name: { $ref: "#/components/schemas/PersonName" }
        last_name: { $ref: "#/components/schemas/PersonName" }
        role:
          type: string
          enum: [ANALYST, SENIOR_ANALYST, RISK_OFFICER]
          description: The user's current role, not the role held during the range
        reviews: { type: integer, minimum: 0 }
        active_hours: { type: integer, minimum: 0, description: Distinct institution clock-hours in the range with at least one review }
        reviews_per_active_hour:
          type: [number, "null"]
          minimum: 0
          description: >-
            reviews / active_hours; a proxy for reviews per hour worked, which no shift data allows.
            At least 1 whenever there is a review, and it favours bursts over steady work. Null when
            active_hours is 0
        average_seconds_to_decision:
          type: [number, "null"]
          minimum: 0
          description: >-
            Mean time from the entry's creation to the decision, over the range's HIGH and MEDIUM
            reviews. It includes time the alert waited in the queue, so it measures the queue as
            much as the person: someone who works an overnight backlog looks slow. Null when none
        average_seconds_from_assignment:
          type: [number, "null"]
          minimum: 0
          description: >-
            Mean time from the earliest assignment of the entry to this person by another user to
            the decision, over the range's HIGH and MEDIUM reviews of such entries (ADR 0116);
            self-assignments never start it. Null when none
        escalations: { type: integer, minimum: 0, description: ESCALATE decisions in the range }
        overturned_decisions: { type: integer, minimum: 0, description: Reviews in the range that a risk officer overrode with a different decision }
        overrides_made:
          type: [integer, "null"]
          minimum: 0
          description: SENIOR_OVERRIDE decisions in the range; null on ANALYST and SENIOR_ANALYST rows
        verified_decisions: { type: integer, minimum: 0, description: The denominator of customer_agreement_rate, over the 30 days ending at `to` }
        customer_agreement_rate:
          type: [number, "null"]
          minimum: 0
          maximum: 1
          description: Customer agreement (not accuracy) over the 30 days ending at `to`, as AnalystPerformance; null while verified_decisions is below 20
        verified_legitimate_decisions: { type: integer, minimum: 0, description: The denominator of false_positive_rate, over the 30 days ending at `to` }
        false_positive_rate:
          type: [number, "null"]
          minimum: 0
          maximum: 1
          description: FP / (FP + TN) over the 30 days ending at `to`, as AnalystPerformance; a WAS_ME may come from a compromised account; null while verified_legitimate_decisions is below 20
```

- **The row is flat.** `user_id`, `first_name`, `last_name` and `role` sit at the row's top level,
  so `x-unique-key: [user_id]` names a property in the item's `required` list, as
  `test_unique_keys_name_properties_of_their_items` requires and as
  `test_examples_respect_the_unique_keys_their_schemas_declare` can index. The first draft's
  dotted `analyst.user_id` and its `allOf` are withdrawn.
- **The keyed-array count rises by two** (`daily` and `analysts`): from 6 at `11448ce` to 8, or 9
  with ADR 0116's `AssignableStaffList`.
- **`test_every_staff_name_field_uses_the_person_name_rule`** gains `TeamMemberPerformance`.
- **`schema-examples.yaml`** gains a `TeamPerformance` example (the examples test requires one).

**The 1,000-row limit has its own problem: `result-too-large`.** The request is valid, so a
`ValidationProblem` with a field code was wrong. The contract gains:

```yaml
components:
  schemas:
    ProblemType:
      enum: [..., "urn:fraudshield:problem:result-too-large"]   # one value appended
    ResultTooLargeProblem:
      allOf:
        - $ref: "#/components/schemas/Problem"
        - type: object
          properties:
            type: { const: "urn:fraudshield:problem:result-too-large" }
            status: { const: 422 }
  responses:
    ValidationOrLimitProblem:
      description: >-
        422: a well-formed field with an unacceptable value (validation, with field codes), or a
        valid request whose answer would exceed a documented server limit (result-too-large, no
        field codes; the limit is named in `detail`). Nothing is truncated.
      x-problem-types:
        - "urn:fraudshield:problem:validation"
        - "urn:fraudshield:problem:result-too-large"
      content:
        application/problem+json:
          schema:
            oneOf:
              - $ref: "#/components/schemas/ValidationProblem"
              - $ref: "#/components/schemas/ResultTooLargeProblem"
```

- **The two alternatives cannot both match:** each fixes its own `type`.
- **The tests:** the problem catalogue test is satisfied (the new type is declared by a response);
  `ResultTooLargeProblem` joins `OPEN_BY_DESIGN` in `test_openapi_examples.py`, as RFC 9457 problems
  stay open; and `schema-examples.yaml` gains a `ResultTooLargeProblem` example.
- **ADR 0116's `listAssignableStaff`** uses the same response for its own 1,000 bound.

**The authorisation matrix:**

```yaml
  getTeamPerformance:
    roles: [SENIOR_ANALYST, RISK_OFFICER]
    ref: "UX-ROLE-SN-04; RISK_OFFICER per UX-ROLE-RO-01; ANALYST excluded by UX-ROLE-AN-04; seniors see analyst and senior rows only (ADR 0117)"
    rules:
      - { effect: filter }
```

Row-level security limits every row to the caller's institution; the filter rule above limits the
roles.

### 8. The CSV

**Format:**
- **Encoding and layout.** RFC 4180, UTF-8 **with** a byte-order mark, CRLF line ends, and one
  header row. The response's `Content-Type` is `text/csv; charset=utf-8; header=present` (RFC 4180
  §3). That the BOM is needed is ASSUMED: spreadsheet software otherwise misreads accented names in
  Kinyarwanda and French. RFC 4180 neither requires nor forbids one.
- **Values.**
  - **Numbers** use `.` as the decimal mark with at most 4 decimals, and no thousands separator.
  - **A null is an empty cell.**
  - **Dates** are ISO 8601.

**Columns: each justified by a source, or left out of the file.** A file of staff names and
per-person figures leaves the system's control, so it carries only what a source asks for, plus
what is needed to read it:

| Column | Source |
|---|---|
| `user_id` | Needed to tell apart two staff with the same name, and to join exports of different ranges; an opaque id, no more personal than the name beside it |
| `first_name`, `last_name`, `role` | UX-ROLE-SN-04 "All analysts": identifies the row |
| `from`, `to`, `utc_offset` | Needed to read the file: which days, in which offset (review finding 9) |
| `reviews`, `active_hours`, `reviews_per_active_hour` | SN-04 "reviews/hour" (the proxy and its inputs) |
| `average_seconds_to_decision`, `average_seconds_from_assignment` | SN-04 "avg review time", under the names of point 7 |
| `verified_decisions`, `customer_agreement_rate` | SN-04 "accuracy rate", as customer agreement (point 5), with its denominator |
| `verified_legitimate_decisions`, `false_positive_rate` | FR-04-11 "false positive rate", with its denominator |

**Left out of the CSV:** `escalations`, `overturned_decisions` and `overrides_made`. No source asks
for them in an export. They stay in the JSON table, which does not leave the console, as context
for a senior or risk officer reading it. The export therefore never shows what the table does
not.

**Formula injection.** A text cell that starts with `=`, `+`, `-`, `@`, a tab or a carriage return
is prefixed with `'`. Names already pass the person-name rule (ADR 0013), so this is a second layer.

**Size.**
- **At most 1,000 rows** (`maxItems`). That 1,000 is far above any institution's decision-role team
  is ASSUMED.
- **The byte bound is 1 MiB.** The worst case, with 100-code-point names in 4-byte UTF-8, is about
  0.96 MB for 1,000 rows: under the bound, but not well under. With the Basic Multilingual Plane
  letters Kinyarwanda and French use, it is well under. A test builds the worst-case row and
  asserts the bound.
- **More rows** are answered with 422 `result-too-large`, never truncated (point 7).
- **No paging,** because sorting is the client's and needs every row.

**It is the same operation.** CSV is chosen with `Accept: text/csv`. It has the same roles, the same
row filter and the same rows.

**The export is audited.** This is the author's choice on the review's recommendation; the owner
may overrule.
- **The event:** `USER_ADMIN`, action `TEAM_PERFORMANCE_EXPORTED`, recording the caller, `from`,
  `to`, `utc_offset` and the number of rows. No thirteenth D-32 type is needed: D-32's types carry
  an `action`.
- **When:** written and committed before the first byte of the file is sent. If the audit write
  fails, the answer is 503 and no file is sent.
- **The JSON variant is not audited.** It is a screen read like every other analyst read, and does
  not leave the console.

### 9. Cost

The team query and the 30-day windows scan `alert_decisions` by institution and time. The
migration of point 1 (V8x, claimed in `M8B_updates.md`) adds:
- **`(institution_id, created_at)` on `alert_decisions`, partial on
  `decision IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE')`,** for the institution-wide figures
  (`reviewed_today`, `average_review_seconds`). With `analyst_id` second, the per-analyst index
  would scan the institution's whole range, since PostgreSQL before 18 has no skip scan.
- **`(institution_id, analyst_id, created_at)` on `alert_decisions`,** for per-person windows.
- **`(institution_id, blocked_at)` on `auto_block_events`,** for `auto_blocked_today`; V5 has only
  the per-account index.
- **The `transactions` lookup** for `fraud_prevented_today` is bounded by `transaction_timestamp`
  (point 4), so the hypertable is not probed in every chunk.

**A run of these queries has not been measured.** They are aggregates over at most 31 days (30 for
the agreement window) of one institution's decisions. Whether a cache is needed is decided by
measurement in CI, not assumed.

### 10. What ships together, and one name left as it is

**One lockstep release.** These response changes break the console's generated client until it is
regenerated, so the console and the server ship together (as ADR 0110's consequences):
- `accuracy_rate` renamed `customer_agreement_rate` (the owner's decision);
- `average_review_seconds` and `analysts_online` made nullable;
- new required fields in `DashboardStats` and `AnalystPerformance`;
- the console's labels: "Agreement with customers" beside its verified count, never "Accuracy".

**`average_review_seconds` keeps its name** in `DashboardStats` and `AnalystPerformance`, made
nullable, with "time to decision" and the queue caveat in its description. Review finding 5 asked
for the honest name in the new schemas (`TeamMemberPerformance`, `daily`, the CSV), and they use
`average_seconds_to_decision`. The two existing fields were not in that finding, and the header's
SRS label is "Avg Review". The cost is that one quantity carries two names across operations.
Renaming both existing fields too costs nothing more in this lockstep release; that is left to the
owner (open questions).

## Requirements: closed, and left open

| Row | What this does | What stays open |
|---|---|---|
| UX-ROLE-SN-04 | Every analyst's reviews per active hour, time to decision (and from assignment), customer agreement with its count, sortable on the client, and the CSV | "Reviews/hour" is per *active* hour (no shift data). "Accuracy" is customer agreement (point 5). A senior sees analyst and senior rows only. The code and the console |
| FR-04-11 | Defines each figure. `false_positive_rate` is the SRS's FPR, computed from the verification tables, as the acceptance asks | **"Accuracy rate" in the strict sense stays open:** it is not computable from existing data, because no ground truth exists (no chargeback producer; a customer's answer can come from a compromised account; labels mostly echo the analysts' own decisions). "Update in real time" depends on the console re-reading after its own decisions (ADR 0115). MEDIUM-only analysts have no agreement figure |
| UX-ROLE-AN-04 | The daily trend (`daily`) | the console's chart |
| UX-DASH-01 | Defines all seven figures, plus `day_starts_at` and `utc_offset` | `analysts_online` is null while Redis is down, and lags by up to 30 s. The real-time part is ADR 0115's |
| Item 9 | Anomaly reviews count, and are excluded from averages (ASSUMED) | none |

## Deliberately left out

- **Shift or roster data,** and therefore "on duty" and true per-hour rates.
- **A client-supplied review start time.**
- **Accuracy against labels.** Labels mostly come from the analysts themselves.
- **Chargebacks.** No producer exists.
- **Per-queue breakdowns in the header figures.**
- **A daily agreement rate.**
- **The value of every decline today** (`declined_value_today`), larger and earlier than
  `fraud_prevented_today` and including false positives. It can be added as a second field if the
  owner wants it.
- **The share of fraud confirmations customers disputed** (`disputed_fraud_confirmation_rate`), and
  a count of same-value overrides.
- **Server-side sorting and paging** of the team table.
- **ADMIN access** to team figures.

## Open questions for the owner

Questions 1 (the time zone) and 2 ("accuracy") were decided by the owner on 2026-10-22 (points 1
and 5). Questions 3 to 8 are answered by the author on the review's recommendations, each marked in
the text as "the author's choice on the review's recommendation; the owner may overrule":
- **3.** Seniors see analyst and senior rows only, declared as a filter rule (point 7).
- **4.** `overrides_made` is added for risk officers (point 7).
- **5.** `fraud_prevented_today` stays "confirmed declines", with its anchor and honest wording
  (point 4).
- **6.** `average_review_seconds` is nullable now (point 4).
- **7.** The CSV export is audited under USER_ADMIN (point 8).
- **8.** The ASSUMED constants are starting values: the 30-day windows and the minimum of 20, with
  the denominators exposed; the 31-day range, now one rule with the agreement window (point 7); the
  BOM; the presence timings, with the extra recompute; 1,000 rows, with `result-too-large`; and
  the 30-day bound on the decline lookup (point 4).

One question remains:
1. **Rename `average_review_seconds` to `average_seconds_to_decision`** in `DashboardStats` and
   `AnalystPerformance` too, in the same lockstep release, so one quantity has one name (point 10)?

## Review 1's findings and their answers

| # | Severity | Finding | Answer |
|---|---|---|---|
| 1 | MAJOR | `accuracy_rate` kept a name whose meaning changed, for a reason that did not hold | Fixed by the owner's decision (2026-10-22): `customer_agreement_rate` everywhere (`AnalystPerformance`, `TeamMemberPerformance`, CSV), in the same lockstep release; strict accuracy left open in FR-04-11's row with its reason (points 5, 10) |
| 2 | MAJOR | `false_positive_rate` was a false discovery rate under the SRS's FPR name | Fixed in point 5: FP ÷ (FP + TN), with `verified_legitimate_decisions` as its denominator and the compromised-account caveat; the disputed-confirmation figure is not returned (named, left out); author's choice on the review's recommendation |
| 3 | MAJOR | `overridden_decisions` counted same-value overrides, and "a senior" is a risk officer | Fixed in point 5: renamed `overturned_decisions`, counting only overrides whose `override_decision` differs, "by a risk officer"; author's choice on the review's recommendation |
| 4 | MAJOR | `average_review_seconds` was 0 with no data | Fixed in points 4 and 6: `[number, "null"]` in `DashboardStats` and `AnalystPerformance`, null when no HIGH or MEDIUM review, in the lockstep release |
| 5 | MAJOR | The team table and CSV ranked people on a figure that measures the queue | Fixed in points 3 and 7: the per-person figure is `average_seconds_to_decision` with the queue caveat in its description (team table, `daily`, CSV); `average_seconds_from_assignment` added beside it, which measures the person once assignment is used. The two existing fields keep their name; renaming them is the one open question (point 10) |
| 6 | MAJOR | Seniors saw risk officers' personal figures by default | Fixed in point 7: seniors get ANALYST and SENIOR_ANALYST rows, risk officers all three, declared as an `x-authorisation-rules` filter entry and a matrix `{ effect: filter }` rule |
| 7 | MINOR | `x-unique-key: [analyst.user_id]` failed the contract's checks | Fixed in point 7: the row is flattened (`user_id` at the top level); the keyed-array count is raised (6 to 8, or 9 with ADR 0116) |
| 8 | MINOR | OpenAPI conventions: CSV schema, Content-Type, the 1,000-row 422, the stats summary, examples, the `allOf` | Fixed: `contentMediaType: text/csv` with the byte bound in the description; `text/csv; charset=utf-8; header=present`; the limit answers 422 `result-too-large` through `ValidationOrLimitProblem`, not a field code (point 7); the `getDashboardStats` summary no longer says "seven" (point 4); the example edits are listed (points 4, 6, 7); the `allOf` is gone with the flat row |
| 9 | MINOR | `day_starts_at` could not carry the offset; the CSV had no zone; the offset came from a dataset parameter | Fixed by the owner's decision (2026-10-22): an IANA zone column on `institutions` (Kinshasa for DR Congo), and an explicit `utc_offset` beside `day_starts_at` in all three schemas and as a CSV column (point 1) |
| 10 | MINOR | Agreement counted customer answers given before the decision | Fixed by the owner's decision (2026-10-22): only answers whose `responded_at` is after the decision's `created_at` verify it (point 5) |
| 11 | MINOR | Team agreement columns null by default and different from "My performance"; `to` alone answered 422 | Fixed in point 7: agreement columns over the 30 days ending at `to`, whatever the range (named by `agreement_from`); `from` defaults to `to` |
| 12 | MINOR | The review-time start could be moved by the person measured; the write unpinned; old rows unaddressed | Fixed in point 3: the start is the entry's `created_at` alone; the value is computed in a BEFORE INSERT trigger from `NEW.created_at`; NULL durations are excluded. The from-assignment figure ignores self-assignments |
| 13 | MINOR | `fraud_prevented_today`'s day undefined; its rationale contradicted point 5 | Fixed in point 4: anchored at the first DECLINE state's `decided_at`; described as declines confirmed by a FRAUD label from an analyst, a risk officer or the customer, not as free of false positives |
| 14 | MINOR | `analysts_online` and ADR 0115's push trigger disagreed | Fixed in point 4 (and ADR 0115 point 6.3): a recompute 30 s after a disconnect, a stated lag of up to 30 s, "has the console open", and `ZREMRANGEBYSCORE` named |
| 15 | MINOR | The proposed index did not serve the institution-wide figures | Fixed in point 9: a partial `(institution_id, created_at)` index, `(institution_id, blocked_at)` on `auto_block_events`, and a time-bounded transaction lookup |
| 16 | MINOR | The CSV of staff data was not audited and carried more than the source asks | Fixed in point 8: audited under USER_ADMIN, action TEAM_PERFORMANCE_EXPORTED, with caller, range and row count; each CSV column is justified by a source, `user_id` included; `escalations`, `overturned_decisions` and `overrides_made` are left out of the file |
| 17 | MINOR | LOCKED users disappeared; `role` undefined in time | Fixed in point 7: LOCKED users are included; `role` is the current role |
| 18 | MINOR | Unmarked assumptions; one assumption the contract already settles | Fixed: the ESCALATE exclusion (point 2), the anomaly exclusion (points 2, 3), the clock-hour granularity with its consequences in the description (point 7), the default range (point 7) and the 1,000-row size claim, now "under, but not well under" (point 8), are marked or corrected; the refused-answer row is cited to the decision-owner contract §6.5 instead of assumed (point 5) |
| Q1 | | The institution's time zone | Decided by the owner (2026-10-22): IANA zone column, `utc_offset` in responses (point 1) |
| Q2 | | "Accuracy" | Decided by the owner (2026-10-22): `customer_agreement_rate`, after-the-decision answers, 30 days, null below 20, count beside it; strict accuracy open (point 5) |
| Q3 | | Team rows | Seniors see analyst and senior rows only (point 7); author's choice on the review's recommendation |
| Q4 | | Overrides as work | `overrides_made` for risk officers (point 7); author's choice on the review's recommendation |
| Q5 | | `fraud_prevented_today` | Confirmed declines, anchored and worded as finding 13 (point 4); author's choice on the review's recommendation |
| Q6 | | Nullable average | Yes (finding 4); author's choice on the review's recommendation |
| Q7 | | Auditing the export | USER_ADMIN, TEAM_PERFORMANCE_EXPORTED (point 8); author's choice on the review's recommendation |
| Q8 | | The ASSUMED constants | Accepted as starting values with the review's conditions (open questions); author's choice on the review's recommendation |
