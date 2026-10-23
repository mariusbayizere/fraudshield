# FraudShieldKafkaConsumerLagHigh

**Meaning.** A consumer group is more than 5,000 messages behind on a topic for 5 minutes (sum
of its instances' `fs_kafka_consumer_lag`). The labels name the topic and group; the consumers of
each topic are listed in `contracts/kafka/topics.yaml`.

**Impact** depends on the group:

| Group | What is late |
|---|---|
| `alerting`, `websocket-fanout` | Analysts see HIGH/MEDIUM alerts late; MEDIUM holds may time out unreviewed (D-10) |
| `persistence`, `audit-writer` | Decisions and audit events are late in TimescaleDB (still in Kafka, not lost) |
| `webhook-dispatcher` | Core banking receives final decisions (`decision.final`) late |
| `notification-service` | Customer SMS and staff emails are late |
| `feature-store-updater` | Velocity features lag, so new fraud bursts score lower |

## Diagnose

1. **FraudShield / Kafka and spool** dashboard: is lag growing (consumer stuck or too slow) or
   flat (consumer stopped)?
2. Consumer pods: `kubectl -n fraudshield get pods`, restarts and `OOMKilled`.
3. Logs of the consuming service for a poison message: repeated failures on the same offset.
   Poison messages must go to `<topic>.dlq`, never be skipped silently.
4. Broker health: under-replicated partitions or a broker down slows every group.

## Mitigate

- Too slow: scale the consuming deployment (partitions are the upper bound on parallelism).
- Poison message: confirm it reached the DLQ and the consumer moved on; inspect the DLQ copy
  (tokens only, no raw PII) and file an issue.
- Never reset consumer offsets forward: that drops decisions or audit events.

## Escalate

Engineering lead if `persistence` or `audit-writer` lag keeps growing for 30 minutes (Kafka
retention of `fs.transactions.scored` is 7 days, `contracts/kafka/topics.yaml`).
