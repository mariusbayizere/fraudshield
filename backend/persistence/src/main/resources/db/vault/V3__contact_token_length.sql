-- A contact is enrolled under the account's token as the contracts define it (ADR 0011):
-- tok_ followed by 24 to 64 letters and digits. V1 allowed exactly 24, which refused every valid
-- token longer than that, so a customer whose institution issues longer tokens could never be
-- sent an SMS. Tokens the vault mints itself (account_tokens) stay exactly 24 characters.

ALTER TABLE vault.contacts DROP CONSTRAINT contacts_account_token_check;
ALTER TABLE vault.contacts ADD CONSTRAINT contacts_account_token_check
  CHECK (account_token ~ '^tok_[A-Za-z0-9]{24,64}$');
