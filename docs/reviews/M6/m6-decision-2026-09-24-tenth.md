> Recorded by the author on 2026-09-24. The review came back clean, so under the owner's rule the
> review loop for the rate-limit fix ends here. MINORs 2 and 3 and NIT 7 were fixed in ADR 0058,
> a documentation-only change. The rest are recorded, not fixed; `docs/parallel/M6_updates.md`
> gives the disposition of each.

# M6 review 10: commits b830cce, 2f92169, 7a86405 (the rate-limit fix, head 7a86405)

Reviewer: a fresh, independent reviewer. Read-only on `/home/marius/fraudshield-m6` at `7a86405`.
The reviewer ran `RateLimitFilterTest`, `RetryAfterTest` and `RateLimitConfigurationTest`: 6/6
passed. The Docker-backed suites were not run, because the host was loaded. They ran in CI on
`7a86405` and were green.

## Verdict: clean (0 BLOCKER, 0 MAJOR, 3 MINOR, 4 NIT)

**Earlier findings checked:**

- Review 8's MAJOR is fixed. `RateLimitFilter` charges one unit on every protected path before the
  body is read.
- Review 9's MAJOR 1 is fixed. There is a deterministic filter test, and the API test with stalled
  connections allows for refill.
- Review 9's MAJOR 2 is fixed. `retryAfterForWholeBatch` computes `ceil((units - remaining) /
  limit)` with `remaining` rounded down, so any error makes the wait too long, never too short.
- No new defect was found in the code the fixes touched.

**Verified:**

- Both limiters charge all or nothing, atomically.
- The Lua TTL equals the time to refill from empty.
- In degraded mode a request can be charged twice, but no extra work is ever admitted.
- `chargeableUnits` matches `submit`'s envelope checks exactly.
- In Spring 7.0.9, headers the controller sets replace the filter's rather than being duplicated.
- The 429 matches `contracts/openapi/fraudshield-api.yaml`: problem+json,
  `urn:fraudshield:problem:rate-limited`, and an integer `Retry-After` of at least 1.
- The defaults are 2,000/s with a burst of 4,000. The application refuses to start if the burst is
  below 1,000 or below the rate.

## Findings

| # | Severity | Finding | Disposition |
|---|---|---|---|
| 1 | MINOR | The final all-or-nothing check in `batchCallersCannotExceedTheTransactionBudget` usually never runs: refused batches drain what remained, so `remaining >= 1` is false. ADR 0058 claimed it as proof. | ADR corrected: this all-or-nothing check rests on `TransactionBudgetTest`. Test left unchanged. |
| 2 | MINOR | A batch refused at admission is told the wait for one unit. That wait fits any batch only while the rate is at least 1,000/s. | ADR 0058 point 6 now states this. It cannot be fixed in code, because the size is unknown at admission. |
| 3 | MINOR | A valid batch whose job cannot be recorded (the database is down) has been charged its item count and gets a 500. | ADR 0058 point 1 now states this. It overcharges and never admits extra work. |
| 4 | NIT | Under clock skew between instances, the Lua refill has no floor at 0, so `RateLimit-Remaining` can go negative. A stale `RateLimit-Degraded` can survive a second charge that was not degraded. | Recorded. Both are cosmetic and more restrictive, never less. |
| 5 | NIT | No deterministic test checks that the controller passes `units` rather than `units - 1` to `retryAfterForWholeBatch`. | Recorded. |
| 6 | NIT | In `exhaustedKeysAreRefusedBeforeTheirBatchBodyIsRead`, the elapsed window includes socket timeouts, so only `isPositive()` still bites. That is enough for review 8's defect. | Recorded. |
| 7 | NIT | ADR 0058's Consequences were stale: they said "three seconds" and omitted the newer tests. | Fixed in the ADR. |
