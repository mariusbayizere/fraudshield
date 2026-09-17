-- Tenancy, shared functions and append-only enforcement (ADR 0017, D-31).

-- The institution of the current transaction, set by the application with
--   SET LOCAL fraudshield.institution_id = '<uuid>'
-- at the start of every transaction. Unset means no tenant: every tenant-scoped query returns no
-- rows and every insert fails, so a missing setting fails closed.
CREATE FUNCTION current_institution() RETURNS uuid
  LANGUAGE sql STABLE PARALLEL SAFE
  AS $$ SELECT nullif(current_setting('fraudshield.institution_id', true), '')::uuid $$;

-- Rejects UPDATE, DELETE and TRUNCATE on append-only tables for every role, including the owner.
-- Grants already deny these to the application roles; this trigger also protects against a
-- mistaken grant or a migration running as the owner.
CREATE FUNCTION forbid_modification() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
BEGIN
  RAISE EXCEPTION 'table %.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
    USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE FUNCTION set_updated_at() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END
$$;

-- Makes a table append-only: triggers for UPDATE, DELETE and TRUNCATE.
CREATE PROCEDURE make_append_only(p_table regclass)
  LANGUAGE plpgsql
  AS $$
BEGIN
  EXECUTE format('CREATE TRIGGER append_only_rows BEFORE UPDATE OR DELETE ON %s FOR EACH ROW EXECUTE FUNCTION fraudshield.forbid_modification()', p_table);
  EXECUTE format('CREATE TRIGGER append_only_truncate BEFORE TRUNCATE ON %s FOR EACH STATEMENT EXECUTE FUNCTION fraudshield.forbid_modification()', p_table);
END
$$;

-- Enables row-level security with the standard tenant policy on a table that has institution_id.
CREATE PROCEDURE enable_tenant_isolation(p_table regclass)
  LANGUAGE plpgsql
  AS $$
BEGIN
  EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', p_table);
  EXECUTE format('CREATE POLICY tenant_isolation ON %s USING (institution_id = fraudshield.current_institution()) WITH CHECK (institution_id = fraudshield.current_institution())', p_table);
END
$$;

-- Tenant isolation for TimescaleDB hypertables. TimescaleDB does not allow compression (columnstore)
-- or continuous aggregates on tables with row-level security, so hypertables are isolated without
-- it: the application may INSERT only rows of the current institution (this trigger) and may read
-- only through a security-barrier view v_<table> filtered by the current institution. No application
-- role has SELECT on the hypertable itself (ADR 0017).
CREATE FUNCTION forbid_other_institution() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
BEGIN
  IF NEW.institution_id IS DISTINCT FROM fraudshield.current_institution() THEN
    RAISE EXCEPTION 'row for institution % does not match the current institution', NEW.institution_id
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END
$$;

CREATE PROCEDURE enable_tenant_view_isolation(p_table regclass)
  LANGUAGE plpgsql
  AS $$
DECLARE
  table_name text := (SELECT relname FROM pg_class WHERE oid = p_table);
BEGIN
  EXECUTE format('CREATE TRIGGER tenant_insert_guard BEFORE INSERT ON %s FOR EACH ROW EXECUTE FUNCTION fraudshield.forbid_other_institution()', p_table);
  EXECUTE format('CREATE VIEW fraudshield.%I WITH (security_barrier = true) AS SELECT * FROM %s WHERE institution_id = fraudshield.current_institution()', 'v_' || table_name, p_table);
END
$$;

CREATE TABLE institutions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code text NOT NULL UNIQUE CHECK (code ~ '^[a-z0-9][a-z0-9-]{1,31}$'),
  name text NOT NULL CHECK (char_length(name) BETWEEN 2 AND 200),
  country char(2) NOT NULL CHECK (country ~ '^[A-Z]{2}$'),
  -- True for institutions created by demo seeding (ADR 0019). Only fs_migrator can write it.
  synthetic boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER institutions_updated_at BEFORE UPDATE ON institutions
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE institutions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON institutions
  USING (id = current_institution()) WITH CHECK (id = current_institution());

-- Whether this database holds synthetic demo data (ADR 0019). Callable before a tenant is set, so
-- every service can refuse to start outside the dev and demo profiles against a seeded database and
-- can show the synthetic-data banner (D-21). Returns one boolean, nothing about any institution.
CREATE FUNCTION deployment_has_synthetic_data() RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$ SELECT EXISTS (SELECT 1 FROM fraudshield.institutions WHERE synthetic) $$;
