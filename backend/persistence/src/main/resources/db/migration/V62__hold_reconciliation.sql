-- Reconciliation of overdue MEDIUM holds (E.6, D-14, ADR 0064).
--
-- The deadline scheduler is a Redis sorted set. If Redis fails over empty, or the process dies
-- between recording a HOLD and scheduling it, the hold would never time out. A leader-run sweep
-- times out every persisted HOLD whose deadline passed with no later state. Row-level security
-- scopes fs_app to one institution per transaction, so the sweep first asks which institutions
-- have such holds: this function returns institution ids only, never a row's content, in the
-- pattern of the auth_find_* functions (V2).

CREATE INDEX decision_states_open_holds ON decision_states (review_deadline_at)
  WHERE decision = 'HOLD';

CREATE FUNCTION institutions_with_overdue_holds(p_before timestamptz)
  RETURNS SETOF uuid
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT DISTINCT h.institution_id FROM fraudshield.decision_states h
    WHERE h.decision = 'HOLD' AND h.review_deadline_at < p_before
      AND NOT EXISTS (SELECT 1 FROM fraudshield.decision_states n
                      WHERE n.institution_id = h.institution_id
                        AND n.transaction_id = h.transaction_id
                        AND n.decision_sequence > 1)
  $$;
REVOKE ALL ON FUNCTION institutions_with_overdue_holds(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION institutions_with_overdue_holds(timestamptz) TO fs_app;
