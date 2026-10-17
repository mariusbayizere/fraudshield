-- The feature store's database fallback (C.4, FR-02-09, PB-69 carried from M5, ADR 0062 point 6).
--
-- When an account's Redis keys have expired or Redis is down, the scorer asks the database for
-- everything those keys held (ADR 0033: the scorer, not the API, reads the account context). The
-- reader is M5's Fallback protocol, implemented over these tables by
-- ml/src/fraudshield_ml/featurestore/postgres.py, and it must answer exactly what the Redis path
-- would have: the account's transactions, its first appearance, its SIM swaps, its KYC tier
-- history and its opening date, and a device's first appearance.
--
-- Two facts had no table: SIM swaps and KYC tier changes. Nothing writes them yet (no MNO feed, no
-- KYC endpoint; ADR 0034's "missing producer"), exactly as nothing writes their Redis keys; the
-- tables exist so that when a producer arrives the fallback already reads what it writes. Both are
-- append-only histories: a swap happened, a tier changed, and neither is ever edited.
--
-- An opening date can precede an account's first transaction, so account_profiles may now hold a
-- profile that has been opened but not yet seen: first_seen_at becomes nullable. Its guard still
-- only lets it move earlier, and the sink's upsert fills it on the first transaction.
--
-- The scorer reads as its own role, fs_scorer (bootstrap.sql), through the SECURITY DEFINER
-- functions below and nothing else. The Fallback protocol, like the Redis keys it replaces, knows an
-- account by its token alone, so these functions are not scoped to an institution: they answer for
-- a token, as Redis does. fs_scorer holds no grant on any table or view, so it cannot list tokens,
-- read a decision, or see anything but the feature inputs of an account it already names.

ALTER TABLE account_profiles ALTER COLUMN first_seen_at DROP NOT NULL;

CREATE TABLE account_sim_swaps (
  institution_id uuid NOT NULL REFERENCES institutions (id),
  account_token text NOT NULL CHECK (is_token(account_token)),
  swapped_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (institution_id, account_token, swapped_at)
);
COMMENT ON TABLE account_sim_swaps IS
  'SIM swaps reported for an account (D-25, days_since_sim_swap). Append-only.';
CALL make_append_only('account_sim_swaps');
CALL enable_tenant_isolation('account_sim_swaps');

CREATE TABLE account_kyc_tiers (
  institution_id uuid NOT NULL REFERENCES institutions (id),
  account_token text NOT NULL CHECK (is_token(account_token)),
  tier smallint NOT NULL CHECK (tier BETWEEN 0 AND 9),
  effective_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (institution_id, account_token, effective_at)
);
COMMENT ON TABLE account_kyc_tiers IS
  'An account''s KYC tier history, read as of the transaction (ADR 0026). Append-only.';
CALL make_append_only('account_kyc_tiers');
CALL enable_tenant_isolation('account_kyc_tiers');

GRANT SELECT, INSERT ON account_sim_swaps, account_kyc_tiers TO fs_app;
GRANT SELECT ON account_sim_swaps, account_kyc_tiers TO fs_app_readonly;

-- device_first_seen looks a device up across accounts; without this it scans every chunk.
CREATE INDEX transactions_device_time ON transactions (device_token, transaction_timestamp)
  WHERE device_token IS NOT NULL;
-- account lookups by token alone, for the same reason (transactions_account_time leads with the
-- institution).
CREATE INDEX transactions_token_time ON transactions (account_token, transaction_timestamp);

-- The account's own transactions in [p_since, p_before), oldest first, with what the feature rows
-- carry. account_country is the institution's country: the sending account's country is an
-- attribute of the account, and an institution's accounts are domestic to it.
CREATE FUNCTION feature_fallback_transactions(p_account text, p_since timestamptz, p_before timestamptz)
  RETURNS TABLE (transaction_id uuid, transaction_timestamp timestamptz, amount numeric,
                 currency char(3), amount_rwf numeric, latitude numeric, longitude numeric,
                 account_country char(2), counterparty_token text, counterparty_country char(2),
                 channel text, device_token text, agent_token text, merchant_category_code char(4))
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT t.transaction_id, t.transaction_timestamp, t.amount, t.currency, t.amount_rwf,
           t.latitude, t.longitude, i.country, t.counterparty_token, t.counterparty_country,
           t.channel, t.device_token, t.agent_token, t.merchant_category_code
    FROM fraudshield.transactions t JOIN fraudshield.institutions i ON i.id = t.institution_id
    WHERE t.account_token = p_account
      AND t.transaction_timestamp >= p_since AND t.transaction_timestamp < p_before
    ORDER BY t.transaction_timestamp, t.received_at, t.transaction_id
  $$;

-- What the account's durable keys held before p_before: first appearance (the profile's, which
-- survives retention, or the earliest transaction's), the last transaction's time and place, the
-- sets of counterparties, their countries and devices, and the opening date.
CREATE FUNCTION feature_fallback_account(p_account text, p_before timestamptz)
  RETURNS TABLE (first_seen timestamptz, last_at timestamptz, last_latitude numeric,
                 last_longitude numeric, counterparties text[], countries text[], devices text[],
                 opened_at timestamptz)
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    WITH earlier AS (
      SELECT t.transaction_timestamp, t.received_at, t.transaction_id, t.latitude, t.longitude,
             t.counterparty_token, t.counterparty_country, t.device_token
      FROM fraudshield.transactions t
      WHERE t.account_token = p_account AND t.transaction_timestamp < p_before
    ), last AS (
      SELECT * FROM earlier ORDER BY transaction_timestamp DESC, received_at DESC,
                                     transaction_id DESC LIMIT 1
    ), profile AS (
      SELECT min(p.first_seen_at) FILTER (WHERE p.first_seen_at < p_before) AS first_seen,
             min(p.opened_at) AS opened_at
      FROM fraudshield.account_profiles p WHERE p.account_token = p_account
    )
    SELECT (SELECT CASE
                     WHEN profile.first_seen IS NULL THEN min(e.transaction_timestamp)
                     WHEN min(e.transaction_timestamp) IS NULL THEN profile.first_seen
                     ELSE least(profile.first_seen, min(e.transaction_timestamp))
                   END FROM earlier e),
           (SELECT transaction_timestamp FROM last),
           (SELECT latitude FROM last),
           (SELECT longitude FROM last),
           ARRAY(SELECT DISTINCT counterparty_token FROM earlier ORDER BY 1),
           ARRAY(SELECT DISTINCT counterparty_country FROM earlier
                 WHERE counterparty_country IS NOT NULL ORDER BY 1),
           ARRAY(SELECT DISTINCT device_token FROM earlier WHERE device_token IS NOT NULL ORDER BY 1),
           profile.opened_at
    FROM profile
  $$;

CREATE FUNCTION feature_fallback_sim_swaps(p_account text)
  RETURNS SETOF timestamptz
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT swapped_at FROM fraudshield.account_sim_swaps WHERE account_token = p_account
    ORDER BY swapped_at
  $$;

CREATE FUNCTION feature_fallback_kyc_tiers(p_account text)
  RETURNS TABLE (tier smallint, effective_at timestamptz)
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT tier, effective_at FROM fraudshield.account_kyc_tiers WHERE account_token = p_account
    ORDER BY effective_at
  $$;

CREATE FUNCTION feature_fallback_device_first_seen(p_device text, p_before timestamptz)
  RETURNS timestamptz
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT min(transaction_timestamp) FROM fraudshield.transactions
    WHERE device_token = p_device AND transaction_timestamp < p_before
  $$;

DO $$
DECLARE
  fn text;
BEGIN
  FOREACH fn IN ARRAY ARRAY[
    'feature_fallback_transactions(text, timestamptz, timestamptz)',
    'feature_fallback_account(text, timestamptz)',
    'feature_fallback_sim_swaps(text)',
    'feature_fallback_kyc_tiers(text)',
    'feature_fallback_device_first_seen(text, timestamptz)'] LOOP
    EXECUTE format('REVOKE ALL ON FUNCTION fraudshield.%s FROM PUBLIC', fn);
    EXECUTE format('GRANT EXECUTE ON FUNCTION fraudshield.%s TO fs_scorer', fn);
  END LOOP;
END
$$;
