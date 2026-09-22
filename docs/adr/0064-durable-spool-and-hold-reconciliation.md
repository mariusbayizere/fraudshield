# 0064 — The durable spool, and reconciling lost holds

- **Status:** Accepted
- **Date:** 2026-09-22
- **Requirements affected:** FR-01-01, FR-03-02, NFR-REL-02
- **Defects referenced:** D-13, D-14, D-15

## Context

D-15 asks for an idempotent `acks=all` producer behind a bounded local disk spool "fsync batched
every 5 ms", replayed in order after a crash, and proven by killing Kafka and the API together.
The decision must also reach PostgreSQL without putting PostgreSQL on the hot path (D-13).

## Decision

1. **One spool record per decision**: its facts as JSON, CRC-framed in segment files. A single
   writer thread takes every pending record, writes them and fsyncs once before any caller is
   answered (**group commit**). Under load each fsync covers everything that arrived during the
   previous one, so fsyncs run back to back; this meets D-15's batching without adding a fixed
   5 ms wait to every decision.
2. **Two consumers** read the same records with their own atomic checkpoints: the Kafka publisher
   and the PostgreSQL writer, so an outage of one never blocks the other. Delivery is at least once:
   Kafka event ids are derived from the fact and the topic, so a republished record repeats its ids;
   the PostgreSQL writer is idempotent on unique keys and keeps a transaction's first decision.
3. **Bounded**: when unconsumed bytes would exceed the bound the append fails and the request is
   answered 503; nothing is dropped. A torn final frame is truncated on recovery; a bad frame
   anywhere else refuses to open. A record PostgreSQL refuses for a non-transient reason is written
   to a dead-letter file and counted instead of blocking the records behind it.
4. **Holds** are scheduled in Redis. If Redis fails over empty, or the process dies between the
   spool append and scheduling, a hold would never time out; a leader-run sweep (V62) times out
   every persisted HOLD with no later state 10 s after its deadline. A repeated timeout carries the
   same sequence and decision, which receivers ignore (ADR 0011).

## Consequences

`DurableSpoolTest`, `KafkaSpoolChaosTest` (Kafka paused, the process killed with a backlog; 500 of
500 reconciled by transaction id), `PostgresAdaptersTest`, `RedisOutageTest` and
`ResilienceApiTest` (PostgreSQL paused). The spool directory must be on a persistent volume per
instance.
