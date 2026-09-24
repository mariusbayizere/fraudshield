-- FraudShield database bootstrap (ADR 0017). Run by a superuser in the target database, before Flyway.
--
-- Creates the four roles without passwords (set them separately from secrets, never in git), the
-- TimescaleDB extension and the fraudshield schema owned by fs_migrator. Flyway then runs as
-- fs_migrator. No role here is a superuser, can create roles or databases, or bypasses row-level
-- security. Idempotent, so it can be re-run safely.

DO $$
DECLARE
  role_name text;
BEGIN
  FOREACH role_name IN ARRAY ARRAY['fs_migrator', 'fs_app', 'fs_app_readonly', 'fs_compliance_ro'] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
      EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', role_name);
    END IF;
    EXECUTE format('ALTER ROLE %I NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', role_name);
  END LOOP;
END
$$;

CREATE EXTENSION IF NOT EXISTS timescaledb;

DO $$
BEGIN
  EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO fs_migrator, fs_app, fs_app_readonly, fs_compliance_ro', current_database());
  EXECUTE format('GRANT TEMPORARY ON DATABASE %I TO fs_migrator', current_database());
END
$$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS fraudshield AUTHORIZATION fs_migrator;
REVOKE ALL ON SCHEMA fraudshield FROM PUBLIC;
GRANT USAGE ON SCHEMA fraudshield TO fs_app, fs_app_readonly, fs_compliance_ro;

-- Every role resolves unqualified names in the fraudshield schema only.
DO $$
DECLARE
  role_name text;
BEGIN
  FOREACH role_name IN ARRAY ARRAY['fs_migrator', 'fs_app', 'fs_app_readonly', 'fs_compliance_ro'] LOOP
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path = fraudshield, public', role_name, current_database());
  END LOOP;
END
$$;
