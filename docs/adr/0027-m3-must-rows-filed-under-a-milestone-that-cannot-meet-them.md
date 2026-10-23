# ADR 0027 — Three M3 Must rows are filed under a milestone that cannot meet them

- **Status:** accepted
- **Date:** 2026-09-20
- **Decided by:** repository owner (2026-09-20), on the reasoning recorded in ADR 0024
- **Context:** M3 close. The governance gate refused to mark M3 complete.
- **Requirements affected:** FR-02-02, FR-02-09, ML-DATA-07, TEST-01, D-03, D-04
- **ADRs referenced:** 0010 (performance benchmarks need dedicated hardware), 0024 (the same
  defect one milestone earlier)

## Context

Setting `current: M4` and `completed: [M0, M1, M2, M3]` made `fs-traceability check` fail with
nine errors across six Must rows assigned to M3:

| Requirement | Asks for | Actual state at M3 close |
|---|---|---|
| `FR-02-02` | 44 features per transaction **within < 10 ms**; acceptance criterion: unit tests confirm all 44 computed for all 6 channels, USSD handles a missing `device_fingerprint` gracefully | The acceptance criterion is met. The `< 10 ms` is a benchmark number, and ADR 0010 forbids gate numbers from anything but the dedicated machine. |
| `FR-02-09` | A **Redis feature store** refreshing velocity features within 100 ms; TTL 30 days; DB fallback; Prometheus p99 | None of it exists. There is no feature store, no Redis, no Prometheus. PB-36/37 record the gap. |
| `ML-DATA-07` | All 44 features computable for **≥ 98% of records**; **< 2% any missing feature** | Six features are missing for **100%** of records because the data they read does not exist (PB-44). |
| `TEST-01` | Unit tests for all 44 features and all 6 channels, with four named scenarios including **`round_sum_flag` correct for EAC cultural norms** | 239 tagged feature tests cover three of the four scenarios. `round_sum_flag` has no source data until a `round_denominations` pack field exists. |
| `D-03` | The feature breakdown sums to 46, not 44 | Resolved: E.2's catalogue enumerates 44 by name and is authoritative; the registry is pinned to it group by group. |
| `D-04` | "USSD: 40 features computed without `device_fingerprint`" is undefined | Resolved: exactly four device features are NaN together, and four agent features likewise; pinned by test. |

This is the second time the generated register has caught what review passes did not — ADR 0024
records the first, at M2 close, and the diagnosis there applies unchanged: **a requirement filed
under a milestone that cannot satisfy it is a specification error, not a deviation.** The remedy is
to file it where it can be satisfied and say why, not to mark it done, and not to bypass the gate.

## Decision

### `FR-02-09` moves to M5

The build prompt's own gate for M5 reads:

> **M5 — Scoring service**
> *Gate:* **FR-02-01, 02-04 … 02-10 tests pass**; `benchmark.py serve` with 200 concurrent
> requests meets p50 < 15, p95 < 25, p99 < 40 ms; memory growth < 50 MB over 10,000 scorings.

`FR-02-09` falls inside `02-04 … 02-10`, so the prompt assigns its test to M5's gate while the
register assigned the row to M3. M3's gate mentions "Redis/DB fallback tested", which is a clause
about the *feature pipeline's* fallback behaviour, not about building the store that M5's gate then
tests. The reassignment rests on the prompt's own structure, as ADR 0024's did.

### `FR-02-02` stays in M3 as `DONE_WITH_DEVIATION`, with its latency clause carried to M10

The row's **acceptance criterion** — "Feature engineering unit tests confirm all 44 computed for
all 6 channel types; USSD handles missing `device_fingerprint` gracefully" — is met, and the
evidence is listed against the row. The `< 10 ms` in the specification line is a different kind of
claim: ADR 0010 already established that latency percentiles may not be taken from shared runners,
and that benchmark rows stay at `VERIFIED_AT_REDUCED_SCALE` at best until they run on the machine
named in `docs/benchmarks/hardware.md`.

So the row is **not** moved. Moving it would take the 44-feature requirement out of the milestone
that actually delivered it and file it under one that has not started, which is a worse record than
the one being fixed. Instead the latency clause is **carried to M10**, the verification campaign,
whose gate is "every matrix row has a final status with evidence" and whose whole subject is
measurement on real hardware. The carry is recorded here, in the row's notes, and as a backlog item
with M10 as its due milestone, so it is a commitment with an address rather than a sentence in a
status field. This is the shape ADR 0024 used for `RES-01`, whose 5M-row clause was carried in the
same way.

### `ML-DATA-07` moves to M6

A completeness requirement cannot be satisfied before the data it counts exists. Of the six
features that are missing for every record, `kyc_tier`, `agent_float_utilisation_ratio` and
`agent_distance_from_registered_km` need tables M6 builds, and `account_age_days` and
`counterparty_account_age_days` need the per-account durable table PB-37 records as absent. M6 is
the last of the enabling milestones, so it is where the row can first be judged.

**It is moved rather than marked done.** The existing note read that the "≥ 98% computable" clause
was "satisfied in the sense of not raising while six features carry nothing". That is a fudge and
it is withdrawn: `< 2% any missing feature` against six features missing for 100% of records is
false, and a register that recorded it as met would assert something untrue in the document the
project uses to prove it does not.

### `TEST-01` moves to M4

Three of its four named scenarios are covered by the 239 tagged feature tests. The fourth —
`round_sum_flag` correct for EAC cultural norms — cannot be written while `round_sum_flag` has no
source data. The registry schedules that gap for M4 (a `round_denominations` field in the country
packs, ADR 0023), so M4 is the first milestone in which the row's coverage target is reachable.

### `D-03` and `D-04` become `DONE`

Both are resolved and both already carry tagged tests; only the register's `status`,
`implementation` and `evidence` fields were never filled in. That is bookkeeping, and it is done
rather than reassigned.

## Consequences

- The reassignments are data in `ROW_MILESTONE_OVERRIDES` in `traceability_seed.py`, not hand edits
  to `requirements.yaml`, so re-seeding preserves them.
- M3 closes with its gate passing on its own. **The hook is not bypassed.**
- M4 inherits `TEST-01`; M5 inherits `FR-02-09`; M6 inherits `ML-DATA-07`; M10 inherits
  `FR-02-02`'s latency clause.
- A reader asking "was M3 really complete?" gets: yes for what M3 was for — 44 features on two
  independent paths, 36 of them computable on this benchmark — with one clause carried and three
  requirements that M3 could not have met, each named here rather than buried in a status field.

## Alternatives rejected

- **Mark the rows DONE.** Rejected for `ML-DATA-07` in particular: six features missing for every
  record is not ≥ 98% completeness under any reading, and the note that said otherwise is withdrawn
  by this ADR.
- **Move `FR-02-02` to M10 entire.** Rejected: its acceptance criterion was met in M3, and filing
  the whole row forward would erase that from the record to solve a problem only its latency clause
  has.
- **Leave `current: M3` and note the disagreement.** Rejected. It was the honest holding position
  for a few hours, but a register permanently one milestone behind the work stops being read, and
  the gate's value is that it is believed.
- **Bypass the gate.** Rejected, for the reason ADR 0024 gives: it caught what the reviews missed.
