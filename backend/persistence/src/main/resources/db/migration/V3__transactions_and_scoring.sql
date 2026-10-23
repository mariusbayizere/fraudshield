-- Transactions, idempotency, scoring and decision states (FR-01, FR-02, D-04, D-12, D-14).

CREATE FUNCTION is_token(p_value text) RETURNS boolean
  LANGUAGE sql IMMUTABLE PARALLEL SAFE
  AS $$ SELECT p_value ~ '^tok_[A-Za-z0-9]{24,64}$' $$;

-- Idempotency record (FR-01-03). A hypertable cannot enforce uniqueness of transaction_id alone, so
-- this regular table does, and also serves as the database fallback for idempotency (C.4).
CREATE TABLE transaction_ids (
  institution_id uuid NOT NULL REFERENCES institutions (id),
  transaction_id uuid NOT NULL,
  request_fingerprint bytea NOT NULL CHECK (octet_length(request_fingerprint) = 32),
  transaction_timestamp timestamptz NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (institution_id, transaction_id)
);
CALL make_append_only('transaction_ids');
CALL enable_tenant_isolation('transaction_ids');

CREATE TABLE transactions (
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  transaction_id uuid NOT NULL,
  account_token text NOT NULL CHECK (is_token(account_token)),
  counterparty_token text NOT NULL CHECK (is_token(counterparty_token)),
  amount numeric(18, 4) NOT NULL CHECK (amount > 0),
  currency char(3) NOT NULL CHECK (currency IN ('RWF', 'KES', 'TZS', 'UGX', 'CDF', 'BIF', 'SSP', 'SOS', 'USD', 'EUR')),
  amount_rwf numeric(18, 4) NOT NULL CHECK (amount_rwf > 0),
  channel text NOT NULL CHECK (channel IN ('MOBILE_MONEY', 'CARD', 'AGENT_BANKING', 'USSD', 'ONLINE', 'BANK_TRANSFER')),
  merchant_category_code char(4) NOT NULL CHECK (merchant_category_code ~ '^[0-9]{4}$'),
  merchant_name text CHECK (char_length(merchant_name) <= 100),
  latitude numeric(9, 6) NOT NULL CHECK (latitude BETWEEN -90 AND 90),
  longitude numeric(9, 6) NOT NULL CHECK (longitude BETWEEN -180 AND 180),
  device_token text CHECK (is_token(device_token)),
  agent_token text CHECK (is_token(agent_token)),
  counterparty_country char(2) CHECK (counterparty_country ~ '^[A-Z]{2}$'),
  transaction_timestamp timestamptz NOT NULL,
  received_at timestamptz NOT NULL,
  processing_duration_ms integer CHECK (processing_duration_ms >= 0),
  PRIMARY KEY (transaction_timestamp, id),
  UNIQUE (institution_id, transaction_id, transaction_timestamp),
  CHECK (channel <> 'AGENT_BANKING' OR agent_token IS NOT NULL),
  CHECK (transaction_timestamp <= received_at + interval '5 minutes')
);
SELECT create_hypertable('transactions', by_range('transaction_timestamp', interval '1 month'));
CREATE INDEX transactions_account_time ON transactions (institution_id, account_token, transaction_timestamp DESC);
CALL make_append_only('transactions');
CALL enable_tenant_view_isolation('transactions');

-- FR-02-01. Hypertables cannot be referenced by foreign keys, so transaction_id links by value.
CREATE TABLE fraud_scores (
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  transaction_id uuid NOT NULL,
  scored_at timestamptz NOT NULL,
  ensemble_score double precision NOT NULL CHECK (ensemble_score BETWEEN 0 AND 1),
  xgboost_score double precision NOT NULL CHECK (xgboost_score BETWEEN 0 AND 1),
  lightgbm_score double precision NOT NULL CHECK (lightgbm_score BETWEEN 0 AND 1),
  anomaly_score double precision NOT NULL CHECK (anomaly_score BETWEEN 0 AND 1),
  anomaly_raw double precision NOT NULL,
  risk_tier text NOT NULL CHECK (risk_tier IN ('HIGH', 'MEDIUM', 'LOW')),
  shap_top5 jsonb CHECK (jsonb_typeof(shap_top5) = 'array' AND jsonb_array_length(shap_top5) <= 5),
  shap_all jsonb CHECK (jsonb_typeof(shap_all) = 'array' AND jsonb_array_length(shap_all) = 44),
  feature_vector jsonb NOT NULL CHECK (jsonb_typeof(feature_vector) = 'object'),
  model_version text NOT NULL CHECK (char_length(model_version) BETWEEN 1 AND 100),
  feature_registry_version text NOT NULL CHECK (char_length(feature_registry_version) BETWEEN 1 AND 100),
  scoring_duration_ms integer NOT NULL CHECK (scoring_duration_ms >= 0),
  requires_analyst_review boolean NOT NULL,
  ml_unavailable_fallback boolean NOT NULL DEFAULT false,
  trace_id text CHECK (trace_id ~ '^[0-9a-f]{32}$'),
  PRIMARY KEY (scored_at, id),
  UNIQUE (institution_id, transaction_id, scored_at),
  CHECK (risk_tier = 'LOW' OR shap_top5 IS NOT NULL OR ml_unavailable_fallback)
);
SELECT create_hypertable('fraud_scores', by_range('scored_at', interval '1 month'));
CREATE INDEX fraud_scores_transaction ON fraud_scores (institution_id, transaction_id);
CALL make_append_only('fraud_scores');
CALL enable_tenant_view_isolation('fraud_scores');

CREATE TABLE shadow_scores (
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  transaction_id uuid NOT NULL,
  model_version text NOT NULL CHECK (char_length(model_version) BETWEEN 1 AND 100),
  ensemble_score double precision NOT NULL CHECK (ensemble_score BETWEEN 0 AND 1),
  risk_tier text NOT NULL CHECK (risk_tier IN ('HIGH', 'MEDIUM', 'LOW')),
  scored_at timestamptz NOT NULL,
  PRIMARY KEY (scored_at, id)
);
SELECT create_hypertable('shadow_scores', by_range('scored_at', interval '1 month'));
CALL make_append_only('shadow_scores');
CALL enable_tenant_view_isolation('shadow_scores');

-- Decision states per transaction (D-14, ADR 0011 section 9): append-only, ordered by sequence.
CREATE TABLE decision_states (
  event_id uuid NOT NULL UNIQUE,
  institution_id uuid NOT NULL REFERENCES institutions (id),
  transaction_id uuid NOT NULL,
  decision_sequence integer NOT NULL CHECK (decision_sequence >= 1),
  decision text NOT NULL CHECK (decision IN ('APPROVE', 'DECLINE', 'HOLD', 'TIMEOUT_RELEASE')),
  final boolean NOT NULL,
  decided_at timestamptz NOT NULL,
  decided_by text NOT NULL CHECK (decided_by IN ('MODEL', 'ANALYST', 'TIMEOUT_POLICY', 'CUSTOMER_VERIFICATION', 'SENIOR_OVERRIDE')),
  reason_codes text[] NOT NULL DEFAULT '{}' CHECK (cardinality(reason_codes) <= 3),
  review_deadline_at timestamptz,
  supersedes_decision text CHECK (supersedes_decision IN ('APPROVE', 'DECLINE', 'HOLD', 'TIMEOUT_RELEASE')),
  PRIMARY KEY (institution_id, transaction_id, decision_sequence),
  FOREIGN KEY (institution_id, transaction_id) REFERENCES transaction_ids (institution_id, transaction_id),
  CHECK ((decision = 'HOLD') = (NOT final)),
  CHECK ((decision = 'HOLD') = (review_deadline_at IS NOT NULL)),
  CHECK ((decision_sequence = 1) = (decided_by = 'MODEL')),
  CHECK ((decision_sequence = 1) = (supersedes_decision IS NULL)),
  CHECK (decision_sequence = 1 OR decision <> 'HOLD')
);
CALL make_append_only('decision_states');
CALL enable_tenant_isolation('decision_states');

-- Database fallback for velocity features when Redis is unavailable (C.4). Mutable cache.
CREATE TABLE account_velocity_cache (
  institution_id uuid NOT NULL REFERENCES institutions (id),
  account_token text NOT NULL CHECK (is_token(account_token)),
  tx_count_60s integer NOT NULL DEFAULT 0 CHECK (tx_count_60s >= 0),
  tx_count_1h integer NOT NULL DEFAULT 0 CHECK (tx_count_1h >= tx_count_60s),
  tx_count_24h integer NOT NULL DEFAULT 0 CHECK (tx_count_24h >= tx_count_1h),
  tx_count_7d integer NOT NULL DEFAULT 0 CHECK (tx_count_7d >= tx_count_24h),
  amount_sum_24h_rwf numeric(18, 4) NOT NULL DEFAULT 0 CHECK (amount_sum_24h_rwf >= 0),
  amount_sum_7d_rwf numeric(18, 4) NOT NULL DEFAULT 0 CHECK (amount_sum_7d_rwf >= amount_sum_24h_rwf),
  unique_counterparties_24h integer NOT NULL DEFAULT 0 CHECK (unique_counterparties_24h >= 0),
  last_tx_latitude numeric(9, 6) CHECK (last_tx_latitude BETWEEN -90 AND 90),
  last_tx_longitude numeric(9, 6) CHECK (last_tx_longitude BETWEEN -180 AND 180),
  last_tx_channel text CHECK (last_tx_channel IN ('MOBILE_MONEY', 'CARD', 'AGENT_BANKING', 'USSD', 'ONLINE', 'BANK_TRANSFER')),
  last_tx_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (institution_id, account_token)
);
CREATE INDEX account_velocity_cache_stale ON account_velocity_cache (updated_at);
CREATE TRIGGER account_velocity_cache_updated_at BEFORE UPDATE ON account_velocity_cache
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CALL enable_tenant_isolation('account_velocity_cache');

CREATE TABLE batch_jobs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  api_key_id uuid NOT NULL,
  state text NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
  total integer NOT NULL CHECK (total BETWEEN 1 AND 1000),
  processed integer NOT NULL DEFAULT 0 CHECK (processed BETWEEN 0 AND total),
  failed integer NOT NULL DEFAULT 0 CHECK (failed BETWEEN 0 AND processed),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  FOREIGN KEY (api_key_id, institution_id) REFERENCES api_keys (id, institution_id),
  CHECK ((state IN ('COMPLETED', 'FAILED')) = (completed_at IS NOT NULL))
);
CREATE UNIQUE INDEX batch_jobs_id_institution ON batch_jobs (id, institution_id);
CREATE TRIGGER batch_jobs_updated_at BEFORE UPDATE ON batch_jobs FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CALL enable_tenant_isolation('batch_jobs');

CREATE TABLE batch_job_items (
  job_id uuid NOT NULL,
  institution_id uuid NOT NULL,
  position integer NOT NULL CHECK (position BETWEEN 0 AND 999),
  transaction_id uuid NOT NULL,
  outcome text NOT NULL CHECK (outcome IN ('DECIDED', 'REJECTED')),
  problem jsonb CHECK (jsonb_typeof(problem) = 'object'),
  recorded_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (job_id, position),
  FOREIGN KEY (job_id, institution_id) REFERENCES batch_jobs (id, institution_id),
  CHECK ((outcome = 'REJECTED') = (problem IS NOT NULL))
);
CALL make_append_only('batch_job_items');
CALL enable_tenant_isolation('batch_job_items');
