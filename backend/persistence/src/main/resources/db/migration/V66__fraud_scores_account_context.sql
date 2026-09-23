-- What the scorer read, kept with the score (ADR 0033, ADR 0061 point 7).
--
-- Since ADR 0033 the scorer reads the account's context from the feature store itself and returns
-- it in ScoringResult.account_context (18), with feature_store_degraded (19) true when it answered
-- from the database fallback or with the state unknown (C.4's DEGRADED_MODE). Both are persisted
-- with the score so that every decision can be audited and replayed against exactly the state it
-- saw. account_context is the message rendered as a JSON object with the proto field names; it is
-- NULL for rule-based fallback decisions and for a scorer that returned no context.
--
-- fraud_scores is a compressed hypertable (V10), so the columns carry no CHECK constraint; the
-- writer validates the shape. The tenant view was created with SELECT *, whose column list is fixed
-- when the view is created, so it is replaced to include them.

ALTER TABLE fraud_scores ADD COLUMN account_context jsonb;
ALTER TABLE fraud_scores ADD COLUMN feature_store_degraded boolean NOT NULL DEFAULT false;

CREATE OR REPLACE VIEW fraudshield.v_fraud_scores WITH (security_barrier = true) AS
  SELECT * FROM fraudshield.fraud_scores WHERE institution_id = fraudshield.current_institution();
