# 0100 — An assumed ingest rate limit, counted in transactions

- **Status:** Proposed, 2026-09-24 (M10). **The number below is ASSUMED**, not sourced and not
  measured; it needs the owner's decision and M6's implementation before the verification campaign
  can treat a refusal as a pass or a failure.
- **Date:** 2026-09-24
- **Requirements affected:** FR-01-01, FR-01-03, FR-01-06, NFR-PERF-01, NFR-PERF-10, TEST-09
- **ADRs referenced:** 0010 (gate numbers only from a dedicated machine), 0011 (API contract
  conventions), 0032 (the latency gate is a throughput requirement)

## Context

The frozen contract declares that ingest operations may refuse a caller: `429` with the problem
type `urn:fraudshield:problem:rate-limited` and a `Retry-After` header of at least one second.
Part E.1 requires a per-key request budget. M6 implements exactly that — a token bucket per API
key in Redis, with a per-instance bucket when Redis is unavailable, marked `RateLimit-Degraded:
true` so that an outage weakens the control visibly instead of switching it off — and every answer
carries `RateLimit-Limit` and `RateLimit-Remaining`.

What nothing states is **the budget**. The specification quantifies only the authentication
limits (login attempts per window per address). M6's implementation therefore carries a
configurable budget with a default of **200 requests per second sustained, burst 400, per API
key**, which is an implementation default and not a requirement: no document says it is right, and
nothing fails if it is wrong.

Two consequences matter to M10, and one of them is a defect rather than a gap.

1. **The campaign cannot classify a refusal.** A `429` during a load run is either the control
   working or the control misconfigured, and with no specified budget there is no rule that
   decides which. A verification report that records "the system refused some requests" without
   being able to say whether it should have is not evidence about anything.
2. **The budget is counted in requests, and the batch endpoint accepts up to a thousand
   transactions in one request.** The filter takes one token per HTTP request, so a caller using
   the batch endpoint may submit up to a thousand times the transactions of a caller using the
   single endpoint, for the same budget. The limit protects the API's request path and not the
   capacity that actually costs money: scoring, the feature store, Kafka and the database.

The system's specified capacity is the throughput target of NFR-PERF-01 (10,000+ transactions per
second sustained), with the earlier roadmap phase at 1,000. Those are the only quantities in the
specification from which a per-tenant budget can honestly be derived.

## Decision

Proposed, for the owner to accept, amend or reject.

1. **Count the budget in transactions, not in requests.** A single-transaction request costs one
   unit; a batch request costs its item count, taken once when the batch is accepted. This makes
   the budget mean the same thing on both endpoints and ties it to the work the system performs.
   A batch whose item count exceeds the remaining burst is refused whole, with `Retry-After`, and
   never partially accepted: a partially accepted batch would give the caller a per-item outcome
   the contract's `JobAccepted` cannot express.
2. **Two budgets, both per API key.**
   - *Default for a newly minted key:* M6's current default, unchanged — 200 transactions per
     second sustained, burst 400. A key created through the admin endpoint starts here, which
     keeps an accidental or hostile key cheap.
   - *Integrator budget, set explicitly per key:* **2,000 transactions per second sustained, burst
     4,000** (two seconds of sustained rate).
3. **The reasoning for 2,000, stated so that it can be argued with.** The deployment is specified
   to sustain 10,000 transactions per second. A budget of one fifth of that lets a single
   integrator take a substantial share without being able to exhaust the system alone; five
   integrators at their full sustained budget reach the specified system capacity, which is the
   point at which the system's own throughput requirement, not the limiter, is the binding
   constraint. The burst of two seconds' worth covers the retry surge that follows a client-side
   outage — the case where an integrator's queue drains at once — without letting one key exceed
   the whole system's specified rate even momentarily. **Every one of those numbers is a modelling
   choice: no published integrator profile, no contract and no measurement supports 2,000 over
   1,500 or 3,000.**
4. **Idempotent replays consume budget.** A replay still costs authentication, validation, a
   fingerprint comparison and a cache read. Exempting replays would make the cheapest way to
   exceed a budget the resending of one transaction, which is also the shape of the duplicate
   storm the reliability requirement describes.
5. **`Retry-After` is the whole number of seconds until the caller's next unit refills, floored at
   one second**, as the contract requires.
6. **Until this ADR is accepted, the campaign records refusals as observations.** A `429` is
   counted, reported with the budget in force and the `RateLimit-*` headers that accompanied it,
   and is neither a pass nor a failure. Refusals are excluded from the error percentage of
   NFR-PERF-10, which measures the system failing, not the system declining; the campaign
   configures the key it uses above the target rate so that the throughput rows measure the
   system rather than the limiter, and records the configured budget in the evidence file.

## Consequences

- **M6 implements it** (the limiter is M6's; this ADR does not change any frozen contract, since
  the `429`, its problem type and `Retry-After` are already in the OpenAPI document and the
  `RateLimit-*` headers are already emitted). The change is the unit the bucket counts, the batch
  charge, and a second configured budget. Flagged in `docs/parallel/M6_updates.md`.
- **M11 carries it as a limitation**: a rate limit presented as a requirement would be a claim the
  project cannot support. It belongs with the other assumed parameters, and the paper's rule that
  an assumed number is labelled as one applies unchanged. Flagged in
  `docs/parallel/M11_updates.md`.
- **If the owner rejects the transaction-count change**, the request-count budget stands and the
  batch hole stands with it; the campaign then reports, in the verification report, that the
  documented protection can be bypassed by a factor of the maximum batch size, which is a finding
  rather than a failure of any row.
- **No row moves because of this ADR.** It adds no measurement and closes no requirement.

## Alternatives considered

- **Leave it unspecified and let operations configure it.** This is the current position. It makes
  every `429` unclassifiable, and it leaves the only written number — an implementation default —
  doing the work of a requirement while nothing tests it.
- **Derive the budget from measured integrator behaviour.** There is no integrator and no
  production traffic; the figure would be invented and dressed as evidence.
- **Charge batches one unit and cap batch submissions separately.** A second limiter for the same
  resource, with its own number to justify; the transaction count already expresses it.
