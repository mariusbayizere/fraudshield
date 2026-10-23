> Recorded by the author on 2026-09-24. Not fixed. The author stopped the fix loop here for an
> owner decision (see `docs/parallel/M6_updates.md`, "SMS race: stopped for the owner").

# M6 review 13: commits 3141763, 098c5dc and b6f79a9 (the SMS-race fix as it stands)

Reviewer: a fresh, independent reviewer. Read-only on `/home/marius/fraudshield-m6` at `b6f79a9`.
The reviewer ran `ParentWaitTest` (1/1), `VerificationFlowTest` (10/10) and `EnvelopeConsumerTest`
(3/3), all passing. Reviews 11 and 12 are confirmed fixed or moot.

## Verdict: CHANGES_REQUIRED (1 MAJOR, 3 MINOR, 1 NIT)

| # | Severity | Finding |
|---|---|---|
| 1 | MAJOR | A transaction decided twice (a crash after the spool append, then a client retry; or two submissions while Redis is down) gets a second random auto-block id (`DecisionService.java:319`). `PostgresSink` keeps only the first decision and skips the second decision's `AutoBlocked` and `CustomerNotificationRequested` rows without writing a dead-letter file (`PostgresSink.java:240-290`), but the Kafka drainer still publishes the second intent. At `b6f79a9` that intent waits for ever and stalls its partition (1 of 12). ADR 0057's remedy, replaying the writer's dead-letter file, does not apply, because nothing was dead-lettered. Only the offset reset works. At `5c3f6b2` the same intent sent a duplicate SMS and was dead-lettered; at `098c5dc` it was dead-lettered after 30 minutes. |
| 2 | MINOR | ADR 0057 omits two costs of an indefinite wait. `fs.notifications.customer` keeps records for 3 days (`topics.yaml:107`, P3D), and with `auto.offset.reset=earliest` a stall that outlives retention silently skips every intent that aged out. And `kafka-consumer-groups --reset-offsets` needs every `notification-service` consumer stopped. |
| 3 | MINOR | A transient failure during the wait removes the wait entry (`EnvelopeConsumer.java:212-218`), so under intermittent PostgreSQL errors the promised one-minute WARN may never fire. |
| 4 | MINOR | The consumer test's longest wait is about 4 s, so re-adding a deadline longer than about 5 s would pass. The "no deadline" property is not actually tested; that needs an injectable clock. |
| 5 | NIT | A poll that held only a WAIT resets another partition's retry backoff. Empty polls already did the same. |

Checked and fine: the consumer loop, the shutdown path, the WARN pacing, no PII in the logs, RLS and
tenant handling in the sender, and the outcome check. The mutations considered (removing the
pre-check, removing the outcome check, exponential backoff for the wait) are caught.
