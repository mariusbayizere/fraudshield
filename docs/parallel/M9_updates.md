# M9 updates (branch `m9/infra`)

Written by the M9 agent for the owner and the M5, M6, M7 and M8 agents. This branch edits shared
files: `docs/traceability/requirements.yaml` (notes on OPS-CI-08, OPS-OBS-01, OPS-OBS-03 and
OPS-OBS-05; no status changes) and its rendered matrix, `docs/backlog/product.md` (the PB-171 to
PB-190 claims row, PB-171 to PB-179 and PB-183), `docs/research/lab_notebook.md` (M9's entries) and
`docs/adr/` (0090, 0091 and 0093). It does not edit `SESSION_STATE.md`. Scope as assigned: the
parts of M9 that need no running services, no Docker and no heavy suites on the laptop.

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
| Threat model | `docs/security/threat_model.md` sections 3.8–3.10, risks R-14–R-18 | review |

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

## 3. Owner decisions (1–4 decided 2026-09-22; 5 decided 2026-10-20, see 3b)

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

   Superseded by 3b: the owner decided all five on 2026-10-20, three of them differently from
   what is proposed here.

## 3b. Owner decisions, 2026-10-20

Given verbally by the owner (Marius Bayizere) and never committed; recorded here on 2026-10-20 at
the owner's instruction, as the owner's decisions. Each entry quotes the owner's words exactly, then
gives **M9's reading**: what M9 takes the decision to require. The readings are the M9 agent's, not
the owner's, and nothing in them is done yet unless it says so. Questions back to the owner are
marked **Open**.

1. Owner: "lokitool in CI only: yes."
   - M9's reading: lokitool (AGPL-3.0) is never shipped in an image or a deployment, and an ADR
     records it.
   - Asked, because `infrastructure/checks/validate.sh` also ran lokitool locally. Owner,
     2026-10-20: "CI only means CI only. Remove it from validate.sh, or make validate.sh skip it
     locally and say why in a comment." Done: `validate.sh` runs the Loki step only where `CI` is
     `true` (GitHub Actions), with the reason in a comment, and prints that it skipped it
     elsewhere.
2. Owner: "Canary by replica count requiring 10 API pods: no. Specify a canary that works at the
   replica counts this system will actually run at, and record the constraint."
   - M9's reading: state the replica counts the system will run at, with their source; design a
     canary whose steps work at those counts; record its constraint in an ADR. The current
     `infrastructure/argo-rollouts/` design (10% by replica count, hence at least 10 API pods;
     `infrastructure/k8s/README.md`) is withdrawn and must change.
3. Owner: "PagerDuty for all alerts: no. Specify a webhook alert sink with PagerDuty as one
   adapter, so no vendor is baked in."
   - M9's reading: Alertmanager routes to a generic webhook receiver contract, with PagerDuty one
     adapter behind it. The current `alertmanager.yml` (PagerDuty receivers per team, keys from
     the Secret `fraudshield-alertmanager-pagerduty`) and the Argo Rollouts `pagerdutyv2`
     notification in `infrastructure/k8s/README.md` must change.
4. Owner: "Minimum Kubernetes 1.34: too aggressive. Set the floor at the oldest version whose
   features you actually use, state which features set it, and record the substitution."
   - M9's reading: inventory the Kubernetes API versions and fields the manifests use and the
     version each became stable; the floor follows from that, and kubeconform's pinned schema
     version moves to it. The floor is not known yet; nothing here asserts one.
5. Owner: "OWASP Dependency-Check with an NVD key: use the recorded substitution (Trivy or OSV),
   not a key I would have to register for."
   - M9's reading and choice: Trivy, which already runs in `security.yml` (section 2);
     OSV-Scanner stays available if Trivy's coverage proves short. An ADR records the
     substitution against the tool SRS 8.1 names.
6. Owner: "Cloud and KMS vendor: deferred. Specify the interface and ship a reference
   implementation; no vendor name in the code path."
   - M9's reading: the vault's `KmsClient` port (section 8.2) gets a reference implementation
     that names no vendor and passes `KmsClientContract`.
   - Section 8.2's acceptance 1 and 2 ask for a binding to a named service (AWS KMS, Google Cloud
     KMS or Vault Transit) tested against the real service. Owner, 2026-10-20: "your reading is
     right. The deferral leaves §8.2 open until I pick a vendor. Record it as open with that
     reason rather than closed." **Section 8.2 acceptance 1 and 2: open, because no vendor has
     been chosen.**
7. Owner: "PB-70/71 to M6 and PB-72 to M10: confirmed." (As already recorded in the claims table of
   `docs/backlog/product.md`, owner confirmation 2026-10-17.)
8. Owner: "Storage-class fsync fallback: raise as an ADR with options and a recommendation, do not
   implement."
   - M9's reading: ADR 0092 (claimed below), Proposed, with options and a recommendation, on what
     happens when a storage class does not meet ADR 0090's condition (spool append p95 on the
     shared volume within the 2 ms step-10 budget). No code.

## 3c. Owner decisions on the staleness findings, 2026-10-20

The owner's words, then what was done on this branch. Sizes were reported to the owner first.

1. **Spool.** "take the stop-gap now (per-pod directories, 2 manifest lines) and record that dead
   pods' spools are then stranded rather than replayed. File (b) — the StatefulSet with a volume
   per pod — as the real fix, owned jointly by M9 and M6, with its canary consequence stated: a
   canary by pod is 33% at 3 replicas, which is the constraint I rejected, so a 10% step needs
   weighted traffic at the edge. Do not start (a)."
   - Done: `infrastructure/k8s/base/api.yaml` sets `FRAUDSHIELD_SPOOL_DIR` to
     `/var/lib/fraudshield/spool/$(FRAUDSHIELD_SPOOL_INSTANCE)`. Until then every API pod opened
     the same directory, because the API reads only `FRAUDSHIELD_SPOOL_DIR`. The spool's
     dead-letter directory sits inside it, so it is per pod too.
   - **A dead pod's spool is stranded, not replayed.** Its records stay on the shared volume
     until an operator drains them. Section 6's replayer contract is not implemented by M6 and
     will not be; it is superseded by PB-171.
   - Filed: PB-171 (M9 and M6), with the canary consequence in the owner's words.
2. **Scorer admin port.** "fix it now, it is a few lines." Done: the scorer listens on 8081
   (`--admin-port` default in `ml/src/fraudshield_ml/serving/cli.py`). `ml.yaml`, its
   NetworkPolicy and the README now use 8081; they said 8000. Still open: `ml.yaml` passes no
   arguments and mounts no certificates, so the scorer cannot start from it yet (PB-179).
3. **ML worker and `/actuator/health/ml`.** "file both, owned by M5 and M6. An alert that fires
   permanently is worse than no alert, so disable or remove those probes until the endpoints
   exist."
   - Filed: PB-172 (the worker, M5) and PB-173 (`/actuator/health/ml`, M6).
   - The `blackbox-ml-health` scrape job is removed from `prometheus.yml`, with a comment saying
     why. With no `probe_success` series, `FraudShieldMlHealthProbeFailing` cannot fire. The
     alert, its unit test and its runbook stay, ready for PB-173.
   - `ml-worker.yaml` is out of the deployed base, and out of the staging patch, until PB-172:
     no code serves its probes or metrics and no image exists.
4. **Metrics.** "file the whole table as scoped work per owner. Write the ADR for the ~35
   emitted names E.10 does not list and bring it to me. Until the metrics exist, mark every
   alert and dashboard panel that reads nothing as not functional in M9's rows — not done."
   - Filed: PB-174 (M6), PB-175 (M5), PB-176 (M5, after PB-172), PB-177 (M6 and M8B) and PB-178
     (M9, on ADR 0093).
   - ADR 0093 is written as Proposed, for the owner.
   - The rows are marked in `docs/traceability/requirements.yaml`; the list is in section 7.
5. **`fs_spool_depth` export.** "Confirm the fs_spool_depth_bytes export with one scrape." Java
   is built and tested in CI only, so the scrape is a test:
   `AssembledApplicationTest.theSpoolDepthGaugeIsExportedUnderTheNameM9sAlertAndDashboardRead`
   scrapes the assembled API's `/actuator/prometheus` and expects `fs_spool_depth_bytes`.
   **Confirmed:** it passed in CI run 39376711927 (java job, `AssembledApplicationTest`: 5 tests,
   0 failures) on `97f4504`, after a first run on `e4b589b` failed with 401 because the test
   harness's management port is 0. The API exports `# TYPE fs_spool_depth_bytes gauge`, and no
   series named `fs_spool_depth`.

## 3d. Owner decisions, 2026-10-20 (third)

1. **Dependency vulnerabilities.** "M9 bumps them now, on this branch. Do not file it for M6 —
   three CRITICAL authentication bypasses in a fraud system do not wait for a milestone handoff.
   Bump Tomcat to 11.0.25, Jackson to 3.1.7 and lz4-java to 1.11.4, get CI green at job level, and
   get the change independently reviewed since it touches every module's dependency tree."
   Done in `backend/pom.xml`, with an amendment to ADR 0007. The notebook entry of 2026-10-20 has
   the record, including the exposure window: about four weeks on `main` (since 2026-09-24) and on
   the branches building the API (since 2026-09-22). It also proposes moving the security workflow
   to `main` ahead of the rest of M9.
2. **ADR 0093: accepted, all four points.** The E.10 amendment and the catalogue change are
   PB-178, not yet made.
3. **The 7 alerts, 15 panels and the canary check that read nothing.** "that is the correct
   status. Leave them not functional rather than quietly adjusting them to something that
   resolves."

### Identifiers claimed by this branch

| Id | Subject | State |
|---|---|---|
| ADR 0090 | API spool on a shared volume with orphan replay | written on this branch |
| ADR 0091 | live AUC and label-coverage metrics | written on this branch |
| ADR 0092 | the storage-class fsync fallback for ADR 0090 (decision 8) | claimed, not written |
| ADR 0093 | the metrics the code emits that Part E.10 does not list | Accepted 2026-10-20 |
| PB-171 to PB-190 | infrastructure follow-ups | PB-171 to PB-179 and PB-183 used here; PB-180 to PB-182 used on `main` (2026-10-21, auth test items for M7); next free PB-184 |

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
| D-28 | threat model 3.8–3.10, Trivy, SBOM, cosign | CI runs above |

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
- No account, device or counterparty tokens as label values (threat model 3.9).

**M6: spool layout and replayer contract (ADR 0090). Superseded, 2026-10-20 (section 3c, item 1):
not binding.** M6 built a per-instance spool with two consumers (ADR 0064), not this; the owner
ruled that this contract's option is not to be started and filed the StatefulSet with a volume
per pod as the fix (PB-171). Until then each pod has its own directory and **nothing replays a
dead pod's spool**. Kept below as the record of what ADR 0090 proposed.

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
  service account token (threat model 3.8).
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
particular: the scorer needs HTTP `/health/live` and `/health/ready` on its admin port 8081 (corrected 2026-10-20 to the scorer's `--admin-port` default; it said 8000),
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
| Grafana dashboards provisioned as code | written on branch (5 dashboards); **15 panels are not functional**: they read metrics nothing emits (list below) |
| Alert rules for every SRS 8.2 threshold | written for all, including the AUC drop (ADR 0091); **7 are not functional** (list below) |
| k8s manifests: namespace, NetworkPolicies, PSA restricted, HPA, PDBs | done on branch for application and observability workloads; data stores not included |
| Argo Rollouts canary with automated analysis | built on branch, never applied to a cluster; the design is withdrawn by the owner (3b, decision 2) and must be redone |
| ZAP 0 critical | not run: no API service in compose yet |
| Gitleaks 0 | existing `ci.yml` job, green on this branch |
| Trivy 0 high/critical unfixed | dependencies and configuration: 0, CI run 35692117624; images: none built yet |
| SBOM and cosign signatures | source SBOM signed and verified in CI; image signatures pending Dockerfiles |

**Not functional until the metric exists (owner, 2026-10-20: "not done").** Checked on
2026-10-20 by resolving every alert, recording rule, dashboard query and canary query on this branch
to the `fs_` metrics it reads, against what `main` emits (`fs_decisions_total`,
`fs_decision_latency_seconds`, `fs_alert_timeout_release_total`, and `fs_spool_depth_bytes`, the
name the scrape test confirmed on 2026-10-20).

| Reads | Missing metric | Backlog |
|---|---|---|
| Alert `FraudShieldTransactionRateDrop`; panels fs-decision-path "Transactions per second" and "Ingest responses by status"; **the canary's `error-rate` analysis, which fails every rollout** | `fs_ingest_requests_total` | PB-174 |
| Alert `FraudShieldKafkaConsumerLagHigh`; panel fs-kafka-spool "Consumer lag by topic and group" | `fs_kafka_consumer_lag` | PB-174 |
| Alert `FraudShieldSpoolBacklog`; panel fs-kafka-spool "Spool depth by pod" | `fs_spool_depth`: the API exports `fs_spool_depth_bytes` (confirmed by the scrape, CI run 39376711927), so these read nothing | PB-174, ADR 0093 |
| Panel fs-decision-path "Redis hit ratio and DB pool in use" | `fs_redis_hit_ratio`, `fs_db_pool_in_use` | PB-174 (DB pool), PB-178 and ADR 0093 (both) |
| Panels fs-decision-path "Scoring latency p95 by stage", "Feature fetch latency p95 by source"; fs-model-health "Model versions by alias" | `fs_scoring_latency_seconds`, `fs_feature_latency_seconds`, `fs_model_version_info` | PB-175 |
| Alerts `FraudShieldFalsePositiveRateHigh`, `FraudShieldFeatureDrift`, `FraudShieldModelAucDrop`; panels fs-risk-overview "False positive rate (labelled)", fs-model-health "Live AUC-ROC (production) and alert level", "Label coverage by alias", "Top 10 features by PSI" | `fs_false_positive_rate`, `fs_feature_psi`, `fs_model_auc_roc`, `fs_model_label_coverage` | PB-176 (after PB-172) |
| Panels fs-analyst-operations "Alert queue depth by tier", "Review duration p50 / p90", "Review duration distribution" | `fs_alert_queue_depth`, `fs_review_duration_seconds` | PB-177 |
| Alert `FraudShieldMlHealthProbeFailing` | its probe is disabled: the API serves no `/actuator/health/ml` | PB-173 |

The p99 latency alert and the canary's latency check read an emitted metric, but interpolate:
`fs_decision_latency_seconds` has no 0.08 bucket bound (PB-174).

Remaining M9 work not started here: data store manifests (CloudNativePG for PostgreSQL +
TimescaleDB and the PII vault, Kafka, Redis, MLflow, object store) with their NetworkPolicies
and encryption at rest (D-20, D-49); Loki, a log collector and the OpenTelemetry Collector; the
edge image (nginx: TLS 1.3, HSTS, CSP, rate limits); staging and production deploy workflows
with the GitHub environment approval; an alerting dead-man's switch and HA (R-15); admission-time
signature verification (R-16); the ADR recording Trivy as the substitution for OWASP
Dependency-Check (3b, decision 5); SonarQube
(skipped without a token, per E.11).

**Scheduled and manual triggers.** The new workflows have nightly `schedule` and
`workflow_dispatch` triggers, but GitHub registers those only from the default branch, which is
still `m0/bootstrap`. Until it changes, they run on push and pull request only.

**Laptop note (2026-09-22).** Reproducing the Trivy failure triggered Maven Central's 429 for this
machine's IP (Retry-After 1800 s, from about 07:47 CEST). Maven builds that need artefacts not
already in `~/.m2` fail until the block clears.

## 8. Carried from M6 to M9 (owner decision, 2026-09-23)

Written by the M6 session at the owner's request, so these cannot be lost at merge. M6's side is on
`m6/decision` (ADR 0062 point 6, ADR 0069 points 3, 9 and 10; `docs/parallel/M6_updates.md`). Each
item needs its acceptance criteria met before M9's gate; M6 does not re-check them.

### 8.1 The local compose changes, carried into the deployment manifests

M6 changed the local stack (`docker-compose.yml`, `.env.example`, `Makefile`,
`infrastructure/docker/pii-vault/init/20-vault-roles.sh`, `infrastructure/docker/timescaledb/init/20-fraudshield-roles.sh`):
`fs_scorer`'s password reaches the main database; the PII vault creates its roles and schema on
first start (`db/vault/bootstrap.sql`); a one-shot `pii-vault-migrate` job (Flyway 12.4.0) applies
`db/vault` as `fs_vault_migrator`; `make up` fails if that job fails
(`infrastructure/docker/scripts/await-oneshot.sh`, which reads the job's recorded exit status:
`docker compose up --wait` ignores a one-shot's exit code, and `docker compose wait` cannot see a
container that has already exited).

Acceptance:
1. The main database bootstrap (`db/bootstrap/bootstrap.sql`) is re-run on every existing database
   **before** Flyway V67, which grants to the new `fs_scorer` role and fails if the role is absent.
2. `FS_SCORER_DB_PASSWORD`, `FS_VAULT_MIGRATOR_DB_PASSWORD` and `FS_VAULT_DB_PASSWORD` come from the
   deployment's secret store, never from a manifest, image or ConfigMap; the scorer receives only
   `FS_SCORER_DB_PASSWORD`, the API only the `fs_vault` password.
3. The vault's migrations run as a separate job with `fs_vault_migrator`'s credentials, and a failed
   job fails the rollout. No long-running workload holds the migrator's credentials.
4. The vault is its own PostgreSQL instance with its own volume and backups. Network policy lets only
   the API (the notification path) and the migration job reach it; `fs_app`, `fs_app_readonly` and
   `fs_compliance_ro` do not exist there, and a smoke check shows a login as `fs_app` is refused.
5. The scorer is started with `--feature-store-database postgresql://fs_scorer@<host>:<port>/<db>`
   and can reach the main database; nothing else logs in as `fs_scorer`.

### 8.2 The key-management binding for the vault (D-20)

The vault's production key provider is `KmsKeyProvider` over a `KmsClient` port. M6 ships the port,
a contract suite and an in-memory double; the vendor binding waits on M9's choice of cloud.

Acceptance:
1. A `KmsClient` implementation for the chosen key service (AWS KMS `GenerateDataKey`/`Decrypt` with
   an encryption context, Google Cloud KMS with additional authenticated data, or Vault Transit
   `datakey`), bound as a Spring bean in the API.
2. It passes `KmsClientContract` (the `fraudshield-notify` test jar, as of `m6/decision` `9f68ec7`)
   against the real service, in CI or a recorded integration environment. The binding's test
   supplies the client, two key ids and `unreachableClient()` (the same binding pointed at an
   endpoint it cannot reach). The contract checks: 256-bit data keys; a wrong context, key or
   wrapping refused; a wrapping that does not verify is `VaultException.permanent`; an unknown key
   and an unreachable service are refused but **not** permanent. Throttling and other transient
   service errors must also map to a non-permanent `VaultException`; the contract cannot provoke
   those against a real service, so the binding shows that mapping in its own unit tests.
3. Production runs with `fraudshield.vault.key-provider=kms` (the default). No production profile
   includes `dev`, `demo` or `test` (which would allow keys from configuration), and no master keys
   appear in production configuration (the application refuses the combination).
4. The blind-index key is configured wrapped under the key service (`fraudshield.vault.index-key`
   plus `index-key-id`) and never changes once tokens exist.
5. Rotation is documented as a runbook: add the new key-encryption key to
   `fraudshield.vault.readable-key-ids` on every instance **before** making it current, and keep a
   retired key readable until no vault row names it (ADR 0069: an instance without a row's key
   retries rather than dead-lettering, so a skipped step stalls notifications instead of losing
   them).

### 8.3 Already M9's, listed for completeness (ADR 0021, D-20)

Encrypted volumes for both databases and Redis, Redis TLS in transit, and the network policies
above. Not re-specified here.

### 8.4 Correction to 8.1 (M6 session, 2026-09-23, after the sixth independent review)

Section 8.1 describes a design M6 has since replaced, and one statement in it is wrong. **Read this
instead of 8.1's description of `make up`:**

- **`docker compose up --wait` does not ignore a one-shot's exit code: it fails on a one-shot that
  has already exited, even with status 0,** unless another service in the same `up` depends on it
  with `condition: service_completed_successfully` (the protection `object-store-init` has, through
  `mlflow`). Whether the one-shot has exited when Compose inspects it is a race. In CI's full `core`
  stack it had, and the `stack` job failed on every M6 push from `0747d6c` to `9f68ec7`; the
  sixth review reproduced it on Compose 5.5.1, 2.29.7 and 2.33.1.
- **Current design (`m6/decision` `e7a5be5`):** `pii-vault-migrate` is listed under the `full`
  profile only, so `--profile core up` never starts it, and `make up` runs it after the stack is
  healthy with `docker compose --profile core run --rm pii-vault-migrate`, which returns Flyway's
  exit status. `await-oneshot.sh` no longer exists.
- **Rule for M9's manifests and workflows:** any service that needs the vault migrated (the API
  when it joins the stack) must depend on `pii-vault-migrate` with
  `condition: service_completed_successfully`, **in the same profile**. An API in `core` cannot
  depend on a `full`-only service (Compose rejects it: "depends on undefined service"), and an API
  with no dependency starts against a vault that was never migrated.
- **Latent trap in this branch:** `.github/workflows/security.yml` runs
  `docker compose --profile full up -d --wait fraudshield-api`. Under `--profile full`,
  `pii-vault-migrate` is started by `up` and the race above returns unless `fraudshield-api` depends
  on it with `service_completed_successfully`. Acceptance for 8.1 therefore adds: **6.** every `up
  --wait` path that starts `pii-vault-migrate` has a dependent with `service_completed_successfully`,
  and CI's job that runs it has executed green on the head that introduces it.

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

## From M7 — the ML image redistributes third-party binaries whose notices must travel with it (owner, 2026-10-17; widened the same day)

M7 read the licence files shipped inside the wheels, not their metadata, when it settled the three
Python licence flags (`tools/src/fraudshield_tools/licences.py`). Every fact below was read from the
locked wheels for Linux x86-64 and CPython 3.12 on 2026-10-17; nothing is taken from a package's
declared licence.

### What the image redistributes

`xgboost` 3.0.2 is a **runtime** dependency of `fraudshield-ml`, not a dev one. A container image
built from the lock therefore redistributes, in binary form, the following (examples, not the
list):

| Component | Comes in with | Licence (shipped text) | Where its text is |
|---|---|---|---|
| NCCL | `nvidia-nccl-cu12` 2.31.2 | BSD-3-Clause (NVIDIA, LBNL, US DoE named); the metadata tag `LicenseRef-NVIDIA-Proprietary` disagrees, and the text governs | `nvidia_nccl_cu12-2.31.2.dist-info/licenses/License.txt` |
| NVTX files inside NCCL | `nvidia-nccl-cu12` | Apache-2.0 WITH LLVM-exception | **not in the wheel**: NCCL's `License.txt` names the NVTX project and points to github.com/NVIDIA/NVTX for its licence, so the text must be fetched from there |
| CUDA device code and the CUDA runtime, statically linked | `xgboost`'s `manylinux_2_28_x86_64` wheel (253,887,220 bytes) | NVIDIA's CUDA Toolkit EULA governs the CUDA runtime it links (`libcudart_static` symbols are present), **not** Apache-2.0; the wheel ships no licence file at all | none in the wheel; see below |
| `libgomp` (GCC runtime) | `xgboost` (`xgboost.libs/libgomp-*.so`) and `scikit-learn` (`scikit_learn.libs/libgomp-*.so`) | GPL-3.0-or-later WITH GCC-exception-3.1 | `scikit-learn`: `dist-info/licenses/COPYING` (whether it covers libgomp is not yet read); `xgboost`: **no file** |
| OpenBLAS, LAPACK, `libgfortran`, `libquadmath` | `scipy` 1.18.1 (`scipy.libs/`) **and `numpy` 2.5.3** (`numpy.libs/`) | BSD-3-Clause; BSD-3-Clause-Open-MPI; GPL-3.0-or-later WITH GCC-exception-3.1; LGPL-2.1-or-later, dynamically linked | each package's `dist-info/licenses/LICENSE.txt` |
| Arrow's bundled libraries | `pyarrow` 25.0.1 | its own and its bundled components' licences (not yet read) | `dist-info/licenses/LICENSE.txt` and `NOTICE.txt` |
| ONNX Runtime's bundled libraries | `onnxruntime` 1.30.0 | its own and its bundled components' licences (not yet read) | `onnxruntime/ThirdPartyNotices.txt`, **inside the package directory, not in `dist-info`** |

Each of these permits redistribution in binary form on condition that its notice travels with it.

### What xgboost's x86-64 wheel ships (inspected 2026-10-17)

- `libxgboost.so` is 405,981,153 bytes. Its `.nv_fatbin` section is 323,896,200 bytes of compiled
  CUDA device code. The CUDA runtime is linked statically: `libcudart_static` symbols are present,
  and no `libcudart` appears among the library's `NEEDED` entries.
- NCCL is not linked; it is loaded with `dlopen` at run time. That is why `ldd` showing no NVIDIA
  library proves nothing about whether NCCL is used. What keeps NCCL unloaded is training and
  serving on CPU only, which is this project's configuration (M4's NCCL entry in `licences.py`,
  2026-09-22).
- The `manylinux_2_28_x86_64` wheel declares `Requires-Dist: nvidia-nccl-cu12` on Linux x86-64.
  pip and uv choose it over the `manylinux2014_x86_64` wheel on any glibc 2.28 or later, which
  includes every current base image.
- The `manylinux2014_x86_64` wheel of the same release (4,904,451 bytes) has no `.nv_fatbin`
  section, links no CUDA runtime, and **does not** declare `nvidia-nccl-cu12`. It still bundles
  `libgomp`. Upstream treats it as a legacy CPU build; a maintained CPU-only route is the separate
  `xgboost-cpu` distribution, which M7 has not inspected.

### The decision that is M9's

Excluding only the `nvidia-*` wheels does not remove NVIDIA code from the image: the CUDA runtime
and device code are inside `libxgboost.so` itself. A CPU-only xgboost build (the `xgboost-cpu`
distribution, a `manylinux2014` pin, or a source build without CUDA) removes both, and with them
the NCCL dependency. Which route, if any, is M9's packaging decision. The CUDA EULA's terms for
redistributing a statically linked runtime have not been read by M7 and must be, if the CUDA wheel
stays.

**Acceptance criterion, needed before M9's image gate:** every distribution installed in an image
has its licence and notice files in that image, including the texts for components it bundles, and
an image-scan step fails the build when one is missing. The scan looks in each distribution's
`dist-info/licenses/` and in the package directories (`onnxruntime/ThirdPartyNotices.txt` is not in
`dist-info`), and it carries an explicit list of the texts that no wheel ships and must be supplied:
NVTX's licence, `libgomp`'s for xgboost, and, if the CUDA wheel stays, NVIDIA's terms for the CUDA
runtime. The rows above are examples the scan must pass, not the list. If NCCL and the CUDA code are
excluded by a CPU-only xgboost, the other rows still apply.

## Carried to M9 (owner decision 2026-10-18)

- **D-51** (from M7): WireMock stubs of Google's JWKS and token endpoints in the compose stack,
  and a Google sandbox contract suite reported as skipped without credentials (PB-113). M7's
  in-process `FakeGoogle` already backs the tests.

## Merging `security/scan-on-main` (owner, 2026-10-20)

That branch moved M9's `security` workflow, its pinned Trivy launcher and the Tomcat, Jackson and
lz4-java bump to `main` ahead of the rest of M9. It was merged on 2026-10-20, a fast-forward of
`main` to `810206b`. The owner asked for three consequences to be recorded for whoever merged it:

1. **Other branches get gated once rebased.** The workflow runs on a push to any branch that
   contains it, so every milestone branch rebased onto `main` afterwards is scanned on every push
   and fails on any HIGH or CRITICAL dependency finding that has a fix. For example,
   `m8/frontend-rebase` changes `frontend/pnpm-lock.yaml`, and its frontend dependencies are gated
   from its first push after a rebase.
2. **M9's own rebase conflicted on `.github/workflows/security.yml`** (2026-10-21). `m9/infra` had
   added the file in its own commits, and `main`'s copy differed: the configuration job was guarded
   behind a `trivy-configuration-target` job and renamed to "misconfiguration in Kubernetes
   manifests and Dockerfiles". The conflict stopped the rebase at the commit that adds the file
   (`ad6ccc2` before the rebase, `b630462` after). It was resolved as planned: that commit's
   version, without the target job (the manifests exist on M9), and with `main`'s job name, since
   the old name claimed compose files and workflows, which Trivy cannot scan
   (`docs/research/lab_notebook.md`, 2026-10-20). M9's two later changes to the file (now `a6c6b77`
   and `41b9132`) applied cleanly, and the bump commit dropped out as already applied.
3. **The nightly scan can turn `main` red on a new CVE**, with no code change, once the default
   branch moves off `m0/bootstrap` (GOV-9). Until then the `schedule` trigger does not register,
   and `main` is scanned only on push, on pull request and by manual dispatch.

## Backlog ids filed on `main` from M9's reservation (2026-10-21)

M9 reserved PB-171 to PB-190 on `m9/infra`, in its claims table and in `docs/backlog/product.md`'s
claims row; PB-171 to PB-179 are used there. The test-flake work on `main` uses three
more, claimed here before use: **PB-180** (`ApiKeyLifecycleTest:142`, splitting a key on its last
"_"), **PB-181** (`ApiKeyLifecycleTest:174`, tampering with a secret's last character) and
**PB-182** (wall-clock assertions in the auth tests). The owner directed (2026-10-21) that they go
to whoever owns those modules; that is M7, which built staff identity, admin and audit (the
backlog's header). PB-183 then went to M9's post-merge review (2026-10-21, on `m9/infra`); the
next free M9 id is PB-184. The rebase of `m9/infra` onto `main` (2026-10-21)
resolved the two branches' PB-171 to PB-190 rows in `docs/backlog/product.md` to one row naming both
sections, PB-171 to PB-182 used.
