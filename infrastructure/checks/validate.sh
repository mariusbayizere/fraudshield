#!/usr/bin/env bash
# Validate everything under infrastructure/ with the pinned, checksum-verified tools only. No
# Docker, no cluster, no running services: the same command runs on a laptop and in CI
# (.github/workflows/infrastructure.yml).
#
# Usage: infrastructure/checks/validate.sh [--quick]
#   --quick skips the week-long promtool tests (about 2.5 minutes on one core).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
bin="$root/infrastructure/bin"
cd "$root"
quick=false
[[ "${1:-}" == "--quick" ]] && quick=true

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

step() { printf '\n== %s\n' "$*"; }

step "Prometheus rules"
"$bin/promtool" check rules infrastructure/prometheus/rules/*.yml

step "Prometheus configuration (rule_files resolved against the repository copies)"
mkdir -p "$scratch/rules"
cp infrastructure/prometheus/rules/*.yml "$scratch/rules/"
sed "s#/etc/prometheus/rules#$scratch/rules#" infrastructure/prometheus/prometheus.yml \
  >"$scratch/prometheus.yml"
"$bin/promtool" check config --syntax-only "$scratch/prometheus.yml"

step "Prometheus rule unit tests"
"$bin/promtool" test rules infrastructure/prometheus/tests/platform_test.yml
if [[ "$quick" == false ]]; then
  "$bin/promtool" test rules infrastructure/prometheus/tests/risk_test.yml
fi

step "Blackbox exporter modules"
"$bin/blackbox_exporter" --config.check --config.file=infrastructure/prometheus/blackbox.yml \
  2>&1 | grep -q "Config file is ok"

step "Loki log rules"
"$bin/lokitool" rules lint --dry-run infrastructure/loki/rules/fraudshield/*.yml

step "Alertmanager configuration and routing"
am_config=infrastructure/alertmanager/alertmanager.yml
"$bin/amtool" check-config "$am_config"
expect_route() {
  local expected="$1"
  shift
  local got
  got="$("$bin/amtool" config routes test --config.file="$am_config" "$@")"
  if [[ "$got" != "$expected" ]]; then
    echo "routing $* -> $got, expected $expected" >&2
    exit 1
  fi
}
expect_route pagerduty-risk team=risk severity=page alertname=FraudShieldTimeoutReleaseRateHigh
expect_route pagerduty-risk team=risk severity=ticket alertname=FraudShieldFalsePositiveRateHigh
expect_route pagerduty-ml team=ml severity=ticket alertname=FraudShieldFeatureDrift
expect_route pagerduty-sre team=sre severity=page alertname=FraudShieldErrorLogged
expect_route pagerduty-sre alertname=AnythingUnlabelled
echo "routing: 5 cases as expected"

step "Metric catalogue (Part E.10)"
uv run --frozen python infrastructure/checks/metric_catalogue.py

printf '\ninfrastructure: all checks passed\n'
