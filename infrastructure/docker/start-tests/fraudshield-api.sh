#!/usr/bin/env bash
# Start test of the API image (M9; owner, 2026-10-22: an image that builds but cannot start is a
# vacuous pass). Usage: fraudshield-api.sh <image reference>
#
# What it proves, and what it does not. With no database reachable, the API must start its JVM,
# load the layered jar and its libraries, prepare the Spring context, and then refuse to start at
# the synthetic-data guard (ADR 0019 point 5; the guard is in the API since the amendment of
# 2026-10-22). Seeing both lines proves the image, its launcher, its classpath and the guard's
# registration. It does not prove the API serves requests: that is the compose stack's start test
# (`fraudshield-api` healthy in the `full` profile).
#
# The container runs as the Kubernetes manifest runs it: user 10001, read-only root filesystem,
# a writable /tmp, every capability dropped (infrastructure/k8s/base/api.yaml).
set -euo pipefail

image=${1:?usage: fraudshield-api.sh <image>}
log=$(mktemp)
trap 'rm -f "$log"' EXIT

status=0
timeout 180 docker run --rm \
  --read-only --tmpfs /tmp:rw,size=64m --cap-drop ALL --security-opt no-new-privileges \
  --user 10001:10001 --memory 1g \
  --env FRAUDSHIELD_DB_URL=jdbc:postgresql://127.0.0.1:1/fraudshield_db \
  "$image" >"$log" 2>&1 || status=$?

expect() {
  if ! grep -q -- "$1" "$log"; then
    echo "start test failed: expected \"$1\" in the container's output" >&2
    tail -n 60 "$log" >&2
    exit 1
  fi
}

if [ "$status" -eq 0 ] || [ "$status" -eq 124 ]; then
  echo "start test failed: the container exited with $status; it must refuse to start" >&2
  tail -n 60 "$log" >&2
  exit 1
fi
expect "Starting FraudShieldApiApplication"
expect "Refusing to start: cannot check the database for synthetic demo data (ADR 0019)"
echo "fraudshield-api: starts as far as the synthetic-data guard, which fails closed (exit $status)"
