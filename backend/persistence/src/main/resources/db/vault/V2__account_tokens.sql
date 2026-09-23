-- The tokenisation map (D-20, ADR 0012 section 4, ADR 0069 point 9).
--
-- An institution knows its customers by account number; every other part of FraudShield knows them
-- only by token. This table is the one place the two meet. A number is found by a keyed hash (a
-- blind index: HMAC-SHA-256 under an index key the database never sees), so the same number always
-- receives the same token without the number being stored in a searchable form; the number itself
-- is kept as AES-256-GCM ciphertext under a per-row data key, as in vault.contacts, so a token can
-- be turned back into the number the customer knows.
--
-- One token per number per institution, for ever: the same account is the same token whether it
-- sends or receives, which the counterparty features depend on. fs_vault may therefore read and
-- insert, and may not update or delete.

CREATE TABLE vault.account_tokens (
  institution_id uuid NOT NULL,
  lookup_hash bytea NOT NULL CHECK (octet_length(lookup_hash) = 32),
  account_token text NOT NULL CHECK (account_token ~ '^tok_[A-Za-z0-9]{24}$'),
  key_id text NOT NULL CHECK (char_length(key_id) BETWEEN 1 AND 64),
  wrapped_key bytea NOT NULL,
  nonce bytea NOT NULL CHECK (octet_length(nonce) = 12),
  ciphertext bytea NOT NULL CHECK (octet_length(ciphertext) BETWEEN 17 AND 256),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (institution_id, lookup_hash),
  UNIQUE (institution_id, account_token)
);
COMMENT ON TABLE vault.account_tokens IS
  'Account number to token, one permanent token per number per institution (D-20).';

GRANT SELECT, INSERT ON vault.account_tokens TO fs_vault;
