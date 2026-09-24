# M9 updates (branch `m9/infra`)

Written by the M9 agent for the owner and the M5, M6, M7 and M8 agents. Shared files
(`requirements.yaml`, `docs/backlog/`, `lab_notebook.md`, `SESSION_STATE.md`, `docs/adr/`) are not
edited on this branch; everything they need is proposed here. Scope as assigned: the parts of M9
that need no running services, no Docker and no heavy suites on the laptop.

**Status: not reviewed, not merged.** No requirement below may move to `DONE` until Principal
Review (Part I) and merge. The milestone gate is **not** met; see "Gate status".

## 1. What exists

| Area | Files | Checked by |
|---|---|---|
| Pinned tools | `infrastructure/bin/` (kustomize 5.8.1, kubeconform 0.8.0, promtool 3.14.0, amtool 0.34.0, lokitool 3.7.7, blackbox_exporter 0.28.0, actionlint 1.7.12, trivy 0.74.0, syft 1.51.1, cosign 3.1.3) | release SHA-256 plus binary SHA-256 on every run |
| Alert rules | `infrastructure/prometheus/rules/{recording,alerts,thresholds}.yml`, `infrastructure/loki/rules/` | promtool check + 13 unit tests, both sides of every threshold; lokitool lint |
| Scrape, probes, routing | `infrastructure/prometheus/{prometheus,blackbox}.yml`, `infrastructure/alertmanager/alertmanager.yml` | promtool check config, blackbox `--config.check`, amtool check-config + 5 routing assertions |
| Dashboards | `infrastructure/grafana/generate_dashboards.py` → `dashboards/*.json` (5), provisioning | `--check` against committed JSON; grid, datasource and runbook-link tests |
| Metric catalogue guard | `infrastructure/checks/metric_catalogue.py` | every query under infrastructure/ uses only Part E.10 metrics and labels and contract label values |
| Runbooks | `docs/runbooks/` (11, one per page alert) | `rule_conventions.py`, both directions |
| Kubernetes | `infrastructure/k8s/{base,overlays/staging,overlays/production}`, `infrastructure/argo-rollouts/` | kubeconform strict (k8s 1.34.11 + Argo CRDs, schemas pinned by commit); `k8s_policy.py` (PSA restricted offline, digests, probes, resources, PDB/HPA/NetworkPolicy coverage), 24 break-one-rule tests; canary queries run through promtool |
| CI workflows (new files only) | `.github/workflows/{infrastructure,security,supply-chain}.yml` | actionlint over all workflows |
| Threat model | `docs/security/threat_model.md` sections 3.6–3.8, risks R-5–R-9 | review |

One command runs every local check: `infrastructure/checks/validate.sh` (`--quick` skips the
week-long alert tests). It needs `uv` and network access for the first download only; no Docker.

## 2. Evidence (commands actually run)

| Check | Where | Result |
|---|---|---|
| `infrastructure/checks/validate.sh --quick` | laptop, 2026-09-22 | all checks passed; 87 pytest tests |
| `promtool test rules infrastructure/prometheus/tests/risk_test.yml` | laptop | SUCCESS, 147 s, 134 MB peak |
| Mutation spot checks | laptop | p99 threshold 0.080→0.090 and Kafka lag `>`→`>=` each failed a tagged promtool test; restored |
| kubeconform strict on a misspelt field | laptop | rejected (`additional properties 'maxUnavaliable' not allowed`) |
| `validate.sh` (full) + actionlint | CI `infrastructure`, runs 35691062981 (8d94d9c), 35691693047 (e408828) | success |
| Source SBOM, keyless cosign sign + verify | CI `supply-chain`, run 35691062958 | success (step "Sign and verify the SBOM") |
| Trivy configuration scan (HIGH/CRITICAL) | CI `security` | success |
| Trivy dependency scan | laptop (with `~/.m2`) | 0 HIGH/CRITICAL with a fix in `uv.lock` and the Maven poms |
| Trivy dependency scan | CI `security` | failed on 8d94d9c and e408828: Maven Central 429 while Trivy resolved parent POMs (reproduced locally). Fixed in 9056cc9 by resolving into `~/.m2` first |
| All workflows on 9056cc9 | CI runs: security 35692117624, supply-chain 35692117608, infrastructure 35692117611, ci 35692117637, stack 35692117616, devcontainer 35692117629 | all success. Executed and passed: Trivy DB download, Maven resolve, dependency scan, HIGH/CRITICAL gate, configuration scan, source SBOM sign + verify. Skipped (shown as skipped, not green): ZAP baseline, image jobs |

## 3. Owner decisions (1–4 decided 2026-09-22; 5 open)

1. **D-15 spool versus the Argo Rollouts canary. Decided 2026-09-22: ADR 0090.** Per-pod
   directories on a shared ReadWriteMany volume plus a replayer that recovers the spools of dead
   pods, conditional on spool append p95 fitting the 2 ms step-10 budget on target hardware (M10).
   Fallback: StatefulSet with per-pod volumes and a partitioned canary. Ephemeral spool storage is
   prohibited and `k8s_policy.py` enforces it. M6's contract is in section 6 below.
2. **AUC-drop alert. Decided 2026-09-22: ADR 0091.** Part E.10 gains `fs_model_auc_roc{alias}`
   and `fs_model_label_coverage{alias}`, and `fs_model_version_info` gains `version`. The
   `FraudShieldModelAucDrop` alert (ticket, ml) fires only while coverage is at least 0.30 (D-11),
   against the covered 30-day mean with at least 72 covered hours. `ml_test.yml` covers the drop,
   the non-drop, low coverage, a short baseline and the shadow alias.
3. **`fs_model_version_info` version label.** Decided with 2 (ADR 0091).
4. **D-10 timeout-release level. Decided 2026-09-22:** 0.10 stays as a placeholder, marked
   ASSUMED in the alert annotation, `thresholds.yml` and the runbook, to be replaced by the value
   from `docs/ml/capacity_model.md` when that exists.
5. **Proposed ADRs** (not written here; `docs/adr/` is not an M9-owned path):
   - lokitool (AGPL-3.0) runs as a CI-only checker and is never shipped;
   - production canary splits by replica count (no service mesh), hence at least 10 API pods;
   - all alert notifications go through PagerDuty services per team, keys from Secrets;
   - minimum supported Kubernetes version 1.34;
   - Trivy covers dependency CVEs; OWASP Dependency-Check (SRS 8.1) needs an NVD API key to run
     in reasonable time and is not configured: adopt with a key, or record the substitution.

## 4. Deviations to record in the traceability rows

| Row | Deviation | Reason |
|---|---|---|
| OPS-OBS-01 (TPS drop > 20%) | Baseline is the same 5 minutes one week earlier, with at least 1 req/s | daily and weekly cycles; no alert in the first week of a deployment |
| OPS-OBS-03 (PSI > 0.2 on top-10) | Alerts on any feature | the top 10 change per model version; can only add alerts |
| OPS-OBS-06 (fraud rate > 3σ) | Live signal is the HIGH-tier share per channel; baseline is 30 days of hourly values; needs 7 days of history | labels arrive hours to weeks later |
| OPS-OBS-05 (uptime) | Probes read the management port (`/actuator/health`, `/actuator/health/ml`) | ADR 0014 section 6 (already recorded) |
| OPS-OBS-02 (any ERROR log) | Loki ruler rule, not Prometheus | log signal |

## 5. Proposed traceability updates (for whoever owns `requirements.yaml`)

All `IN_PROGRESS` until review and merge; evidence paths are on `m9/infra`.

| ID | Implementation | Tests / evidence |
|---|---|---|
| OPS-OBS-01 | `infrastructure/prometheus/rules/*.yml`, `infrastructure/grafana/` | `prometheus/tests/*_test.yml`, `checks/tests/test_dashboards.py` |
| OPS-OBS-02 | `infrastructure/loki/rules/fraudshield/errors.yml` | `lokitool rules lint`; alert not yet exercised against real logs |
| OPS-OBS-03 | `alerts.yml` FraudShieldFeatureDrift and FraudShieldModelAucDrop; dashboard `fs-model-health` | `platform_test.yml`, `ml_test.yml` (ADR 0091) |
| OPS-OBS-05 | `prometheus.yml` blackbox jobs, `blackbox.yml`, probe alerts | `platform_test.yml` (two consecutive failures, flapping stays silent) |
| OPS-OBS-06 | `recording.yml` risk signals, FraudShieldFraudRateAnomaly | `risk_test.yml` (fires, 1σ silent, short history silent) |
| D-10 (5) | FraudShieldTimeoutReleaseRateHigh, `thresholds.yml` | `risk_test.yml` (above, below, low volume) |
| OPS-CI-04 | `.github/workflows/security.yml` | Trivy config scan passing in CI; dependency scan see section 2; ZAP not yet run (no API) |
| OPS-CI-06 | `.github/workflows/supply-chain.yml` | source SBOM signed and verified in CI; images not built (no Dockerfiles) |
| OPS-CI-07 | `infrastructure/k8s/overlays/staging` | rendered and validated; no deploy workflow yet |
| OPS-CI-08 | `infrastructure/argo-rollouts`, `overlays/production` | `test_canary_analysis.py`; never applied to a cluster |
| D-28 | threat model 3.6–3.8, Trivy, SBOM, cosign | CI runs above |

## 6. Interface requirements for other milestones

**M4/M5 (ML worker): model metrics (ADR 0091).** Publish `fs_model_auc_roc{alias}` and
`fs_model_label_coverage{alias}` together from the hourly drift job, over the same window, and
`fs_model_version_info{alias, version}`. `alias` is the MLflow alias without `@`: `production`,
`shadow`, `previous_production`.

**M6 (API) and M5 (scorer): metrics.** The alerts and dashboards already read these, and
`metric_catalogue.py` will reject any rename.
- Names and label sets exactly as Part E.10. Label values are contract values: `channel` from
  OpenAPI `Channel`; `fs_decisions_total{tier}` from `RiskTier`, `{decision}` from `Decision`
  (the synchronous APPROVE/DECLINE/HOLD), `{fallback}` `"true"`/`"false"`;
  `fs_alert_queue_depth{tier}` from `AlertTier`; `fs_kafka_consumer_lag{topic, group}` from
  `contracts/kafka/topics.yaml` (topic names and consumer names).
- `fs_ingest_requests_total{status}` is the HTTP status code as a string ("200", "503"): the
  canary counts `5..` as errors.
- Histograms (dashboards and alerts use `_bucket`): `fs_decision_latency_seconds` with bucket
  bounds including **0.04 and 0.08** (the SLO and the alert/canary threshold; without an exact
  0.08 bound the p99 test is an interpolation), and `fs_scoring_latency_seconds{stage}`,
  `fs_feature_latency_seconds{source}`, `fs_review_duration_seconds`.
- `fs_kafka_consumer_lag`: each instance reports the lag of the partitions it owns, so the sum
  over instances is the group's lag. If an exporter reports whole-group lag instead, change the
  alert to `max`.
- `fs_spool_depth`: per API pod, number of decisions not yet acknowledged by Kafka.
- No account, device or counterparty tokens as label values (threat model 3.7).

**M6: spool layout and replayer contract (ADR 0090).** Binding on the M6 spool module unless
changed by a new ADR. The manifests provide the volume and two variables; everything else is M6's.

- **Where.** `$FRAUDSHIELD_SPOOL_DIR` (`/var/lib/fraudshield/spool`) is a ReadWriteMany volume
  shared by all API pods. Each pod owns `$FRAUDSHIELD_SPOOL_DIR/$FRAUDSHIELD_SPOOL_INSTANCE/`
  (`FRAUDSHIELD_SPOOL_INSTANCE` is the pod name, unique per pod lifetime) and writes nowhere else
  except while replaying a claimed directory.
- **Files in a pod directory.**
  - `segment-<20-digit zero-padded sequence>.log`: append-only; records written in decision order;
    fsync batched every 5 ms (D-15); a segment is closed at a size bound and never modified after.
  - Each record is self-delimiting and checksummed (length + CRC32C or equivalent) so a torn tail
    written at the moment of a crash is detected and ignored, never replayed as garbage.
  - Each record carries the Kafka topic, key, the envelope `event_id` and the `transaction_id`, so
    replay needs nothing but the file.
  - `heartbeat`: its mtime is refreshed at least every 1 s by the owning pod.
  - `acked`: the highest sequence position acknowledged by Kafka (`acks=all`), updated after
    acknowledgement; replay starts after it. Fully acknowledged segments may be deleted by the
    owner.
- **Orphan detection.** A directory is orphaned when its `heartbeat` is older than 30 s
  (configurable, named constant with units). No Kubernetes API access is needed; API pods have no
  service account token (threat model 3.6).
- **Claiming.** A live pod claims an orphan by an atomic `rename` of the directory to
  `$FRAUDSHIELD_SPOOL_DIR/.claimed/<original name>.<claimer pod name>`. Only the pod whose rename
  succeeds replays it. A claimed directory whose claimer's own heartbeat goes stale can be claimed
  again the same way (the claimer died mid-replay).
- **Owner fencing.** An owner that finds its directory renamed (checked on every fsync batch) has
  been presumed dead: it stops accepting decisions (readiness DOWN) and exits, so no decision is
  written to a directory that is no longer its own.
- **Replay.** Segments in sequence order, records in file order, from after `acked`, through the
  same idempotent producer (`enable.idempotence=true`, `acks=all`). Duplicates are expected; every
  consumer is idempotent on `event_id` (ADR 0012).
- **Deletion.** A claimed directory is deleted only after every record is acknowledged **and** no
  byte has been appended for at least the orphan timeout (a fenced owner's last batch may land
  late).
- **Bound and back-pressure.** The spool has a configured size bound per pod; at the bound the pod
  answers decisions with the degraded behaviour M6 defines (never silently drops a decision).
- **Metrics.** `fs_spool_depth` per pod counts unacknowledged records in its own directory;
  records being replayed from claimed directories are counted by the claimer.
- **Tests M6 owns.** Torn-tail record ignored; replay order; concurrent claim by two pods (one
  wins); fenced owner stops; deletion waits for quiescence. The pod-deletion chaos case is M10's
  (ADR 0090 decision 5).

**M5, M6, M8: deployment interface.** Ports, probe paths, UID 10001, read-only root filesystem,
writable paths and the spool directory are in the table in `infrastructure/k8s/README.md`. In
particular: the scorer needs HTTP `/health/live` and `/health/ready` on its admin port 8000,
because kubelet gRPC probes cannot present an mTLS client certificate; the API must drain its
spool on SIGTERM within 120 s.

**Image builds.** `supply-chain.yml` builds an image as soon as its Dockerfile appears at:
`backend/Dockerfile`, `ml/Dockerfile`, `ml/Dockerfile.worker`, `frontend/Dockerfile`,
`infrastructure/docker/edge/Dockerfile`. If owners prefer other paths, change the list in the
`image-matrix` job.

## 7. Gate status (D.3 M9) and remaining work

| Gate item | Status |
|---|---|
| All 8 CI stages of SRS 8.1 | partial: security (Trivy, ZAP job) and image/SBOM/sign stages added; no integration stage, ML gate, staging or production deploy workflow |
| Grafana dashboards provisioned as code | done on branch (5 dashboards) |
| Alert rules for every SRS 8.2 threshold | all, including the AUC drop (ADR 0091); the AUC and coverage gauges are not emitted yet |
| k8s manifests: namespace, NetworkPolicies, PSA restricted, HPA, PDBs | done on branch for application and observability workloads; data stores not included |
| Argo Rollouts canary with automated analysis | done on branch; never applied to a cluster |
| ZAP 0 critical | not run: no API service in compose yet |
| Gitleaks 0 | existing `ci.yml` job, green on this branch |
| Trivy 0 high/critical unfixed | dependencies and configuration: 0, CI run 35692117624; images: none built yet |
| SBOM and cosign signatures | source SBOM signed and verified in CI; image signatures pending Dockerfiles |

Remaining M9 work not started here: data store manifests (CloudNativePG for PostgreSQL +
TimescaleDB and the PII vault, Kafka, Redis, MLflow, object store) with their NetworkPolicies
and encryption at rest (D-20, D-49); Loki, a log collector and the OpenTelemetry Collector; the
edge image (nginx: TLS 1.3, HSTS, CSP, rate limits); staging and production deploy workflows
with the GitHub environment approval; an alerting dead-man's switch and HA (R-6); admission-time
signature verification (R-7); OWASP Dependency-Check or its recorded substitution; SonarQube
(skipped without a token, per E.11).

**Scheduled and manual triggers.** The new workflows have nightly `schedule` and
`workflow_dispatch` triggers, but GitHub registers those only from the default branch, which is
still `m0/bootstrap`. Until it changes, they run on push and pull request only.

**Laptop note (2026-09-22).** Reproducing the Trivy failure triggered Maven Central's 429 for this
machine's IP (Retry-After 1800 s, from about 07:47 CEST). Maven builds that need artefacts not
already in `~/.m2` fail until the block clears.

## From M5 — a Prometheus alert, and two carries you may own (owner decision, 2026-09-22)

Appended by the M5 agent from `m5/scoring`. This file also exists on `m9/infra`; keep both sides at
merge, nothing here replaces M9's own content.

### Alert: a feature served from a constant (owner's condition 3, ADR 0034)

M5's feature store exports a counter for every read where a feature fell back to a constant because
no deployed component writes the state it reads. In production this must page, because the cost is
invisible in AUC and large in decisions: on M4's gate model, 162 of 725 frauds stopped reaching the
0.60 flag threshold (a 22% fall) while AUC moved 0.0089.

**Two states, two lifetimes** — the first draft of this rule had one arm over every state, which
would have paged continuously from the day it was deployed, and a rule that always fires is a rule
that gets silenced (found by the M5 re-review, N5):

- `state="outcomes"` **should be silent today** and must page if it starts. M5 changed the counter
  so it counts only reads where a verdict could have arrived and none had: the counterparty has a
  transaction older than three weeks (the ~95th percentile of E.3's label delay, median 72 h,
  log-sigma 1.2) and not one of its rows carries a verdict. Ordinary label latency no longer
  increments it, so once PB-70's consumer is deployed this arm goes to zero and stays there. It is
  the signal that the consumer has stopped.
- `state=~"kyc_tier|account_opened_at"` **fires on essentially every read** and will
  until PB-71 lands, because no contract exists for that state. Deploy it **inhibited** (or as a
  ticket, not a page) and turn it into a page in the same change that deploys the producer. Its
  value before then is the dashboard panel and the number in the runbook, not the page.

The counter no longer increments on a first-ever transaction (nothing to have written yet), and
the account-state arms stand down on a degraded read (the `feature_store_degraded` flag already
carries that), so both arms mean "a producer is missing" and nothing else.

**There is deliberately no `sim_swaps` arm.** Most accounts never had a SIM swap and the store
writes no "checked, none found" sentinel, so an absent swap cannot be told apart from an absent
producer: the arm fired on ordinary traffic and could never have fallen silent, even after PB-71
ships. `days_since_sim_swap` being constant is recorded in ADR 0034 and logged by every worker at
startup; **M9 should watch the SIM-swap producer's own liveness** once PB-71 gives it one, rather
than inferring it from scoring reads. The `outcomes` arm covers both features PB-70 feeds --
`counterparty_confirmed_fraud_90d` on the counterparty key and `geo_cell_fraud_rate_30d` on the
cell key, the latter being the only one an agent cash-out with no counterparty has at all.

```yaml
# infrastructure/prometheus/rules/ (M9 owns the file and the routing)
- alert: LabelsConsumerNotWritingOutcomes
  # Verdicts are not reaching the feature store: PB-70's consumer is down, stopped or not deployed.
  expr: sum(rate(fs_feature_store_missing_producer_reads_total{state="outcomes"}[30m])) > 0.01
  for: 30m
  labels:
    severity: page
  annotations:
    summary: "Scoring is serving fraud outcomes as constants (ADR 0034)"
    description: >
      counterparty_confirmed_fraud_90d and geo_cell_fraud_rate_30d are being read as constants on
      counterparties old enough to have been adjudicated, so the served model is not the evaluated
      one. On the gate model this state cost 235 risk-tier changes and 140 of 725 flagged frauds.
      Check the fs.labels consumer (PB-70, owner M6) before anything else.
    runbook_url: https://…/runbooks/labels-consumer-not-writing-outcomes

- alert: AccountReferenceStateHasNoProducer
  # Expected to fire until PB-71 lands: deploy inhibited, promote to `page` with the producer.
  expr: sum by (state) (
          rate(fs_feature_store_missing_producer_reads_total{
            state=~"kyc_tier|account_opened_at"}[30m])) > 0.01
  for: 2h
  labels:
    severity: ticket   # becomes `page` in the change that deploys PB-71's producer
  annotations:
    summary: "{{ $labels.state }} has no producer; scoring serves it as a constant (ADR 0034)"
    description: >
      Known and accepted while PB-71 (owner M6) is open: no contract carries SIM swaps, KYC tier or
      account opening, so days_since_sim_swap is NaN, kyc_tier and account_age_days are absent and
      synthetic_identity_score loses two of its four terms. This arm is the record that the carry
      is still open; it is not an incident until the producer exists.
    runbook_url: https://…/runbooks/account-reference-state-has-no-producer
```

The metric is `fs_feature_store_missing_producer_reads_total{state=...}`, incremented in
`ml/src/fraudshield_ml/featurestore/store.py` (`_count_missing_producers`, with the rules above in
its docstring and `ml/tests/featurestore/test_ingest.py` asserting both sides of each). Two
runbooks are needed, one per alert, as M9's `rule_conventions.py` requires.

### The two carries: M6 builds them, M9 deploys and watches them

Both are owned by **M6** (it owns the decision-side services and their database); the owner's
condition 2 left the choice between M6 and M9 open, and this is the assignment M5 proposes for
confirmation at integration. M9's part is the deployment and the two alerts above.

| Id | Carry | Owner | M9's part |
|---|---|---|---|
| PB-70 | a consumer of `fs.labels` calling `fraudshield_ml.featurestore.ingest.apply_label` | M6 | deploy it; `LabelsConsumerNotWritingOutcomes` goes to `page` |
| PB-71 | a contract **and** producer for account reference state (none exists today) | M6 | deploy it; `AccountReferenceStateHasNoProducer` goes from `ticket` to `page` |
| PB-72 | re-measure the skew end to end and require it to be **zero** | M10 | supply the production figures the measurement needs |

The full statements, with their acceptance tests, are in `docs/parallel/M6_updates.md` under "From
M5"; PB-72 is carried in `docs/parallel/M10_updates.md`. The backlog numbers are proposals: M5 does
not edit `docs/backlog/`, and PB-68 is already taken by the frontier write-up item.
