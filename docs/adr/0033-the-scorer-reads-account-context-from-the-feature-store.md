# 0033 — The scorer reads account context from the feature store; the API sends the account token

- **Status:** Accepted. Approved by the owner on 2026-09-22, who lifted the contract freeze for
  this one change so that it lands on `m5/scoring` before M6 merges. Applied as proposed.
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

`buf lint` and `buf breaking` (the repository's pinned buf, `contracts/proto/buf.yaml`) pass on the
applied file against `origin/main`. **Correction to the proposal:** no baseline file needed
regenerating. ADR 0016 replaced the descriptor baseline with `buf breaking`; the proto header's
mention of `scoring-v1.json` predates it. Two self-tests in `contracts/tests/test_proto_breaking.py`
injected a test field at number 18, which this change now uses; they inject at 90 instead, and the
contracts suite passes (490).

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


## Applied (2026-09-22)

- **Contract:** `contracts/proto/fraudshield/scoring/v1/scoring.proto` has
  `ScoreRequest.context` removed (2 and `context` reserved), and adds
  `ScoringResult.account_context` (18) and `ScoringResult.feature_store_degraded` (19).
- **Scorer:** `ml/` regenerates its messages from it. `ScoringService` takes a context source, the
  feature store in production, and answers `UNAVAILABLE` without one. `ScoringResult` returns the
  context read and the degraded flag. The shadow model scores the same read as production.
- **CLI:** `fs-scorer serve` requires `--feature-store`. `--static-contexts` exists only for
  benchmarking on a machine without Redis, and those reports say the store read is excluded.
- **The whole-day skew (M5 review finding M5-2) is gone.** The only path now reads the store, and
  the store returns the ages at full precision.
- **M6 builds its Java client against this contract** (`docs/parallel/M5_updates.md`).
