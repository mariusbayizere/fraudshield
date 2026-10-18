#!/usr/bin/env bash
# Creates the PII vault's roles and schema (db/vault/bootstrap.sql, ADR 0069) and sets the role
# passwords from the environment (.env via docker-compose.yml). Runs on the vault container's first
# start (docker-entrypoint-initdb.d); idempotent, so it can be run again with `docker compose exec`.
# Tables are created by the pii-vault-migrate service (Flyway, as fs_vault_migrator), not here.
set -euo pipefail

database=${POSTGRES_DB:-fraudshield_pii}
psql -v ON_ERROR_STOP=1 --quiet --username "${POSTGRES_USER:-postgres}" --dbname "$database" \
  -f /fraudshield-vault/bootstrap.sql

# Passwords are passed as psql variables and are never written to a file or the server log.
psql -v ON_ERROR_STOP=1 --quiet --username "${POSTGRES_USER:-postgres}" --dbname "$database" \
  --set=migrator="${FS_VAULT_MIGRATOR_DB_PASSWORD:?}" \
  --set=vault="${FS_VAULT_DB_PASSWORD:?}" <<'SQL'
ALTER ROLE fs_vault_migrator PASSWORD :'migrator';
ALTER ROLE fs_vault PASSWORD :'vault';
SQL
echo "vault roles ready in $database"
