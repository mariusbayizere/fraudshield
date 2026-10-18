# 0059 — The M6 latency gate is NOT MET on the hardware available; it is measured in M10

- **Status:** Accepted (owner decision, 2026-09-23)
- **Date:** 2026-09-23
- **Decided by:** the owner, on the evidence recorded here; written by the author
- **Requirements affected:** FR-01-01, FR-03-01, FR-03-03 (the M6 gate's latency criterion)
- **ADRs referenced:** 0010 (benchmarks come from a dedicated machine), 0033 (the scorer reads its
  own context), 0060 (M6 numbering; see below), 0061

**Numbering.** M6's block `0060`–`0069` is full. Sequential numbering from M1–M5 has reached
`0034`, and M7, M8 and M9 own `0070`, `0080` and `0090` onwards, so M6 counts down from `0059`
for any further ADR (recorded in ADR 0060).

## Context

D.3's M6 gate includes "end-to-end p95 decision latency < 50 ms at the largest achievable load".
ADR 0010 point 4 says gate numbers come only from a dedicated machine whose specification is
recorded in `docs/benchmarks/hardware.md`; until one is used, benchmark rows may reach at most
`VERIFIED_AT_REDUCED_SCALE`, and never on the strength of shared hardware.

Two measurements exist, both on shared hardware, both with the scorer replaced by a gRPC double that
answers at once (so M5's model inference is in neither):

- `docs/benchmarks/2026-09-22-M6-decision-latency.json`: a 4-vCPU Codespace (AMD EPYC 7763,
  16 GB) running the dev-container compose stack beside the benchmark, one-minute load 23. Not a
  recorded machine in `hardware.md`.
- `docs/benchmarks/2026-09-23-M6-decision-latency.json`: `dev-laptop-01` (Intel i5-6200U, 2 cores /
  4 threads, 7.6 GiB), shared with other sessions, one-minute load 3.4 before and 17.6 after.

Neither host can show the criterion met or refuted: the load generator, the API and PostgreSQL,
Redis and Kafka share four hardware threads, and the independent review could not reproduce the
one passing figure of the first run.

## Decision

1. **The M6 latency criterion is recorded as NOT MET on the hardware available**, and M6 is not
   held open for it. The functional parts of the gate stand on their own evidence (the full backend
   `verify`, the idempotency and 10,000-duplicate tests, the ML, Kafka, Redis and PostgreSQL chaos
   tests).
2. **The laptop and Codespace numbers are shapes, not gate figures.** What they may be used for:
   the latency grows with offered load on a shared host; the server-side decision p95 (42–43 ms at
   25–50 req/s on the laptop) sits well under the client-side p95 (107–121 ms), which points at
   host contention rather than the decision path; the path degrades without failing up to about
   200 req/s and sheds errors at 300 req/s. What they may not be used for: any statement that the
   p95 target is or is not met. No traceability row cites them as gate evidence.
3. **The measurement moves to M10** (verification campaign), on the dedicated benchmark machine
   ADR 0010 plans, **with M5's real scorer** in place of the double, so model inference is inside
   the number the gate is about. The row carrying it is proposed in `docs/parallel/M6_updates.md`
   (backlog `PB-73`, number to confirm at integration).
4. **Consequences for statuses.** FR-01-01, FR-03-01 and FR-03-03 stay `IN_PROGRESS` until the M10
   measurement; they do not become `VERIFIED_AT_REDUCED_SCALE` on these numbers. Whether
   `m6-complete` is tagged with the latency criterion NOT MET under this ADR is the owner's decision
   at merge (after M5); if it is, the tag's annotation names this ADR.

5. **FR-01-06 goes with it** (owner decision, later on 2026-09-23). Its 30 s batch criterion measured
   30.20 s once on `dev-laptop-01` at host load ~7 and passed on re-run: a ~0.2 s, load-sensitive
   margin is not evidence. FR-01-06 is `IN_PROGRESS` and carried to M10 as `PB-74`, measured on the
   same dedicated machine as the latency gate.

## Consequences

- The M6 gate record (`docs/parallel/M6_updates.md`, "M6 gate") states the criterion as NOT MET
  under ADR 0059 and keeps both benchmark files as they are.
- M10 inherits a concrete measurement: the `DecisionLatencyBenchmark` harness (opt-in with
  `-Dfs.benchmark=true`), the rate steps used here, and the requirement to record the machine in
  `hardware.md` first.
- A latency regression introduced before M10 would not be caught by a gate. The server-side stage
  timings the benchmark records (idempotency claim, account read, score, state writes, spool) are
  the early signal, and are worth re-reading whenever the synchronous path changes.
