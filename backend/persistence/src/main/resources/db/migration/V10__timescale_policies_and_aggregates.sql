-- Compression, retention and continuous aggregates (D-32, D-49, ADR 0017, ADR 0018).

ALTER TABLE transactions SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'institution_id, account_token',
  timescaledb.compress_orderby = 'transaction_timestamp DESC, transaction_id, id');
SELECT add_compression_policy('transactions', compress_after => interval '30 days');

ALTER TABLE fraud_scores SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'institution_id',
  timescaledb.compress_orderby = 'scored_at DESC, transaction_id, id');
SELECT add_compression_policy('fraud_scores', compress_after => interval '30 days');

ALTER TABLE shadow_scores SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'institution_id, model_version',
  timescaledb.compress_orderby = 'scored_at DESC, id');
SELECT add_compression_policy('shadow_scores', compress_after => interval '7 days');
SELECT add_retention_policy('shadow_scores', drop_after => interval '180 days');

-- D-32: compressed after 30 days, kept 7 years (BNR retention). Anchors keep the chain verifiable
-- from the oldest retained position after old chunks are dropped.
ALTER TABLE audit_events SET (
  timescaledb.compress,
  timescaledb.compress_segmentby = 'writer_partition',
  timescaledb.compress_orderby = 'seq, event_at, id');
SELECT add_compression_policy('audit_events', compress_after => interval '30 days');
SELECT add_retention_policy('audit_events', drop_after => interval '7 years');

-- Hourly activity per account: database fallback for velocity features when Redis is down (C.4).
CREATE MATERIALIZED VIEW account_activity_hourly
  WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
  SELECT institution_id, account_token,
         time_bucket(interval '1 hour', transaction_timestamp) AS bucket,
         count(*) AS transaction_count,
         sum(amount_rwf) AS amount_rwf
  FROM transactions
  GROUP BY institution_id, account_token, bucket
  WITH NO DATA;
SELECT add_continuous_aggregate_policy('account_activity_hourly',
  start_offset => interval '8 days', end_offset => interval '1 hour', schedule_interval => interval '15 minutes');

-- 15-minute volume per MCC and channel for circuit breakers and dashboards (FR-03-07, FR-05-02).
CREATE MATERIALIZED VIEW merchant_activity_15m
  WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
  SELECT institution_id, merchant_category_code, channel,
         time_bucket(interval '15 minutes', transaction_timestamp) AS bucket,
         count(*) AS transaction_count,
         sum(amount_rwf) AS amount_rwf
  FROM transactions
  GROUP BY institution_id, merchant_category_code, channel, bucket
  WITH NO DATA;
SELECT add_continuous_aggregate_policy('merchant_activity_15m',
  start_offset => interval '2 days', end_offset => interval '15 minutes', schedule_interval => interval '5 minutes');

-- Continuous aggregates cannot carry row-level security, so applications read them only through these
-- security-barrier views, which filter by the current institution exactly like the tenant policy.
CREATE VIEW v_account_activity_hourly WITH (security_barrier = true) AS
  SELECT * FROM account_activity_hourly WHERE institution_id = current_institution();
CREATE VIEW v_merchant_activity_15m WITH (security_barrier = true) AS
  SELECT * FROM merchant_activity_15m WHERE institution_id = current_institution();
