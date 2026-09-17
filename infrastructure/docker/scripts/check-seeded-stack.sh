#!/usr/bin/env bash
# After `make seed-demo` on the Compose stack (ADR 0017, ADR 0019): the credentials file is private
# and git-ignored, the migrations are applied, the application role logs in with its password over
# TCP and cannot read another tenant's rows (or any row without a tenant), and the demo institution
# with its five accounts exists. Prints no secret.
set -Eeuo pipefail

root=$(git rev-parse --show-toplevel)
cd "$root"
fail() {
  echo "seeded stack check failed: $1" >&2
  [[ -z "${GITHUB_ACTIONS:-}" ]] || echo "::error title=seeded stack check failed::$1"
  exit 1
}
trap 'fail "line $LINENO: $BASH_COMMAND"' ERR

[[ "$(stat -c '%a' .demo-credentials)" == "600" ]] || fail ".demo-credentials is not mode 600"
git check-ignore -q .demo-credentials || fail ".demo-credentials is not git-ignored"
echo ".demo-credentials: mode 600, git-ignored"

# Every query runs in the TimescaleDB container: as postgres over the local socket for fixture
# lookups, and as fs_app over TCP to the service address (not 127.0.0.1, which initdb trusts), so
# the role's password is actually checked.
superuser_sql() {
  docker compose --profile core exec -T timescaledb \
    psql -U postgres -d fraudshield_db -v ON_ERROR_STOP=1 -tA -c "$1"
}
app_sql() {
  local password=$1
  shift
  local args=()
  for sql in "$@"; do args+=(-c "$sql"); done
  docker compose --profile core exec -T -e PGPASSWORD="$password" timescaledb \
    psql -h timescaledb -U fs_app -d fraudshield_db -v ON_ERROR_STOP=1 -tAq "${args[@]}"
}
app_password=$(grep '^FS_APP_DB_PASSWORD=' .env | cut -d= -f2-)

version=$(superuser_sql "select max(version::int) from fraudshield.flyway_schema_history where success")
[[ "$version" -ge 11 ]] || fail "migrations not applied (version '$version')"
echo "migrations applied up to V$version"

no_tenant=$(app_sql "$app_password" "select count(*) from users")
[[ "$no_tenant" == "0" ]] || fail "fs_app read $no_tenant users without a tenant"
echo "fs_app without a tenant sees 0 users (fail closed)"

institution=$(superuser_sql "select id from fraudshield.institutions where code = 'demo-bank'")
[[ -n "$institution" ]] || fail "demo institution missing"
in_tenant=$(app_sql "$app_password" "set fraudshield.institution_id = '$institution'" \
  "select count(*) from users")
[[ "$in_tenant" == "5" ]] || fail "expected 5 demo users in the demo tenant, found '$in_tenant'"
other=$(app_sql "$app_password" \
  "set fraudshield.institution_id = '00000000-0000-4000-8000-000000000000'" \
  "select count(*) from users")
[[ "$other" == "0" ]] || fail "another tenant sees $other demo users"
echo "demo tenant sees 5 users; another tenant sees 0"

if app_sql "wrong-$app_password" "select 1" >/dev/null 2>&1; then
  fail "fs_app logged in with a wrong password"
fi
echo "wrong password refused"
echo "seeded stack checks passed"
