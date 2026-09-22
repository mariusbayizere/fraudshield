# 0061 — Decision engine semantics the SRS left open

- **Status:** Accepted; revised the same day after the owner's decision on ADR 0033 (points 3 and 7)
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
3. **Fallback rule set `fallback-rules-2`** (C.4): MEDIUM (a hold) at 2,000,000 RWF or more,
   otherwise LOW; it never blocks. The fallback sees the transaction and nothing else, because the
   account's behaviour exists only as model features and the decision path does not compute them
   (point 7). The institution's own rules on request fields still apply; rules on features evaluate
   UNKNOWN while the scorer is down and do not fire (point 2). Blocking on amount alone would decline
   legitimate payments without evidence, so the fallback leans to holding. This is weaker than
   `fallback-rules-1`, which it replaces: velocity bursts, new-counterparty amounts and the SIM-swap
   pattern are not caught until the replay job re-scores fallback decisions after recovery (C.4).
   The persisted record needs a score that does not exist, so it carries the lower edge of its
   tier's interval (0 or the MEDIUM threshold) flagged by `ml_unavailable_fallback`, which keeps
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
7. **No model features in Java; the boundary of the decision path's state** (owner decision
   2026-09-22, ADR 0033). Each of the 44 features has one implementation, the Python feature
   pipeline with its parity suite (M3). The scorer reads the account context from the feature
   store, computes the features and returns their values in `ScoringResult.feature_vector`; the
   feature store has **one writer, the ML side**. The decision path sends the transaction and the
   account token, and custom rules, the D-25 self-service policy and the persisted record read
   feature values from that result, never recomputing them. The decision path's own Redis state is
   decision state only: freeze windows and the frozen flag, MCC circuit-breaker counts and states,
   idempotency claims, decision states and hold timers, under `fs:{...}` keys that never overlap
   the store's `fs:fv1:` prefix. An earlier version of this branch computed the context in Java
   and changed the definition of `counterparty_unique_senders_24h` for late arrivals; that was
   training–serving skew and it was removed.

## Consequences

Tested by `DecisionEngineTest` (including a generative property over thresholds and rules),
`FreezeAndBreakerTest`, `FallbackRulesTest`, `RuleCompilerTest`,
`DecisionServiceTest.rulesReadTheFeatureValuesTheScorerReturned` and `GrpcScorerTest` (the request
carries no context). The fallback's threshold is configuration; changing it is a new version name.
