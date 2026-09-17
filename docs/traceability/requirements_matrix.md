<!-- GENERATED FILE — do not edit. Source: docs/traceability/requirements.yaml
     and test tags in code. Regenerate with: uv run fs-traceability render -->

# FraudShield Requirements Traceability Matrix

Rows: 258. Status counts: DONE 2, IN_PROGRESS 11, NOT_STARTED 245.

| ID | Priority | Milestone | Status | Title | Implementation | Tests | Evidence | Deviations |
|---|---|---|---|---|---|---|---|---|
| FR-01-01 | M | M6 | NOT_STARTED | POST /api/v1/transactions/ingest accepts transaction payload; validates schema; publishes to Kafka within 5ms | — | — | — | D-13 |
| FR-01-02 | M | M6 | NOT_STARTED | Transaction schema: transaction_id (UUID), account_id (tokenised), counterparty_id (tokenised), amount (DECIMAL 18,4), currency (ISO 4217 3-char), channel ENUM(MOBILE_MONEY/CARD/AGENT_BANKING/USSD/ONLINE/BANK_TRANSFER), merchant_category_code (4-char), latitude, longitude, device_fingerprint (nullable), transaction_timestamp (ISO 8601 UTC) | — | backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/SchemaPoliciesTest.java:144<br>contracts/tests/test_events.py:365<br>contracts/tests/test_openapi.py:110<br>contracts/tests/test_validation_vectors.py:137<br>contracts/tests/test_validation_vectors.py:39<br>contracts/tests/test_validation_vectors.py:78 | — | — |
| FR-01-03 | M | M6 | NOT_STARTED | Idempotent ingestion: duplicate transaction_id within 24 hours returns 200 with cached ScoringResult; not reprocessed | — | contracts/tests/test_openapi.py:304 | — | D-12 |
| FR-01-04 | M | M6 | NOT_STARTED | All 6 East African channels handled as first-class types with channel-specific feature engineering | — | contracts/tests/test_openapi.py:95 | — | D-04 |
| FR-01-05 | M | M6 | NOT_STARTED | API key authentication for core banking system (machine-to-machine); JWT for human-facing endpoints; keys scoped to ingestion endpoints only | — | contracts/tests/test_authorisation_matrix.py:136<br>contracts/tests/test_authorisation_matrix.py:55<br>contracts/tests/test_openapi.py:56 | — | — |
| FR-01-06 | S | M6 | NOT_STARTED | Batch ingestion POST /api/v1/transactions/ingest/batch accepts up to 1,000 transactions; returns 202 Accepted with job_id; results queryable | — | — | — | — |
| FR-01-07 | M | M1 | NOT_STARTED | OpenAPI 3.1 specification served at /api/docs with request/response examples for all 6 channel types | — | contracts/tests/test_openapi.py:299<br>contracts/tests/test_openapi.py:40<br>contracts/tests/test_openapi.py:46<br>contracts/tests/test_openapi.py:95<br>contracts/tests/test_openapi_examples.py:84 | — | — |
| FR-02-01 | M | M5 | NOT_STARTED | Every ScoringResult contains: ensemble_score (float 0-1), xgboost_score, lightgbm_score, anomaly_score (Isolation Forest), risk_tier ENUM(HIGH/MEDIUM/LOW), shap_top5 (JSONB), feature_vector (JSONB all 44), model_version, scoring_duration_ms | — | contracts/tests/test_openapi.py:267<br>contracts/tests/test_proto.py:45 | — | D-12 |
| FR-02-02 | M | M3 | NOT_STARTED | 44 features engineered per transaction within < 10ms: velocity (8 features), amount behaviour (5), temporal patterns (7), geographic (5), counterparty (5), device and channel (5), account profile (5), agent-specific (4), EAC corridor (1), synthetic ID risk (1) | — | — | — | D-03<br>D-04 |
| FR-02-03 | M | M4 | NOT_STARTED | XGBoost + LightGBM probabilities isotonic-regression calibrated; Expected Calibration Error < 0.05 on validation set | — | — | — | D-05 |
| FR-02-04 | M | M4 | NOT_STARTED | SHAP TreeExplainer (exact, no sampling) computes top-5 SHAP features for all transactions with ensemble_score >= 0.60 | — | — | — | D-05 |
| FR-02-05 | M | M5 | NOT_STARTED | Isolation Forest anomaly_score computed for every transaction; transactions with anomaly_score > 0.7 sent to analyst review regardless of ensemble_score | — | — | — | D-06<br>D-10 |
| FR-02-06 | M | M5 | NOT_STARTED | Risk thresholds configurable at runtime without model reload via Redis config store; change takes effect within 60 seconds | — | backend/common/src/test/java/io/github/mariusbayizere/fraudshield/common/config/DualControlWorkflowTest.java:26<br>contracts/tests/test_authorisation_matrix.py:103 | — | docs/adr/0014-staff-authorisation-model.md |
| FR-02-07 | M | M5 | NOT_STARTED | ML scoring latency: p50 < 15ms, p95 < 25ms, p99 < 40ms (ensemble + SHAP for flagged transactions combined) | — | — | — | D-16 |
| FR-02-08 | M | M5 | NOT_STARTED | Shadow mode: new model scores every transaction alongside production model; only production result triggers actions; comparison logged to MLflow | — | — | — | D-11 |
| FR-02-09 | M | M3 | NOT_STARTED | Redis feature store pre-computes and updates velocity features for every account within 100ms of transaction completion | — | — | — | — |
| FR-02-10 | M | M5 | NOT_STARTED | Model versioning: every ScoringResult records model_version string; old model continues scoring during blue-green switch | — | contracts/tests/test_proto.py:45 | — | — |
| FR-03-01 | M | M6 | NOT_STARTED | HIGH risk (ensemble_score >= 0.85): auto-blocked within 50ms of transaction receipt; account flagged; customer SMS dispatched asynchronously | — | — | — | D-13 |
| FR-03-02 | S | M6 | NOT_STARTED | MEDIUM risk (0.60-0.84): transaction held; published to analyst queue; 30-second countdown; auto-released with TIMEOUT label if no analyst action | — | — | — | D-10<br>D-14<br>D-18 |
| FR-03-03 | M | M6 | NOT_STARTED | LOW risk (< 0.60): approved and processed within total pipeline latency budget; no analyst involvement | — | — | — | — |
| FR-03-04 | M | M6 | NOT_STARTED | Customer SMS on auto-block: masked account number, transaction amount, currency, timestamp, 10-minute verification link | — | — | — | D-25<br>D-43 |
| FR-03-05 | M | M6 | NOT_STARTED | Customer verification: legitimate transaction verified via SMS link; block lifted within 10 seconds; false positive logged to ML retraining pipeline | — | — | — | D-25 |
| FR-03-06 | S | M6 | NOT_STARTED | Account freeze logic: 3+ HIGH-risk transactions from same account within 1 hour triggers full account freeze + risk officer notification | — | — | — | — |
| FR-03-07 | S | M6 | NOT_STARTED | MCC circuit breaker: fraud rate > 5% from specific MCC in 15-minute rolling window flags all transactions to that MCC for analyst review | — | backend/common/src/test/java/io/github/mariusbayizere/fraudshield/common/config/DualControlWorkflowTest.java:27<br>backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/SchemaPoliciesTest.java:91<br>contracts/tests/test_authorisation_matrix.py:109 | — | docs/adr/0014-staff-authorisation-model.md<br>D-18 |
| FR-03-08 | M | M6 | NOT_STARTED | All auto-block decisions append-only in audit log; no decision deletable or modifiable; override creates new audit entry referencing original | — | — | — | D-30 |
| FR-04-01 | M | M8 | NOT_STARTED | Analyst authenticates with email + password OR Google OAuth 2.0; JWT issued; first name, last name, avatar displayed in header | — | — | — | — |
| FR-04-02 | M | M8 | NOT_STARTED | Real-time alert feed via WebSocket; sorted fraud_probability descending; HIGH pinned at top; MEDIUM shows countdown timer | — | — | — | D-10<br>D-35 |
| FR-04-03 | M | M8 | NOT_STARTED | Alert card shows: risk badge (HIGH/MEDIUM), fraud probability MUI CircularProgress gauge, amount + currency, channel icon + label, masked account name, merchant, timestamp, top-3 SHAP feature pills | — | — | — | — |
| FR-04-04 | M | M8 | NOT_STARTED | One-click CONFIRM FRAUD or MARK LEGITIMATE with mandatory comment field (min 10 characters); actions complete within 2 seconds | — | — | — | D-44 |
| FR-04-05 | M | M8 | NOT_STARTED | SHAP waterfall chart in investigation panel: all 44 features ranked by absolute SHAP value; positive contributions red, negative green; base value and final score annotated | — | — | — | D-45 |
| FR-04-06 | M | M8 | NOT_STARTED | Account history timeline: last 30 transactions with amount, channel icon, timestamp, risk score MUI Chip, outcome badge (APPROVED/BLOCKED/PENDING) | — | — | — | — |
| FR-04-07 | S | M8 | NOT_STARTED | Behavioural fingerprint: 4-panel view: hourly transaction pattern heatmap, amount distribution histogram, channel breakdown pie chart, 'This transaction vs your normal' comparison table | — | — | — | — |
| FR-04-08 | C | M8 | NOT_STARTED | Transaction network graph: flagged account + counterparty + 2nd-degree connections; known fraud nodes highlighted red with glow | — | — | — | — |
| FR-04-09 | M | M8 | NOT_STARTED | Alert filter and search: by transaction_id, account_id, amount range, channel, date range, risk tier, analyst, outcome | — | — | — | — |
| FR-04-10 | M | M8 | NOT_STARTED | Priority escalation: ANALYST escalates to SENIOR_ANALYST or RISK_OFFICER with mandatory escalation reason | — | contracts/tests/test_authorisation_matrix.py:244 | — | — |
| FR-04-11 | S | M8 | NOT_STARTED | Analyst performance dashboard: alerts reviewed today, average review time (seconds), accuracy rate from customer verifications, backlog size | — | — | — | — |
| FR-04-12 | S | M8 | NOT_STARTED | WCAG 2.1 AA: risk tier conveyed by text label not colour alone; all controls keyboard-navigable; SHAP chart readable by screen reader | — | — | — | — |
| FR-05-01 | M | M8 | NOT_STARTED | Risk Officer sees all analyst alerts plus escalated alerts; can senior-override any analyst decision with mandatory reason | — | contracts/tests/test_authorisation_matrix.py:103<br>contracts/tests/test_authorisation_matrix.py:230 | — | — |
| FR-05-02 | M | M8 | NOT_STARTED | Portfolio risk dashboard: fraud prevented today/week/month (RWF amount + count), fraud rate by channel, fraud rate by MCC, geographic fraud heatmap (Leaflet.js), top-10 fraud counterparty accounts | — | — | — | D-46 |
| FR-05-03 | M | M8 | NOT_STARTED | Model performance monitoring: live AUC-ROC from analyst labels, precision, recall, F1, false positive rate trend, drift indicator vs deployment baseline | — | — | — | — |
| FR-05-04 | S | M8 | NOT_STARTED | Fraud campaign detection: clusters of related fraudulent transactions grouped by shared feature (device, counterparty, geography, MCC) within rolling time window | — | — | — | — |
| FR-05-05 | S | M8 | NOT_STARTED | Custom rule management: create, enable, disable, delete alert rules using structured rule DSL without code deployment | — | — | — | — |
| FR-05-06 | S | M8 | NOT_STARTED | SAR (Suspicious Activity Report) auto-draft for confirmed fraud transactions in Rwanda BNR reporting format; exportable as PDF | — | — | — | D-22<br>D-31 |
| FR-05-07 | M | M8 | NOT_STARTED | Per-channel threshold management: different HIGH/MEDIUM/LOW thresholds for each of 6 channel types | — | backend/common/src/test/java/io/github/mariusbayizere/fraudshield/common/config/DualControlWorkflowTest.java:25<br>backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/DatabaseSecurityTest.java:560<br>backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/demo/DemoDataSeederTest.java:28<br>contracts/tests/test_authorisation_matrix.py:103<br>contracts/tests/test_authorisation_matrix.py:109<br>contracts/tests/test_authorisation_matrix.py:304<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13 | — | docs/adr/0014-staff-authorisation-model.md |
| FR-06-01 | M | M7 | NOT_STARTED | Admin creates staff accounts: first name, last name, email, phone (country code flag + dial code + local number stored as E.164), employee ID, department, role (ANALYST/SENIOR_ANALYST/RISK_OFFICER/ADMIN) | — | contracts/tests/test_authorisation_matrix.py:128 | — | — |
| FR-06-02 | M | M7 | NOT_STARTED | Admin views, updates, and deactivates any account; deactivated accounts blocked from all auth methods including Google OAuth | — | — | — | D-27 |
| FR-06-03 | M | M8 | NOT_STARTED | Model management: list all MLflow model versions with training date, dataset size, AUC-ROC, precision, recall, F1; promote to production; rollback to previous; enable/disable shadow mode | — | contracts/tests/test_authorisation_matrix.py:128 | — | D-50 |
| FR-06-04 | S | M8 | NOT_STARTED | Dataset management: upload new labelled transaction CSV for retraining; trigger retraining job; monitor progress as percentage in real time | — | — | — | — |
| FR-06-05 | M | M9 | NOT_STARTED | System health panel: Kafka consumer lag per topic, ML scoring p50/p95/p99 latency, API error rate, Redis hit rate, DB pool usage, auto-block rate, alert queue depth, analyst response time distribution | — | — | — | — |
| FR-06-06 | M | M7 | NOT_STARTED | Full audit log: every system action with user first name + last name, role, timestamp, action type, entity, before/after values; searchable; immutable | — | backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/DatabaseSecurityTest.java:379<br>backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/DatabaseSecurityTest.java:471<br>backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/DatabaseSecurityTest.java:74 | — | D-32 |
| FR-06-07 | M | M7 | NOT_STARTED | API key management: create, rotate, revoke keys for core banking integrations; each key scoped to specific endpoint groups; key name and last-4 chars visible in UI | — | contracts/tests/test_authorisation_matrix.py:128 | — | D-19<br>D-31 |
| FR-07-01 | M | M7 | NOT_STARTED | Four roles with distinct access scopes: ANALYST, SENIOR_ANALYST, RISK_OFFICER, ADMIN; role embedded in JWT claims; cannot self-elevate | — | contracts/tests/test_authorisation_matrix.py:145<br>contracts/tests/test_authorisation_matrix.py:207<br>contracts/tests/test_authorisation_matrix.py:230<br>contracts/tests/test_authorisation_matrix.py:55<br>contracts/tests/test_authorisation_matrix.py:60<br>contracts/tests/test_authorisation_matrix.py:92 | — | D-24 |
| FR-07-02 | M | M7 | NOT_STARTED | Email + password registration: first name, last name, country-code phone (flag + dial code selector, 249 countries), employee ID, department, password + confirm password; strength meter and complexity requirements | — | backend/common/src/test/java/io/github/mariusbayizere/fraudshield/common/identity/PersonNameTest.java:18<br>contracts/tests/test_openapi.py:314<br>contracts/tests/test_openapi.py:445<br>contracts/tests/test_openapi.py:522<br>frontend/src/lib/validation/personName.test.ts:26<br>frontend/src/lib/validation/personName.test.ts:31<br>frontend/src/lib/validation/personName.test.ts:36 | — | D-24 |
| FR-07-03 | M | M7 | NOT_STARTED | Google OAuth 2.0 sign-in on login and registration pages; Google ID token verified server-side against Google JWKS; account linked by email | — | — | — | D-23 |
| FR-07-04 | M | M7 | NOT_STARTED | JWT access tokens: 15-minute expiry; RS256; containing user_id, role, first_name, email; refresh tokens: 7-day expiry in httpOnly SameSite=Strict cookie | — | — | — | D-27 |
| FR-07-05 | M | M7 | NOT_STARTED | Machine-to-machine API keys for transaction ingestion; keys scoped to ingestion endpoints only; cannot access analyst, admin, or ML endpoints | — | contracts/tests/test_authorisation_matrix.py:136<br>contracts/tests/test_authorisation_matrix.py:55<br>contracts/tests/test_openapi.py:56 | — | — |
| FR-07-06 | M | M7 | NOT_STARTED | Login rate limited: 10 attempts per 15 minutes per IP; 5 failed attempts locks analyst account with unlock email sent to account email | — | — | — | D-26 |
| FR-07-07 | M | M7 | NOT_STARTED | Password policy: minimum 8 characters, 1 uppercase, 1 lowercase, 1 digit, 1 special character; bcrypt cost factor 12 | — | contracts/tests/test_validation_vectors.py:190<br>contracts/tests/test_validation_vectors.py:205<br>contracts/tests/test_validation_vectors.py:212 | — | — |
| FR-07-08 | S | M7 | NOT_STARTED | Password reset: email OTP (6 digits, 10-minute expiry); OTP verified server-side; single-use; triggers password change form | — | — | — | D-31 |
| FR-07-09 | M | M7 | NOT_STARTED | All sessions invalidated on password change; Google OAuth session revoked via Google token revocation API on logout | — | — | — | D-27 |
| NFR-PERF-01 | M | M10 | NOT_STARTED | Transaction ingestion throughput | — | — | — | — |
| NFR-PERF-02 | M | M10 | NOT_STARTED | Auto-block end-to-end latency | — | — | — | D-13 |
| NFR-PERF-03 | M | M10 | NOT_STARTED | ML ensemble scoring latency | — | — | — | — |
| NFR-PERF-04 | M | M10 | NOT_STARTED | Feature engineering latency | — | — | — | — |
| NFR-PERF-05 | M | M10 | NOT_STARTED | Analyst dashboard real-time update | — | — | — | — |
| NFR-PERF-06 | M | M10 | NOT_STARTED | SHAP waterfall chart render | — | — | — | — |
| NFR-PERF-07 | M | M10 | NOT_STARTED | Alert feed initial load | — | — | — | — |
| NFR-PERF-08 | M | M10 | NOT_STARTED | Google OAuth login flow | — | — | — | — |
| NFR-PERF-09 | M | M10 | NOT_STARTED | System availability | — | — | — | — |
| NFR-PERF-10 | M | M10 | NOT_STARTED | API ingestion error rate | — | — | — | — |
| NFR-SEC-01 | M | M9 | NOT_STARTED | Encryption at rest | — | — | — | D-20 |
| NFR-SEC-02 | M | M9 | NOT_STARTED | Encryption in transit | — | — | — | — |
| NFR-SEC-03 | M | M1 | NOT_STARTED | PII tokenisation | — | backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/SchemaPoliciesTest.java:143<br>contracts/tests/test_events.py:190<br>contracts/tests/test_events.py:221<br>contracts/tests/test_events.py:292<br>contracts/tests/test_events.py:365<br>contracts/tests/test_openapi.py:453<br>contracts/tests/test_validation_vectors.py:39 | — | — |
| NFR-SEC-04 | M | M9 | NOT_STARTED | No secrets in version control | — | — | — | — |
| NFR-SEC-05 | M | M1 | IN_PROGRESS | Audit log immutability | backend/persistence/src/main/resources/db/migration/V8__audit_log.sql<br>backend/persistence/src/main/resources/db/migration/V11__grants.sql | backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/DatabaseSecurityTest.java:75 | — | D-32 |
| NFR-SEC-06 | M | M6 | NOT_STARTED | SQL injection prevention | — | — | — | — |
| NFR-SEC-07 | M | M9 | NOT_STARTED | XSS prevention | — | — | — | — |
| NFR-SEC-08 | M | M9 | NOT_STARTED | API key security | — | — | — | D-19 |
| NFR-SEC-09 | M | M9 | NOT_STARTED | Data residency | — | — | — | D-21 |
| NFR-SEC-10 | M | M9 | NOT_STARTED | Annual penetration test | — | — | — | D-28 |
| NFR-REL-01 | M | M6 | NOT_STARTED | ML scoring service down | — | — | — | — |
| NFR-REL-02 | M | M6 | NOT_STARTED | Kafka broker failure | — | — | — | D-15 |
| NFR-REL-03 | M | M6 | NOT_STARTED | Redis feature store down | — | — | — | — |
| NFR-REL-04 | M | M6 | NOT_STARTED | PostgreSQL primary failure | — | — | — | D-49 |
| NFR-REL-05 | M | M6 | NOT_STARTED | Duplicate transaction storm | — | — | — | — |
| NFR-REL-06 | M | M6 | NOT_STARTED | Google OAuth service unavailable | — | — | — | D-51 |
| UX-REG-01 | M | M8 | NOT_STARTED | First Name | — | backend/common/src/test/java/io/github/mariusbayizere/fraudshield/common/identity/PersonNameTest.java:19<br>contracts/tests/test_openapi.py:314<br>contracts/tests/test_openapi.py:522<br>frontend/src/lib/validation/personName.test.ts:26<br>frontend/src/lib/validation/personName.test.ts:31<br>frontend/src/lib/validation/personName.test.ts:36 | — | — |
| UX-REG-02 | M | M8 | NOT_STARTED | Last Name | — | backend/common/src/test/java/io/github/mariusbayizere/fraudshield/common/identity/PersonNameTest.java:20<br>contracts/tests/test_openapi.py:522<br>frontend/src/lib/validation/personName.test.ts:31<br>frontend/src/lib/validation/personName.test.ts:36 | — | — |
| UX-REG-03 | M | M8 | NOT_STARTED | Email | — | — | — | — |
| UX-REG-04 | M | M8 | NOT_STARTED | Phone Number | — | — | — | D-38 |
| UX-REG-05 | M | M8 | NOT_STARTED | Employee ID | — | — | — | — |
| UX-REG-06 | M | M8 | NOT_STARTED | Department | — | — | — | D-24 |
| UX-REG-07 | M | M8 | NOT_STARTED | Password | — | — | — | — |
| UX-REG-08 | M | M8 | NOT_STARTED | Confirm Password | — | — | — | — |
| UX-REG-09 | M | M8 | NOT_STARTED | OR Divider | — | — | — | — |
| UX-REG-10 | M | M8 | NOT_STARTED | Google Sign-In | — | — | — | — |
| UX-DASH-01 | M | M8 | NOT_STARTED | Stats Header (sticky MUI AppBar) | — | — | — | D-33 |
| UX-DASH-02 | M | M8 | NOT_STARTED | Alert Feed (MUI DataGrid virtualised) | — | — | — | D-34<br>D-35 |
| UX-DASH-03 | M | M8 | NOT_STARTED | Risk Score Gauge | — | — | — | D-33 |
| UX-DASH-04 | M | M8 | NOT_STARTED | Investigation Drawer (MUI Drawer anchor='right' width=560px) | — | — | — | — |
| UX-DASH-05 | M | M8 | NOT_STARTED | SHAP Waterfall Chart | — | — | — | D-45 |
| UX-DASH-06 | M | M8 | NOT_STARTED | Action Panel (bottom of drawer) | — | — | — | D-44 |
| UX-DASH-07 | S | M8 | NOT_STARTED | MEDIUM Timer Badge | — | — | — | — |
| UX-DASH-08 | M | M8 | NOT_STARTED | Account History Timeline | — | — | — | — |
| UX-DASH-09 | S | M8 | NOT_STARTED | Behavioural Fingerprint (4 panels) | — | — | — | — |
| MOB-PWA-01 | M | M8 | NOT_STARTED | Web App Manifest | — | — | — | — |
| MOB-PWA-02 | M | M8 | NOT_STARTED | Service Worker | — | — | — | — |
| MOB-PWA-03 | M | M8 | NOT_STARTED | Offline App Shell | — | — | — | — |
| MOB-PWA-04 | M | M8 | NOT_STARTED | Offline Data Cache | — | — | — | — |
| MOB-PWA-05 | S | M8 | NOT_STARTED | Background Sync | — | — | — | D-29<br>D-41 |
| MOB-PWA-06 | M | M8 | NOT_STARTED | Install Prompt | — | — | — | — |
| MOB-PWA-07 | M | M8 | NOT_STARTED | iOS PWA | — | — | — | — |
| MOB-PWA-08 | S | M8 | NOT_STARTED | Push Notifications | — | — | — | D-41<br>D-47 |
| MOB-PWA-09 | M | M8 | NOT_STARTED | Lighthouse PWA Score | — | — | — | D-40 |
| MOB-TOUCH-01 | M | M8 | NOT_STARTED | Minimum touch target | — | — | — | D-37 |
| MOB-TOUCH-02 | M | M8 | NOT_STARTED | Touch feedback | — | — | — | — |
| MOB-TOUCH-03 | S | M8 | NOT_STARTED | Swipe on cards | — | — | — | D-47 |
| MOB-TOUCH-04 | S | M8 | NOT_STARTED | Pull-to-refresh | — | — | — | D-47 |
| MOB-TOUCH-05 | M | M8 | NOT_STARTED | Pinch-to-zoom allowed | — | — | — | — |
| MOB-TOUCH-06 | M | M8 | NOT_STARTED | No hover-only features | — | — | — | — |
| MOB-TOUCH-07 | M | M8 | NOT_STARTED | Virtual keyboard handling | — | — | — | — |
| MOB-TOUCH-08 | S | M8 | NOT_STARTED | Haptic feedback | — | — | — | D-47 |
| MOB-NET-01 | M | M8 | NOT_STARTED | 4G / WiFi (urban Kigali) | — | — | — | — |
| MOB-NET-02 | M | M8 | NOT_STARTED | 3G (peri-urban Rwanda) | — | — | — | — |
| MOB-NET-03 | M | M8 | NOT_STARTED | 2G EDGE (rural agent location) | — | — | — | — |
| MOB-NET-04 | M | M8 | NOT_STARTED | Intermittent / offline | — | — | — | D-29 |
| MOB-NET-05 | M | M8 | NOT_STARTED | SMS only (no data, feature phone) | — | — | — | D-42 |
| MOB-PERF-01 | M | M8 | NOT_STARTED | First Contentful Paint (FCP) | — | — | — | — |
| MOB-PERF-02 | M | M8 | NOT_STARTED | Largest Contentful Paint (LCP) | — | — | — | — |
| MOB-PERF-03 | M | M8 | NOT_STARTED | Time to Interactive (TTI) | — | — | — | — |
| MOB-PERF-04 | M | M8 | NOT_STARTED | Total Blocking Time (TBT) | — | — | — | — |
| MOB-PERF-05 | M | M8 | NOT_STARTED | Cumulative Layout Shift (CLS) | — | — | — | — |
| MOB-PERF-06 | M | M8 | NOT_STARTED | JS bundle (initial, gzipped) | — | — | — | D-39 |
| MOB-PERF-07 | M | M8 | NOT_STARTED | Offline app shell load | — | — | — | — |
| MOB-PERF-08 | M | M8 | NOT_STARTED | Memory on entry-level device | — | — | — | — |
| MOB-COMP-01 | M | M8 | NOT_STARTED | Navigation | — | — | — | D-36 |
| MOB-COMP-02 | M | M8 | NOT_STARTED | Detail Panels | — | — | — | D-36 |
| MOB-COMP-03 | M | M8 | NOT_STARTED | Stats Header | — | — | — | — |
| MOB-COMP-04 | M | M8 | NOT_STARTED | Data Tables | — | — | — | — |
| MOB-COMP-05 | M | M8 | NOT_STARTED | Charts (Recharts) | — | — | — | — |
| MOB-COMP-06 | M | M8 | NOT_STARTED | Forms (all) | — | — | — | — |
| MOB-COMP-07 | M | M8 | NOT_STARTED | Country Code Selector | — | — | — | — |
| MOB-COMP-08 | M | M8 | NOT_STARTED | SHAP Waterfall Chart | — | — | — | D-45 |
| MOB-COMP-09 | M | M8 | NOT_STARTED | Password Strength Meter | — | — | — | — |
| MOB-DEV-01 | M | M10 | NOT_STARTED | Tecno Spark Go / Itel A23 (320px, Android Go, Chrome Lite) | — | — | — | D-47 |
| MOB-DEV-02 | M | M10 | NOT_STARTED | iPhone SE 2022 (375px, Safari iOS) | — | — | — | — |
| MOB-DEV-03 | M | M10 | NOT_STARTED | Samsung Galaxy A14 (412px, Chrome Android) | — | — | — | — |
| MOB-DEV-04 | M | M10 | NOT_STARTED | iPad mini 6 (768px, Safari iPadOS) | — | — | — | — |
| MOB-DEV-05 | M | M10 | NOT_STARTED | HP Pavilion / 13-inch laptop (1280px, Chrome) | — | — | — | D-40 |
| MOB-DEV-06 | M | M10 | NOT_STARTED | Desktop 1440px (Firefox) | — | — | — | — |
| MOB-DEV-07 | M | M10 | NOT_STARTED | Opera Mini / KaiOS (feature phone) | — | — | — | D-42 |
| ML-DATA-01 | M | M2 | NOT_STARTED | Total size | — | — | — | D-07 |
| ML-DATA-02 | M | M2 | NOT_STARTED | Fraud rate | — | — | — | — |
| ML-DATA-03 | M | M2 | NOT_STARTED | Channel distribution | — | — | — | — |
| ML-DATA-04 | M | M2 | NOT_STARTED | Fraud pattern diversity | — | — | — | D-08 |
| ML-DATA-05 | M | M2 | NOT_STARTED | Geographic coverage | — | — | — | — |
| ML-DATA-06 | M | M2 | NOT_STARTED | Temporal coverage | — | — | — | D-07 |
| ML-DATA-07 | M | M2 | NOT_STARTED | Feature completeness | — | — | — | D-04 |
| ML-DATA-08 | M | M2 | NOT_STARTED | Dataset release | — | — | — | — |
| ML-GATE-01 | M | M4 | NOT_STARTED | AUC-ROC (last-3-month test set) | — | — | — | D-07 |
| ML-GATE-02 | M | M4 | NOT_STARTED | Precision at 1% FPR | — | ml/tests/metrics/test_operating_points.py:16<br>ml/tests/metrics/test_operating_points.py:22 | — | D-01 |
| ML-GATE-03 | M | M4 | NOT_STARTED | Recall (fraud capture rate) | — | ml/tests/metrics/test_operating_points.py:33 | — | D-02 |
| ML-GATE-04 | M | M4 | NOT_STARTED | F1 Score | — | ml/tests/metrics/test_operating_points.py:33 | — | D-02 |
| ML-GATE-05 | M | M4 | NOT_STARTED | False Positive Rate at threshold 0.85 | — | — | — | D-02 |
| ML-GATE-06 | M | M4 | NOT_STARTED | False Negative Rate | — | — | — | D-02 |
| ML-GATE-07 | M | M4 | NOT_STARTED | MOBILE_MONEY channel AUC-ROC | — | — | — | — |
| ML-GATE-08 | M | M4 | NOT_STARTED | USSD channel AUC-ROC | — | — | — | — |
| ML-GATE-09 | M | M4 | NOT_STARTED | AGENT_BANKING channel AUC-ROC | — | — | — | — |
| ML-GATE-10 | M | M4 | NOT_STARTED | SHAP coverage | — | — | — | — |
| ML-GATE-11 | M | M4 | NOT_STARTED | Expected Calibration Error (ECE) | — | — | — | D-05 |
| ML-GATE-12 | M | M5 | NOT_STARTED | Inference latency p99 | — | — | — | D-16 |
| ML-GATE-13 | M | M5 | NOT_STARTED | Shadow mode AUC-ROC delta | — | — | — | D-11 |
| OPS-CI-01 | M | M9 | NOT_STARTED | Lint + Static Analysis | — | — | — | — |
| OPS-CI-02 | M | M9 | NOT_STARTED | Unit Tests | — | — | — | — |
| OPS-CI-03 | M | M9 | NOT_STARTED | Integration Tests | — | — | — | — |
| OPS-CI-04 | M | M9 | NOT_STARTED | Security Scan | — | — | — | — |
| OPS-CI-05 | M | M9 | NOT_STARTED | ML Evaluation Gate | — | — | — | D-07 |
| OPS-CI-06 | M | M9 | NOT_STARTED | Docker Build | — | — | — | — |
| OPS-CI-07 | M | M9 | NOT_STARTED | Staging Deploy | — | — | — | docs/adr/0014-staff-authorisation-model.md |
| OPS-CI-08 | M | M9 | NOT_STARTED | Production Deploy | — | — | — | — |
| OPS-OBS-01 | M | M9 | NOT_STARTED | Application Metrics | — | — | — | — |
| OPS-OBS-02 | M | M9 | NOT_STARTED | Structured Logs | — | — | — | — |
| OPS-OBS-03 | M | M9 | NOT_STARTED | ML Drift Monitoring | — | — | — | — |
| OPS-OBS-04 | M | M9 | NOT_STARTED | Regulatory Audit Trail | — | — | — | D-32 |
| OPS-OBS-05 | M | M9 | NOT_STARTED | Uptime | — | — | — | docs/adr/0014-staff-authorisation-model.md |
| OPS-OBS-06 | M | M9 | NOT_STARTED | Fraud Rate Anomaly | — | — | — | — |
| TEST-01 | M | M3 | NOT_STARTED | Unit: Feature Engineering | — | — | — | D-04 |
| TEST-02 | M | M4 | NOT_STARTED | Unit: ML Scoring | — | — | — | D-05<br>D-06 |
| TEST-03 | M | M6 | NOT_STARTED | Unit: Risk Decision Engine | — | — | — | D-17<br>D-18 |
| TEST-04 | M | M7 | NOT_STARTED | Unit: Auth (JWT + OAuth + API Key) | — | — | — | — |
| TEST-05 | M | M6 | NOT_STARTED | Integration: Transaction Pipeline | — | — | — | — |
| TEST-06 | M | M8 | NOT_STARTED | Integration: Analyst Decision Flow | — | — | — | — |
| TEST-07 | M | M7 | NOT_STARTED | Integration: OAuth Flow | — | — | — | D-23<br>D-51 |
| TEST-08 | M | M5 | NOT_STARTED | Integration: Model Shadow Mode | — | — | — | D-11 |
| TEST-09 | M | M10 | NOT_STARTED | Performance: Load | — | — | — | — |
| TEST-10 | M | M5 | NOT_STARTED | Performance: ML Throughput | — | — | — | D-16 |
| TEST-11 | M | M8 | NOT_STARTED | Frontend: Unit | — | — | — | — |
| TEST-12 | M | M8 | NOT_STARTED | Frontend: E2E | — | — | — | D-34 |
| TEST-13 | M | M9 | NOT_STARTED | Security | — | — | — | — |
| TEST-14 | M | M4 | NOT_STARTED | ML Evaluation Gate | — | — | — | — |
| RES-01 | M | M2 | NOT_STARTED | FraudShield-EAC-Transactions dataset | — | — | — | D-08<br>D-09 |
| RES-02 | M | M2 | NOT_STARTED | Dataset datasheet | — | — | — | D-09 |
| RES-03 | M | M11 | NOT_STARTED | Trained model weights | — | — | — | — |
| RES-04 | M | M11 | NOT_STARTED | SHAP analysis notebook | — | — | — | — |
| RES-05 | M | M11 | NOT_STARTED | Training and evaluation pipeline | — | — | — | — |
| RES-06 | M | M11 | NOT_STARTED | Full system source code | — | — | — | — |
| RES-07 | M | M11 | NOT_STARTED | Citation file | — | — | — | — |
| D-01 | M | M4 | IN_PROGRESS | "Precision at 1% FPR ≥ 0.720" is mathematically impossible. | ml/src/fraudshield_ml/metrics/operating_points.py | ml/tests/metrics/test_operating_points.py:16<br>ml/tests/metrics/test_operating_points.py:22<br>ml/tests/metrics/test_operating_points.py:27 | — | — |
| D-02 | M | M4 | IN_PROGRESS | Recall, F1 and FNR thresholds lack a defined operating point. | ml/src/fraudshield_ml/metrics/operating_points.py | ml/tests/metrics/test_operating_points.py:33<br>ml/tests/metrics/test_operating_points.py:41<br>ml/tests/metrics/test_operating_points.py:65<br>ml/tests/metrics/test_operating_points.py:73 | — | — |
| D-03 | M | M3 | NOT_STARTED | The feature breakdown sums to 46, not 44. | — | — | — | — |
| D-04 | M | M3 | NOT_STARTED | "USSD: 40 features computed without device_fingerprint" is undefined. | — | contracts/tests/test_openapi.py:95<br>contracts/tests/test_proto.py:61<br>contracts/tests/test_proto.py:86 | — | — |
| D-05 | M | M4 | NOT_STARTED | Calibrating two models separately, then averaging, does not yield a calibrated ensemble; SHAP additivity is only exact in each model's margin space. | — | — | — | — |
| D-06 | M | M4 | NOT_STARTED | Isolation Forest score range and threshold conflict. | — | — | — | — |
| D-07 | M | M2 | NOT_STARTED | Temporal split definitions disagree | — | — | — | — |
| D-08 | M | M2 | NOT_STARTED | Results on synthetic data can look perfect and prove nothing. | — | — | — | — |
| D-09 | M | M4 | IN_PROGRESS | Unsupported numeric claims in the SRS | docs/research/claims_register.md | — | — | — |
| D-10 | M | M6 | NOT_STARTED | Alert volume will overwhelm analysts; the 30-second MEDIUM timer then auto-releases most risky transactions. | — | contracts/tests/test_events.py:179 | — | — |
| D-11 | M | M5 | NOT_STARTED | Shadow "within 1%" is ambiguous and labels are delayed. | — | — | — | — |
| D-12 | M | M6 | NOT_STARTED | Model extraction and privacy risk: returning the full ScoringResult (feature_vector, per-model scores, SHAP) to the core-banking caller exposes model internals. | — | contracts/tests/test_openapi.py:140<br>contracts/tests/test_openapi.py:162<br>contracts/tests/test_openapi.py:174 | — | — |
| D-13 | M | M6 | NOT_STARTED | The latency budget sums to 51 ms and puts PostgreSQL and Kafka round-trips in the synchronous path, but the p95 target is 50 ms. | — | — | — | — |
| D-14 | M | M6 | NOT_STARTED | MEDIUM "hold for 30 seconds" cannot be an open HTTP request. | — | contracts/tests/test_compatibility.py:38<br>contracts/tests/test_events.py:179<br>contracts/tests/test_events.py:372<br>contracts/tests/test_openapi.py:162<br>contracts/tests/test_openapi.py:174<br>contracts/tests/test_openapi.py:210<br>contracts/tests/test_openapi.py:361<br>contracts/tests/test_webhooks.py:27<br>contracts/tests/test_webhooks.py:75<br>contracts/tests/test_webhooks.py:86 | — | — |
| D-15 | M | M6 | NOT_STARTED | "Buffer up to 10,000 transactions in memory" loses data if the pod dies. | — | — | — | — |
| D-16 | M | M5 | NOT_STARTED | Python in the hot path at 10k TPS. | — | — | — | — |
| D-17 | M | M6 | NOT_STARTED | pytest is listed for the Risk Decision Engine, which is a Spring Boot service. | — | backend/common/src/test/java/io/github/mariusbayizere/fraudshield/common/money/MoneyBoundaryTest.java:21 | — | — |
| D-18 | M | M6 | NOT_STARTED | "Exactly 30 s" and "exactly at 5%" need tolerances and minimum volume. | — | — | — | — |
| D-19 | M | M7 | NOT_STARTED | API keys "stored as bcrypt hash" cannot be verified at 10,000 TPS | — | contracts/tests/test_openapi.py:334 | — | — |
| D-20 | M | M1 | NOT_STARTED | "PostgreSQL TDE" does not exist in community PostgreSQL 16. | — | — | — | — |
| D-21 | M | M9 | IN_PROGRESS | Data residency: SRS says "af-south-1 or equivalent", but af-south-1 is Cape Town, outside the EAC — contradicting its own requirement. | backend/persistence/src/main/java/io/github/mariusbayizere/fraudshield/persistence/demo/DemoSeedGuard.java<br>backend/persistence/src/main/java/io/github/mariusbayizere/fraudshield/persistence/demo/SyntheticDataFlag.java<br>frontend/src/lib/environment/syntheticDataBanner.ts<br>tools/src/fraudshield_tools/seed_demo.py | backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/demo/DemoDataSeederTest.java:29<br>backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/demo/DemoSeedGuardTest.java:18<br>backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/demo/SyntheticDataFlagTest.java:12<br>backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/demo/SyntheticDataGuardCoverageTest.java:23<br>backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/demo/SyntheticDataGuardTest.java:35<br>contracts/tests/test_authorisation_matrix.py:166<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13<br>tools/tests/test_seed_demo.py:13 | — | docs/adr/0019-demo-seeding-and-synthetic-data-banner.md |
| D-22 | M | M8 | NOT_STARTED | Regulatory mapping is unverified. | — | — | — | — |
| D-23 | M | M7 | NOT_STARTED | Google OAuth "new Google user creates ANALYST account" would let any Google user into a bank's fraud console. | — | — | — | — |
| D-24 | M | M7 | NOT_STARTED | The registration form lets users pick a role (Department = role); FR-07-01 says users cannot self-elevate. | — | contracts/tests/test_openapi.py:445 | — | — |
| D-25 | M | M6 | NOT_STARTED | Customer verification by SMS link defeats itself in SIM-swap fraud, | — | contracts/tests/test_events.py:190<br>contracts/tests/test_events.py:204 | — | — |
| D-26 | M | M7 | NOT_STARTED | "10 attempts per 15 min per IP" will lock out whole bank offices and mobile users behind carrier-grade NAT; 5-failure account lock enables denial-of-service against analysts. | — | — | — | — |
| D-27 | M | M7 | NOT_STARTED | Stateless JWTs cannot be invalidated "within 5 seconds" on deactivation or password change. | — | — | — | — |
| D-28 | M | M9 | NOT_STARTED | A third-party penetration test cannot be performed by the build agent. | — | — | — | — |
| D-29 | M | M8 | NOT_STARTED | Offline analyst decisions replayed later (Background Sync) can be stale or dangerous, | — | contracts/tests/test_openapi.py:369 | — | — |
| D-30 | M | M1 | IN_PROGRESS | auto_block_events is declared immutable but contains mutable columns | backend/persistence/src/main/resources/db/migration/V5__blocking_verification_and_labels.sql<br>backend/persistence/src/main/resources/db/migration/V4__alerts_rules_and_campaigns.sql<br>backend/persistence/src/main/resources/db/migration/V1__tenancy_and_common_functions.sql | backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/DatabaseSecurityTest.java:25<br>backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/SchemaPoliciesTest.java:167 | — | docs/adr/0017-data-model-and-database-security.md |
| D-31 | M | M1 | IN_PROGRESS | Tables required by functional requirements are missing from the schema. | backend/persistence/src/main/resources/db/migration/V2__identity_and_access.sql<br>backend/persistence/src/main/resources/db/migration/V3__transactions_and_scoring.sql<br>backend/persistence/src/main/resources/db/migration/V6__risk_configuration.sql<br>backend/persistence/src/main/resources/db/migration/V7__models_and_training.sql<br>backend/persistence/src/main/resources/db/migration/V9__outbox.sql<br>backend/persistence/src/main/resources/db/migration/V11__grants.sql | backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/DatabaseSecurityTest.java:26<br>tools/tests/test_migration_guard.py:11<br>tools/tests/test_migration_guard.py:11<br>tools/tests/test_migration_guard.py:11<br>tools/tests/test_migration_guard.py:11<br>tools/tests/test_migration_guard.py:11 | — | docs/adr/0017-data-model-and-database-security.md |
| D-32 | M | M1 | IN_PROGRESS | Audit log needs tamper evidence, not only permissions, and "12 action types" are unnamed. | backend/persistence/src/main/resources/db/migration/V8__audit_log.sql<br>backend/persistence/src/main/resources/db/migration/V10__timescale_policies_and_aggregates.sql | backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/DatabaseSecurityTest.java:27<br>backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/SchemaPoliciesTest.java:54<br>contracts/tests/test_events.py:309<br>contracts/tests/test_openapi.py:423 | — | docs/adr/0017-data-model-and-database-security.md |
| D-33 | M | M8 | IN_PROGRESS | Several SRS colour pairings fail WCAG 2.1 AA text contrast | frontend/src/design-system/color/contrast.ts | frontend/src/design-system/color/contrast.test.ts:46<br>frontend/src/design-system/color/contrast.test.ts:60<br>frontend/src/design-system/color/contrast.test.ts:66 | — | — |
| D-34 | M | M8 | NOT_STARTED | animate-pulse "infinite" on HIGH cards violates WCAG 2.2.2 (motion > 5 s needs a pause control), conflicts with the mobile battery principle in 05B, and causes fatigue on long shifts. | — | — | — | — |
| D-35 | M | M8 | NOT_STARTED | HIGH "pinned at top" uses MUI X DataGrid row pinning, which is a paid (Pro) feature. | — | — | — | — |
| D-36 | M | M8 | NOT_STARTED | Breakpoint names and widths conflict | — | — | — | — |
| D-37 | M | M8 | NOT_STARTED | Tailwind and MUI styles fight each other. | — | — | — | — |
| D-38 | M | M8 | NOT_STARTED | Flags from flagcdn.com leak staff IP addresses to a third party, break the CSP, fail offline and cost data on 3G. | — | — | — | — |
| D-39 | M | M8 | NOT_STARTED | 200 KB gzipped initial bundle vs MUI + DataGrid + Recharts + Leaflet + graph library + Framer Motion. | — | — | — | — |
| D-40 | M | M8 | NOT_STARTED | Lighthouse removed its PWA category in Lighthouse 12, | — | — | — | — |
| D-41 | M | M8 | NOT_STARTED | iOS web push only works for Home-Screen-installed PWAs | — | — | — | — |
| D-42 | M | M8 | NOT_STARTED | Feature phones (KaiOS/Opera Mini, 240 px, 2G) will not run a bank analyst console, and analysts are staff with smartphones or PCs. | — | — | — | — |
| D-43 | M | M8 | IN_PROGRESS | SRS has no localisation, yet serves Rwanda and the EAC. | backend/common/src/main/java/io/github/mariusbayizere/fraudshield/common/money/Money.java<br>backend/common/src/main/java/io/github/mariusbayizere/fraudshield/common/money/CurrencyCode.java | backend/common/src/test/java/io/github/mariusbayizere/fraudshield/common/money/MoneyBoundaryTest.java:22<br>backend/common/src/test/java/io/github/mariusbayizere/fraudshield/common/money/MoneyTest.java:19<br>backend/common/src/test/java/io/github/mariusbayizere/fraudshield/common/money/MoneyTest.java:27<br>backend/common/src/test/java/io/github/mariusbayizere/fraudshield/common/money/MoneyTest.java:46<br>contracts/tests/test_events.py:340<br>contracts/tests/test_openapi.py:110<br>contracts/tests/test_openapi.py:128 | — | — |
| D-44 | M | M8 | NOT_STARTED | "Undo within 5 seconds" conflicts with immutable decisions and immediate side effects | — | — | — | — |
| D-45 | M | M8 | NOT_STARTED | SHAP chart "all 44 features ranked" overwhelms under time pressure. | — | — | — | — |
| D-46 | M | M8 | NOT_STARTED | Leaflet base-map tiles from public servers break the CSP, data residency and 2G usability; per-point fraud maps can expose individuals. | — | — | — | — |
| D-47 | M | M0 | DONE | KinyaMed text in 05B | tools/src/fraudshield_tools/scope_guard.py<br>tools/src/fraudshield_tools/traceability_seed.py | tools/tests/test_scope_and_registers.py:169<br>tools/tests/test_scope_and_registers.py:26<br>tools/tests/test_scope_and_registers.py:31<br>tools/tests/test_scope_and_registers.py:56<br>tools/tests/test_scope_and_registers.py:73 | tools/tests/test_scope_and_registers.py (5 tests tagged D-47: repository clean; detection in content, paths, identifiers and compound names; allowlist and pragma; seeded rows)<br>docs/adr/0008-out-of-scope-content-guard.md (decision)<br>docs/reviews/M0/milestone-review.md (M0 milestone review, APPROVED_WITH_MINORS)<br>83a9090 (reviewed merge candidate, fast-forwarded to main) | — |
| D-48 | M | M0 | DONE | The roadmap compresses everything into weeks 4–6. | docs/adr/0001-record-architecture-decisions.md<br>docs/traceability/requirements.yaml | — | docs/reviews/M0/milestone-review.md (inspection: all 258 rows carry milestones M0-M12, none a calendar date)<br>docs/adr/0001-record-architecture-decisions.md (gated milestones replace the SRS roadmap dates) | — |
| D-49 | M | M1 | IN_PROGRESS | TimescaleDB availability and licensing. | backend/persistence/src/main/resources/db/migration/V10__timescale_policies_and_aggregates.sql<br>docs/adr/0018-timescaledb-licence-and-deployment.md | backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/SchemaPoliciesTest.java:55<br>backend/persistence/src/test/java/io/github/mariusbayizere/fraudshield/persistence/SchemaPoliciesTest.java:90 | — | — |
| D-50 | M | M5 | NOT_STARTED | MLflow "stages" are deprecated in favour of registry aliases. | — | — | — | — |
| D-51 | M | M6 | NOT_STARTED | External services must work locally and in CI without real credentials. | — | — | — | — |
