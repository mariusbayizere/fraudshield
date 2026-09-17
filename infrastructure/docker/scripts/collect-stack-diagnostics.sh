#!/usr/bin/env bash
# Snapshot of the core stack's resources and logs into a directory (default build/stack-diagnostics):
# Docker daemon memory and CPUs, disk usage, per-container usage, OOM kills and restarts, and the
# recent logs of MLflow, the object store and TimescaleDB. Written after every smoke test, pass or
# fail, so the stack job and the devcontainer job can be compared (MLflow HTTP 500 investigation).
# Every value from .env is replaced by *** so no credential reaches an artifact or annotation.
set -uo pipefail

root=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
out=${1:-$root/build/stack-diagnostics}
mkdir -p "$out"
compose() { docker compose --profile core "$@"; }

{
  echo "## docker info"
  docker info --format 'MemTotal={{.MemTotal}} NCPU={{.NCPU}} Driver={{.Driver}} DockerRootDir={{.DockerRootDir}} CgroupVersion={{.CgroupVersion}}'
  echo "## host memory (free -m)"
  free -m 2>/dev/null || cat /proc/meminfo | head -5
  echo "## disk (df -h)"
  df -h / /var/lib/docker 2>/dev/null || df -h /
  echo "## docker system df"
  docker system df
  echo "## docker stats --no-stream"
  docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}'
  echo "## containers: state, OOM kills, restarts, memory limit"
  for container in $(compose ps -a -q); do
    docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}} status={{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} restarts={{.RestartCount}} limit={{.HostConfig.Memory}}' "$container"
  done
  echo "## cgroup memory events per container (oom_kill > 0: the kernel killed a process inside)"
  for container in $(compose ps -q); do
    service=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$container")
    events=$(docker exec "$container" cat /sys/fs/cgroup/memory.events 2>/dev/null | tr '\n' ' ')
    stat=$(docker exec "$container" sh -c "grep -E '^(anon|file|shmem) ' /sys/fs/cgroup/memory.stat" 2>/dev/null | tr '\n' ' ')
    peak=$(docker exec "$container" cat /sys/fs/cgroup/memory.peak 2>/dev/null)
    echo "$service: ${events:-n/a} | ${stat:-n/a} | peak=${peak:-n/a}"
  done
  echo "## mlflow processes (pid, VmRSS kB, command)"
  mlflow_container=$(compose ps -q mlflow)
  if [[ -n "$mlflow_container" ]]; then
    docker exec "$mlflow_container" sh -c 'for p in /proc/[0-9]*; do
      rss=$(awk "/^VmRSS/ {print \$2}" "$p/status" 2>/dev/null)
      [ -n "$rss" ] && printf "%s %s %s\n" "${p#/proc/}" "$rss" "$(tr "\0" " " < "$p/cmdline")"
    done' 2>&1 | sed -E 's#://[^@ ]*@#://***@#g' | cut -c1-200
  fi
} > "$out/resources.txt" 2>&1

for service in mlflow object-store timescaledb; do
  compose logs --no-color --tail 300 "$service" > "$out/logs-$service.txt" 2>&1
done

# Redact every .env value in pure bash (the devcontainer has no python3). Fail closed: without a
# readable .env the logs are dropped rather than written unredacted.
if [[ -r "$root/.env" ]]; then
  mapfile -t values < <(grep -E '^[A-Z0-9_]+=.{8,}$' "$root/.env" | cut -d= -f2- | awk '{ print length, $0 }' | sort -rn | cut -d' ' -f2-)
  for path in "$out"/*.txt; do
    text=$(<"$path")
    for value in "${values[@]}"; do
      text=${text//"$value"/***}
    done
    printf '%s\n' "$text" > "$path"
  done
else
  rm -f "$out"/logs-*.txt
  echo "no readable .env: service logs not collected" >> "$out/resources.txt"
fi
echo "stack diagnostics written to ${out#$root/}"
