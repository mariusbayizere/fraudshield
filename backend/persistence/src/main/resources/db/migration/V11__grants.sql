-- Least-privilege grants (ADR 0017, M1 gate).
--
-- fs_app: INSERT and SELECT only on append-only tables; UPDATE where the table holds mutable state;
--   DELETE only on caches, expiring credentials and the outbox.
-- fs_app_readonly: SELECT on application tables and views (reporting, support), without credentials.
-- fs_compliance_ro: SELECT on audit, decision and configuration history only, plus audit verification.
-- Nobody but fs_migrator (the owner) touches audit_chain_heads or the continuous aggregates directly.

REVOKE ALL ON ALL TABLES IN SCHEMA fraudshield FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA fraudshield FROM PUBLIC;
REVOKE ALL ON ALL PROCEDURES IN SCHEMA fraudshield FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA fraudshield REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- Append-only: INSERT, SELECT.
-- Hypertables: INSERT on the table, reads only through the tenant views (ADR 0017).
GRANT INSERT ON transactions, fraud_scores, shadow_scores, audit_events TO fs_app;
GRANT SELECT ON v_transactions, v_fraud_scores, v_shadow_scores, v_audit_events TO fs_app, fs_app_readonly;
GRANT SELECT ON v_audit_events TO fs_compliance_ro;

GRANT SELECT, INSERT ON
  transaction_ids, decision_states, batch_job_items,
  alert_decisions, alert_decision_commits, alert_decision_undos, alert_rule_versions,
  fraud_campaign_transactions, auto_block_events, customer_notifications, customer_verifications,
  customer_verification_responses, unblock_events, account_freeze_events, label_events,
  risk_threshold_versions, risk_thresholds, mcc_circuit_breaker_settings_versions,
  mcc_circuit_breaker_events, audit_anchors
  TO fs_app;

-- Mutable state: SELECT, INSERT, UPDATE.
GRANT SELECT, INSERT, UPDATE ON
  users, api_keys, alert_queue_entries, alert_rules, fraud_campaigns, sar_reports, config_changes,
  batch_jobs, model_versions, training_datasets, retraining_jobs
  TO fs_app;

-- Expiring or cached rows: also DELETE.
GRANT SELECT, INSERT, UPDATE, DELETE ON
  refresh_tokens, password_reset_otps, email_verification_tokens, account_velocity_cache, outbox_events
  TO fs_app;
GRANT SELECT, INSERT, DELETE ON office_ip_allowlist TO fs_app;
GRANT SELECT ON institutions TO fs_app;

GRANT SELECT ON v_alert_decision_state, v_auto_block_status, v_account_activity_hourly, v_merchant_activity_15m
  TO fs_app, fs_app_readonly;

-- Read-only access never includes credential material: no password hashes, token hashes, API-key
-- HMACs or webhook secrets (threat model R-3).
GRANT SELECT (id, institution_id, first_name, last_name, email, phone, role, requested_role, status,
  locked_until, failed_login_count, token_version, email_verified, preferred_locale, avatar_url,
  oauth_provider, employee_id, department, last_login_at, created_at, updated_at)
  ON users TO fs_app_readonly;
GRANT SELECT (id, institution_id, key_id, name, last_four, scopes, state, webhook_url, created_by,
  created_at, expires_at, revoked_at, last_used_at, replaced_by)
  ON api_keys TO fs_app_readonly;
GRANT SELECT (id, institution_id, auto_block_event_id, verification_channel, created_at, expires_at)
  ON customer_verifications TO fs_app_readonly;
GRANT SELECT ON
  institutions, office_ip_allowlist, transaction_ids, decision_states, account_velocity_cache, batch_jobs, batch_job_items,
  alert_queue_entries, alert_decisions, alert_decision_commits, alert_decision_undos, alert_rules,
  alert_rule_versions, fraud_campaigns, fraud_campaign_transactions, sar_reports, auto_block_events,
  customer_notifications, customer_verification_responses, unblock_events,
  account_freeze_events, label_events, config_changes, risk_threshold_versions, risk_thresholds,
  mcc_circuit_breaker_settings_versions, mcc_circuit_breaker_events, model_versions, training_datasets,
  retraining_jobs, audit_anchors
  TO fs_app_readonly;

GRANT SELECT ON
  institutions, audit_anchors, alert_decisions, alert_decision_commits, alert_decision_undos,
  decision_states, auto_block_events, unblock_events, account_freeze_events, customer_verification_responses,
  config_changes, risk_threshold_versions, risk_thresholds, mcc_circuit_breaker_settings_versions,
  sar_reports, label_events, model_versions
  TO fs_compliance_ro;
GRANT SELECT ON v_alert_decision_state, v_auto_block_status TO fs_compliance_ro;

GRANT EXECUTE ON FUNCTION current_institution(), is_token(text), deployment_has_synthetic_data()
  TO fs_app, fs_app_readonly, fs_compliance_ro;
-- The tenant views call current_institution() with the view owner's rights; the functions used by
-- CHECK constraints and triggers run as the inserting role.
GRANT EXECUTE ON FUNCTION
  auth_find_user_by_email(text), auth_find_email_verification(bytea), auth_find_api_key(text),
  auth_find_refresh_token(bytea), verification_find_by_token(bytea)
  TO fs_app;
GRANT EXECUTE ON FUNCTION verify_audit_chain(smallint, bigint, bytea) TO fs_compliance_ro;
-- The trigger functions run as trigger bodies and need no EXECUTE grant.
