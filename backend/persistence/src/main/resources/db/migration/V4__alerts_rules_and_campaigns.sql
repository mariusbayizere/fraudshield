-- Alert queue, analyst decisions, rules, campaigns and SAR drafts (FR-04, FR-05, D-10, D-22, D-29,
-- D-30, D-44).

CREATE TABLE alert_queue_entries (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  transaction_id uuid NOT NULL,
  fraud_score_id uuid NOT NULL,
  tier text NOT NULL CHECK (tier IN ('HIGH', 'MEDIUM', 'ANOMALY')),
  status text NOT NULL DEFAULT 'PENDING' CHECK (status IN (
    'PENDING', 'IN_REVIEW', 'CONFIRMED_FRAUD', 'MARKED_LEGITIMATE', 'AUTO_RELEASED', 'ESCALATED', 'OVERRIDDEN')),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  fraud_probability double precision NOT NULL CHECK (fraud_probability BETWEEN 0 AND 1),
  expected_loss_rwf numeric(18, 4) CHECK (expected_loss_rwf >= 0),
  assigned_analyst_id uuid,
  assigned_at timestamptz,
  review_deadline_at timestamptz,
  escalated_from_alert_id uuid,
  escalation_target_role text CHECK (escalation_target_role IN ('SENIOR_ANALYST', 'RISK_OFFICER')),
  escalation_reason text CHECK (char_length(escalation_reason) BETWEEN 10 AND 2000),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (assigned_analyst_id, institution_id) REFERENCES users (id, institution_id),
  CHECK ((assigned_analyst_id IS NULL) = (assigned_at IS NULL)),
  CHECK ((escalated_from_alert_id IS NULL) = (escalation_target_role IS NULL)),
  CHECK ((escalated_from_alert_id IS NULL) = (escalation_reason IS NULL)),
  -- D-10: anomaly-only alerts hold nothing and have no timer; original MEDIUM alerts always do.
  CHECK (tier <> 'ANOMALY' OR review_deadline_at IS NULL),
  CHECK (tier <> 'MEDIUM' OR escalated_from_alert_id IS NOT NULL OR review_deadline_at IS NOT NULL)
);
CREATE UNIQUE INDEX alert_queue_entries_id_institution ON alert_queue_entries (id, institution_id);
ALTER TABLE alert_queue_entries ADD FOREIGN KEY (escalated_from_alert_id, institution_id)
  REFERENCES alert_queue_entries (id, institution_id);
CREATE INDEX alert_queue_entries_feed ON alert_queue_entries (institution_id, status, tier, fraud_probability DESC);
CREATE INDEX alert_queue_entries_deadline ON alert_queue_entries (review_deadline_at) WHERE status IN ('PENDING', 'IN_REVIEW');
CREATE TRIGGER alert_queue_entries_updated_at BEFORE UPDATE ON alert_queue_entries
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CALL enable_tenant_isolation('alert_queue_entries');

-- Immutable decision facts (SRS: IMMUTABLE after creation). Commit and undo (D-44) and the customer's
-- later confirmation are separate append-only facts, so no decision row ever changes (D-30 pattern).
CREATE TABLE alert_decisions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  alert_queue_entry_id uuid NOT NULL,
  analyst_id uuid,
  decision text NOT NULL CHECK (decision IN (
    'CONFIRM_FRAUD', 'MARK_LEGITIMATE', 'ESCALATE', 'AUTO_RELEASED', 'AUTO_BLOCKED', 'SENIOR_OVERRIDE')),
  analyst_comment text CHECK (char_length(analyst_comment) <= 2000),
  decision_duration_seconds integer CHECK (decision_duration_seconds >= 0),
  override_of_decision_id uuid,
  idempotency_key uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (institution_id, idempotency_key),
  FOREIGN KEY (alert_queue_entry_id, institution_id) REFERENCES alert_queue_entries (id, institution_id),
  FOREIGN KEY (analyst_id, institution_id) REFERENCES users (id, institution_id),
  CHECK (decision NOT IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE') OR char_length(analyst_comment) >= 10),
  CHECK (decision <> 'SENIOR_OVERRIDE' OR (override_of_decision_id IS NOT NULL AND char_length(analyst_comment) >= 20)),
  CHECK ((decision = 'SENIOR_OVERRIDE') = (override_of_decision_id IS NOT NULL)),
  CHECK ((decision IN ('AUTO_RELEASED', 'AUTO_BLOCKED')) = (analyst_id IS NULL)),
  CHECK (override_of_decision_id IS NULL OR override_of_decision_id <> id)
);
CREATE UNIQUE INDEX alert_decisions_id_institution ON alert_decisions (id, institution_id);
ALTER TABLE alert_decisions ADD FOREIGN KEY (override_of_decision_id, institution_id)
  REFERENCES alert_decisions (id, institution_id);
CREATE INDEX alert_decisions_entry ON alert_decisions (alert_queue_entry_id);
CALL make_append_only('alert_decisions');
CALL enable_tenant_isolation('alert_decisions');

CREATE TABLE alert_decision_commits (
  decision_id uuid PRIMARY KEY,
  institution_id uuid NOT NULL,
  committed_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (decision_id, institution_id) REFERENCES alert_decisions (id, institution_id)
);
CALL make_append_only('alert_decision_commits');
CALL enable_tenant_isolation('alert_decision_commits');

CREATE TABLE alert_decision_undos (
  decision_id uuid PRIMARY KEY,
  institution_id uuid NOT NULL,
  undone_by uuid NOT NULL,
  undone_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (decision_id, institution_id) REFERENCES alert_decisions (id, institution_id),
  FOREIGN KEY (undone_by, institution_id) REFERENCES users (id, institution_id)
);
CALL make_append_only('alert_decision_undos');
CALL enable_tenant_isolation('alert_decision_undos');

-- A decision is either committed or undone, never both.
CREATE FUNCTION forbid_commit_and_undo() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
BEGIN
  -- Serialise commit and undo of the same decision so both checks cannot pass concurrently.
  PERFORM pg_advisory_xact_lock(hashtextextended('alert_decision:' || NEW.decision_id::text, 0));
  IF TG_TABLE_NAME = 'alert_decision_commits'
     AND EXISTS (SELECT 1 FROM fraudshield.alert_decision_undos u WHERE u.decision_id = NEW.decision_id) THEN
    RAISE EXCEPTION 'decision % was undone and cannot be committed', NEW.decision_id USING ERRCODE = 'check_violation';
  END IF;
  IF TG_TABLE_NAME = 'alert_decision_undos'
     AND EXISTS (SELECT 1 FROM fraudshield.alert_decision_commits c WHERE c.decision_id = NEW.decision_id) THEN
    RAISE EXCEPTION 'decision % is committed and cannot be undone', NEW.decision_id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER alert_decision_commits_exclusive BEFORE INSERT ON alert_decision_commits
  FOR EACH ROW EXECUTE FUNCTION forbid_commit_and_undo();
CREATE TRIGGER alert_decision_undos_exclusive BEFORE INSERT ON alert_decision_undos
  FOR EACH ROW EXECUTE FUNCTION forbid_commit_and_undo();

CREATE VIEW v_alert_decision_state WITH (security_invoker = true) AS
  SELECT d.*,
         CASE WHEN c.decision_id IS NOT NULL THEN 'COMMITTED'
              WHEN u.decision_id IS NOT NULL THEN 'UNDONE'
              ELSE 'PENDING_COMMIT' END AS state,
         c.committed_at, u.undone_at, u.undone_by
  FROM alert_decisions d
  LEFT JOIN alert_decision_commits c ON c.decision_id = d.id
  LEFT JOIN alert_decision_undos u ON u.decision_id = d.id;

CREATE TABLE alert_rules (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  rule_name text NOT NULL CHECK (rule_name ~ '^[A-Za-z0-9 _.-]{3,80}$'),
  state text NOT NULL DEFAULT 'ENABLED' CHECK (state IN ('ENABLED', 'DISABLED', 'DELETED')),
  current_version integer NOT NULL DEFAULT 1 CHECK (current_version >= 1),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  trigger_count bigint NOT NULL DEFAULT 0 CHECK (trigger_count >= 0),
  last_triggered_at timestamptz,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (institution_id, rule_name),
  FOREIGN KEY (created_by, institution_id) REFERENCES users (id, institution_id)
);
CREATE UNIQUE INDEX alert_rules_id_institution ON alert_rules (id, institution_id);
CREATE TRIGGER alert_rules_updated_at BEFORE UPDATE ON alert_rules FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CALL enable_tenant_isolation('alert_rules');

CREATE TABLE alert_rule_versions (
  rule_id uuid NOT NULL,
  institution_id uuid NOT NULL,
  version integer NOT NULL CHECK (version >= 1),
  description text NOT NULL CHECK (char_length(description) BETWEEN 10 AND 1000),
  rule_expression jsonb NOT NULL CHECK (jsonb_typeof(rule_expression) = 'object'),
  risk_tier_override text NOT NULL CHECK (risk_tier_override IN ('HIGH', 'MEDIUM')),
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (rule_id, version),
  FOREIGN KEY (rule_id, institution_id) REFERENCES alert_rules (id, institution_id),
  FOREIGN KEY (created_by, institution_id) REFERENCES users (id, institution_id)
);
CALL make_append_only('alert_rule_versions');
CALL enable_tenant_isolation('alert_rule_versions');

CREATE TABLE fraud_campaigns (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  campaign_name text NOT NULL CHECK (campaign_name ~ '^CMP-[0-9]{8}-(DEVICE|COUNTERPARTY|GEOGRAPHIC|MCC)-[0-9]{4}$'),
  detected_at timestamptz NOT NULL,
  shared_feature_type text NOT NULL CHECK (shared_feature_type IN ('DEVICE', 'COUNTERPARTY', 'GEOGRAPHIC', 'MCC')),
  shared_feature_value text NOT NULL CHECK (char_length(shared_feature_value) BETWEEN 1 AND 128),
  transaction_count integer NOT NULL CHECK (transaction_count >= 5),
  total_amount_rwf numeric(18, 4) NOT NULL CHECK (total_amount_rwf >= 0),
  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED')),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  closed_at timestamptz,
  closed_by uuid,
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (institution_id, campaign_name),
  FOREIGN KEY (closed_by, institution_id) REFERENCES users (id, institution_id),
  CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
  CHECK ((closed_at IS NULL) = (closed_by IS NULL))
);
CREATE UNIQUE INDEX fraud_campaigns_id_institution ON fraud_campaigns (id, institution_id);
CREATE TRIGGER fraud_campaigns_updated_at BEFORE UPDATE ON fraud_campaigns FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CALL enable_tenant_isolation('fraud_campaigns');

CREATE TABLE fraud_campaign_transactions (
  campaign_id uuid NOT NULL,
  institution_id uuid NOT NULL,
  transaction_id uuid NOT NULL,
  added_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (campaign_id, transaction_id),
  FOREIGN KEY (campaign_id, institution_id) REFERENCES fraud_campaigns (id, institution_id)
);
CALL make_append_only('fraud_campaign_transactions');
CALL enable_tenant_isolation('fraud_campaign_transactions');

-- SAR drafts (D-22): editable while DRAFT; frozen once signed off.
CREATE TABLE sar_reports (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id uuid NOT NULL REFERENCES institutions (id),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  template_version text NOT NULL CHECK (char_length(template_version) BETWEEN 1 AND 32),
  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SIGNED_OFF')),
  transaction_ids uuid[] NOT NULL CHECK (cardinality(transaction_ids) >= 1),
  fields jsonb NOT NULL CHECK (jsonb_typeof(fields) = 'array'),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  signed_off_by uuid,
  signed_off_at timestamptz,
  attestation text CHECK (char_length(attestation) BETWEEN 20 AND 2000),
  FOREIGN KEY (signed_off_by, institution_id) REFERENCES users (id, institution_id),
  CHECK ((status = 'SIGNED_OFF') = (signed_off_by IS NOT NULL)),
  CHECK ((signed_off_by IS NULL) = (signed_off_at IS NULL)),
  CHECK ((signed_off_by IS NULL) = (attestation IS NULL))
);
CREATE FUNCTION forbid_signed_sar_change() RETURNS trigger
  LANGUAGE plpgsql
  AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'SAR reports are never deleted' USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF OLD.status = 'SIGNED_OFF' THEN
    RAISE EXCEPTION 'SAR report % is signed off and cannot change', OLD.id USING ERRCODE = 'insufficient_privilege';
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END
$$;
CREATE TRIGGER sar_reports_frozen_after_sign_off BEFORE UPDATE OR DELETE ON sar_reports
  FOR EACH ROW EXECUTE FUNCTION forbid_signed_sar_change();
CALL enable_tenant_isolation('sar_reports');
