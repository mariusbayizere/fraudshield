> Recorded by the author on 2026-09-24. The MAJOR and all three MINORs were addressed in the next
> commit, with ADR 0057; `docs/parallel/M6_updates.md` maps each finding to its fix.

# M6 review 11: commit 3141763 (no SMS before its auto-block event is recorded)

Reviewer: a fresh, independent reviewer. Read-only on `/home/marius/fraudshield-m6` at `3141763`.
The reviewer ran `ParentWaitTest` (2/2), `VerificationFlowTest` (9/9) and `EnvelopeConsumerTest`
(3/3), all passing, and mutation-checked the new tests by reasoning.

## Verdict: CHANGES_REQUIRED (1 MAJOR, 3 MINOR, 2 NIT)

| # | Severity | Finding | Disposition |
|---|---|---|---|
| 1 | MAJOR | The wait was timed from the record's Kafka timestamp (CreateTime). After a PostgreSQL outage longer than 10 minutes, each intent written during it was already past the bound when PostgreSQL returned. It would be dead-lettered as `parent_not_recorded` the first time its parent was missing while the drainer wrote its backlog. The customer SMS was lost, and nothing replays the DLQ. `EnvelopeConsumer.java:79-84, 203-208, 236-239` | Fixed. The wait is timed only while PostgreSQL answers without the parent, restarts after any transient failure, and ignores the record timestamp. The bound is 30 min, ASSUMED (ADR 0057). |
| 2 | MINOR | While one record waited, the exponential backoff (up to 30 s) slowed every other partition on the consumer. A parent that never arrives is realistic: `PostgresSink` sends a batch it refuses to a dead-letter file. | Fixed. A record waiting for its parent is re-read every 500 ms at a fixed pace. The stall of that record's own partition is documented in ADR 0057. |
| 3 | MINOR | A double SMS was still possible after a partial success (pre-existing). | Partly fixed. An intent whose SENT or FAILED outcome is already recorded is not sent again. The window between a successful send and its record remains; it needs a provider idempotency key, left for the owner in ADR 0057. |
| 4 | MINOR | ADR 0064 point 5 was not updated. | Fixed: it now carries an amendment pointing to ADR 0057. |
| 5 | NIT | Producer clock skew stretched or shrank the wait. | Moot: the record timestamp is no longer used. |
| 6 | NIT | The new test's `@Tag` (FR-03-04) differs from its class's (D-14). | Kept. Both tags apply, and the row it evidences is FR-03-04. |

**Checked and fine:** no send, issue or record happens before the pre-check. RLS is correct: the
tenant is set transaction-locally, `fs_app` has SELECT on the table, and the institution comes from
the envelope. `seek` keeps the record's timestamp. The webhook consumer has no FK race, and nothing
consumes `fs.notifications.staff` in main code. The test isolation is sound. The mutations
considered all fail the tests.
