#!/usr/bin/env bash
# Run a command while printing a memory and swap line every 30 s (owner, 2026-10-19).
#
#   .github/scripts/with-memory-curve.sh <label> <file> -- <command> [args...]
#
# Peak RSS alone cannot say why a run died. Each line goes to the job log as it is taken, so the
# curve survives a runner lost to memory, and to <file>, which the job uploads. `swapon --show`
# is recorded first. The command's exit status is this script's.
set -euo pipefail
if [ "$#" -lt 4 ] || [ "$3" != "--" ]; then
  echo "usage: with-memory-curve.sh <label> <file> -- <command> [args...]" >&2
  exit 2
fi
label=$1
file=$2
shift 3
mkdir -p "$(dirname "$file")"
{ echo "[$label] swap devices:"; swapon --show || echo "(none)"; } | tee -a "$file"
(
  # The sampler must never fail the step: a failed reading or write is skipped, not fatal. It
  # sleeps in the background and waits, so a TERM interrupts the wait at once and the sleep is
  # killed with it, rather than outliving the step by up to 30 s.
  set +e
  nap=
  trap '[ -n "$nap" ] && kill "$nap" 2>/dev/null; exit 0' TERM
  while :; do
    line="[$label] $(date -u +%H:%M:%S) $(free -m | awk '/^Mem:/ {printf "mem used %s MB, available %s MB; ", $3, $7} /^Swap:/ {printf "swap used %s of %s MB", $3, $2}')"
    echo "$line"
    echo "$line" >> "$file"
    sleep 30 &
    nap=$!
    wait "$nap"
  done
) &
sampler=$!
# Tolerates "nothing to kill", so the trap cannot replace the command's exit status.
trap 'kill "$sampler" 2>/dev/null || true; wait "$sampler" 2>/dev/null || true' EXIT
"$@"
