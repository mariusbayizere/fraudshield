# 0022 — Dataset generator architecture

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** ML-DATA-01 … 08, RES-01, RES-02, D-07, D-08 (build prompt Part E.3)
- **Owner directions (2026-09-17):** parameter provenance, streaming under 2 GB peak RSS,
  byte-identical output across chunk sizes, executable anti-leakage and shortcut checks, and
  scenario design notes.

## Context

FraudShield-EAC-Transactions is a synthetic benchmark that later papers and defences cite. Four
properties matter more than realism details:

1. Every number can be traced to a source or is plainly marked as assumed.
2. Output is reproducible from a seed.
3. Labels cannot be predicted from artefacts of the generator (D-08).
4. It runs on a laptop with little memory.

## Decision

### 1. Package and parameters

- `dataset/` becomes a uv workspace member (`fraudshield-dataset`):
  - `dataset/src/fraudshield_dataset/generator/` is the simulator;
  - `dataset/src/fraudshield_dataset/realism/` holds the checks and the report;
  - `dataset/generator/params/*.yaml` holds every parameter, as data outside the code.
- Each parameter carries:
  - `value`;
  - `unit`;
  - `provenance`: one of `SOURCED`, `ASSUMED`, `CALIBRATED_TO_SRS_TARGET`;
  - `citation`: required for `SOURCED`, giving document, table or page, year and URL. Only sources
    actually read may be cited.
  - `rationale`: required for `ASSUMED`.
- SRS 7.1 targets (fraud rate, channel mix, country mix, size, span) are `CALIBRATED_TO_SRS_TARGET`,
  citing the SRS row.
- A loader rejects any parameter without these fields, and code reads parameters only through the
  loader.
- `dataset/params_provenance.md` is generated. It lists every parameter by category with counts per
  provenance type, and the datasheet includes it.

### 2. Determinism

- Randomness comes from `numpy.random.Generator(PCG64)`, seeded by a `SeedSequence` derived from
  `(global seed, stream name, entity id, month)`. It never depends on processing order or chunking.
- Entities (customers, accounts, devices, agents, merchants) are generated per fixed **shard**:
  `shard = keyed hash(account id) mod 64`. The shard count is part of the dataset definition, not a
  tuning knob.
- The `chunk_size` setting only controls how many shards are simulated together in memory.
- Cross-account structures are planned first, per month, from their own streams, in a small plan
  table: mule rings, merchant fraud, agent collusion, synthetic identities. Account simulation then
  reads that plan.
- Identifiers (`transaction_id`, tokens) are derived from a keyed hash (BLAKE2b) of
  `(seed, entity id, sequence number)`. They carry no order, time or label information.
- Output rows are sorted by `(transaction_timestamp, transaction_id)`.
- **Test:** the same seed at different chunk sizes gives byte-identical Parquet files and checksums.

### 3. Streaming and memory

- Simulation runs month by month. Within a month, shards are generated in batches of `chunk_size`
  and concatenated.
- Each month is sorted and written as `transactions/month=YYYY-MM/part-0000.parquet`, with a fixed
  row-group size and fixed writer options. The monthly volume at the 5M target is about 350K rows,
  under 100 MB in columnar form.
- CSV exists only at export (`fs-dataset export --csv`), streamed row group by row group.
- Peak RSS (`resource.getrusage`) is recorded in the realism report. A test fails the run above
  2 GB.
- Development and CI tests use 100K–500K rows. The full 5M+ run happens in Codespaces or a
  dispatched CI job, with the machine spec recorded.

### 4. Temporal split and labels (D-07)

- The simulation covers 24 months with volume growth. Splits are by time only:
  - **train:** about 4.16M rows;
  - **validation:** about 520K rows; its chronologically last 40% is the calibration split;
  - **7-day embargo**, excluded from every split;
  - **test:** about 520K rows, the most recent contiguous period.
- Boundaries are computed from the daily row counts after generation. The test span is measured and
  reported, and the test set is called the "temporal hold-out test set".
- Label columns:
  - `is_fraud_observed`: after 1–2% label noise;
  - `is_fraud_true` and `fraud_type`: in a separate labels table, for evaluating the checks and
    the noise;
  - `label_available_at`: the label delay, from hours to weeks.

### 5. Anti-leakage and realism checks are tests (D-08)

- **Gate checks (pytest, tagged D-08):** each fails the build.
  - Every single-feature AUC is at most 0.80 (as `max(AUC, 1 − AUC)`) over raw and cheap per-row
    derived columns.
  - The trivial rule baseline AUC is reported.
  - The observed label noise rate is between 1% and 2%.
  - The novelty sub-variant appears only in the test period.
  - Fraud and legitimate rows share schema, null pattern and value formats per channel.
  - **Shortcut detector:** a depth-3 decision tree trained only on non-behavioural columns
    (identifier bytes, token prefixes, sub-second timestamp parts, row position within its file,
    file index) must reach a cross-validated AUC within 0.5 ± 0.03.
- **Deferred to M3:** the same single-feature AUC limit is re-run over the 44 engineered features
  there.
- **Realism report:** `fs-dataset report` generates `dataset/realism_report.md` from a generated
  dataset.

### 6. Fraud scenarios

- Eight generators, each a module with randomised parameters and temporal evolution:
  - SIM-swap takeover;
  - account takeover;
  - agent fraud;
  - velocity fraud;
  - card-not-present;
  - mule accounts;
  - merchant fraud;
  - synthetic identity.
- Adversarial adaptation starts in simulation month 12. One novel sub-variant is enabled only in the
  test period.
- Each scenario has a design note in `docs/ml/scenarios/`. Notes describe the mechanism, detection
  signals, legitimate look-alikes and assumed parameters, at detection level only.

### 6a. SRS targets are met by construction, not by sampling luck

Owner direction, 2026-09-17: the fraud-rate and country-mix targets must hold at any size, and a
deviation must be explainable as bias or as noise. Probabilistic sampling met them only in
expectation, and a 200K-row run missed the test-period fraud rate (0.605% against 0.91%) and the
country mix (2.2 pp) well outside tolerance. Four changes make the targets structural:

- **A monthly fraud intensity schedule.** `fraud.monthly_intensity` (24 values,
  CALIBRATED_TO_SRS_TARGET) carries a rising trend with month-to-month variation. `fraud_schedule`
  rescales it so that the volume-weighted overall rate is exactly 0.87% and refuses to run if the
  planned test period lands more than 0.02 pp from 0.91%.
- **Exact scenario quotas.** Each month's target is split across the scenarios by their ML-DATA-04
  shares and placed on exactly that many victims, chosen by the smallest keyed draw among the
  eligible customers. Nothing is left to per-customer coin flips.
- **Capacity-aware reallocation.** A scenario can be infeasible in a month: at the start of the
  simulation no customer has reached its bust-out month, so synthetic identity cannot run. Its
  share is reallocated to the scenarios that have spare capacity, so the month still hits its
  fraud target exactly while the mix stays as close to ML-DATA-04 as the population allows. The
  generator fails if no scenario has capacity for the month's target.
- **Exact population quotas.** Country, then segment within country, are assigned by sequential
  apportionment, so every prefix of the population is within one customer of its quota. Activity
  multipliers are placed on the log-normal's quantiles and rescaled to average one per country, so
  volume and mix no longer inherit the tail's sampling variance.

Verification reports a Wilson 95% confidence interval for each month's fraud rate next to its
target, so noise (the interval covers the target) is distinguishable from bias (it does not). The
same standard sizes the identifier-construction band: the wider of 0.03 and 1.96 null standard
errors for the number of distinct tokens actually measured, because an AUC over a few hundred fraud
tokens is noisy by construction.

Dataset-level target checks run on the test period, which the SRS specifies as a dataset property.
No model metric or feature-label statistic is computed on the test period during generator work.

### 7. Dependencies

`numpy` (BSD-3-Clause), `pyarrow` (Apache-2.0) and `PyYAML` (MIT), all permissive (ADR 0009).

scikit-learn was considered for the shortcut detector and rejected:
- it pulls in SciPy, whose wheel bundles OpenBLAS and GCC runtime libraries under several licences,
  including GPL-3.0 with the GCC runtime exception;
- it adds a heavy import on a low-memory machine.

The detector needs only rank-based AUC and a depth-limited decision tree. Both are implemented in
NumPy with their own unit tests against hand-computed cases.

## Consequences

- Generated data is never committed. Released artefacts carry SHA-256 checksums.
- Changing the shard count, the hash key derivation or the writer options changes every output
  file. This is a dataset version change and is recorded in the datasheet.
- Most behavioural parameters will be `ASSUMED`. The datasheet and paper must say so, and the
  counts per provenance type are part of the generated report.
