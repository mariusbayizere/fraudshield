-- Durable per-account state (PB-37, ADR 0062).
--
-- Accounts existed only as account_token columns on transactions and their aggregates, so nothing
-- durable held an account's first appearance or its opening date. velocity_ratio_1h_vs_30d divides
-- by observed history (history_basis=OBSERVED_CAPPED) and therefore needs the first-seen time to
-- survive a cache flush; without it the online path fails closed (NaN). account_age_days needs the
-- opening date, which the institution supplies and which is not the first-seen time: an account can
-- be opened long before it transacts.
--
-- Mutable only in the two directions that keep the facts true, enforced below for every role:
-- first_seen_at may move earlier (a late-arriving earlier transaction) and never later; opened_at
-- may be supplied once and never changed or removed.

CREATE TABLE account_profiles (
  institution_id uuid NOT NULL REFERENCES institutions (id),
  account_token text NOT NULL CHECK (is_token(account_token)),
  first_seen_at timestamptz NOT NULL,
  opened_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (institution_id, account_token),
  CHECK (opened_at IS NULL OR opened_at <= first_seen_at)
);

CREATE FUNCTION guard_account_profile_update() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'account profiles are never deleted' USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF (NEW.institution_id, NEW.account_token, NEW.created_at)
     IS DISTINCT FROM (OLD.institution_id, OLD.account_token, OLD.created_at) THEN
    RAISE EXCEPTION 'an account profile''s identity cannot change' USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF NEW.first_seen_at > OLD.first_seen_at THEN
    RAISE EXCEPTION 'first_seen_at can only move earlier' USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.opened_at IS NOT NULL AND NEW.opened_at IS DISTINCT FROM OLD.opened_at THEN
    RAISE EXCEPTION 'opened_at is set once' USING ERRCODE = 'check_violation';
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END
$$;
CREATE TRIGGER account_profiles_guard BEFORE UPDATE OR DELETE ON account_profiles
  FOR EACH ROW EXECUTE FUNCTION guard_account_profile_update();
CREATE TRIGGER account_profiles_no_truncate BEFORE TRUNCATE ON account_profiles
  FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();
CALL enable_tenant_isolation('account_profiles');

GRANT SELECT, INSERT, UPDATE ON account_profiles TO fs_app;
GRANT SELECT ON account_profiles TO fs_app_readonly;
