# 0062 — Durable account profiles, and a bounded wait for them

- **Status:** Accepted; revised the same day for ADR 0033 (the scorer, not the API, reads it), and
  on 2026-09-23 for the database fallback's reader (PB-69: V67, `fs_scorer`)
- **Date:** 2026-09-22
- **Requirements affected:** FR-02-09, FR-02-02
- **Defects referenced:** D-04; backlog PB-37

## Context

PB-37: no table held an account's first appearance or opening date, so after a cache flush the
online path either guessed first-seen from its earliest cached arrival (which collapses the
velocity ratio on every account at once) or failed closed.

## Decision

V60 adds `account_profiles` (tenant-isolated): `first_seen_at`, which may only move earlier, and
`opened_at`, which the institution supplies and may be set once, both enforced by a trigger for
every role. The PostgreSQL writer upserts first-seen from every decided transaction, so it is
durable independently of Redis.

Under ADR 0033 the decision path no longer reads the feature store: the scorer does, and its
database fallback (the `Fallback` protocol M5 defined) reads these tables. How it reads them is
settled below (revised 2026-09-23); the earlier plan, `fs_app_readonly` under row-level security,
did not survive contact with the protocol, which names an account by its token alone and never by
institution, so a tenant-scoped role has nothing to scope by. The decision path reads PostgreSQL on its hot path only
for the freeze flag after a Redis flush (`RedisAccountStatus`), and that lookup keeps the **50 ms
budget** the PostgreSQL chaos test forced: past it the account is treated as not frozen for that
request and asked about again on the next one.

No source supplies `opened_at` yet (the ingest contract carries no opening date); until one does,
`account_age_days` stays absent.

**The database fallback's reader (PB-69, carried from M5; revised 2026-09-23).** M6 implements
M5's `Fallback` protocol as `ml/src/fraudshield_ml/featurestore/postgres.py` on the branch that
holds both trees (`m6/featurestore-fallback`, merged after M5 and M6), and V67 gives it what it
reads:

1. **What it returns** is exactly what `ReplayFallback`, the reference, returns: the account's
   transactions within the longest feature window, its first appearance (the profile's, which
   survives retention, or the earliest transaction's), the last transaction's time and place, the
   sets of counterparties, their countries and devices, its SIM swaps, its KYC tier history and its
   opening date; and a device's first appearance on any account. The acceptance test,
   `test_db_fallback.py::test_a_read_through_the_fallback_equals_the_redis_read[m6-postgresql]`,
   replays a corpus against TimescaleDB built from this repository's bootstrap and migrations and
   requires every context and every full-precision age to equal the Redis path's.
2. **Two new append-only tables**, `account_sim_swaps` and `account_kyc_tiers`, because the
   fallback must return those histories and nothing held them. Nothing writes them yet, exactly as
   nothing writes their Redis keys (no MNO feed, no KYC endpoint): when a producer arrives it
   writes both, and the fallback already reads it.
3. **An opening date may precede the first transaction**, so `first_seen_at` becomes nullable and
   the writer's upsert fills a NULL on the first transaction; the trigger still only lets it move
   earlier.
4. **Its own role, `fs_scorer`** (bootstrap), which holds no grant on any table or view and may
   only execute five `SECURITY DEFINER` functions, `feature_fallback_*`. They answer for a token,
   not for an institution, which is the scope the Redis keys already have; `fs_scorer` cannot list
   tokens, read a decision or a score, or see anything but the feature inputs of an account it
   already names. Two indexes serve the token-only lookups.
5. **Failure is never "never seen".** The reader raises `FallbackUnavailable` when the database
   cannot answer, and every statement is bounded (`statement_timeout`, 100 ms by default), because
   it runs on the scorer's synchronous path. After any failure the reader fails reads at once for
   a cool-down (5 s) instead of reconnecting on every Redis miss, a read waits for another's turn
   no longer than the statement timeout, and connections carry a 200 ms socket timeout, so a
   blackholed database costs one attempt, not a queue of them (the delta review, 2026-09-23).
   While the database is down, a read that misses Redis makes the scorer answer UNAVAILABLE and
   that one payment is decided by `fallback-rules-2`; reads that hit Redis are unaffected. Returning `None` would read a known account as new
   and score its history away; raising makes the scorer answer UNAVAILABLE and the API decide on
   its rule-based fallback (C.4).
6. **The driver is pg8000** (BSD-3-Clause). psycopg is LGPL, which ADR 0009 allows only in tools
   that are never distributed, and the scorer is.

The compose stack sets `fs_scorer`'s password only when `FS_SCORER_DB_PASSWORD` is supplied; without
it the role cannot log in and the fallback stays off, failing closed. Passing that variable to the
database container is a `docker-compose.yml` change proposed in `docs/parallel/M6_updates.md`, not
made here.

## Consequences

`PostgresAdaptersTest.firstSeenIsDurableAndOnlyMovesEarlier` asserts the upsert and the trigger;
`RedisAdaptersTest.flushesRestoreTheFreezeAndFirstSeenStaysDurableForTheScorer` asserts the freeze
restore and that `fs_app_readonly` reads first-seen under row-level security;
`RedisAdaptersTest.stalledPostgresNeverStallsTheFreezeCheck` asserts the budget. `PostgresAdaptersTest.accountsOpenedBeforeTheyAreSeenTakeTheirFirstSeenFromTheirFirstPayment`
asserts point 3; the fallback's acceptance test is named in point 1.
