-- Roles of the PII vault instance (D-20). Run once as a superuser, before the vault migrations.
--
-- Only two roles exist here: fs_vault_migrator, which owns the schema, and fs_vault, which the
-- notification service uses. The main database's roles are deliberately absent: nothing that can
-- read transactions can read a phone number, and nothing here can read a transaction.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fs_vault_migrator') THEN
    CREATE ROLE fs_vault_migrator LOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fs_vault') THEN
    CREATE ROLE fs_vault LOGIN;
  END IF;
END
$$;

REVOKE ALL ON DATABASE fraudshield_pii FROM PUBLIC;
GRANT CONNECT ON DATABASE fraudshield_pii TO fs_vault_migrator, fs_vault;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
-- The migrator creates and owns the vault's schema (Flyway's history table lives there too).
-- PostgreSQL checks CREATE on the database even for CREATE SCHEMA IF NOT EXISTS, so the grant is
-- needed although the schema is created here; fs_vault is given no such grant.
GRANT CREATE ON DATABASE fraudshield_pii TO fs_vault_migrator;
CREATE SCHEMA IF NOT EXISTS vault AUTHORIZATION fs_vault_migrator;
REVOKE ALL ON SCHEMA vault FROM PUBLIC;
