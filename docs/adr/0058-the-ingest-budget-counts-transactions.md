# 0058 — The ingest budget counts transactions, at an assumed 2,000 per second per key

- **Status:** Accepted (owner decision, 2026-09-24)
- **Date:** 2026-09-24
- **Decided by:** the owner, adopting ADR 0100 (M10, `m10/verification`) with one change; written by
  the author
- **Requirements affected:** FR-01-02 (E.1's 429), FR-01-06 (batch ingestion), NFR-SEC-03
- **ADRs referenced:** 0100 (M10's proposal and the defect it found), 0060 (M6 numbering: M6 counts
  down from 0059 now its block is full), 0010

## Context

M10 found, while specifying the rate limit for the verification campaign, that **the control does
not hold as built**: the per-API-key budget was counted in requests, and the batch endpoint accepts
up to 1,000 transactions per request, so a caller using batches got up to 1,000 times the
throughput of one using single submissions, for the same budget. The limit protected the request
path and not the capacity that costs money: scoring, the feature store, Kafka and the database.

The budget itself, 200 requests per second with a burst of 400, was an implementation default doing
a requirement's job: no document says it is right and nothing fails if it is wrong.

## Decision

1. **The budget is counted in transactions** (ADR 0100 point 1). A request for one transaction costs
   one unit. A batch costs its item count, **charged in two steps and accepted all or nothing**:
   one admission unit before its body is read (point 2), then the rest once its size is known. If
   fewer units remain than the rest, the rest is not taken, the whole batch is refused with 429 and
   `Retry-After`, and no job is recorded; the admission unit is kept, as it is for any refused
   request, so a refused batch costs one unit and an accepted batch exactly its item count. A
   partially accepted batch would give the caller per-item outcomes `JobAccepted` cannot
   express.
2. **Where the charge is taken.** The rate-limit filter charges one unit for every request on the
   machine paths, the batch submission included, **before the body is read**: a key over its budget
   is refused with 429 without the server reading or parsing anything. A batch's item count is known
   only once its body is read, so the controller then charges the rest (items minus the admission
   unit), once and all or nothing, before the job is recorded. An accepted batch therefore costs
   exactly its item count; a refused or invalid batch envelope (malformed JSON, a wrong field, an
   item count outside 1 to 1,000, a body over the size limit, a missing scope) costs the one
   admission unit, like any request. A request with no API key has no budget to charge; the key
   filter refuses it first. **Revised the same day after review 8**: the first version exempted the
   batch submission from the filter and charged it only in the controller, after the body had been
   read, so an exhausted key could make the server read and parse 4 MiB bodies without limit, since
   a refused charge takes nothing.
3. **The default budget is 2,000 transactions per second sustained, burst 4,000, per API key**
   (ADR 0100 points 2 and 3), and it is **ASSUMED**: one fifth of the system's specified 10,000 per
   second, with two seconds of burst. No integrator profile, contract or measurement supports 2,000
   over 1,500 or 3,000. **The owner replaced the 200 per second default** rather than keeping it
   for newly minted keys, as ADR 0100 proposed: that tier would need a per-key budget stored with
   the key (M7's key administration), and a second number with no more support than the first.
   Per-key budgets remain possible later without changing the unit.
4. **The burst must be at least the largest batch (1,000).** A batch is charged whole, so a smaller
   burst would refuse a full-size batch forever while telling the caller to retry. The application
   refuses to start with such a configuration.
5. **Idempotent replays consume budget** (ADR 0100 point 4): the filter charges before the
   idempotency check, so resending one transaction is not a way around the budget.
6. **`Retry-After`** is the whole number of seconds until enough units have refilled for the request
   to be accepted if it is sent again, floored at one (ADR 0100 point 5). For a batch refused at its
   second charge that is the wait for **the whole batch**, since a retry pays admission again, not
   only for the rest (revised after review 9). `RateLimit-Remaining` on a refusal reports what is
   left after the admission unit: the refused charge itself takes nothing.
7. **Outage behaviour is unchanged**: with Redis unavailable each instance charges its own bucket in
   the same units, marked `RateLimit-Degraded: true` and counted.

## Consequences

- `TransactionBudgetTest` (both limiters: a batch costs its item count; a refused charge takes
  nothing and says so; single and batch charges share one bucket per key);
  `RateLimitApiTest.batchCallersCannotExceedTheTransactionBudget` (batches of 300 as fast as the
  client sends, for three seconds: the transactions accepted never exceed the burst plus what
  refilled, a refused batch records no job, and what remained after a refusal still buys a smaller
  batch); `exhaustedKeysAreRefusedBeforeTheirBatchBodyIsRead` (an exhausted key's stalled batch is
  answered 429 while its body is still arriving);
  `refusedOrInvalidBatchesCostOneUnitAndValidOnesTheirItemCount`; and
  `RateLimitConfigurationTest` (the default, and the burst check).
- The configuration keys are renamed to say what they count:
  `fraudshield.rate-limit.transactions-per-second` and `fraudshield.rate-limit.burst`.
- M10's campaign can classify a 429 against a stated budget, and configures its own key above the
  target rate for throughput rows, as ADR 0100 point 6 describes. M11 carries the budget as an
  assumed parameter.
- If the assumed numbers prove wrong, changing them is configuration; the unit is the decision.
