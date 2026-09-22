# 0061 — Decision engine semantics the SRS left open

- **Status:** Accepted
- **Date:** 2026-09-22
- **Requirements affected:** FR-03-01, FR-03-02, FR-03-03, FR-03-06, FR-03-07, FR-05-05, FR-05-07,
  NFR-REL-01
- **Defects referenced:** D-02, D-06, D-10, D-12, D-17, D-18

## Context

E.6 fixes the order of evaluation but leaves details open that change outcomes: what tier a frozen
account's decline carries, what the rule-based fallback is, how "fraud rate over a rolling
window" is counted across instances, and what a rule does with a missing value.

## Decision

1. **Order** exactly as E.6, as one pure function (`DecisionEngine`): frozen → DECLINE with
   `ACCOUNT_FROZEN` first; else tier from half-open per-channel thresholds (compared in decimal,
   so 0.85 is HIGH at 0.85); custom rules only raise; an open MCC breaker makes LOW into MEDIUM;
   LOW with an anomaly percentile at or above the configured threshold goes to ANOMALY_REVIEW
   without a hold (D-10). A frozen account's decline reports the computed tier, because reporting
   HIGH would be a claim the model did not make.
2. **Rules are three-valued** (Kleene): a comparison with a missing value is UNKNOWN and a rule
   fires only on TRUE, so absent data never raises a tier unless the rule asks `is_null`.
3. **Fallback rule set `fallback-rules-1`** (C.4): HIGH on 5 transactions in 60 s, a new
   counterparty with at least 10,000,000 RWF, or the SIM-swap takeover pattern (swap under 7 days,
   new counterparty, at least 500,000 RWF); MEDIUM on 10 in an hour, 2,000,000 RWF or more, or a
   new counterparty or new device with 500,000 RWF or more. It leans to holding: while the model is
   down, a hold costs a review and a wrong approval costs a customer's money. The persisted record
   needs a score that does not exist, so it carries the lower edge of its tier's interval (0, the
   MEDIUM threshold or the HIGH threshold) flagged by `ml_unavailable_fallback`, which keeps
   expected-loss ordering consistent with the tier.
4. **MCC counts** are one-minute buckets in Redis shared by every instance; the window's edge has
   one-minute granularity. The breach test itself is exact (`fraud > threshold × n` with n at least
   the minimum), so exactly 5.0% does not open it and one more fraud does (D-18). The monitor runs
   every 5 s, well within FR-03-07's 60 s; state changes are compare-and-set.
5. **Reason codes** group the 44 features into coarse codes, at most three, because D-12 limits
   what the machine response reveals.
6. **Audit**: holds, frozen-account declines, auto-blocks, freezes, every later decision change,
   breaker changes and idempotency conflicts are audited; LOW approvals are not, because
   `decision_states` is already an append-only record of every one and auditing each would put the
   audit hash chain on the path of every payment.
7. **Distinct counts are computed in the store.** The counterparty's senders over 24 h and the
   device's accounts over 7 days keep one member per account, scored with its latest time
   (`ZADD GT`), and are read with `ZCOUNT` and `ZSCORE`. A popular merchant's set therefore holds
   one entry per payer, not one per payment, and the read returns two integers rather than the set.
   An earlier version stored one member per payment and counted in Java; under the benchmark's
   single counterparty it shipped tens of thousands of members per decision. The cost is one
   deviation for late arrivals: an account whose latest payment is newer than the scored
   transaction is not counted even if it also paid inside the window, so a late-arriving
   transaction can see one sender fewer than the batch features would. In-order traffic is exact.
   Decisions, the response and the audit record run before the feature-store update and the MCC
   count, which run on a virtual thread after the response (C.2); a transaction that arrives
   within a few milliseconds of its predecessor on the same account can miss that predecessor.

## Consequences

Tested by `DecisionEngineTest` (including a generative property over thresholds and rules),
`FreezeAndBreakerTest`, `FallbackRulesTest`, `RuleCompilerTest` and
`RedisAdaptersTest.counterpartyAndDeviceSetsCountDistinctOtherAccountsInOpenWindows`. The
fallback's thresholds are configuration; changing them is a new version name.
