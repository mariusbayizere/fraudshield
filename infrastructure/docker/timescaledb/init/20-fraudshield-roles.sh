#!/usr/bin/env bash
# Creates the FraudShield roles, schema and TimescaleDB extension (bootstrap.sql, ADR 0017) and
# sets the role passwords from the environment (.env via docker-compose.yml).
# Runs on first container start (docker-entrypoint-initdb.d) and again from `make seed-demo`
# (`docker compose exec`), so it must stay idempotent. Tables are created by Flyway, not here.
set -euo pipefail

database=${POSTGRES_DB:-fraudshield_db}
bootstrap=${FRAUDSHIELD_BOOTSTRAP_SQL:-/fraudshield-bootstrap/bootstrap.sql}
psql -v ON_ERROR_STOP=1 --quiet --username "${POSTGRES_USER:-postgres}" --dbname "$database" \
  -f "$bootstrap"

# Passwords are passed as psql variables (visible to processes inside this container while psql runs;
# backlog PB-9) and are never written to a file or the server log.
psql -v ON_ERROR_STOP=1 --quiet --username "${POSTGRES_USER:-postgres}" --dbname "$database" \
  --set=migrator="${FS_MIGRATOR_DB_PASSWORD:?}" \
  --set=app="${FS_APP_DB_PASSWORD:?}" \
  --set=app_readonly="${FS_APP_READONLY_DB_PASSWORD:?}" \
  --set=compliance="${FS_COMPLIANCE_RO_DB_PASSWORD:?}" <<'SQL'
ALTER ROLE fs_migrator PASSWORD :'migrator';
ALTER ROLE fs_app PASSWORD :'app';
ALTER ROLE fs_app_readonly PASSWORD :'app_readonly';
ALTER ROLE fs_compliance_ro PASSWORD :'compliance';
SQL
# fs_scorer (ADR 0062 point 6) is the scorer's feature-store fallback. Its password is optional
# here: without FS_SCORER_DB_PASSWORD the role has none and cannot log in, so the scorer's database
# fallback stays off (fail closed) until the deployment supplies one.
if [[ -n "${FS_SCORER_DB_PASSWORD:-}" ]]; then
  psql -v ON_ERROR_STOP=1 --quiet --username "${POSTGRES_USER:-postgres}" --dbname "$database" \
    --set=scorer="${FS_SCORER_DB_PASSWORD}" <<'SQL'
ALTER ROLE fs_scorer PASSWORD :'scorer';
SQL
fi
echo "fraudshield roles ready in $database"
