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

## 3. Decisions the owner needs to make

1. **D-15 spool versus the Argo Rollouts canary. Decided 2026-09-22: ADR 0090.** Per-pod
   directories on a shared ReadWriteMany volume plus a replayer that recovers the spools of dead
   pods, conditional on spool append p95 fitting the 2 ms step-10 budget on target hardware (M10).
   Fallback: StatefulSet with per-pod volumes and a partitioned canary. Ephemeral spool storage is
   prohibited and `k8s_policy.py` enforces it. M6's contract is in section 6 below.
2. **SRS 8.2 "AUC-ROC drop > 0.03" has no alert.** Part E.10 defines no live AUC metric, and the
   metric-catalogue check (as instructed) rejects anything outside E.10. Proposal: add
   `fs_model_auc_roc{alias}` (AUC on labels available so far, with `fs_label_coverage_ratio`) to
   E.10; the rule and its test are then a few lines.
3. **`fs_model_version_info{alias}` carries no version.** With `alias` as its only E.10 label a
   dashboard can show that `@production` exists, not which version it is. Proposal: add a
   `version` label in E.10.
4. **D-10 timeout-release level.** `fraudshield:config:timeout_release_ratio_max` defaults to
   0.10 in `rules/thresholds.yml`. It is a starting value, not a derived one; replace it with the
   value from `docs/ml/capacity_model.md` (not yet written).
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
| OPS-OBS-03 | `alerts.yml` FraudShieldFeatureDrift; dashboard `fs-model-health` | `platform_test.yml`; AUC-drop part missing (decision 2) |
| OPS-OBS-05 | `prometheus.yml` blackbox jobs, `blackbox.yml`, probe alerts | `platform_test.yml` (two consecutive failures, flapping stays silent) |
| OPS-OBS-06 | `recording.yml` risk signals, FraudShieldFraudRateAnomaly | `risk_test.yml` (fires, 1σ silent, short history silent) |
| D-10 (5) | FraudShieldTimeoutReleaseRateHigh, `thresholds.yml` | `risk_test.yml` (above, below, low volume) |
| OPS-CI-04 | `.github/workflows/security.yml` | Trivy config scan passing in CI; dependency scan see section 2; ZAP not yet run (no API) |
| OPS-CI-06 | `.github/workflows/supply-chain.yml` | source SBOM signed and verified in CI; images not built (no Dockerfiles) |
| OPS-CI-07 | `infrastructure/k8s/overlays/staging` | rendered and validated; no deploy workflow yet |
| OPS-CI-08 | `infrastructure/argo-rollouts`, `overlays/production` | `test_canary_analysis.py`; never applied to a cluster |
| D-28 | threat model 3.6–3.8, Trivy, SBOM, cosign | CI runs above |

## 6. Interface requirements for other milestones

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
| Alert rules for every SRS 8.2 threshold | all but AUC drop (decision 2) |
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
