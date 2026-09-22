# 0062 — Durable account profiles, and a bounded wait for them

- **Status:** Accepted
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
every role. The PostgreSQL writer upserts first-seen from every decided transaction. The Redis
feature store restores first-seen, opening date and the freeze flag from PostgreSQL when Redis does
not hold them.

The restore runs on the hot path, so it has a **50 ms budget**: past it, first-seen stays unknown
and `mean_hourly_count_30d` is NaN (the ratio fails closed, D-04 native missing handling), and the
account is not reported as new either. Found by the PostgreSQL chaos test, which hung without it.

No source supplies `opened_at` yet (the ingest contract carries no opening date); until one does,
`account_age_days` stays absent.

## Consequences

`HistoryCalculatorTest` asserts the fail-closed mean; `RedisAdaptersTest` asserts the restore
after a flush and that a stalled PostgreSQL costs under 500 ms; `PostgresAdaptersTest` asserts the
trigger. The Python online path (M5) must read `mean_hourly_count_30d` as NaN when it is NaN.
