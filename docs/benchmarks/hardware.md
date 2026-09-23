# Machines used for measurements

Every benchmark file in `docs/benchmarks/` states which machine produced it. Each machine that
produces measurements has a level-2 section whose heading starts with its lower-case ID
(`## dev-laptop-01 …`); traceability rows cite it as `machine: <id>` in `reduced_scale`, and
`fs-traceability check` rejects IDs not recorded here (ADR 0010). Requirements whose targets need
more capacity than the machine used are marked `VERIFIED_AT_REDUCED_SCALE` with the scale used
(build prompt A.3 rule 8).

Policy (ADR 0010): performance gate numbers are never taken from shared CI runners; the Docker
stack is verified in CI and in Codespaces, not on `dev-laptop-01`.

## dev-laptop-01 (recorded 2026-09-17)

| Property | Value | How obtained |
|---|---|---|
| CPU | Intel Core i5-6200U @ 2.30 GHz, 2 cores / 4 threads | `/proc/cpuinfo`, `nproc` |
| Memory | 7.8 GiB total; ~1.8 GiB available at the time of recording | `free -m` |
| Disk | 468 GiB, 181 GiB free | `df -h /home` |
| OS | Linux 7.0.0-31-generic | `uname -r` |
| Java | OpenJDK 21.0.12 | `java -version` |
| Python | CPython 3.12.14 (uv-managed) | `uv python list` |
| Node | 24.21.0, pnpm 12.4.2 | `node -v`, `pnpm --version` |
| Docker | Engine installed but **not used**: owner decision 2026-09-17, too little free memory while working (ADR 0010) | `systemctl status docker` |

Implications recorded now so they are not rediscovered later:

- The `core` compose profile sets memory limits totalling 3,968 MiB, more than the ~1.8 GiB free;
  the stack runs in CI and Codespaces instead (ADR 0010).
- A 5M-row dataset and gradient-boosting training are feasible but slow on two cores; runs on this
  machine are reduced-scale and reported with wall-clock time.
- Load tests at 10,000 TPS are not achievable here; they need the dedicated benchmark machine
  below, with the distributed Locust configuration for full scale.

## GitHub-hosted CI runners (not a measurement machine)

`ubuntu-24.04` runners run every Docker-dependent suite (`REQUIRE_DOCKER=1`) and the `stack` job
that evidences the M0 gate. They are shared, and their hardware varies between runs, so their
timings are never used as gate evidence (ADR 0010). There is deliberately no machine ID for them.

## Planned: dedicated benchmark machine (not yet provisioned)

Needed before M5, M6 and M10 benchmark rows can be closed beyond `VERIFIED_AT_REDUCED_SCALE`.
Candidate: a Codespaces machine or cloud instance with dedicated vCPUs. When provisioned, add a
section headed with its ID recording, from commands run on the machine at measurement time: CPU
model and core/thread count (`lscpu`), memory (`free -m`), disk type, OS and kernel (`uname -a`),
JDK (`java -version`), Python and key library versions, container runtime version, whether the
vCPUs are dedicated or shared, and the date. No numbers are recorded here in advance.

## Memory budget per compose profile

Sum of `deploy.resources.limits.memory`, enforced by `fs-compose-budget` (ADR 0010):

| Profile | Budget (MiB) | Current total (MiB) | Services |
|---|---|---|---|
| core | 4,096 | 4,096 | timescaledb 768, pii-vault 256, pii-vault-migrate 128 (one-shot, M6), redis 320, kafka 768, object-store 384, object-store-init 128, mlflow 1,024, mailpit 64, wiremock 256 |
| ml | 6,144 | 0 | scoring service and workers arrive in M5 |
| obs | 5,120 | 0 | Prometheus, Grafana, Loki, OpenTelemetry Collector arrive in M9 |
| full | 10,240 | 4,096 | all profiles together |

Budgets fit the 16 GB Codespaces machine with room for the IDE, Maven and test JVMs. The `core`
total exceeds the memory `dev-laptop-01` has free, which is why the stack does not run there.
