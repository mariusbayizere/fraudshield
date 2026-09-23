-- Reconciliation of unblocks whose decision transition did not happen (E.7, D-25, ADR 0065).
--
-- The customer's answer and its unblock_events row commit before the decision transition runs.
-- If that call fails (the spool is full or its outcome is unknown, Redis refuses the state write),
-- the block is recorded as lifted while the decision stays DECLINE, with no webhook and no label,
-- and the customer cannot answer a second time: the token is used. A leader-run sweep applies the
-- transition afterwards. Row-level security scopes fs_app to one institution per transaction, so
-- the sweep first asks which institutions have such unblocks: this function returns institution
-- ids only, never a row's content, in the pattern of institutions_with_overdue_holds (V62).

CREATE INDEX unblock_events_recent ON unblock_events (unblocked_at);

CREATE FUNCTION institutions_with_unapplied_unblocks(p_before timestamptz)
  RETURNS SETOF uuid
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT DISTINCT u.institution_id FROM fraudshield.unblock_events u
    JOIN fraudshield.auto_block_events b ON b.id = u.auto_block_event_id
    WHERE u.cause = 'CUSTOMER_VERIFICATION' AND u.unblocked_at < p_before
      AND NOT EXISTS (SELECT 1 FROM fraudshield.decision_states s
                      WHERE s.institution_id = u.institution_id
                        AND s.transaction_id = b.transaction_id
                        AND s.decided_by = 'CUSTOMER_VERIFICATION')
  $$;
REVOKE ALL ON FUNCTION institutions_with_unapplied_unblocks(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION institutions_with_unapplied_unblocks(timestamptz) TO fs_app;
