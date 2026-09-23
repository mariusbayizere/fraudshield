-- The PII vault's own schema, in its own PostgreSQL instance (D-20, ADR 0012 section 4).
--
-- This database holds the only customer phone numbers and account numbers in FraudShield. It is
-- not the main database: fs_app, fs_app_readonly and fs_compliance_ro do not exist here and no
-- grant is written for them. One role, fs_vault, may read and write the contacts table and
-- nothing else. Values are stored as AES-256-GCM ciphertext under a per-row data key, itself
-- wrapped by the key provider's master key (envelope encryption), so a dump of this database
-- without the key material reveals nothing but row counts and timestamps.

CREATE SCHEMA IF NOT EXISTS vault;
REVOKE ALL ON SCHEMA vault FROM PUBLIC;

CREATE TABLE vault.contacts (
  institution_id uuid NOT NULL,
  account_token text NOT NULL CHECK (account_token ~ '^tok_[A-Za-z0-9]{24}$'),
  key_id text NOT NULL CHECK (char_length(key_id) BETWEEN 1 AND 64),
  wrapped_key bytea NOT NULL,
  nonce bytea NOT NULL CHECK (octet_length(nonce) = 12),
  ciphertext bytea NOT NULL CHECK (octet_length(ciphertext) BETWEEN 1 AND 4096),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (institution_id, account_token)
);
COMMENT ON TABLE vault.contacts IS
  'Encrypted customer contact details, one row per account token (D-20).';

CREATE OR REPLACE FUNCTION vault.touch_updated_at() RETURNS trigger
  LANGUAGE plpgsql AS $$
  BEGIN
    NEW.updated_at := now();
    RETURN NEW;
  END;
  $$;
CREATE TRIGGER contacts_touch_updated_at BEFORE UPDATE ON vault.contacts
  FOR EACH ROW EXECUTE FUNCTION vault.touch_updated_at();

GRANT USAGE ON SCHEMA vault TO fs_vault;
GRANT SELECT, INSERT, UPDATE ON vault.contacts TO fs_vault;
