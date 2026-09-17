#!/usr/bin/env bash
# Runs once on first container start (docker-entrypoint-initdb.d).
# Creates the MLflow tracking database and its least-privilege owner role.
# Application roles (fs_app, fs_app_readonly, fs_compliance_ro, fs_migrator) are created by
# Flyway migrations in M1, not here.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=mlflow_password="$MLFLOW_DB_PASSWORD" <<'SQL'
CREATE ROLE mlflow LOGIN PASSWORD :'mlflow_password';
CREATE DATABASE mlflow OWNER mlflow;
REVOKE ALL ON DATABASE mlflow FROM PUBLIC;
SQL
