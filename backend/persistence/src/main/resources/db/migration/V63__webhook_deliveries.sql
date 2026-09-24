-- decision.final webhook deliveries (D-14, E.1, ADR 0066).
--
-- One row per decision state sent to an institution. At most one delivery per transaction is
-- pending: a newer state supersedes a pending older one, which is then never retried, so a failing
-- old delivery cannot delay a newer decision (contracts/webhooks/decision-final.md). Retries back
-- off exponentially with full jitter from 30 seconds for up to 24 hours; a delivery that exhausts
-- them is DEAD_LETTERED and listed for administrators (GET /admin/webhook-deliveries). The body is
-- the exact bytes signed, kept as text so a retry sends the same body.

CREATE TABLE webhook_deliveries (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  transaction_id uuid NOT NULL,
  event_id uuid NOT NULL UNIQUE,
  decision_sequence integer NOT NULL CHECK (decision_sequence >= 2),
  body text NOT NULL CHECK (char_length(body) BETWEEN 2 AND 8192),
  state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'DELIVERED', 'SUPERSEDED', 'DEAD_LETTERED')),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  first_attempt_at timestamptz,
  next_attempt_at timestamptz,
  last_status_code integer CHECK (last_status_code BETWEEN 100 AND 599),
  last_error text CHECK (char_length(last_error) <= 200),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((state = 'PENDING') = (next_attempt_at IS NOT NULL))
);
CREATE UNIQUE INDEX webhook_deliveries_one_pending ON webhook_deliveries (institution_id, transaction_id)
  WHERE state = 'PENDING';
CREATE INDEX webhook_deliveries_due ON webhook_deliveries (next_attempt_at) WHERE state = 'PENDING';
CREATE INDEX webhook_deliveries_dead ON webhook_deliveries (institution_id, updated_at DESC)
  WHERE state = 'DEAD_LETTERED';
CREATE TRIGGER webhook_deliveries_updated_at BEFORE UPDATE ON webhook_deliveries
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CALL enable_tenant_isolation('webhook_deliveries');

-- The dispatcher serves every institution; row-level security scopes each transaction to one, so
-- this names the due deliveries (ids only) and the dispatcher then locks each under its tenant.
CREATE FUNCTION webhook_deliveries_due(p_now timestamptz, p_limit integer)
  RETURNS TABLE (delivery_id uuid, institution_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT id, institution_id FROM fraudshield.webhook_deliveries
    WHERE state = 'PENDING' AND next_attempt_at <= p_now
    ORDER BY next_attempt_at LIMIT p_limit
  $$;
REVOKE ALL ON FUNCTION webhook_deliveries_due(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION webhook_deliveries_due(timestamptz, integer) TO fs_app;

GRANT SELECT, INSERT, UPDATE ON webhook_deliveries TO fs_app;
GRANT SELECT ON webhook_deliveries TO fs_app_readonly;
