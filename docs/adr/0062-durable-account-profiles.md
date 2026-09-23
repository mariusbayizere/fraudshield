# 0062 — Durable account profiles, and a bounded wait for them

- **Status:** Accepted; revised the same day for ADR 0033 (the scorer, not the API, reads it)
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
database fallback (the `Fallback` protocol M5 defined) reads `account_profiles.first_seen_at` with
`fs_app_readonly`, which V60 grants SELECT. The decision path reads PostgreSQL on its hot path only
for the freeze flag after a Redis flush (`RedisAccountStatus`), and that lookup keeps the **50 ms
budget** the PostgreSQL chaos test forced: past it the account is treated as not frozen for that
request and asked about again on the next one.

No source supplies `opened_at` yet (the ingest contract carries no opening date); until one does,
`account_age_days` stays absent.

## Consequences

`PostgresAdaptersTest.firstSeenIsDurableAndOnlyMovesEarlier` asserts the upsert and the trigger;
`RedisAdaptersTest.flushesRestoreTheFreezeAndFirstSeenStaysDurableForTheScorer` asserts the freeze
restore and that `fs_app_readonly` reads first-seen under row-level security;
`RedisAdaptersTest.stalledPostgresNeverStallsTheFreezeCheck` asserts the budget. The scorer's
PostgreSQL `Fallback` implementation is M5's (the reader) against these M6 tables.
