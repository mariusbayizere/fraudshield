#!/usr/bin/env bash
# M10 plan step 2, ingest leg: prove the load harness runs, and that it can fail.
#
# Run from the repository root after `make up`, `make seed-demo` and the API's start in the `full`
# profile (the m10-harness workflow does all three). Each case runs Locust headless at a low rate
# and asserts the exact exit status the harness assigns to the outcome, plus what reached the API:
#
#   case                         host                          expected
#   pass                         the API                       0; requests > 0, no failures,
#                                                              Kafka grew by what was accepted,
#                                                              >= 60 accepted, every pack
#                                                              currency among them
#   wrong-key                    the API                       3 (preflight); nothing sent
#   synthetic-false              proxy: environment false      3; nothing forwarded, Kafka unchanged
#   environment-without-flag     proxy: environment {}         3; nothing forwarded
#   environment-failing          proxy: environment HTTP 503   3; nothing forwarded
#   unreachable                  a closed port                 3; nothing sent
#   key-revoked-in-run           proxy: 401 after N ingest-    4; stops well before its run time
#                                path requests (the preflight
#                                key check is the first)
#   proxy-control                proxy, no fault               0; so the refusals above are the
#                                                              injected fault's, not the proxy's
#   mutant-shape-stops           the API, harness with the     5 (nothing sent); the defect that
#                                old shape defect restored     once made such runs exit 0
#   mutant-pacing-raises         the API, harness with the     6 (a task raised)
#                                old pacing defect restored
#
# The two mutants are copies of the harness under $out with one line changed back to the defect
# step 2 found. If the substitution does not apply, the script stops there and the job fails (the
# later cases do not run), so a later edit of that line cannot silently turn the mutant into the
# working harness.
#
# The proxy (fault_proxy.py) forwards everything else to the real API, so a harness that ignored a
# fault would send real load and the Kafka count would show it. No number printed here is a
# measurement: this is a shared runner (ADR 0010) and the rate is deliberately trivial.
# The verdict conditions are single-quoted on purpose: verdict() evaluates them after the case ran.
# shellcheck disable=SC2016
set -Eeuo pipefail

root=$(git rev-parse --show-toplevel)
cd "$root"
api=http://127.0.0.1:8080
out=${STEP2_OUT:-build/m10-step2}
mkdir -p "$out"
compose=(docker compose --profile full)
locust=(uv run --no-project --with "locust==${LOCUST_VERSION:?set LOCUST_VERSION}" locust
  --headless --tags sustained --only-summary)
harness=tests/performance

key=$(grep '^FRAUDSHIELD_DEMOSEED_APIKEY=' .demo-credentials | cut -d= -f2-)
[[ -n "$key" ]] || { echo "no demo API key in .demo-credentials" >&2; exit 1; }
# A well-formed key with its last character changed: the same shape, not the same key.
last=${key: -1}
if [[ "$last" == "A" ]]; then swap=B; else swap=A; fi
wrong_key="${key%?}$swap"

failures=0
summary="$out/summary.md"
printf '| case | exit | expected | seconds | locust requests / failures | reached the API | verdict |\n|---|---|---|---|---|---|---|\n' >"$summary"

# raw_records: the topic's record count, summed over its partitions. The per-partition end
# offsets of the last read are kept in $out/.offsets.last, so a case's records can be read back.
raw_records() {
  "${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx64m kafka \
    /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:29092 \
    --topic fs.transactions.raw --time -1 >"$out/.offsets.last"
  awk -F: '{sum += $3} END {print sum + 0}' "$out/.offsets.last"
}

# case_records <before> <after> <file>: every record written between two offset snapshots
# (topic:partition:offset lines), as partition<TAB>offset<TAB>key<TAB>value. The key is the
# transaction_id (contracts/kafka/topics.yaml). Prints one line per partition whose read came up
# short or could not be made, so a partial read is reported as such, not as missing records; the
# consumer's stderr goes to <file>.err. The consumer reads a single partition in offset order, so
# a record's offset is its start plus its position.
case_records() {
  local partition start end got
  : >"$3"
  : >"$3.err"
  # Driven by the after snapshot, so a missing or empty before snapshot shows as unreadable.
  while IFS=: read -r _ partition end; do
    start=$(awk -F: -v p="$partition" '$2 == p {print $3}' "$1")
    if ! [[ "$partition" =~ ^[0-9]+$ && "$start" =~ ^[0-9]+$ && "$end" =~ ^[0-9]+$ ]]; then
      echo "offsets unreadable for partition '$partition'"
      continue
    fi
    (( end > start )) || continue
    # </dev/null: compose exec copies its stdin into the container, and would otherwise consume
    # the rest of the offsets file this loop reads. --formatter-property, not --property: Kafka
    # 4.3 prints the latter's deprecation warning on stdout, ahead of the records.
    "${compose[@]}" exec -T -e KAFKA_HEAP_OPTS=-Xmx64m kafka \
      /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 \
      --topic fs.transactions.raw --partition "$partition" --offset "$start" \
      --max-messages $((end - start)) --timeout-ms 30000 \
      --formatter-property print.key=true --formatter-property key.separator=$'\t' \
      </dev/null 2>>"$3.err" |
      awk -v p="$partition" -v o="$start" '{print p "\t" o + NR - 1 "\t" $0}' >"$3.part" ||
      echo "partition $partition: the consumer failed"
    got=$(wc -l <"$3.part")
    cat "$3.part" >>"$3"
    (( got == end - start )) || echo "partition $partition: read $got of $((end - start))"
  done <"$2"
  [[ -s "$2" ]] || echo "no offsets after the case"
  rm -f "$3.part"
}

# reconcile <case>: names each of the case's raw records by transaction_id against the ids the
# harness wrote (PB-198), after any shortfall in reading them. Reports only; the verdict does not
# depend on it yet.
reconcile() {
  local name=$1 shortfall
  shortfall=$(case_records "$out/$name.offsets.before" "$out/$name.offsets.after" \
    "$out/$name.records.tsv" | paste -sd ';' -)
  [[ -z "$shortfall" ]] || printf 'READ INCOMPLETE (%s); ' "$shortfall"
  python3 tests/performance/step2/reconcile.py "$out/$name.ids.json" \
    "$out/$name.records.tsv" "$out/$name.reconcile.json" ||
    echo "records: reconciliation did not complete (see the job log)"
}

# settled_records: the topic's count once two reads 3 s apart agree (the API publishes
# asynchronously), giving up after 30 s and returning the last read.
settled_records() {
  local previous current
  previous=$(raw_records)
  for _ in $(seq 10); do
    sleep 3
    current=$(raw_records)
    [[ "$current" == "$previous" ]] && break
    previous=$current
  done
  echo "$current"
}

# mutant <name> <line as fixed> <line as it was>: a copy of the harness with one line restored to
# its defect, printed as its directory; fails unless exactly one line changed.
mutant() {
  local dir="$out/mutants/$1"
  rm -rf "$dir"
  mkdir -p "$dir"
  cp "$harness"/*.py "$dir/"
  python3 - "$dir/locustfile.py" "$2" "$3" <<'PY'
import sys
from pathlib import Path
path, fixed, broken = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
text = path.read_text()
if text.count(fixed) != 1:
    sys.exit(f"mutant: expected exactly one occurrence of {fixed!r}, found {text.count(fixed)}")
path.write_text(text.replace(fixed, broken))
PY
  echo "$dir"
}

# proxy <port> <counts file> [fault args...]: start fault_proxy.py and wait until it listens.
proxy_pids=()
proxy() {
  local port=$1 counts=$2
  shift 2
  python3 tests/performance/step2/fault_proxy.py --listen "$port" --upstream "$api" \
    --counts "$counts" "$@" &
  proxy_pids+=($!)
  for _ in $(seq 50); do
    [[ -s "$counts" ]] && return 0
    sleep 0.1
  done
  echo "proxy on $port did not start" >&2
  return 1
}
trap 'kill "${proxy_pids[@]}" 2>/dev/null || true' EXIT

# run_case <name> <expected exit> <api key> <host> <locust args...>: runs one case and records
# exit, seconds, Locust's request and failure counts, and the Kafka delta in $out/<name>.*.
run_case() {
  local name=$1 expected=$2 api_key=$3 host=$4
  shift 4
  local before after started rc=0
  before=$(settled_records)
  cp "$out/.offsets.last" "$out/$name.offsets.before"
  rm -f "$out/$name.ids.json"
  started=$(date +%s)
  FS_PERF_API_KEY="$api_key" "${locust[@]}" -f "${case_harness:-$harness}" --host "$host" \
    --csv "$out/$name" "$@" >"$out/$name.log" 2>&1 || rc=$?
  case_seconds=$(($(date +%s) - started))
  after=$(settled_records)
  cp "$out/.offsets.last" "$out/$name.offsets.after"
  case_rc=$rc
  case_kafka=$((after - before))
  # The harness's own provenance line, written at quit: exact, where the stats CSV is rewritten
  # only once a second and misses the last of the run.
  # The currency split is printed as CUR=n,... in sorted order, or "none".
  read -r case_requests case_failures case_accepted case_currencies < <(python3 - "$out/$name.log" <<'PY'
import json, sys
lines = [line for line in open(sys.argv[1]) if line.startswith('{"run_id"')]
record = json.loads(lines[-1]) if lines else {}
split = record.get("accepted_new_by_currency") or {}
print(record.get("requests", 0), record.get("failures", 0), record.get("accepted_new", 0),
      ",".join(f"{k}={v}" for k, v in split.items()) or "none")
PY
  )
  case_expected=$expected
}

# verdict <name> <reached text> <condition...>: appends the row; the case passes only when the
# exit status is the expected one and every extra condition holds.
verdict() {
  local name=$1 reached=$2
  shift 2
  local ok=true
  [[ "$case_rc" == "$case_expected" ]] || ok=false
  for condition in "$@"; do
    if ! eval "$condition"; then
      ok=false
      echo "case $name: condition failed: $condition" >&2
    fi
  done
  local word=PASS
  if [[ "$ok" != true ]]; then
    word=FAIL
    failures=$((failures + 1))
    echo "::error title=m10 step 2 case $name::exit $case_rc (expected $case_expected); see $out/$name.log"
    tail -n 40 "$out/$name.log" >&2
  fi
  printf '| %s | %s | %s | %s | %s / %s | %s | %s |\n' "$name" "$case_rc" "$case_expected" \
    "$case_seconds" "$case_requests" "$case_failures" "$reached" "$word" >>"$summary"
  echo "case $name: $word (exit $case_rc, expected $case_expected)"
}

# refused <text> <case>: the case's log has a preflight refusal containing <text> (fixed string).
refused() { grep -F "PREFLIGHT REFUSED: " "$out/$2.log" | grep -qF "$1"; }
forwarded() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["ingest_forwarded"])' "$1"; }

users=2
rate=1

# accepted_in <currency>: the case's accepted split has at least one ingest in that currency.
accepted_in() { [[ ",$case_currencies," =~ ,$1=[1-9][0-9]*, ]]; }
# split_total: the sum of the case's accepted split.
split_total() {
  local total=0 part parts
  IFS=, read -ra parts <<<"$case_currencies"
  for part in "${parts[@]}"; do [[ "$part" == *=* ]] && total=$((total + ${part#*=})); done
  echo "$total"
}

# 1. The run passes against the real API, and what it says it sent is what arrived. Every pack
# currency must be among the accepted ingests, read from the run rather than inferred from the
# absence of failures. With at least 60 accepted (about 120 are sent at the nominal rate), a
# uniform draw over five currencies leaves one out with probability below 1e-5, so a missing
# currency is a finding, not chance; a run too slow to reach 60 fails on that floor instead.
run_case pass 0 "$key" "$api" -u "$users" -r "$users" -t 60s --fs-user-throughput "$rate" \
  --fs-ids-out "$out/pass.ids.json"
pass_records=$(reconcile pass) || pass_records="records: reconciliation did not complete"
verdict pass "Kafka +$case_kafka; $case_accepted accepted ($case_currencies); $pass_records" \
  '(( case_requests > 0 ))' '(( case_failures == 0 ))' \
  '(( case_kafka >= case_accepted && case_kafka <= case_accepted + users ))' \
  '(( case_accepted >= 60 ))' '(( $(split_total) == case_accepted ))' \
  'accepted_in RWF' 'accepted_in KES' 'accepted_in TZS' 'accepted_in UGX' 'accepted_in CDF' \
  'grep -q "preflight passed" "$out/pass.log"'

# 2. A wrong key stops the run before the first ingest request.
run_case wrong-key 3 "$wrong_key" "$api" -u "$users" -r "$users" -t 60s
verdict wrong-key "Kafka +$case_kafka" '(( case_requests == 0 ))' '(( case_kafka == 0 ))' \
  'refused "refused the key (HTTP 401" wrong-key'

# 3 to 5. The environment endpoint says no, says nothing, or fails.
proxy 18081 "$out/synthetic-false.counts.json" --environment-body '{"synthetic_data":false}'
run_case synthetic-false 3 "$key" http://127.0.0.1:18081 -u "$users" -r "$users" -t 60s
verdict synthetic-false "Kafka +$case_kafka; proxy forwarded $(forwarded "$out/synthetic-false.counts.json")" \
  '(( case_requests == 0 ))' '(( case_kafka == 0 ))' \
  '[[ $(forwarded "$out/synthetic-false.counts.json") == 0 ]]' \
  'refused "synthetic_data=False" synthetic-false'

proxy 18082 "$out/environment-without-flag.counts.json" --environment-body '{}'
run_case environment-without-flag 3 "$key" http://127.0.0.1:18082 -u "$users" -r "$users" -t 60s
verdict environment-without-flag "proxy forwarded $(forwarded "$out/environment-without-flag.counts.json")" \
  '(( case_requests == 0 ))' '[[ $(forwarded "$out/environment-without-flag.counts.json") == 0 ]]' \
  'refused "synthetic_data=None" environment-without-flag'

proxy 18083 "$out/environment-failing.counts.json" --environment-status 503 \
  --environment-body '{"status":503}'
run_case environment-failing 3 "$key" http://127.0.0.1:18083 -u "$users" -r "$users" -t 60s
verdict environment-failing "proxy forwarded $(forwarded "$out/environment-failing.counts.json")" \
  '(( case_requests == 0 ))' '[[ $(forwarded "$out/environment-failing.counts.json") == 0 ]]' \
  'refused "answered HTTP 503" environment-failing'

# 6. Nothing listens at the host.
run_case unreachable 3 "$key" http://127.0.0.1:9 -u "$users" -r "$users" -t 60s
verdict unreachable "Kafka +$case_kafka" '(( case_requests == 0 ))' '(( case_kafka == 0 ))' \
  'refused "could not be read" unreachable'

# 7. The key stops working mid-run: the run stops at the first 401, not at its run time.
revoke_after=10
proxy 18084 "$out/key-revoked-in-run.counts.json" --revoke-after "$revoke_after"
run_case key-revoked-in-run 4 "$key" http://127.0.0.1:18084 -u "$users" -r "$users" -t 60s \
  --fs-user-throughput "$rate"
verdict key-revoked-in-run "proxy forwarded $(forwarded "$out/key-revoked-in-run.counts.json")" \
  '(( case_seconds < 45 ))' \
  '[[ $(forwarded "$out/key-revoked-in-run.counts.json") == "$revoke_after" ]]' \
  'grep -q "KEY REFUSED IN RUN: HTTP 401" "$out/key-revoked-in-run.log"'

# 8. The proxy with no fault passes, so the refusals above are the faults', not the proxy's.
# (The upper bound in case 1 allows for requests in flight when the run's users are stopped: they
# reach the API, and Kafka, but not the run's statistics.)
proxy 18085 "$out/proxy-control.counts.json"
run_case proxy-control 0 "$key" http://127.0.0.1:18085 -u "$users" -r "$users" -t 20s \
  --fs-user-throughput "$rate" --fs-ids-out "$out/proxy-control.ids.json"
control_records=$(reconcile proxy-control) ||
  control_records="records: reconciliation did not complete"
verdict proxy-control "proxy forwarded $(forwarded "$out/proxy-control.counts.json"); accepted ($case_currencies); Kafka +$case_kafka; $control_records" \
  '(( case_requests > 0 ))' '(( case_failures == 0 ))'

# 9. The harness with the shape defect restored sends nothing; that must fail, not exit 0.
case_harness=$(mutant shape-stops \
  'return parsed.num_users or 1, float(parsed.spawn_rate or 1)' 'return None')
run_case mutant-shape-stops 5 "$key" "$api" -u "$users" -r "$users" -t 20s
verdict mutant-shape-stops "Kafka +$case_kafka" '(( case_requests == 0 ))' '(( case_kafka == 0 ))' \
  'grep -q "NOTHING SENT" "$out/mutant-shape-stops.log"'

# 10. The harness with the pacing defect restored raises in every user; that must fail too.
case_harness=$(mutant pacing-raises 'return float(self.pacing(self))' 'return float(self.pacing())')
run_case mutant-pacing-raises 6 "$key" "$api" -u "$users" -r "$users" -t 20s
verdict mutant-pacing-raises "Kafka +$case_kafka" \
  'grep -q "TASK RAISED" "$out/mutant-pacing-raises.log"'
unset case_harness

cat "$summary"
[[ -z "${GITHUB_STEP_SUMMARY:-}" ]] || { echo "## M10 step 2, ingest leg"; cat "$summary"; } >>"$GITHUB_STEP_SUMMARY"
if ((failures > 0)); then
  echo "$failures case(s) did not behave as expected" >&2
  exit 1
fi
echo "every case behaved as expected"
