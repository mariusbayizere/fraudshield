# The benchmark machine M10 needs

**Nothing has been measured, and this document asserts no performance figure.** It states the
capacity to rent so that the campaign in `m10_plan.md` can run, and the properties that make a
measurement admissible under ADR 0010. Whether the capacity below is *enough* is itself one of
M10's findings: the sizing rests on ADR 0032's estimate from the development laptop, and ADR 0032
says in terms that the answer is the measurement, not the estimate.

## Shape: two rented machines, not one

| Role | Why it is separate |
|---|---|
| **System under test** — the cluster running ingest, decision, scorer, workers, console, Kafka, PostgreSQL/TimescaleDB, Redis and the observability stack | Everything measured runs here, and nothing else does |
| **Load and journey host** — Locust master and workers, the Playwright browsers | A load generator that competes with the system for cores measures the generator. The development laptop already showed what a busy host does to a percentile, and that is the confound the campaign exists to remove |

A single machine can host both only for the exploratory step; no row of the plan may be closed
from a run where the generator shared the system's cores.

## System under test

| Resource | Specification | Reason |
|---|---|---|
| vCPU | **32 dedicated cores**, one NUMA node if possible; **not** a burstable or credit-limited instance family | ADR 0032 derives the scorer's core requirement from the laptop's per-core service time and recommends planning in this range, then measuring. Burstable cores make percentiles a property of the credit balance |
| CPU generation | A current server generation (for example Ice Lake or later on Intel, Zen 3 or later on AMD), with AVX-512 or its AMD equivalent available | The scorer runs ONNX Runtime; the instruction set is part of what the measurement will describe, so it must be recorded rather than assumed |
| Memory | **64 GiB** | The compose profiles budget the local stack at a fraction of this, but the campaign runs multiple replicas of the API and scorer plus Kafka, PostgreSQL, Redis and Prometheus at once, with headroom so that nothing is measured while swapping |
| Disk (data) | **500 GiB NVMe SSD**, local or network-attached with dedicated IOPS | Kafka retention across repeated runs, TimescaleDB, Prometheus series, and the evidence files |
| Disk (spool) | A volume supporting **ReadWriteMany** access, sized well above the M9 manifests' request, on storage whose fsync latency is known | ADR 0090 puts the API's spool on a shared volume and makes its append latency an M10 measurement. Cloud file shares vary enormously here, and the storage class is part of the result: record it |
| Network | **10 Gbps**, and the load host in the same zone as the cluster | Request bodies are small, but Kafka replicates every message and the measurement must not be shaped by a shared uplink |
| Clock | NTP or chrony synchronised, drift recorded | End-to-end latency is measured across components; unsynchronised clocks produce percentiles that cannot be trusted or reproduced |
| Kubernetes | A real cluster, at least three nodes if the budget allows, so replicas, pod disruption budgets and the canary analysis behave as deployed | Chaos cases 04 and 06 delete pods and promote replicas; a single-node cluster cannot express them |
| Container runtime | Recorded version; cgroup v2 | ADR 0010 requires the runtime version in the machine record |

If three nodes are out of budget, one node with the capacity above still runs every load row and
chaos cases 01, 02, 03 and 05; cases 04 and 06 then wait for a multi-node cluster and are recorded
as not run rather than approximated.

## Load and journey host

| Resource | Specification | Reason |
|---|---|---|
| vCPU | **8 dedicated cores** | Locust workers are one process per core; the campaign plan sets the worker count from the target rate and the per-user rate |
| Memory | **16 GiB** | Worker processes, plus three browser engines for the journeys |
| Disk | 100 GiB SSD | Traces, videos and reports from failed journeys |
| Network | 10 Gbps, same zone as the cluster | The generator must not be the bottleneck, and cross-zone latency would be measured as system latency |
| Software | Python for Locust, and the browsers Playwright installs for Chromium, Firefox and WebKit | |

## What must be recorded before the first measurement

ADR 0010 requires a machine section in `docs/benchmarks/hardware.md`, written from commands run on
the machine itself, before any number is taken. It must carry: CPU model and core and thread count
(`lscpu`), memory (`free -m`), disk type and storage class, OS and kernel (`uname -a`), JDK
(`java -version`), Python and key library versions, container runtime version, Kubernetes version,
**whether the vCPUs are dedicated or shared**, and the date. The file states that no numbers are
recorded there in advance, and that holds.

## Rental notes for the owner

* **Dedicated, not shared.** Most of the cheap instance families are burstable. The distinction is
  the single most important property on this page, and it is the one the machine record is
  required to state.
* **The spool volume is the awkward requirement.** ReadWriteMany with predictable fsync latency is
  not the default on any cloud; if the available option is a managed file share, expect its
  latency to be part of what ADR 0090's measurement reports, and keep the ADR's named fallback in
  view.
* **Hourly rental is enough for most rows.** The exception is the shadow-mode window, which runs
  for its full specified duration and needs the deployment up for all of it; plan for that row to
  dominate the rental period.
* **Two smaller machines beat one large one** if the choice arises: the separation of load
  generation from the system under test is what makes the numbers admissible at all.
