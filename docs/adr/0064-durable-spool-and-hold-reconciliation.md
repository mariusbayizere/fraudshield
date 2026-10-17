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

5. **The reading end dead-letters too** (Principal Review finding 7). `EnvelopeConsumer` commits
   only after its handler returns, so a transient failure re-reads the record; it now backs off
   exponentially to 30 s instead of re-reading every 100 ms. A record the handler can never accept
   — a malformed envelope, a missing institution, a row PostgreSQL refuses (SQLSTATE outside the
   transient classes 08, 40, 53, 57, 58) — goes to `<topic>.dlq` (C.3, ADR 0012) with the reason,
   the source topic, partition, offset and time in its headers, and the consumer commits past it.
   Otherwise one poison record stalls every customer SMS or webhook on its partition. If the
   dead-letter send itself fails, the record is retried rather than dropped. **Amended
   2026-09-24 (ADR 0056, `docs/architecture/decision-fact-ordering.md`):**
   - A dead-lettered copy keeps the record's original headers, for every reason.
   - A customer SMS intent is classified against PostgreSQL before anything is sent. An intent that
     is not the kept decision's is dead-lettered at once (`not_the_kept_decision`).
   - An intent whose parent is not written yet waits within its partition's wait budget, and is then
     dead-lettered (`parent_not_recorded`), to be replayed with `DeadLetterReplay`.
   - Every re-read pauses its partition, never the consumer thread.
6. **A caller that stops waiting for the spool** may already have had its record taken by the
   writer. `appendAndWait` withdraws a record only while it is still queued; once taken, the caller
   waits up to 5 s more, and a record whose fsync does not confirm in time raises "outcome
   unknown" rather than "not recorded" (ADR 0067 point 5).

## Consequences

`DurableSpoolTest`, `SpoolDrainerTest` (a sink that refuses N times: order kept, nothing committed
past a refused batch, a restarted consumer resumes), `EnvelopeConsumerTest` (a poison record is
dead-lettered with its reason and the good record behind it is handled), `KafkaSpoolChaosTest` (Kafka paused, the process killed with a backlog; 500 of
500 reconciled by transaction id), `PostgresAdaptersTest`, `RedisOutageTest` and
`ResilienceApiTest` (PostgreSQL paused). The spool directory must be on a persistent volume per
instance.

**What is not proven** (Principal Review finding 11): D-15 asks for the API pod to be *killed*.
`KafkaSpoolChaosTest` closes the writer in the same JVM, which joins the writer thread and flushes
the producer — a clean shutdown with a backlog. The replay from checkpoints is real; surviving a
SIGKILL mid-fsync is not shown end to end. Running the writer in a forked JVM and destroying it is
open work, recorded in `M6_updates.md`.
