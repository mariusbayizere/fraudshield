# Reference hardware for local measurements

Every benchmark file in `docs/benchmarks/` states which machine produced it. Results from the
machine below are **development-machine measurements**; requirements whose targets need more
capacity (10,000 TPS ingestion, training on 5M rows within practical time) are marked
`VERIFIED_AT_REDUCED_SCALE` with the scale used (build prompt A.3 rule 8).

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
| Docker | Engine installed; daemon not running during M0 (starting it requires administrator rights) | `systemctl status docker` |

Implications recorded now so they are not rediscovered later:

- The full `core` compose profile sets memory limits totalling 3,968 MiB (about 3.9 GiB); with ~1.8 GiB free
  the stack may need other applications closed.
- A 5M-row dataset and gradient-boosting training are feasible but slow on two cores; full
  runs will be reported with wall-clock time and reduced-scale runs used for iteration.
- Load tests at 10,000 TPS are not achievable on this machine; M10 will report the largest
  sustained rate measured and provide the distributed Locust configuration for full scale.
