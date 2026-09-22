# 0033 — The scorer reads account context from the feature store; the API sends the account token

- **Status:** Proposed. The contract change needs the owner's approval; `contracts/` is frozen.
- **Date:** 2026-09-22
- **Decided by:** the owner (2026-09-22) set the direction: no third implementation of
  parity-critical logic; the scorer reads the context itself; the API sends only the account token.
  The author wrote the proposal.
- **Requirements affected:** FR-02-01, FR-02-02, FR-02-09, FR-01-* (the API's hot path)
- **Defects referenced:** D-13 (the synchronous budget), D-03, D-04
- **ADRs referenced:** 0011, 0012 (contract evolution), 0016 (buf), 0025 (parity tolerance), 0027

## Context

The scoring contract (`contracts/proto/fraudshield/scoring/v1/scoring.proto`) has the Java API read
the account's state from Redis in one pipelined round trip, assemble an `AccountContext` and send it
in `ScoreRequest.context`. The scorer turns that context into the 44 features.

Assembling `AccountContext` is not a lookup. Every field is a window aggregate evaluated at the
**scored transaction's own timestamp**: counts in `(t − 1h, t)`, the mean hourly count over the
observed history capped at 30 days with the last hour excluded, the 90-day median and MAD, the
counterparty's confirmed frauds whose labels became available before `t`, and so on. M5 implements
that arithmetic once, in `ml/src/fraudshield_ml/featurestore/store.py`. A prefix replay holds it
equal to the batch path the model was trained on, at every step, with four mutation checks
(`ml/tests/featurestore/test_serving_parity.py`).

If the API assembles the context, M6 has to write that arithmetic again in Java: a third
implementation of the logic M3 spent a milestone making agree on two paths (ADR 0025, the parity
suite). Any drift between them is training–serving skew. It is silent, it lands on the features
with the most signal (velocity, novelty), and nothing in production would detect it.

## Options considered

1. **Keep the contract; the API reimplements the context in Java**, held to the Python store by
   shared golden vectors. Rejected by the owner: a third implementation of parity-critical logic,
   whose correctness depends on a test suite the Java side has to keep passing forever.
2. **The API calls a Python context service, then calls Score.** One implementation, but two
   network hops in the synchronous path that D-13 already had to re-budget.
3. **The scorer reads the context from the store itself; the API sends the transaction**, which
   already carries `account_token`. One implementation, and no new hop: the Redis round trip moves
   from the API to the scorer.

## Decision

Option 3, as the diff in `docs/parallel/M5_proto_proposal.diff` states it:

- `ScoreRequest.context` (field 2) is **removed with its number and name reserved**, as ADR 0012
  allows. The `AccountContext` message stays: messages are never deleted, and it documents the
  store's read model.
- `ScoringResult` gains `AccountContext account_context = 18`, the state the scorer read, so every
  decision can be audited and replayed against exactly what it saw, and
  `bool feature_store_degraded = 19`, true when the scorer answered from the database fallback or
  with the state unknown (C.4's DEGRADED_MODE), so the API can surface it.
- The header comment's hot-path description is amended to match.

`buf lint` and `buf breaking` (the repository's pinned buf, `contracts/proto/buf.yaml`) both pass on
the proposed file against the current one. `contracts/proto/baseline/scoring-v1.json` must be
regenerated with the change, since `contracts/tests/test_proto.py` compares against it.

## Consequences

- **One implementation of the context**, the one the parity suite verifies. The Java API no longer
  reads the feature store for scoring. It still reads its own Redis keys, such as idempotency and
  configuration.
- **The database fallback moves with it.** When Redis is down or a key has expired, the scorer asks
  a `Fallback` (the `account_velocity_cache` and the per-account durable table PB-37 records as
  absent). M6 owns those tables and their schema; M5 owns the Python reader, which exists as the
  `Fallback` protocol and needs a PostgreSQL implementation.
- **The scorer's dependencies grow** by a Redis client on the hot path, which it already has for
  thresholds and store writes, and read access to those fallback tables.
- **Already prepared in `ml/`, and compatible either way:** the scorer reads the context from the
  store whenever a request arrives without one (`ScoringService` with a context source). Today's
  contract permits that, since a message field can be unset. Once the change is approved, the
  generated messages are regenerated and the path becomes the only one.
- **M6 is the consumer to tell:** `docs/parallel/M5_updates.md` flags this for the M6 agent. The
  Java client should send `ScoreRequest` without `context` from the start, and read
  `feature_store_degraded`.
