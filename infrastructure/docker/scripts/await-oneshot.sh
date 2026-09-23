#!/usr/bin/env sh
# Waits for a one-shot compose service to finish and fails unless it exited 0.
#
#   await-oneshot.sh <service> [compose arguments...]
#
# `docker compose up --wait` treats a one-shot as ready once it has started and ignores its exit
# code, and `docker compose wait` sees only containers that are still running (it reports "no
# containers" for one that already exited, which is the normal case in the full stack). This reads
# the container's recorded state instead, so it works whether the service is still running or
# finished long ago (the fourth review, 2026-09-23).
set -eu

service=$1
shift
deadline=$(( $(date +%s) + ${ONESHOT_TIMEOUT_SECONDS:-300} ))
while :; do
  status=$(docker compose "$@" ps -a --format '{{.State}} {{.ExitCode}}' "$service" 2>/dev/null || true)
  case "$status" in
    "exited 0") exit 0 ;;
    exited\ *)
      echo "$service failed (${status#exited }): see 'docker compose logs $service'" >&2
      exit 1 ;;
    "")
      echo "$service has no container: it did not run" >&2
      exit 1 ;;
  esac
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "$service did not finish in time (state: $status)" >&2
    exit 1
  fi
  sleep 2
done
