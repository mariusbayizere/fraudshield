#!/usr/bin/env bash
# Print and annotate the state of every core-profile service that is not healthy (or, for the
# one-shot init container, did not exit 0), with its most recent log lines. Used by CI when
# `make up` or `make smoke` fails, because job logs are not readable without authentication
# while annotations are.
set -uo pipefail

annotate() {
  # GitHub workflow commands require %, CR and LF to be escaped in the message.
  local title=$1 message=$2
  message=${message//'%'/'%25'}
  message=${message//$'\r'/'%0D'}
  message=${message//$'\n'/'%0A'}
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::error title=${title}::${message}"
  fi
}

docker compose --profile core ps -a --format 'table {{.Service}}\t{{.Status}}'
problems=0
for container in $(docker compose --profile core ps -a -q); do
  service=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$container")
  state=$(docker inspect --format '{{.State.Status}} exit={{.State.ExitCode}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container")
  case "$service:$state" in
    object-store-init:"exited exit=0 "*) continue ;;
    *:"running exit=0 health=healthy") continue ;;
  esac
  problems=$((problems + 1))
  logs=$(docker logs --tail 25 "$container" 2>&1)
  health_log=$(docker inspect --format '{{if .State.Health}}{{range .State.Health.Log}}{{.ExitCode}}: {{.Output}}{{end}}{{end}}' "$container" | tail -c 1500)
  printf '\n== %s (%s)\n%s\n-- last healthcheck output:\n%s\n' "$service" "$state" "$logs" "$health_log"
  annotate "stack: ${service} ${state}" "$(printf '%s\n--- logs ---\n%s\n--- healthcheck ---\n%s' "$state" "$logs" "$health_log" | tail -c 3500)"
done
echo "services with problems: $problems"
