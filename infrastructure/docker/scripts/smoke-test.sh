#!/usr/bin/env bash
# Functional smoke test for the `core` compose profile (M0 gate: "make up healthy").
# Healthchecks prove each process answers; this proves each service does its job:
# TimescaleDB extension available, PII vault reachable, Redis authenticated round-trip,
# Kafka topic create/describe/delete, S3 bucket present, MLflow run with an artifact stored
# through the S3 store, Mailpit and WireMock ready.
set -euo pipefail

compose() { docker compose --profile core "$@"; }
step() { printf '\n== %s\n' "$1"; }

step "service health"
unhealthy=$(compose ps --format '{{.Service}} {{.Health}}' | awk '$1 != "object-store-init" && $2 != "healthy"')
compose ps --format 'table {{.Service}}\t{{.Image}}\t{{.Status}}'
if [[ -n "$unhealthy" ]]; then
  echo "not healthy: $unhealthy" >&2
  exit 1
fi
init_exit=$(docker inspect --format '{{.State.ExitCode}}' "$(compose ps -a -q object-store-init)")
[[ "$init_exit" == "0" ]] || { echo "object-store-init exited $init_exit" >&2; exit 1; }
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
  echo "redis accepted an unauthenticated read" >&2
  exit 1
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
compose exec -T object-store sh -c 'echo "fs.ls -l /buckets/mlflow-artifacts" | weed shell -master=127.0.0.1:9333' | head -5

step "mailpit and wiremock ready"
compose exec -T mailpit /mailpit readyz && echo "mailpit ready"
compose exec -T wiremock curl -fsS http://127.0.0.1:8080/__admin/health && echo

step "all smoke checks passed"
