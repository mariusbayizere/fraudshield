> Recorded by the author on 2026-09-24. This is the independent review of the ordering contract's
> implementation, at `bd48557`. The runtime code was found clean. The MAJOR concerned proof: the
> documents claimed every invariant was tested. The missing tests were added in the next commit,
> and the contract's section 8 now maps each invariant to its test.

# M6 review: implementation of the decision-fact ordering contract (bd48557)

Reviewer: a fresh, independent reviewer. Read-only, apart from running `WaitBudgetTest` (7),
`SmsOrderingTest` (10) and `EnvelopeConsumerTest` (7) in one reactor, all green.

## Verdict: runtime code clean. 1 MAJOR on verification, 7 MINOR, 6 NIT

Checked and correct:

- CLASSIFY is one statement, its 9 parameters are bound in order, it is NULL-safe, and the checks
  run in the order S0 to S5.
- Link eligibility fails closed.
- The derived ids and the header source.
- The budget accounting against W-b, and the trip rule.
- The pause, resume and seek lifecycle.
- The checkpoint on every commit and on revoke.
- `WaitBudget`'s arithmetic.
- The replay tool's window, dedupe and refusal.
- The 503.

No path was found to an indefinite stall, a send without B, or a second send.

| # | Severity | Finding | Disposition |
|---|---|---|---|
| M1 | MAJOR | The documents said every invariant I1 to I16 is tested. Untested were: the grace re-read, I12, I7e, I8's grace-S5 clause, and I14 after a crash or a lost partition. | Tests added for the grace re-read, grace-S5, I12, I7e and the I14 crash. The contract's section 8 maps each invariant to its test and names what rests on review (the lost partition's no-commit; `seekBack` with membership kept). ADR 0056 and M6_updates corrected. |
| m1 | MINOR | The contract was changed after its sound review (W-b attempt end; item 4 locale) and nothing said so. | Flagged in the contract's status. |
| m2 | MINOR | S2r was logged but not counted. | `CustomerSmsSender.resolvedBeforeSend()`, tested. |
| m3 | MINOR | The WAIT WARN did not name B. | The WARN carries the S4 message, which names B. |
| m4 | MINOR | The I9 test cannot detect two statements. | A structural test: one connection, one statement besides the tenant's. |
| m5 | MINOR | The I7f test does not exercise `seekBack`. | Recorded in section 8 as proved in part; `seekBack` by review. |
| m6 | MINOR | No test showed the header on the wire. | `KafkaSpoolChaosTest.theSmsIntentCarriesItsTransactionOnTheWire`. |
| m7 | MINOR | Timing-fragile assertions. | Loosened to 3 s, and to bound − 1 s. |
| n | NIT | The replay doc said "ingest image"; the D-c command resolved siblings from `~/.m2`; a malformed `fs-replayed` aborted a run; Redis could be left paused; `close()` could race after a join timeout; a malformed payload was retried for ever (pre-existing). | Doc fixed; the command now uses `-am`; the parse is guarded; the pauses sit inside `try`. A malformed payload is now dead-lettered as `malformed_envelope` and tested. The `close()` race is accepted: shutdown only, after a 10 s join. |
