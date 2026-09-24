#!/usr/bin/env bash
# Pull a compose profile's images, retrying a failed pull with backoff.
#
# Image pulls fail for reasons that have nothing to do with the stack: a registry CDN resets the
# connection mid-layer, or Docker Hub rate-limits. One such reset failed a whole devcontainer run
# on 2026-09-24 (lab notebook). `docker compose up` pulls implicitly and does not retry, so this
# script does the pull first, on its own, and says plainly that a pull failed. Everything after it
# — creating containers, healthchecks, the stack itself — is not retried: a stack that comes up
# only on the second attempt is a defect, not a flake.
set -Eeuo pipefail

profile="${1:-core}"
attempts="${PULL_ATTEMPTS:-3}"
delay="${PULL_BACKOFF_SECONDS:-5}"

for attempt in $(seq 1 "$attempts"); do
  if docker compose --profile "$profile" pull --quiet; then
    exit 0
  fi
  if [ "$attempt" -lt "$attempts" ]; then
    echo "pull-images: pulling the '$profile' images failed (attempt $attempt of $attempts);" \
      "retrying in ${delay}s" >&2
    sleep "$delay"
    delay=$((delay * 2))
  fi
done

echo "pull-images: could not pull the '$profile' images in $attempts attempts." >&2
echo "pull-images: this is a registry or network failure, not a stack failure:" \
  "no container was started." >&2
exit 1
