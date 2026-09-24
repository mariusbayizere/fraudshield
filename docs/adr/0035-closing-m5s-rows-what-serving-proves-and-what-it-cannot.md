# 0035 — Closing M5's rows: what serving proves, and what it cannot

- **Status:** Accepted, 2026-09-23 (owner: settle all 14 M5 rows on-branch before the tag, as M4
  did in `b3c050e`/`4722ca7`).
- **Date:** 2026-09-23
- **Requirements affected:** FR-02-01, FR-02-05, FR-02-06, FR-02-07, FR-02-08, FR-02-09, FR-02-10,
  ML-GATE-12, ML-GATE-13, TEST-08, TEST-10, D-11, D-16, D-50
- **ADRs referenced:** 0010 (gate numbers only from the dedicated machine), 0027 (a requirement
  filed where it cannot be met is carried, not marked met), 0031 (M4's equivalent), 0032 (the
  latency gate is a throughput requirement), 0033, 0034 (the training–serving skew carry)

## Context

M5 built the scoring service: a gRPC scorer against the frozen contract, model hot-swap by MLflow
alias, shadow scoring, the Redis feature store, and the promotion gate. Its fourteen rows were all
`NOT_STARTED` at the end of the work. M4's precedent is that rows are settled **on the branch,
before the milestone tag**, each carrying its implementation and its evidence, and that a clause
the milestone cannot meet is carried rather than described as met.

Three kinds of row need different treatment here, and conflating them would misreport the
milestone.

## Decision

**1. Nine rows are met in `ml/` and read DONE, with their evidence.** FR-02-01, FR-02-05,
FR-02-08, FR-02-10, D-11 and D-50 are proved by tests that run in the fast suite, and — for the
alias and hot-swap paths — against a real MLflow 3.16.0 container.

**2. Two rows are DONE_WITH_DEVIATION, because a clause of the acceptance criterion belongs to a
milestone that has not run.** This ADR is their deviation record.

- **FR-02-06.** The scorer re-reads `fs:config:model_thresholds` from Redis every 10 s and applies
  new thresholds to the next transaction with no model reload (`serving/thresholds.py`). The
  acceptance criterion names `PATCH /api/v1/admin/thresholds` and an integration test over it; the
  endpoint is M7's, under ADR 0014's dual control. The scorer half is complete and tested; the
  write path and its integration test are carried to M7.
- **TEST-08.** Shadow scoring runs on every transaction, only the production result is returned,
  and the comparison is flushed to MLflow per shadow version. "Shadow score in audit log" needs the
  backend's audit store (M6/M7), which does not exist yet.

**3. Three rows stay IN_PROGRESS because the quantity they assert has not been measured where it
counts, and one because its own metric needs a deployment.**

- **FR-02-07, ML-GATE-12, TEST-10 (latency).** Measured on `dev-laptop-01`, shared with two other
  agents: server-side `scoring_duration_ms` p50 12 ms / p95 27 ms / p99 39 ms, but client-observed
  p50 1,164 ms at 200 in flight, throughput 183 req/s
  (`docs/benchmarks/m5_serve_laptop_4af4e5e.json`). ADR 0010 makes no number from that machine gate
  evidence, and ADR 0032 records why the gate as written is a throughput requirement of about
  13,000 req/s rather than a per-request latency one. TEST-10's **memory** clause does pass at the
  stated scale (0.54 MB growth over 10,000 consecutive scorings against a 50 MB limit,
  `docs/benchmarks/m5_memory_laptop_4af4e5e.json`); its latency clause does not, here. Marking
  these DONE_WITH_DEVIATION would read as "met, with a note"; they are unmeasured on the machine
  that decides, so they stay open and are carried to M10.
- **ML-GATE-13.** Every clause of D-11's gate is implemented and tested, including refusal with the
  reason named, and the promotion path re-derives each clause from the comparator's own figures
  rather than trusting its verdict. But the metric the row asserts — a shadow AUC delta over a
  24-hour window of at least 50,000 scores — cannot be produced without a deployment running the
  shadow comparator. The logic is evidence; the number is not yet in existence.

**4. FR-02-09 does not read DONE, and its row carries the measured skew.** Per ADR 0034 (option 3,
owner decision 2026-09-22): the Redis feature store, its parity with the batch path at every prefix,
the 30-day TTL, the update-latency metric and the scorer's read are built and tested, on fakeredis
and on real Redis 7.2.16. The row is open because four trained features are served from state no
deployed component writes. Measured on M4's gate model over the whole test period (101,909 rows,
985 frauds), twice and independently: **served AUC 0.9611 against 0.9700 as trained; 254
transactions change risk tier; 162 of 725 frauds no longer reach the 0.60 flag threshold, a 22%
fall in detections at the operating point.** Carried with acceptance tests as PB-70 (a `fs.labels`
consumer calling `apply_label`, owner M6) and PB-71 (a contract and producer for account reference
state, owner M6), with PB-69 for the database fallback (M6) and PB-72 requiring M10's end-to-end
verification to re-measure the skew and find **zero**.

**5. D-16 stays IN_PROGRESS.** Its resolution — processes rather than threads, `nthread=1`, a
compiled ONNX path, SO_REUSEPORT — is implemented and measured, and the measurement is what shows
the remaining gap. The defect closes when the throughput in ADR 0032 is demonstrated on the
dedicated machine.

## Consequences

- M5 may be tagged with nine rows met, two carried with their clause named, and four open with the
  reason and the number attached. No M5 row claims a threshold it has not measured.
- The ML-GATE rows M4 closed describe the **trained** model; ADR 0034's table is the difference
  between that and what production would serve until PB-70 and PB-71 land.
- M10's end-to-end verification inherits four open rows: FR-02-07, ML-GATE-12, TEST-10 and
  ML-GATE-13 need the dedicated machine and a running deployment; FR-02-09 needs the skew
  re-measured at zero.
