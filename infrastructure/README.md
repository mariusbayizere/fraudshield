# infrastructure

| Path | Contents | Milestone |
|---|---|---|
| `docker/` | Compose support files: TimescaleDB init, WireMock stubs, `.env` generator | M0 |
| `k8s/` | Kustomize base and overlays, namespace `fraudshield`, NetworkPolicies, HPA, PDBs ([README](k8s/README.md)) | M9 |
| `prometheus/`, `alertmanager/`, `grafana/`, `loki/` | Scrape config, alert rules for SRS 8.2 and D-10 with promtool tests, routing, dashboards as code, log alert rule | M9 |
| `argo-rollouts/` | Canary with automated analysis and rollback | M9 |
| `bin/` | Checksum-pinned launchers for every validator (never taken from PATH) | M9 |
| `checks/` | `validate.sh` (all checks, no Docker or cluster) and the policy, metric-catalogue and runbook checks | M9 |

The compose stack is defined in the root `docker-compose.yml`.

**Validate everything:** `infrastructure/checks/validate.sh` (add `--quick` to skip the
week-long alert tests, about 2.5 minutes). Needs `uv` and network access to download the pinned
tools and schemas once; no Docker, no cluster.
