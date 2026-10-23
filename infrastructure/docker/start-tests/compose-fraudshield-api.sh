#!/usr/bin/env bash
# Start test of the API in the Compose stack (M9; owner, 2026-10-23: the API must be shown to
# serve requests, not merely to start). What it checks is listed below; that it passes is
# evidence only from a recorded CI run of the stack job, cited in docs/reviews/M9. Run after `make up` and `make seed-demo`, from the
# repository root; CI's stack job runs it. Usage: compose-fraudshield-api.sh
#
# It starts the API with `docker compose --profile full up --build --wait`, which builds the image and
# waits for every dependency: the main database's migrations (fraudshield-migrate), the vault's
# (pii-vault-migrate) and the Kafka topics (kafka-topics-init) must complete, and the API's own
# healthcheck (actuator health: database, Redis, mail) must pass. Then, over the published port:
#
#   1. GET /api/v1/health answers {"status":"UP"};
#   2. GET /api/v1/environment answers exactly {"synthetic_data":true} (dev profile, D-21);
#   3. POST /api/v1/transactions/ingest without an API key is refused with 401;
#   4. the same POST with the demo API key (`make seed-demo`, hashed with the API's pepper from
#      .env) is decided: 200, the transaction's id echoed, and ml_unavailable_fallback true,
#      because this stack has no scorer yet (PB-179) and the rule fallback decides (C.4);
#   5. the identical request again is an idempotent replay (Idempotent-Replayed: true), which
#      the API answers from its idempotency record rather than by deciding again;
#   6. GET /api/v1/decisions/{id} with the same key answers 200 for that transaction;
#   7. the accepted transaction reached Kafka: fs.transactions.raw holds more records than it did
#      before step 4 (waiting up to 30 s for the API to publish).
#
# Steps 4 to 7 need PostgreSQL (API-key lookup under the tenant), Redis, Kafka and the rule
# engine working together in the built image. The API key is read from .demo-credentials and
# never printed. On failure the API's log tail is printed.
set -Eeuo pipefail

root=$(git rev-parse --show-toplevel)
cd "$root"
base=http://127.0.0.1:8080/api/v1
compose=(docker compose --profile full)

fail() {
  echo "compose start test failed: $1" >&2
  [[ -z "${GITHUB_ACTIONS:-}" ]] || echo "::error title=compose start test (fraudshield-api)::$1"
  echo "--- fraudshield-api log (last 150 lines) ---" >&2
  "${compose[@]}" logs --no-color --tail 150 fraudshield-api >&2 || true
  exit 1
}
trap 'fail "line $LINENO: $BASH_COMMAND"' ERR

[[ -f .demo-credentials ]] || fail ".demo-credentials is missing; run make seed-demo first"
api_key=$(grep '^FRAUDSHIELD_DEMOSEED_APIKEY=' .demo-credentials | cut -d= -f2-)
[[ -n "$api_key" ]] || fail "no demo API key in .demo-credentials"

started=$(date +%s)
"${compose[@]}" up -d --build --wait --wait-timeout 900 fraudshield-api \
  || fail "the API did not become healthy"
echo "fraudshield-api healthy after $(($(date +%s) - started)) s (build included)"
for job in fraudshield-migrate pii-vault-migrate kafka-topics-init; do
  code=$("${compose[@]}" ps -a --format '{{.ExitCode}}' "$job")
  [[ "$code" == "0" ]] || fail "$job exited with '$code'"
done
"${compose[@]}" logs --no-color kafka-topics-init | grep -q 'kafka topics ready' \
  || fail "kafka-topics-init did not report its topics"
echo "one-shots completed: fraudshield-migrate, pii-vault-migrate, kafka-topics-init"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# request <name> <method> <path> [curl args...]: body to $work/<name>.body, headers to .headers,
# status printed.
request() {
  local name=$1 method=$2 path=$3
  shift 3
  # A connection failure prints 000 rather than failing inside $(...), where fail() would run
  # in a subshell and lose its annotation.
  curl -sS -o "$work/$name.body" -D "$work/$name.headers" -w '%{http_code}' \
    -X "$method" "$base$path" "$@" || true
}

raw_records() {
  "${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx64m kafka \
    /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:29092 \
    --topic fs.transactions.raw --time -1 | awk -F: '{sum += $3} END {print sum + 0}'
}

status=$(request health GET /health)
[[ "$status" == "200" && "$(cat "$work/health.body")" == '{"status":"UP"}' ]] \
  || fail "GET /health answered $status: $(head -c 300 "$work/health.body")"
echo "1. GET /health: 200 {\"status\":\"UP\"}"

status=$(request environment GET /environment)
[[ "$status" == "200" && "$(cat "$work/environment.body")" == '{"synthetic_data":true}' ]] \
  || fail "GET /environment answered $status: $(head -c 300 "$work/environment.body")"
echo "2. GET /environment: 200 {\"synthetic_data\":true}"

transaction=$(python3 -c 'import uuid; print(uuid.uuid4())')
now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
cat >"$work/transaction.json" <<JSON
{"transaction_id":"$transaction","account_id":"tok_A1b2C3d4E5f6G7h8J9k0L1m2",
 "counterparty_id":"tok_Z9y8X7w6V5u4T3s2R1q0P9o8","amount":"15000","currency":"RWF",
 "channel":"MOBILE_MONEY","merchant_category_code":"4829","latitude":-1.9441,"longitude":30.0619,
 "device_fingerprint":"tok_D3v1c3F1ng3rpr1ntAaBbCc0","counterparty_country":"RW",
 "transaction_timestamp":"$now"}
JSON

before=$(raw_records)

status=$(request anonymous POST /transactions/ingest \
  -H 'Content-Type: application/json' --data-binary @"$work/transaction.json")
[[ "$status" == "401" ]] || fail "an ingest without an API key answered $status, not 401"
echo "3. POST /transactions/ingest without a key: 401"

status=$(request first POST /transactions/ingest -H "X-API-Key: $api_key" \
  -H 'Content-Type: application/json' --data-binary @"$work/transaction.json")
[[ "$status" == "200" ]] \
  || fail "an ingest with the demo key answered $status: $(head -c 500 "$work/first.body")"
python3 - "$work/first.body" "$transaction" <<'PY' || fail "unexpected decision body"
import json, sys
body = json.load(open(sys.argv[1], encoding="utf-8"))
assert body["transaction_id"] == sys.argv[2], body
assert body["decision"] in {"APPROVE", "HOLD", "DECLINE"}, body
assert body["ml_unavailable_fallback"] is True, body
print(f"4. POST /transactions/ingest with the demo key: 200, decision {body['decision']},"
      f" risk tier {body['risk_tier']}, rule fallback (no scorer)")
PY

status=$(request replay POST /transactions/ingest -H "X-API-Key: $api_key" \
  -H 'Content-Type: application/json' --data-binary @"$work/transaction.json")
[[ "$status" == "200" ]] || fail "the replayed ingest answered $status"
grep -qi '^Idempotent-Replayed: true' "$work/replay.headers" \
  || fail "the replayed ingest carried no Idempotent-Replayed header"
cmp -s "$work/first.body" "$work/replay.body" || fail "the replay's body differs from the first"
echo "5. identical POST again: 200, Idempotent-Replayed: true, same body"

for _ in $(seq 1 30); do
  status=$(request decision GET "/decisions/$transaction" -H "X-API-Key: $api_key")
  [[ "$status" == "404" ]] || break
  sleep 1
done
[[ "$status" == "200" ]] \
  || fail "GET /decisions/{id} answered $status: $(head -c 500 "$work/decision.body")"
echo "6. GET /decisions/{id} with the demo key: 200"

for _ in $(seq 1 30); do
  records=$(raw_records)
  ((records > before)) && break
  sleep 1
done
((records > before)) || fail "fs.transactions.raw still holds $records record(s), as before"
echo "7. fs.transactions.raw: $before record(s) before, $records after"

echo "--- fraudshield-api memory (limit 1 GiB, ASSUMED) ---"
docker stats --no-stream --format '{{.Name}} {{.MemUsage}} {{.MemPerc}}' \
  "$("${compose[@]}" ps -q fraudshield-api)"
echo "compose start test passed"
