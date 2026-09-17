# infrastructure

| Path | Contents | Milestone |
|---|---|---|
| `docker/` | Compose support files: TimescaleDB init, WireMock stubs, `.env` generator | M0 |
| `k8s/` | Kustomize base and overlays, namespace `fraudshield`, NetworkPolicies, HPA, PDBs | M9 |
| `prometheus/`, `alertmanager/`, `grafana/` | Metrics, alert rules for SRS 8.2, dashboards as code | M9 |
| `argo-rollouts/` | Canary with automated analysis and rollback | M9 |

The compose stack is defined in the root `docker-compose.yml`.
