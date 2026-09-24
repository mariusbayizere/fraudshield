> Recorded by the author on 2026-09-24. The MAJOR led to a change of design: no deadline at all
> (ADR 0057, rewritten). That removes MINORs 1 and 2 and resolves the NITs as listed.

# M6 review 12: commits 3141763 and 098c5dc (the parent-wait rework)

Reviewer: a fresh, independent reviewer. Read-only on `/home/marius/fraudshield-m6` at `098c5dc`.
The reviewer ran `ParentWaitTest` (2/2), `VerificationFlowTest` (10/10) and `EnvelopeConsumerTest`
(3/3), all passing, and mutation-checked the new tests by reasoning. Review 11's MAJOR and MINORs
were confirmed fixed as reported.

## Verdict: CHANGES_REQUIRED (1 MAJOR, 2 MINOR, 4 NIT)

| # | Severity | Finding | Disposition |
|---|---|---|---|
| 1 | MAJOR | PostgreSQL can keep answering reads while refusing or holding the writer's inserts for more than 30 minutes (a full disk, 53100; a lock; the decision service's own pool or network path). In that case every intent written during the stall is still dead-lettered, and the customer is never told. ADR 0057 point 3 ("only if the parent will never arrive") was false. | Redesigned: an intent is never dead-lettered for waiting. A wait of a minute or more is logged at WARN every minute. The operator's ways out are in ADR 0057. |
| 2 | MINOR | A stale wait entry survived partition revocation, so a rebalance did not always restart the clock. | Moot: there is no deadline clock. The entry now only paces the WARN, and the map stays bounded by the partition count. |
| 3 | MINOR | A failed dead-letter send silently restarted the 30-minute wait. | Moot: nothing is dead-lettered for waiting. |
| 4 | NIT | With a RETRY and a WAIT in the same poll, the waiting record follows the retry backoff rather than 500 ms. The exponential backoff reset is pre-existing. | ADR 0057 point 2 now says so. |
| 5 | NIT | `ParentWaitTest`'s constants test did not test what its name claimed. | Replaced by a test of the WARN cadence. |
| 6 | NIT | A sender test comment claimed "the same intent is sent", but the test used another intent. | Comment corrected. The consumer test covers "parent appears later". |
| 7 | NIT | The wait logged at DEBUG only. | It is now logged at WARN after a minute, then every minute. |
