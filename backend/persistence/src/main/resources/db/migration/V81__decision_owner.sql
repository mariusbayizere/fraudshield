-- One owner per transaction's decision (docs/architecture/decision-owner-contract.md, ADR 0114).
--
-- Every later change to a transaction's decision is a request to one row, decision_owners, evaluated
-- under that row's lock by DecisionOwner (Java). This migration adds the owner, the record of every
-- request after sequence 1, the analyst decision's timing columns and triggers, the contest and its
-- operator exits, and the listers the cross-institution workers need. It is deployed by a
-- stop-and-drain cutover (contract A3): no spool writer runs while it does.

-- Java's UUID.nameUUIDFromBytes (MD5, version 3) of a UTF-8 name, so that SQL derives the same
-- request ids as DecisionOwner (contract §4.5). DecisionOwnerIdsTest pins that the two agree.
CREATE FUNCTION fs_name_uuid(p_name text) RETURNS uuid
  LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT (substr(h, 1, 12) || '3' || substr(h, 14, 3)
            || substr('89ab', (('x' || substr(h, 17, 1))::bit(4)::int & 3) + 1, 1)
            || substr(h, 18))::uuid
    FROM (SELECT md5(convert_to(p_name, 'UTF8')) AS h) AS m
  $$;
GRANT EXECUTE ON FUNCTION fs_name_uuid(text) TO fs_app, fs_app_readonly, fs_compliance_ro;

-- §9: the timeout policy's own decision snapshot, written by the sink for sequence 1 from V81 on.
-- Nullable: rows before V81 never had it, and the backfill derives theirs from risk_thresholds.
ALTER TABLE decision_states ADD COLUMN timeout_policy text
  CHECK (timeout_policy IN ('RELEASE_WITH_TIMEOUT_LABEL', 'DECLINE_AND_VERIFY'));

-- §4.4: an override's label names the label it supersedes, when that label was recorded (§5).
ALTER TABLE label_events ADD COLUMN supersedes_label_id uuid;

-- Alert entries (§5, §7.2): a timeout under DECLINE_AND_VERIFY closes as AUTO_DECLINED, and a
-- resolution names itself on the entries it closes.
ALTER TABLE alert_queue_entries DROP CONSTRAINT alert_queue_entries_status_check;
ALTER TABLE alert_queue_entries ADD CONSTRAINT alert_queue_entries_status_check CHECK (status IN (
  'PENDING', 'IN_REVIEW', 'CONFIRMED_FRAUD', 'MARKED_LEGITIMATE', 'AUTO_RELEASED', 'AUTO_DECLINED',
  'ESCALATED', 'OVERRIDDEN'));
ALTER TABLE alert_queue_entries ADD COLUMN resolution_id uuid;

-- Analyst decisions (§9): written once, at insert. Existing rows predate P2 and are AUTO_* only.
ALTER TABLE alert_decisions
  ADD COLUMN undo_until timestamptz,
  ADD COLUMN prior_status text CHECK (prior_status IN ('PENDING', 'IN_REVIEW')),
  ADD COLUMN alert_version bigint CHECK (alert_version >= 0),
  ADD COLUMN request_fingerprint bytea CHECK (octet_length(request_fingerprint) = 32),
  ADD COLUMN override_decision text CHECK (override_decision IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE')),
  ADD COLUMN escalated_to_alert_id uuid;
ALTER TABLE alert_decisions ADD CONSTRAINT alert_decisions_override_decision
  CHECK ((decision = 'SENIOR_OVERRIDE') = (override_decision IS NOT NULL)) NOT VALID;
ALTER TABLE alert_decisions ADD CONSTRAINT alert_decisions_escalated_to
  CHECK ((decision = 'ESCALATE') = (escalated_to_alert_id IS NOT NULL)) NOT VALID;
ALTER TABLE alert_decisions ADD FOREIGN KEY (escalated_to_alert_id, institution_id)
  REFERENCES alert_queue_entries (id, institution_id);

-- DB time, the undo window, and one live analyst decision per entry (§9's backstop). The entry's
-- advisory lock is lock level 2 (§3), taken here re-entrantly after D1 took it explicitly.
CREATE FUNCTION alert_decision_timing() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
BEGIN
  NEW.created_at := clock_timestamp();
  IF NEW.decision IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE') THEN
    NEW.undo_until := NEW.created_at + interval '5 seconds';
    PERFORM pg_advisory_xact_lock(hashtextextended('alert_entry:' || NEW.alert_queue_entry_id::text, 0));
    IF EXISTS (SELECT 1 FROM fraudshield.alert_decisions d
               WHERE d.alert_queue_entry_id = NEW.alert_queue_entry_id
                 AND d.decision IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE')
                 AND NOT EXISTS (SELECT 1 FROM fraudshield.alert_decision_undos u
                                 WHERE u.decision_id = d.id)) THEN
      RAISE EXCEPTION 'entry % already has a live analyst decision', NEW.alert_queue_entry_id
        USING ERRCODE = 'unique_violation';
    END IF;
  ELSE
    NEW.undo_until := NULL;
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER alert_decisions_timing BEFORE INSERT ON alert_decisions
  FOR EACH ROW EXECUTE FUNCTION alert_decision_timing();

-- A commit is refused before the window closes; an undo after it, or of anything but an analyst
-- decision. Both stamp DB time. They run after V4's forbid_commit_and_undo (alphabetical order of
-- trigger names: "..._exclusive" before "..._window"), which takes the decision's lock, level 3.
CREATE FUNCTION alert_decision_commit_window() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  until timestamptz;
BEGIN
  NEW.committed_at := clock_timestamp();
  SELECT d.undo_until INTO until FROM fraudshield.alert_decisions d WHERE d.id = NEW.decision_id;
  IF until IS NULL THEN
    RAISE EXCEPTION 'decision % is committed on insert and takes no commit row', NEW.decision_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.committed_at < until THEN
    RAISE EXCEPTION 'decision % is still in its undo window', NEW.decision_id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER alert_decision_commits_window BEFORE INSERT ON alert_decision_commits
  FOR EACH ROW EXECUTE FUNCTION alert_decision_commit_window();

CREATE FUNCTION alert_decision_undo_window() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  until timestamptz;
BEGIN
  NEW.undone_at := clock_timestamp();
  SELECT d.undo_until INTO until FROM fraudshield.alert_decisions d WHERE d.id = NEW.decision_id;
  IF until IS NULL OR NEW.undone_at >= until THEN
    RAISE EXCEPTION 'decision % can no longer be undone', NEW.decision_id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER alert_decision_undos_window BEFORE INSERT ON alert_decision_undos
  FOR EACH ROW EXECUTE FUNCTION alert_decision_undo_window();

-- COMMITTED: a commit row, or a kind that is committed on insert (undo_until IS NULL). Recreated,
-- not replaced, because alert_decisions gained columns and d.* moves the view's own columns.
DROP VIEW v_alert_decision_state;
CREATE VIEW v_alert_decision_state WITH (security_invoker = true) AS
  SELECT d.*,
         CASE WHEN c.decision_id IS NOT NULL OR d.undo_until IS NULL THEN 'COMMITTED'
              WHEN u.decision_id IS NOT NULL THEN 'UNDONE'
              ELSE 'PENDING_COMMIT' END AS state,
         coalesce(c.committed_at, CASE WHEN d.undo_until IS NULL THEN d.created_at END) AS committed_at,
         u.undone_at, u.undone_by
  FROM alert_decisions d
  LEFT JOIN alert_decision_commits c ON c.decision_id = d.id
  LEFT JOIN alert_decision_undos u ON u.decision_id = d.id;
GRANT SELECT ON v_alert_decision_state TO fs_app, fs_app_readonly, fs_compliance_ro;

-- Effects of committed analyst decisions and overrides (D4), and the breaker's own record (E2).
CREATE TABLE alert_decision_effects (
  decision_id uuid PRIMARY KEY,
  institution_id uuid NOT NULL,
  outcome text NOT NULL CHECK (outcome IN ('APPLIED', 'LABEL_ONLY', 'SKIPPED', 'FAILED')),
  reason text CHECK (char_length(reason) <= 200),
  sqlstate text CHECK (sqlstate ~ '^[0-9A-Z]{5}$'),
  done_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  FOREIGN KEY (decision_id, institution_id) REFERENCES alert_decisions (id, institution_id),
  CHECK ((outcome = 'FAILED') = (sqlstate IS NOT NULL))
);
CALL make_append_only('alert_decision_effects');
CALL enable_tenant_isolation('alert_decision_effects');

CREATE TABLE alert_decision_breaker_counts (
  decision_id uuid PRIMARY KEY,
  institution_id uuid NOT NULL,
  counted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  FOREIGN KEY (decision_id, institution_id) REFERENCES alert_decisions (id, institution_id)
);
CALL make_append_only('alert_decision_breaker_counts');
CALL enable_tenant_isolation('alert_decision_breaker_counts');

-- §4.1: the owner. Mutable, one row per transaction any later request touches. No foreign key to
-- transaction_ids: a timeout may create it from its DueHold before the sink has run (§4.2).
CREATE TABLE decision_owners (
  institution_id uuid NOT NULL REFERENCES institutions (id),
  transaction_id uuid NOT NULL,
  first_event_id uuid NOT NULL,
  first_decision text NOT NULL CHECK (first_decision IN ('APPROVE', 'DECLINE', 'HOLD')),
  first_deadline timestamptz,
  timeout_policy text CHECK (timeout_policy IN ('RELEASE_WITH_TIMEOUT_LABEL', 'DECLINE_AND_VERIFY')),
  sequence integer NOT NULL CHECK (sequence >= 1),
  decision text NOT NULL CHECK (decision IN ('APPROVE', 'DECLINE', 'HOLD', 'TIMEOUT_RELEASE')),
  decided_by text NOT NULL CHECK (decided_by IN (
    'MODEL', 'ANALYST', 'TIMEOUT_POLICY', 'CUSTOMER_VERIFICATION', 'SENIOR_OVERRIDE')),
  claim text NOT NULL DEFAULT 'NONE' CHECK (claim IN ('NONE', 'ANALYST', 'ESCALATED')),
  claim_decision_id uuid,
  deadline timestamptz,
  prior_claim text CHECK (prior_claim IN ('NONE', 'ESCALATED')),
  prior_deadline timestamptz,
  contested_since timestamptz,
  resolution_id uuid,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (institution_id, transaction_id),
  CHECK ((claim_decision_id IS NOT NULL) = (claim = 'ANALYST')),
  CHECK (decision <> 'HOLD' OR sequence = 1),
  CHECK ((decision <> 'HOLD' AND claim <> 'ESCALATED') OR deadline IS NOT NULL),
  CHECK ((first_decision = 'HOLD') = (first_deadline IS NOT NULL))
);
CREATE INDEX decision_owners_overdue ON decision_owners (deadline)
  WHERE decision = 'HOLD' AND contested_since IS NULL;
CREATE INDEX decision_owners_contested ON decision_owners (contested_since)
  WHERE contested_since IS NOT NULL;
CREATE TRIGGER decision_owners_updated_at BEFORE UPDATE ON decision_owners
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CALL enable_tenant_isolation('decision_owners');

-- §4.4: the record of every request after sequence 1. APPLIED rows are the only record of every
-- state after the first; the view below joins them with sequence 1.
CREATE TABLE transaction_requests (
  institution_id uuid NOT NULL REFERENCES institutions (id),
  transaction_id uuid NOT NULL,
  request_id uuid NOT NULL UNIQUE,
  kind text NOT NULL CHECK (kind IN ('TIMEOUT', 'EFFECT', 'OVERRIDE', 'CUSTOMER_YES', 'CUSTOMER_NO',
    'RESOLVE', 'RELEASE_FAILED', 'BACKFILL')),
  outcome text NOT NULL CHECK (outcome IN ('APPLIED', 'LABEL_ONLY', 'REFUSED', 'RELEASED', 'PRE_V81')),
  reason text CHECK (char_length(reason) <= 200),
  sequence integer CHECK (sequence >= 2),
  decision text CHECK (decision IN ('APPROVE', 'DECLINE', 'TIMEOUT_RELEASE')),
  supersedes_decision text CHECK (supersedes_decision IN ('APPROVE', 'DECLINE', 'HOLD', 'TIMEOUT_RELEASE')),
  reason_codes text[] CHECK (cardinality(reason_codes) <= 3),
  event_id uuid UNIQUE,
  decided_by text CHECK (decided_by IN (
    'ANALYST', 'TIMEOUT_POLICY', 'CUSTOMER_VERIFICATION', 'SENIOR_OVERRIDE')),
  decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  event_body jsonb,
  UNIQUE (institution_id, transaction_id, sequence),
  CHECK ((outcome = 'APPLIED') = (sequence IS NOT NULL)),
  CHECK ((outcome = 'APPLIED') = (decision IS NOT NULL AND supersedes_decision IS NOT NULL
    AND reason_codes IS NOT NULL AND decided_by IS NOT NULL)),
  CHECK ((outcome IN ('APPLIED', 'LABEL_ONLY')) = (event_id IS NOT NULL AND event_body IS NOT NULL))
);
CREATE INDEX transaction_requests_transaction ON transaction_requests (institution_id, transaction_id);
CALL make_append_only('transaction_requests');
CALL enable_tenant_isolation('transaction_requests');

CREATE TABLE transaction_request_recordings (
  event_id uuid PRIMARY KEY,
  institution_id uuid NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CALL make_append_only('transaction_request_recordings');
CALL enable_tenant_isolation('transaction_request_recordings');

-- §7.1: first decisions the owner did not stand on.
CREATE TABLE decision_conflicts (
  institution_id uuid NOT NULL REFERENCES institutions (id),
  transaction_id uuid NOT NULL,
  event_id uuid NOT NULL,
  decision text NOT NULL CHECK (decision IN ('APPROVE', 'DECLINE', 'HOLD')),
  review_deadline_at timestamptz,
  detected_by text NOT NULL CHECK (detected_by IN ('SINK', 'DUE_HOLD')),
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (institution_id, transaction_id, event_id)
);
CALL make_append_only('decision_conflicts');
CALL enable_tenant_isolation('decision_conflicts');

-- §7.2: what an operator authorised. The resolver applies each through DecisionOwner.
CREATE TABLE decision_conflict_resolutions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  transaction_id uuid NOT NULL,
  kind text NOT NULL CHECK (kind IN ('RESOLVE', 'RELEASE_FAILED')),
  decision_id uuid,
  chosen_decision text CHECK (chosen_decision IN ('APPROVE', 'DECLINE')),
  contested_since timestamptz,
  staff_user_id uuid NOT NULL,
  db_login text NOT NULL,
  reason text NOT NULL CHECK (char_length(reason) BETWEEN 20 AND 2000),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  FOREIGN KEY (staff_user_id, institution_id) REFERENCES users (id, institution_id),
  CHECK ((kind = 'RESOLVE') = (chosen_decision IS NOT NULL AND contested_since IS NOT NULL)),
  CHECK ((kind = 'RELEASE_FAILED') = (decision_id IS NOT NULL))
);
CREATE UNIQUE INDEX decision_conflict_resolutions_id_institution
  ON decision_conflict_resolutions (id, institution_id);
CALL make_append_only('decision_conflict_resolutions');
CALL enable_tenant_isolation('decision_conflict_resolutions');

-- §4.7: what every reader of a transaction's states reads. security_invoker, so it isolates exactly
-- as its two row-level-secured tables do.
CREATE VIEW v_decision_states WITH (security_invoker = true) AS
  SELECT s.event_id, s.institution_id, s.transaction_id, s.decision_sequence, s.decision, s.final,
         s.decided_at, s.decided_by, s.reason_codes, s.review_deadline_at, s.supersedes_decision
  FROM decision_states s
  WHERE s.decision_sequence = 1
  UNION ALL
  SELECT r.event_id, r.institution_id, r.transaction_id, r.sequence, r.decision, true,
         r.decided_at, r.decided_by, r.reason_codes, NULL::timestamptz, r.supersedes_decision
  FROM transaction_requests r
  WHERE r.outcome = 'APPLIED';

-- §5: whether an auto-blocked transaction is blocked now, beside when it was first lifted.
CREATE OR REPLACE VIEW v_auto_block_status WITH (security_invoker = true) AS
  SELECT b.id AS auto_block_event_id, b.institution_id, b.transaction_id, b.account_token, b.blocked_at,
         EXISTS (SELECT 1 FROM customer_notifications n
                 WHERE n.auto_block_event_id = b.id AND n.event IN ('SENT', 'DELIVERED')) AS customer_sms_sent,
         r.answer = 'WAS_ME' AS customer_verified,
         r.responded_at AS verified_at,
         u.unblocked_at,
         f.event = 'FROZEN' AS account_frozen,
         f.occurred_at AS account_frozen_changed_at,
         coalesce((SELECT l.decision FROM v_decision_states l
                   WHERE l.institution_id = b.institution_id AND l.transaction_id = b.transaction_id
                   ORDER BY l.decision_sequence DESC LIMIT 1) = 'DECLINE', true) AS currently_blocked
  FROM auto_block_events b
  LEFT JOIN customer_verifications v ON v.auto_block_event_id = b.id
  LEFT JOIN customer_verification_responses r ON r.verification_id = v.id
  LEFT JOIN unblock_events u ON u.auto_block_event_id = b.id
  LEFT JOIN LATERAL (
    SELECT e.event, e.occurred_at FROM account_freeze_events e
    WHERE e.institution_id = b.institution_id AND e.account_token = b.account_token
    ORDER BY e.occurred_at DESC LIMIT 1) f ON true;

-- §4.4 and §6.4: the backfill. A3 has stopped every writer, so nothing moves under it. This runs as
-- the table owner, which row-level security does not restrict.
INSERT INTO transaction_requests (institution_id, transaction_id, request_id, kind, outcome, sequence,
    decision, supersedes_decision, reason_codes, event_id, decided_by, decided_at, event_body)
  SELECT s.institution_id, s.transaction_id, fs_name_uuid('backfill|' || s.event_id::text), 'BACKFILL',
         'APPLIED', s.decision_sequence, s.decision, s.supersedes_decision, s.reason_codes, s.event_id,
         s.decided_by, s.decided_at,
         jsonb_build_object('state', jsonb_build_object(
           'event_id', s.event_id, 'sequence', s.decision_sequence, 'decision', s.decision,
           'decided_at', s.decided_at, 'decided_by', s.decided_by, 'reason_codes', to_jsonb(s.reason_codes),
           'supersedes', s.supersedes_decision), 'backfilled', true)
  FROM decision_states s
  WHERE s.decision_sequence > 1;
INSERT INTO transaction_request_recordings (event_id, institution_id)
  SELECT r.event_id, r.institution_id FROM transaction_requests r WHERE r.kind = 'BACKFILL';

INSERT INTO decision_owners (institution_id, transaction_id, first_event_id, first_decision,
    first_deadline, timeout_policy, sequence, decision, decided_by, deadline)
  SELECT f.institution_id, f.transaction_id, f.event_id, f.decision, f.review_deadline_at,
         CASE WHEN f.decision = 'HOLD' THEN coalesce((
           SELECT r.medium_timeout_policy FROM risk_thresholds r JOIN transactions t
             ON t.institution_id = f.institution_id AND t.transaction_id = f.transaction_id
           WHERE r.institution_id = f.institution_id AND r.channel = t.channel
             AND r.version = (SELECT max(v.version) FROM risk_threshold_versions v
                              WHERE v.institution_id = f.institution_id AND v.effective_at <= f.decided_at)
           LIMIT 1), 'RELEASE_WITH_TIMEOUT_LABEL') END,
         l.decision_sequence, l.decision, l.decided_by,
         CASE WHEN l.decision = 'HOLD' THEN f.review_deadline_at END
  FROM decision_states f
  JOIN LATERAL (SELECT x.decision_sequence, x.decision, x.decided_by FROM decision_states x
                WHERE x.institution_id = f.institution_id AND x.transaction_id = f.transaction_id
                ORDER BY x.decision_sequence DESC LIMIT 1) l ON true
  WHERE f.decision_sequence = 1 AND (l.decision = 'HOLD' OR l.decision_sequence > 1);

-- PRE_V81 markers, only for answers whose effect is complete or moot (review 2, MAJOR 1; review 3,
-- finding 6). A WAS_ME that M6 recorded but never applied, or a NOT_ME whose label never arrived,
-- gets none, and the reconciler submits it once after the cutover.
INSERT INTO transaction_requests (institution_id, transaction_id, request_id, kind, outcome, reason)
  SELECT r.institution_id, b.transaction_id, fs_name_uuid('verification|' || r.verification_id::text),
         CASE r.answer WHEN 'WAS_ME' THEN 'CUSTOMER_YES' ELSE 'CUSTOMER_NO' END, 'PRE_V81', 'pre-v81'
  FROM customer_verification_responses r
  JOIN customer_verifications v ON v.id = r.verification_id
  JOIN auto_block_events b ON b.id = v.auto_block_event_id
  WHERE (r.answer = 'NOT_ME' AND EXISTS (
           SELECT 1 FROM label_events e WHERE e.institution_id = r.institution_id
             AND e.transaction_id = b.transaction_id AND e.source = 'CUSTOMER'))
     OR (r.answer = 'WAS_ME' AND (
           EXISTS (SELECT 1 FROM decision_states s WHERE s.institution_id = r.institution_id
                     AND s.transaction_id = b.transaction_id AND s.decided_by = 'CUSTOMER_VERIFICATION')
           OR (SELECT NOT (s.decision = 'DECLINE' AND s.decided_by IN ('MODEL', 'TIMEOUT_POLICY'))
               FROM decision_states s WHERE s.institution_id = r.institution_id
                 AND s.transaction_id = b.transaction_id
               ORDER BY s.decision_sequence DESC LIMIT 1)));

-- From here on decision_states holds sequence 1 only (§4.4). NOT VALID: the backfilled history keeps
-- its later rows, and only new writes are checked.
ALTER TABLE decision_states ADD CONSTRAINT decision_states_first_only
  CHECK (decision_sequence = 1) NOT VALID;

-- §4.3's backstop: an APPLIED request carries the owner's next sequence. DecisionOwner inserts the
-- request before it updates the owner row, in one transaction, under the row's lock.
CREATE FUNCTION transaction_request_sequence() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
DECLARE
  current_sequence integer;
BEGIN
  IF NEW.outcome = 'APPLIED' THEN
    SELECT o.sequence INTO current_sequence FROM fraudshield.decision_owners o
    WHERE o.institution_id = NEW.institution_id AND o.transaction_id = NEW.transaction_id;
    IF current_sequence IS NULL OR NEW.sequence <> current_sequence + 1 THEN
      RAISE EXCEPTION 'transaction %: request sequence % is not the owner''s next', NEW.transaction_id,
        NEW.sequence USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER transaction_requests_sequence BEFORE INSERT ON transaction_requests
  FOR EACH ROW EXECUTE FUNCTION transaction_request_sequence();

-- Listers (contract §14). Row-level security scopes fs_app to one institution, so each worker that
-- crosses institutions first asks which institutions have work: ids only, never a row's content, in
-- V62's pattern. Each compares with its caller's time argument or its own DB time.

-- §6.3: owners held past their deadline, and sequence-1 HOLDs that no owner has taken yet.
CREATE OR REPLACE FUNCTION institutions_with_overdue_holds(p_before timestamptz)
  RETURNS SETOF uuid
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT o.institution_id FROM fraudshield.decision_owners o
    WHERE o.decision = 'HOLD' AND o.claim <> 'ANALYST' AND o.contested_since IS NULL
      AND o.deadline < p_before
    UNION
    SELECT h.institution_id FROM fraudshield.decision_states h
    WHERE h.decision = 'HOLD' AND h.decision_sequence = 1 AND h.review_deadline_at < p_before
      AND NOT EXISTS (SELECT 1 FROM fraudshield.decision_owners o
                      WHERE o.institution_id = h.institution_id AND o.transaction_id = h.transaction_id)
  $$;

-- §6.5: customer answers with no request row whose owner can act on them now.
CREATE OR REPLACE FUNCTION institutions_with_unapplied_unblocks(p_before timestamptz)
  RETURNS SETOF uuid
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT DISTINCT r.institution_id FROM fraudshield.customer_verification_responses r
    JOIN fraudshield.customer_verifications v ON v.id = r.verification_id
    JOIN fraudshield.auto_block_events b ON b.id = v.auto_block_event_id
    WHERE r.responded_at < p_before
      AND NOT EXISTS (SELECT 1 FROM fraudshield.transaction_requests q
                      WHERE q.request_id = fraudshield.fs_name_uuid('verification|' || r.verification_id::text))
      AND NOT EXISTS (SELECT 1 FROM fraudshield.decision_owners o
                      WHERE o.institution_id = b.institution_id AND o.transaction_id = b.transaction_id
                        AND (o.contested_since IS NOT NULL OR o.claim = 'ANALYST'
                             OR (o.claim = 'ESCALATED' AND o.deadline > clock_timestamp())))
  $$;

CREATE FUNCTION institutions_with_due_commits()
  RETURNS SETOF uuid
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT DISTINCT d.institution_id FROM fraudshield.alert_decisions d
    WHERE d.undo_until <= clock_timestamp()
      AND NOT EXISTS (SELECT 1 FROM fraudshield.alert_decision_commits c WHERE c.decision_id = d.id)
      AND NOT EXISTS (SELECT 1 FROM fraudshield.alert_decision_undos u WHERE u.decision_id = d.id)
  $$;

CREATE FUNCTION institutions_with_pending_effects()
  RETURNS SETOF uuid
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT d.institution_id FROM fraudshield.alert_decisions d
    JOIN fraudshield.alert_decision_commits c ON c.decision_id = d.id
    JOIN fraudshield.alert_queue_entries e ON e.id = d.alert_queue_entry_id
    WHERE NOT EXISTS (SELECT 1 FROM fraudshield.alert_decision_effects f WHERE f.decision_id = d.id)
      AND NOT EXISTS (SELECT 1 FROM fraudshield.decision_owners o
                      WHERE o.institution_id = e.institution_id AND o.transaction_id = e.transaction_id
                        AND o.contested_since IS NOT NULL)
    UNION
    SELECT d.institution_id FROM fraudshield.alert_decisions d
    JOIN fraudshield.alert_decision_effects f ON f.decision_id = d.id
    JOIN fraudshield.alert_queue_entries e ON e.id = d.alert_queue_entry_id
    WHERE f.outcome IN ('APPLIED', 'LABEL_ONLY') AND e.tier IN ('MEDIUM', 'ANOMALY')
      AND (d.decision = 'CONFIRM_FRAUD' OR d.override_decision = 'CONFIRM_FRAUD')
      AND NOT EXISTS (SELECT 1 FROM fraudshield.alert_decision_breaker_counts k WHERE k.decision_id = d.id)
  $$;

CREATE FUNCTION institutions_with_unrecorded_requests(p_before timestamptz)
  RETURNS SETOF uuid
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT DISTINCT r.institution_id FROM fraudshield.transaction_requests r
    WHERE r.outcome IN ('APPLIED', 'LABEL_ONLY') AND r.decided_at < p_before
      AND NOT EXISTS (SELECT 1 FROM fraudshield.transaction_request_recordings g
                      WHERE g.event_id = r.event_id)
  $$;

CREATE FUNCTION institutions_with_unapplied_resolutions()
  RETURNS SETOF uuid
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT DISTINCT z.institution_id FROM fraudshield.decision_conflict_resolutions z
    WHERE NOT EXISTS (SELECT 1 FROM fraudshield.transaction_requests q
                      WHERE q.request_id = fraudshield.fs_name_uuid(
                        CASE z.kind WHEN 'RESOLVE' THEN 'resolve|' ELSE 'release_failed|' END || z.id::text))
  $$;

-- §7.4: the alarms' figures, across institutions. Counts and ages only, never a row's content.
CREATE FUNCTION decision_alarm_figures()
  RETURNS TABLE (alarm text, total bigint, oldest_seconds double precision)
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT 'contested', count(*), coalesce(max(extract(epoch FROM clock_timestamp() - o.contested_since)), 0)
      FROM fraudshield.decision_owners o WHERE o.contested_since IS NOT NULL
    UNION ALL
    SELECT 'holds_overdue', count(*), coalesce(max(extract(epoch FROM clock_timestamp() - x.deadline)), 0)
      FROM (SELECT o.deadline FROM fraudshield.decision_owners o
            WHERE o.decision = 'HOLD' AND o.deadline < clock_timestamp() - interval '15 seconds'
            UNION ALL
            SELECT h.review_deadline_at FROM fraudshield.decision_states h
            WHERE h.decision = 'HOLD' AND h.decision_sequence = 1
              AND h.review_deadline_at < clock_timestamp() - interval '15 seconds'
              AND NOT EXISTS (SELECT 1 FROM fraudshield.decision_owners o
                              WHERE o.institution_id = h.institution_id
                                AND o.transaction_id = h.transaction_id)) x
    UNION ALL
    SELECT 'answers_deferred', count(*),
           coalesce(max(extract(epoch FROM clock_timestamp() - greatest(r.responded_at, o.updated_at))), 0)
      FROM fraudshield.customer_verification_responses r
      JOIN fraudshield.customer_verifications v ON v.id = r.verification_id
      JOIN fraudshield.auto_block_events b ON b.id = v.auto_block_event_id
      LEFT JOIN fraudshield.decision_owners o
        ON o.institution_id = b.institution_id AND o.transaction_id = b.transaction_id
      WHERE NOT EXISTS (SELECT 1 FROM fraudshield.transaction_requests q
                        WHERE q.request_id = fraudshield.fs_name_uuid('verification|' || r.verification_id::text))
        AND (o.institution_id IS NULL OR (o.contested_since IS NULL AND o.claim <> 'ANALYST'
             AND NOT (o.claim = 'ESCALATED' AND o.deadline > clock_timestamp())))
    UNION ALL
    SELECT 'effects_pending', count(*), coalesce(max(extract(epoch FROM clock_timestamp() - c.committed_at)), 0)
      FROM fraudshield.alert_decision_commits c
      WHERE NOT EXISTS (SELECT 1 FROM fraudshield.alert_decision_effects f WHERE f.decision_id = c.decision_id)
    UNION ALL
    SELECT 'claims_failed', count(*), coalesce(max(extract(epoch FROM clock_timestamp() - f.done_at)), 0)
      FROM fraudshield.decision_owners o
      JOIN fraudshield.alert_decision_effects f ON f.decision_id = o.claim_decision_id
      WHERE o.claim = 'ANALYST' AND f.outcome = 'FAILED'
    UNION ALL
    SELECT 'commits_overdue', count(*), coalesce(max(extract(epoch FROM clock_timestamp() - d.undo_until)), 0)
      FROM fraudshield.alert_decisions d
      WHERE d.undo_until < clock_timestamp() - interval '5 seconds'
        AND NOT EXISTS (SELECT 1 FROM fraudshield.alert_decision_commits c WHERE c.decision_id = d.id)
        AND NOT EXISTS (SELECT 1 FROM fraudshield.alert_decision_undos u WHERE u.decision_id = d.id)
    UNION ALL
    SELECT 'requests_unrecorded', count(*), coalesce(max(extract(epoch FROM clock_timestamp() - r.decided_at)), 0)
      FROM fraudshield.transaction_requests r
      WHERE r.outcome IN ('APPLIED', 'LABEL_ONLY')
        AND NOT EXISTS (SELECT 1 FROM fraudshield.transaction_request_recordings g WHERE g.event_id = r.event_id)
  $$;

-- §7.2: the operator exits. They authorise and record; the resolver applies each through
-- DecisionOwner, so the rule table stays in one place. The staff user is asserted by the caller and
-- cannot be authenticated here, so the database login is recorded beside it (flagged to M12).
CREATE FUNCTION resolution_actor(p_staff_user_id uuid)
  RETURNS fraudshield.users
  LANGUAGE plpgsql SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
DECLARE
  actor fraudshield.users;
BEGIN
  SELECT u.* INTO actor FROM fraudshield.users u WHERE u.id = p_staff_user_id;
  IF actor.id IS NULL OR actor.role <> 'RISK_OFFICER' OR actor.status <> 'ACTIVE' THEN
    RAISE EXCEPTION 'staff user % is not an active risk officer', p_staff_user_id
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  PERFORM set_config('fraudshield.institution_id', actor.institution_id::text, true);
  RETURN actor;
END
$$;
REVOKE ALL ON FUNCTION resolution_actor(uuid) FROM PUBLIC;

CREATE FUNCTION resolve_contested_decision(p_transaction_id uuid, p_decision text,
    p_staff_user_id uuid, p_reason text)
  RETURNS uuid
  LANGUAGE plpgsql SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
DECLARE
  actor fraudshield.users := fraudshield.resolution_actor(p_staff_user_id);
  owner fraudshield.decision_owners;
  resolution uuid;
BEGIN
  SELECT o.* INTO owner FROM fraudshield.decision_owners o
  WHERE o.institution_id = actor.institution_id AND o.transaction_id = p_transaction_id FOR UPDATE;
  IF owner.contested_since IS NULL THEN
    RAISE EXCEPTION 'transaction % is not contested', p_transaction_id USING ERRCODE = 'check_violation';
  END IF;
  INSERT INTO fraudshield.decision_conflict_resolutions (institution_id, transaction_id, kind,
      chosen_decision, contested_since, staff_user_id, db_login, reason)
    VALUES (actor.institution_id, p_transaction_id, 'RESOLVE', p_decision, owner.contested_since,
      actor.id, session_user, p_reason)
    RETURNING id INTO resolution;
  INSERT INTO fraudshield.audit_events (institution_id, writer_partition, event_type, action,
      entity_type, entity_id, user_id, user_first_name, user_last_name, user_role, after_value, event_at)
    VALUES (actor.institution_id, 0, 'SENIOR_OVERRIDE', 'RESOLUTION_AUTHORISED', 'transaction',
      p_transaction_id::text, actor.id, actor.first_name, actor.last_name, actor.role,
      jsonb_build_object('resolution_id', resolution, 'kind', 'RESOLVE', 'decision', p_decision,
        'db_login', session_user, 'reason', p_reason), clock_timestamp());
  RETURN resolution;
END
$$;

CREATE FUNCTION release_failed_claim(p_decision_id uuid, p_staff_user_id uuid, p_reason text)
  RETURNS uuid
  LANGUAGE plpgsql SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
DECLARE
  entry fraudshield.alert_queue_entries;
  actor fraudshield.users;
  resolution uuid;
BEGIN
  SELECT e.* INTO entry FROM fraudshield.alert_queue_entries e
  JOIN fraudshield.alert_decisions d ON d.alert_queue_entry_id = e.id WHERE d.id = p_decision_id;
  IF entry.id IS NULL THEN
    RAISE EXCEPTION 'decision % is unknown', p_decision_id USING ERRCODE = 'check_violation';
  END IF;
  actor := fraudshield.resolution_actor(p_staff_user_id);
  IF actor.institution_id <> entry.institution_id
     OR NOT EXISTS (SELECT 1 FROM fraudshield.alert_decision_effects f
                    WHERE f.decision_id = p_decision_id AND f.outcome = 'FAILED') THEN
    RAISE EXCEPTION 'decision % has no failed effect to release', p_decision_id
      USING ERRCODE = 'check_violation';
  END IF;
  INSERT INTO fraudshield.decision_conflict_resolutions (institution_id, transaction_id, kind,
      decision_id, staff_user_id, db_login, reason)
    VALUES (actor.institution_id, entry.transaction_id, 'RELEASE_FAILED', p_decision_id, actor.id,
      session_user, p_reason)
    RETURNING id INTO resolution;
  INSERT INTO fraudshield.audit_events (institution_id, writer_partition, event_type, action,
      entity_type, entity_id, user_id, user_first_name, user_last_name, user_role, after_value, event_at)
    VALUES (actor.institution_id, 0, 'ANALYST_DECISION', 'RELEASE_AUTHORISED', 'alert_decision',
      p_decision_id::text, actor.id, actor.first_name, actor.last_name, actor.role,
      jsonb_build_object('resolution_id', resolution, 'kind', 'RELEASE_FAILED',
        'db_login', session_user, 'reason', p_reason), clock_timestamp());
  RETURN resolution;
END
$$;

-- Grants (V11's scheme). New SECURITY DEFINER functions are pinned in DatabaseSecurityTest.
GRANT SELECT, INSERT, UPDATE ON decision_owners TO fs_app;
GRANT SELECT, INSERT ON transaction_requests, transaction_request_recordings, decision_conflicts,
  decision_conflict_resolutions, alert_decision_effects, alert_decision_breaker_counts TO fs_app;
GRANT SELECT ON v_decision_states, v_auto_block_status TO fs_app, fs_app_readonly, fs_compliance_ro;
GRANT SELECT ON decision_owners, transaction_requests, decision_conflicts, decision_conflict_resolutions,
  alert_decision_effects, alert_decision_breaker_counts TO fs_app_readonly, fs_compliance_ro;
GRANT EXECUTE ON FUNCTION institutions_with_due_commits(), institutions_with_pending_effects(),
  institutions_with_unrecorded_requests(timestamptz), institutions_with_unapplied_resolutions(),
  decision_alarm_figures() TO fs_app;
GRANT EXECUTE ON FUNCTION resolve_contested_decision(uuid, text, uuid, text),
  release_failed_claim(uuid, uuid, text) TO fs_operator;
