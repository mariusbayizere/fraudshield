-- Auto-blocks, customer verification, unblocks, freezes and labels as append-only facts (D-25, D-30).

CREATE TABLE auto_block_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  transaction_id uuid NOT NULL,
  fraud_score_id uuid NOT NULL,
  account_token text NOT NULL CHECK (is_token(account_token)),
  blocked_at timestamptz NOT NULL,
  block_reason text NOT NULL CHECK (char_length(block_reason) BETWEEN 1 AND 200),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (institution_id, transaction_id)
);
CREATE UNIQUE INDEX auto_block_events_id_institution ON auto_block_events (id, institution_id);
CREATE INDEX auto_block_events_account ON auto_block_events (institution_id, account_token, blocked_at DESC);
CALL make_append_only('auto_block_events');
CALL enable_tenant_isolation('auto_block_events');

-- One row per notification lifecycle event; contact details are never stored (D-25, NFR-SEC-03).
CREATE TABLE customer_notifications (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  notification_id uuid NOT NULL,
  auto_block_event_id uuid,
  account_token text NOT NULL CHECK (is_token(account_token)),
  channel text NOT NULL CHECK (channel IN ('SMS')),
  template_key text NOT NULL CHECK (template_key ~ '^sms\.[a-z_]+$'),
  locale text NOT NULL CHECK (locale IN ('en', 'rw', 'fr', 'sw')),
  verification_link_allowed boolean NOT NULL,
  event text NOT NULL CHECK (event IN ('REQUESTED', 'SENT', 'DELIVERED', 'FAILED')),
  provider_reference text CHECK (char_length(provider_reference) <= 128),
  occurred_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (notification_id, event),
  FOREIGN KEY (auto_block_event_id, institution_id) REFERENCES auto_block_events (id, institution_id)
);
CALL make_append_only('customer_notifications');
CALL enable_tenant_isolation('customer_notifications');

CREATE TABLE customer_verifications (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL,
  auto_block_event_id uuid NOT NULL UNIQUE,
  verification_token_hash bytea NOT NULL UNIQUE CHECK (octet_length(verification_token_hash) = 32),
  verification_channel text NOT NULL CHECK (verification_channel IN ('SMS_LINK', 'PUSH', 'IVR')),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  FOREIGN KEY (auto_block_event_id, institution_id) REFERENCES auto_block_events (id, institution_id),
  CHECK (expires_at > created_at)
);
CREATE UNIQUE INDEX customer_verifications_id_institution ON customer_verifications (id, institution_id);
CALL make_append_only('customer_verifications');
CALL enable_tenant_isolation('customer_verifications');

-- The verification page receives only the token (D-42).
CREATE FUNCTION verification_find_by_token(p_token_hash bytea)
  RETURNS TABLE (verification_id uuid, institution_id uuid, expires_at timestamptz)
  LANGUAGE sql STABLE SECURITY DEFINER
  SET search_path = fraudshield, pg_temp
  AS $$
    SELECT id, institution_id, expires_at FROM fraudshield.customer_verifications
    WHERE verification_token_hash = p_token_hash
  $$;

-- The token is single-use: at most one response per verification.
CREATE TABLE customer_verification_responses (
  verification_id uuid PRIMARY KEY,
  institution_id uuid NOT NULL,
  answer text NOT NULL CHECK (answer IN ('WAS_ME', 'NOT_ME')),
  self_service_allowed boolean NOT NULL,
  responded_at timestamptz NOT NULL DEFAULT now(),
  ip_address inet,
  FOREIGN KEY (verification_id, institution_id) REFERENCES customer_verifications (id, institution_id)
);
CREATE FUNCTION forbid_expired_verification_response() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM fraudshield.customer_verifications v
                 WHERE v.id = NEW.verification_id AND v.expires_at > NEW.responded_at) THEN
    RAISE EXCEPTION 'verification % has expired', NEW.verification_id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER customer_verification_responses_not_expired BEFORE INSERT ON customer_verification_responses
  FOR EACH ROW EXECUTE FUNCTION forbid_expired_verification_response();
CALL make_append_only('customer_verification_responses');
CALL enable_tenant_isolation('customer_verification_responses');

CREATE TABLE unblock_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL,
  auto_block_event_id uuid NOT NULL UNIQUE,
  cause text NOT NULL CHECK (cause IN ('CUSTOMER_VERIFICATION', 'ANALYST', 'SENIOR_OVERRIDE')),
  actor_user_id uuid,
  verification_id uuid,
  unblocked_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (auto_block_event_id, institution_id) REFERENCES auto_block_events (id, institution_id),
  FOREIGN KEY (actor_user_id, institution_id) REFERENCES users (id, institution_id),
  FOREIGN KEY (verification_id, institution_id) REFERENCES customer_verifications (id, institution_id),
  CHECK ((cause = 'CUSTOMER_VERIFICATION') = (verification_id IS NOT NULL)),
  CHECK ((cause = 'CUSTOMER_VERIFICATION') = (actor_user_id IS NULL))
);
CALL make_append_only('unblock_events');
CALL enable_tenant_isolation('unblock_events');

CREATE TABLE account_freeze_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  account_token text NOT NULL CHECK (is_token(account_token)),
  event text NOT NULL CHECK (event IN ('FROZEN', 'UNFROZEN')),
  cause text NOT NULL CHECK (char_length(cause) BETWEEN 3 AND 500),
  actor_user_id uuid,
  auto_block_event_id uuid,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (actor_user_id, institution_id) REFERENCES users (id, institution_id),
  FOREIGN KEY (auto_block_event_id, institution_id) REFERENCES auto_block_events (id, institution_id),
  CHECK (event <> 'UNFROZEN' OR actor_user_id IS NOT NULL)
);
CREATE INDEX account_freeze_events_account ON account_freeze_events (institution_id, account_token, occurred_at DESC);
CALL make_append_only('account_freeze_events');
CALL enable_tenant_isolation('account_freeze_events');

CREATE VIEW v_auto_block_status WITH (security_invoker = true) AS
  SELECT b.id AS auto_block_event_id, b.institution_id, b.transaction_id, b.account_token, b.blocked_at,
         EXISTS (SELECT 1 FROM customer_notifications n
                 WHERE n.auto_block_event_id = b.id AND n.event IN ('SENT', 'DELIVERED')) AS customer_sms_sent,
         r.answer = 'WAS_ME' AS customer_verified,
         r.responded_at AS verified_at,
         u.unblocked_at,
         f.event = 'FROZEN' AS account_frozen,
         f.occurred_at AS account_frozen_changed_at
  FROM auto_block_events b
  LEFT JOIN customer_verifications v ON v.auto_block_event_id = b.id
  LEFT JOIN customer_verification_responses r ON r.verification_id = v.id
  LEFT JOIN unblock_events u ON u.auto_block_event_id = b.id
  LEFT JOIN LATERAL (
    SELECT e.event, e.occurred_at FROM account_freeze_events e
    WHERE e.institution_id = b.institution_id AND e.account_token = b.account_token
    ORDER BY e.occurred_at DESC LIMIT 1) f ON true;

-- Labels with availability time, for the E.2 leakage rule and retraining.
CREATE TABLE label_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  transaction_id uuid NOT NULL,
  label text NOT NULL CHECK (label IN ('FRAUD', 'LEGITIMATE')),
  source text NOT NULL CHECK (source IN ('ANALYST', 'SENIOR_OVERRIDE', 'CUSTOMER', 'CHARGEBACK')),
  source_reference uuid,
  transaction_timestamp timestamptz NOT NULL,
  label_available_at timestamptz NOT NULL DEFAULT now(),
  CHECK (label_available_at >= transaction_timestamp)
);
CREATE INDEX label_events_transaction ON label_events (institution_id, transaction_id, label_available_at);
CALL make_append_only('label_events');
CALL enable_tenant_isolation('label_events');
