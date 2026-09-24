#!/usr/bin/env bash
# Functional smoke test for the `core` compose profile (M0 gate: "make up healthy").
# Healthchecks prove each process answers; this proves each service does its job:
# TimescaleDB extension available, PII vault reachable, Redis authenticated round-trip,
# Kafka topic create/describe/delete, S3 bucket present, MLflow run with an artifact stored
# through the S3 store, Mailpit and WireMock ready.
set -Eeuo pipefail

compose() { docker compose --profile core "$@"; }
current_step="start"
step() {
  current_step="$1"
  printf '\n== %s\n' "$1"
}
# Every failure path goes through fail(), which in GitHub Actions also emits an error
# annotation, readable through the public API without log access.
fail() {
  "$(dirname "$0")/collect-stack-diagnostics.sh" >&2 || true
  echo "smoke test failed in step '${current_step}': $1" >&2
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::error title=smoke test failed::step '${current_step}': $1"
  fi
  exit 1
}
trap 'fail "line $LINENO, exit $?: $BASH_COMMAND"' ERR

step "service health"
compose ps -a --format 'table {{.Service}}\t{{.Image}}\t{{.Status}}'
for service in timescaledb pii-vault redis kafka object-store mlflow mailpit wiremock; do
  container=$(compose ps -q "$service")
  [[ -n "$container" ]] || fail "$service has no running container"
  health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container")
  [[ "$health" == "healthy" ]] || fail "$service health is '$health'"
  echo "$service: $health"
done
init_container=$(compose ps -a -q object-store-init)
[[ -n "$init_container" ]] || fail "object-store-init container not found"
init_exit=$(docker inspect --format '{{.State.ExitCode}}' "$init_container")
[[ "$init_exit" == "0" ]] || fail "object-store-init exited $init_exit"
echo "object-store-init exited 0"

step "timescaledb: extension preloaded and installable; mlflow database exists"
compose exec -T timescaledb psql -U postgres -d fraudshield_db -tAc \
  "select current_setting('shared_preload_libraries'), default_version from pg_available_extensions where name = 'timescaledb'"
compose exec -T timescaledb psql -U postgres -d mlflow -tAc "select current_database()"

step "pii-vault: separate instance"
compose exec -T pii-vault psql -U postgres -d fraudshield_pii -tAc "select current_database(), version()"

step "redis: authenticated write/read, unauthenticated access refused"
compose exec -T redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli set fs:smoke ok EX 30 && REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli get fs:smoke'
if compose exec -T redis redis-cli get fs:smoke 2>&1 | grep -q '^ok$'; then
  fail "redis accepted an unauthenticated read"
fi
echo "unauthenticated read refused"

step "kafka: create, describe and delete a topic"
kafka_cli() { compose exec -T -e KAFKA_HEAP_OPTS=-Xmx128m kafka "/opt/kafka/bin/$1" --bootstrap-server kafka:29092 "${@:2}"; }
kafka_cli kafka-topics.sh --create --if-not-exists --topic fs.smoke --partitions 1 --replication-factor 1
kafka_cli kafka-topics.sh --describe --topic fs.smoke
kafka_cli kafka-topics.sh --delete --topic fs.smoke

step "object store: mlflow-artifacts bucket exists"
compose exec -T object-store sh -c 'echo "s3.bucket.list" | weed shell -master=127.0.0.1:9333' | grep mlflow-artifacts

step "mlflow: log a run with an artifact through the S3 store and read it back"
compose exec -T mlflow python - <<'PY'
import mlflow
from mlflow.tracking import MlflowClient

mlflow.set_tracking_uri("http://127.0.0.1:5000")
mlflow.set_experiment("m0-smoke")
with mlflow.start_run() as run:
    mlflow.log_param("purpose", "m0-gate")
    mlflow.log_text("smoke", "smoke.txt")
client = MlflowClient()
artifacts = [a.path for a in client.list_artifacts(run.info.run_id)]
assert artifacts == ["smoke.txt"], artifacts
path = client.download_artifacts(run.info.run_id, "smoke.txt", "/tmp")
assert open(path).read() == "smoke"
print("artifact round-trip ok; artifact_uri =", run.info.artifact_uri)
PY
# The artifact must exist as an object in the S3 bucket, not only through the MLflow API.
# Captured first, then searched: `tee | grep -q` can fail spuriously on SIGPIPE under pipefail.
bucket_tree=$(compose exec -T object-store sh -c \
  'echo "fs.tree /buckets/mlflow-artifacts" | weed shell -master=127.0.0.1:9333')
printf '%s\n' "$bucket_tree"
[[ "$bucket_tree" == *smoke.txt* ]] || fail "smoke.txt not found in the mlflow-artifacts bucket"
echo "artifact present in the object store"

step "memory: no process killed at a container limit; mlflow within budget"
# A process killed by the cgroup OOM killer leaves the container running (no OOMKilled flag, no
# restart), so read the kernel's own counters. MLflow previously ran at its limit and failed uploads
# with HTTP 500 (job execution processes; see docker-compose.yml).
for service in timescaledb pii-vault redis kafka object-store mlflow mailpit wiremock; do
  container=$(compose ps -q "$service")
  kills=$(docker exec "$container" cat /sys/fs/cgroup/memory.events | awk '/^oom_kill / {print $2}')
  [[ "$kills" == "0" ]] || fail "$service: the kernel killed $kills process(es) at the memory limit"
done
mlflow_container=$(compose ps -q mlflow)
anon=$(docker exec "$mlflow_container" cat /sys/fs/cgroup/memory.stat | awk '/^anon / {print $2}')
limit=$(docker inspect --format '{{.HostConfig.Memory}}' "$mlflow_container")
echo "mlflow anonymous memory: $((anon / 1048576)) MiB of $((limit / 1048576)) MiB"
(( anon * 100 < limit * 60 )) || fail "mlflow uses $((anon * 100 / limit))% of its memory limit (budget 60%)"
echo "no OOM kills; mlflow within 60% of its limit"

step "mailpit and wiremock ready"
compose exec -T mailpit /mailpit readyz && echo "mailpit ready"
compose exec -T wiremock curl -fsS http://127.0.0.1:8080/__admin/health && echo

"$(dirname "$0")/collect-stack-diagnostics.sh"
step "all smoke checks passed"
