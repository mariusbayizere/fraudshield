-- Append-only, hash-chained audit log (FR-06-06, D-32).
--
-- Each writer partition is one SHA-256 chain. A trigger running with the owner's rights assigns
-- seq, prev_hash and row_hash from a locked chain head, so the application cannot forge or skip
-- them and concurrent writers to one partition are serialised. Tampering with a stored row, deleting
-- one or re-ordering them breaks the chain, which verify_audit_chain reports. A daily job stores the
-- signed Merkle root of the day's hashes in audit_anchors.

CREATE TABLE audit_events (
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  writer_partition smallint NOT NULL CHECK (writer_partition BETWEEN 0 AND 63),
  seq bigint NOT NULL,
  event_type text NOT NULL CHECK (event_type IN (
    'AUTH', 'USER_ADMIN', 'TRANSACTION_DECISION', 'AUTO_BLOCK', 'CUSTOMER_VERIFICATION', 'ANALYST_DECISION',
    'SENIOR_OVERRIDE', 'THRESHOLD_CHANGE', 'RULE_CHANGE', 'MODEL_LIFECYCLE', 'API_KEY_LIFECYCLE',
    'REGULATORY_REPORT')),
  action text NOT NULL CHECK (action ~ '^[A-Z][A-Z_]{1,63}$'),
  entity_type text NOT NULL CHECK (char_length(entity_type) BETWEEN 1 AND 64),
  entity_id text NOT NULL CHECK (char_length(entity_id) BETWEEN 1 AND 128),
  user_id uuid,
  user_first_name text CHECK (char_length(user_first_name) <= 100),
  user_last_name text CHECK (char_length(user_last_name) <= 100),
  user_role text CHECK (user_role IN ('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER', 'ADMIN')),
  before_value jsonb,
  after_value jsonb,
  ip_address inet,
  user_agent text CHECK (char_length(user_agent) <= 1024),
  correlation_id uuid,
  event_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT now(),
  prev_hash bytea NOT NULL CHECK (octet_length(prev_hash) = 32),
  row_hash bytea NOT NULL CHECK (octet_length(row_hash) = 32),
  PRIMARY KEY (event_at, id),
  UNIQUE (writer_partition, seq, event_at),
  CHECK ((user_id IS NULL) = (user_role IS NULL))
);
SELECT create_hypertable('audit_events', by_range('event_at', interval '1 month'));
CREATE INDEX audit_events_type_time ON audit_events (institution_id, event_type, event_at DESC);
CREATE INDEX audit_events_user_time ON audit_events (institution_id, user_id, event_at DESC);
CREATE INDEX audit_events_chain ON audit_events (writer_partition, seq);
CALL make_append_only('audit_events');
CALL enable_tenant_view_isolation('audit_events');

CREATE TABLE audit_chain_heads (
  writer_partition smallint PRIMARY KEY CHECK (writer_partition BETWEEN 0 AND 63),
  last_seq bigint NOT NULL CHECK (last_seq >= 0),
  last_hash bytea NOT NULL CHECK (octet_length(last_hash) = 32)
);

-- The exact bytes that are hashed; used by the trigger and by verification.
CREATE FUNCTION audit_row_hash(e audit_events) RETURNS bytea
  LANGUAGE sql IMMUTABLE PARALLEL SAFE
  AS $$
    SELECT sha256(convert_to(jsonb_build_array(
      e.writer_partition, e.seq, encode(e.prev_hash, 'hex'), e.id, e.institution_id, e.event_type, e.action,
      e.entity_type, e.entity_id, e.user_id, e.user_first_name, e.user_last_name, e.user_role,
      e.before_value, e.after_value, host(e.ip_address), masklen(e.ip_address), e.user_agent, e.correlation_id,
      to_char(e.event_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))::text, 'UTF8'))
  $$;

CREATE FUNCTION audit_events_chain() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
DECLARE
  head fraudshield.audit_chain_heads%ROWTYPE;
BEGIN
  INSERT INTO fraudshield.audit_chain_heads (writer_partition, last_seq, last_hash)
    VALUES (NEW.writer_partition, 0, '\x0000000000000000000000000000000000000000000000000000000000000000'::bytea)
    ON CONFLICT (writer_partition) DO NOTHING;
  SELECT * INTO STRICT head FROM fraudshield.audit_chain_heads
    WHERE writer_partition = NEW.writer_partition FOR UPDATE;
  NEW.seq := head.last_seq + 1;
  NEW.prev_hash := head.last_hash;
  NEW.recorded_at := now();
  NEW.row_hash := fraudshield.audit_row_hash(NEW);
  UPDATE fraudshield.audit_chain_heads SET last_seq = NEW.seq, last_hash = NEW.row_hash
    WHERE writer_partition = NEW.writer_partition;
  RETURN NEW;
END
$$;
-- BEFORE triggers fire in name order: tenant_insert_guard runs first, so a rejected row never
-- advances the chain head (and the head update is rolled back with the statement anyway).
CREATE TRIGGER zz_audit_events_hash_chain BEFORE INSERT ON audit_events
  FOR EACH ROW EXECUTE FUNCTION audit_events_chain();

-- Verifies one partition's chain from its first row (or from an anchored position). Runs with the
-- owner's rights so it sees every institution's rows, and returns positions only, never content.
CREATE FUNCTION verify_audit_chain(
    p_writer_partition smallint,
    p_after_seq bigint DEFAULT 0,
    p_after_hash bytea DEFAULT '\x0000000000000000000000000000000000000000000000000000000000000000'::bytea)
  RETURNS TABLE (checked_rows bigint, first_bad_seq bigint, problem text)
  LANGUAGE plpgsql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
DECLARE
  e fraudshield.audit_events;
  expected_seq bigint := p_after_seq + 1;
  expected_prev bytea := p_after_hash;
  checked bigint := 0;
BEGIN
  FOR e IN SELECT * FROM fraudshield.audit_events
           WHERE writer_partition = p_writer_partition AND seq > p_after_seq ORDER BY seq LOOP
    IF e.seq <> expected_seq THEN
      RETURN QUERY SELECT checked, expected_seq, 'missing row: sequence gap'::text; RETURN;
    END IF;
    IF e.prev_hash <> expected_prev THEN
      RETURN QUERY SELECT checked, e.seq, 'prev_hash does not match the previous row'::text; RETURN;
    END IF;
    IF e.row_hash <> fraudshield.audit_row_hash(e) THEN
      RETURN QUERY SELECT checked, e.seq, 'row content does not match row_hash'::text; RETURN;
    END IF;
    checked := checked + 1;
    expected_seq := e.seq + 1;
    expected_prev := e.row_hash;
  END LOOP;
  IF EXISTS (SELECT 1 FROM fraudshield.audit_chain_heads h
             WHERE h.writer_partition = p_writer_partition AND h.last_seq >= expected_seq) THEN
    RETURN QUERY SELECT checked, expected_seq, 'missing row: chain head is ahead of stored rows'::text; RETURN;
  END IF;
  RETURN QUERY SELECT checked, NULL::bigint, NULL::text;
END
$$;

CREATE TABLE audit_anchors (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  anchor_date date NOT NULL,
  writer_partition smallint NOT NULL CHECK (writer_partition BETWEEN 0 AND 63),
  last_seq bigint NOT NULL CHECK (last_seq >= 0),
  last_hash bytea NOT NULL CHECK (octet_length(last_hash) = 32),
  merkle_root bytea NOT NULL CHECK (octet_length(merkle_root) = 32),
  signature bytea NOT NULL CHECK (octet_length(signature) BETWEEN 64 AND 1024),
  signing_key_id text NOT NULL CHECK (char_length(signing_key_id) BETWEEN 1 AND 128),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (anchor_date, writer_partition)
);
CALL make_append_only('audit_anchors');
