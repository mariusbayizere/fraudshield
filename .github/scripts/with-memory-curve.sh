#!/usr/bin/env bash
# Run a command while printing a memory and swap line every 30 s (owner, 2026-10-19).
#
#   .github/scripts/with-memory-curve.sh <label> <file> -- <command> [args...]
#
# Peak RSS alone cannot say why a run died. Each line goes to the job log as it is taken, so the
# curve survives a runner lost to memory, and to <file>, which the job uploads. `swapon --show`
# is recorded first. The command's exit status is this script's.
set -euo pipefail
label=$1
file=$2
shift 3
mkdir -p "$(dirname "$file")"
{ echo "[$label] swap devices:"; swapon --show || echo "(none)"; } | tee -a "$file"
(
  while :; do
    line="[$label] $(date -u +%H:%M:%S) $(free -m | awk '/^Mem:/ {printf "mem used %s MB, available %s MB; ", $3, $7} /^Swap:/ {printf "swap used %s of %s MB", $3, $2}')"
    echo "$line"
    echo "$line" >> "$file"
    sleep 30
  done
) &
sampler=$!
trap 'pkill -P "$sampler" 2>/dev/null; kill "$sampler" 2>/dev/null || true' EXIT
"$@"
