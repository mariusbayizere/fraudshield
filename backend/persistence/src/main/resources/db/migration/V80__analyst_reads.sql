-- What P1 of the analyst API reads (M8B, ADR 0112).
--
-- The explanation (getAlertExplanation) needs the SHAP base value and the final margin. The scorer
-- returns both (scoring.proto fields 12 and 13); nothing kept them. They are NULL for decisions
-- below 0.60 (no SHAP), for the rule-based fallback, and for every row scored before this
-- migration, and an explanation is then answered 404. fraud_scores is a compressed hypertable
-- (V10), so the columns carry no CHECK constraint, as V66's did not.
--
-- getAccountNetwork reads an account's counterparties in both directions; transactions is indexed
-- by account only (V3), so the counterparty direction gets its own index.

ALTER TABLE fraud_scores ADD COLUMN shap_base_value double precision;
ALTER TABLE fraud_scores ADD COLUMN final_margin double precision;

CREATE OR REPLACE VIEW fraudshield.v_fraud_scores WITH (security_barrier = true) AS
  SELECT * FROM fraudshield.fraud_scores WHERE institution_id = fraudshield.current_institution();

CREATE INDEX transactions_counterparty_time
  ON transactions (institution_id, counterparty_token, transaction_timestamp DESC);
