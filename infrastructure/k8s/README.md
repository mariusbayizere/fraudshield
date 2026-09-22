# Kubernetes manifests

Kustomize base and overlays for one FraudShield deployment per namespace (build prompt D.3 M9,
C.1). The infrastructure is cloud-agnostic Kubernetes; production data residency is a deployment
prerequisite the code cannot satisfy (D-21).

| Path | Contents |
|---|---|
| `base/` | Namespace (Pod Security `restricted`), NetworkPolicies, edge, API, scorer, ML worker, front end; pulls in `../../prometheus`, `../../alertmanager`, `../../grafana` |
| `overlays/staging/` | Namespace `fraudshield-staging`, rolling updates, smaller footprint (SRS 8.1 Staging Deploy) |
| `overlays/production/` | Namespace `fraudshield`, API released by the Argo Rollouts canary in `../../argo-rollouts` (SRS 8.1 Production Deploy) |

**Validate** (no cluster or Docker needed): `infrastructure/checks/validate.sh`. For each overlay
it renders with the pinned kustomize, validates against Kubernetes 1.34 and Argo Rollouts CRD
schemas pinned by commit (kubeconform, strict), and runs `infrastructure/checks/k8s_policy.py`
(Pod Security `restricted` offline, digest-pinned third-party images, probes, resources, and a
NetworkPolicy, PodDisruptionBudget and, for serving components, an HPA for every workload).

## Cluster prerequisites

- Kubernetes **1.34 or later**, a CNI that enforces NetworkPolicy, a default StorageClass
  (Prometheus 50 Gi, Alertmanager 1 Gi), and a LoadBalancer for the edge Service.
- A **ReadWriteMany** StorageClass for the API spool claim `fraudshield-api-spool` (20 Gi), set as
  its `storageClassName` at deployment. ADR 0090 makes its fsync latency an acceptance condition,
  measured in M10; ephemeral storage for the spool is not allowed.
- **Argo Rollouts** controller in namespace `argo-rollouts` (production), with the notification
  service `pagerdutyv2` configured so an aborted canary pages (SRS 8.1).
- Secrets created per environment, never committed: `fraudshield-api-secrets`,
  `fraudshield-ml-secrets`, `fraudshield-ml-worker-secrets`, `fraudshield-edge-tls`
  (`kubernetes.io/tls`), `fraudshield-alertmanager-pagerduty` (keys `sre`, `risk`, `ml`),
  `fraudshield-grafana-admin` (key `password`).
- Data stores are not in these manifests yet: PostgreSQL + TimescaleDB (CloudNativePG, D-49), the
  PII vault, Redis, Kafka, MLflow and its object store. Their pods must carry
  `fraudshield.io/datastore: postgresql | pii-vault | redis | kafka | mlflow | object-store`,
  which the client NetworkPolicies already allow; their own policies ship with them.
- Loki and a log collector are not deployed yet; Grafana's Loki datasource and the Loki rule in
  `infrastructure/loki/rules/` assume a service `fraudshield-loki:3100`.

## Deployment interface

What each FraudShield image must provide for these manifests to work. Owners confirm or change
these through `docs/parallel/M9_updates.md`.

| Workload | Image | Ports | Probes | Other |
|---|---|---|---|---|
| `fraudshield-api` | `ghcr.io/mariusbayizere/fraudshield-api` | `http` 8080, `management` 8081 (health and `/actuator/prometheus`) | startup and liveness `GET :8081/actuator/health`; readiness `GET :8080/api/v1/health` (ADR 0014) | UID 10001, read-only root; writable `/tmp`; spool on the shared volume at `$FRAUDSHIELD_SPOOL_DIR/$FRAUDSHIELD_SPOOL_INSTANCE` (pod name) with replay of dead pods' directories (ADR 0090, contract in `docs/parallel/M9_updates.md`); `SPRING_PROFILES_ACTIVE` from the overlay |
| `fraudshield-ml` | `ghcr.io/mariusbayizere/fraudshield-ml` | `grpc` 50051 (mTLS), `admin` 8000 (`/metrics`) | `GET :8000/health/live`, `GET :8000/health/ready` (kubelet gRPC probes cannot present a client certificate) | UID 10001, read-only root, writable `/tmp` |
| `fraudshield-ml-worker` | `ghcr.io/mariusbayizere/fraudshield-ml-worker` | `metrics` 8000 (`/metrics`) | `GET :8000/health/live`, `GET :8000/health/ready` | UID 10001, read-only root, writable `/tmp` |
| `fraudshield-frontend` | `ghcr.io/mariusbayizere/fraudshield-frontend` | `http` 8080 | `GET :8080/` | UID 10001, read-only root, writable `/tmp` |
| `fraudshield-edge` | `ghcr.io/mariusbayizere/fraudshield-edge` | `https` 8443 | `GET https://:8443/healthz` | UID 10001, read-only root; certificate at `/etc/fraudshield/tls`; writable `/var/cache/nginx`, `/tmp` |

FraudShield images are untagged in the manifests. The deploy step pins each by digest
(`kustomize edit set image ghcr.io/mariusbayizere/fraudshield-api@sha256:…`) after the image is
built, signed and scanned; the policy check fails on any tag, so `:latest` cannot be deployed.

## Canary (production)

`argo-rollouts/rollout.yaml` takes the API pod template from the Deployment (`workloadRef`) and
holds the Deployment at 0 replicas. Steps: 10% for 30 minutes, then 100%. Background analysis
(`argo-rollouts/analysis-template.yaml`) runs every minute on the canary pods only and aborts on
5xx error rate > 0.5% or p99 decision latency > 80 ms; both fail closed when the canary has no
traffic or no metrics. The split is by replica count, so production keeps at least 10 API pods.
`infrastructure/checks/tests/test_canary_analysis.py` runs the template's own queries through
promtool.
