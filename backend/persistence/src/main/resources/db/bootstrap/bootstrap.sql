-- FraudShield database bootstrap (ADR 0017). Run by a superuser in the target database, before Flyway.
--
-- Creates the five roles without passwords (set them separately from secrets, never in git), the
-- TimescaleDB extension and the fraudshield schema owned by fs_migrator. Flyway then runs as
-- fs_migrator. No role here is a superuser, can create roles or databases, or bypasses row-level
-- security. Idempotent, so it can be re-run safely.

DO $$
DECLARE
  role_name text;
BEGIN
  FOREACH role_name IN ARRAY ARRAY['fs_migrator', 'fs_app', 'fs_app_readonly', 'fs_compliance_ro', 'fs_scorer'] LOOP
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
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO fs_migrator, fs_app, fs_app_readonly, fs_compliance_ro, fs_scorer', current_database());
  EXECUTE format('GRANT TEMPORARY ON DATABASE %I TO fs_migrator', current_database());
END
$$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS fraudshield AUTHORIZATION fs_migrator;
REVOKE ALL ON SCHEMA fraudshield FROM PUBLIC;
GRANT USAGE ON SCHEMA fraudshield TO fs_app, fs_app_readonly, fs_compliance_ro;
-- fs_scorer (ADR 0062 point 6): the scorer's feature-store fallback. USAGE on the schema lets it
-- call the feature_fallback_* functions V67 grants it; it holds no grant on any table or view.
GRANT USAGE ON SCHEMA fraudshield TO fs_scorer;

-- Every role resolves unqualified names in the fraudshield schema only.
DO $$
DECLARE
  role_name text;
BEGIN
  FOREACH role_name IN ARRAY ARRAY['fs_migrator', 'fs_app', 'fs_app_readonly', 'fs_compliance_ro', 'fs_scorer'] LOOP
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path = fraudshield, public', role_name, current_database());
  END LOOP;
END
$$;
