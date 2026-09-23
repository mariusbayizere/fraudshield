# 0063 — FX rates for RWF normalisation

- **Status:** Accepted
- **Date:** 2026-09-22
- **Requirements affected:** FR-01-02, FR-02-02
- **Defects referenced:** D-43, D-51

## Context

E.2 normalises every amount to RWF "using the FX table for the transaction date", and
`transactions.amount_rwf` is required, but no FX table existed.

## Decision

V61 adds global, append-only `fx_rates` (a correction is a later row for the same date). Ingest
uses the latest rate dated on or before the transaction's UTC date, cached ten minutes; RWF is 1.
A currency with no rate at all is answered **503** (retry), not decided with a guessed amount:
every amount feature and threshold is in RWF. Operators load rates; development and demo databases
hold synthetic rates only (D-21).

## Consequences

`PostgresAdaptersTest.fxRatesAreReadAsOfTheTransactionDate`. A deployment must load rates for
every non-RWF currency it accepts before accepting it.
