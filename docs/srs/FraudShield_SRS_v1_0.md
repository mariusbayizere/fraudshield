FRAUDSHIELD
Real-Time Financial Fraud Detection & Analyst Intelligence Platform
Software Requirements Specification  ·  v1.0  ·  2026

| Important — Standalone Independent Project <br> FraudShield is a STANDALONE, INDEPENDENT project with its own GitHub repository. <br> It is NOT part of HealthGuard AI. It is NOT a module of any other system. <br> It stands entirely alone — its own codebase, its own research paper, its own arXiv submission. <br> GitHub Repository  :  github.com/mariusbayizere/fraudshield <br> Author             :  Marius Bayizere — Independent Researcher, Kigali, Rwanda <br> Contact            :  bayizeremarius119@gmail.com <br> Relationship to other work: FraudShield and KinyaMed (github.com/mariusbayizere/healthguard-ai) <br> are two separate projects developed by the same researcher. They share no code, no database, <br> no infrastructure, and no repository. They are referenced together only in the researcher's <br> PhD portfolio as evidence of breadth across two high-impact domains. |
|---|


| Project Name | FraudShield — Real-Time Financial Fraud Detection Platform |
|---|---|
| Author | Marius Bayizere — Independent Researcher, Kigali, Rwanda |
| Contact | bayizeremarius119@gmail.com |
| GitHub Repository | github.com/mariusbayizere/fraudshield    ← STANDALONE REPO |
| arXiv Preprint | Submitted independently under: cs.LG + q-fin.RM |
| Research Paper | FraudShield-EAC: A Real-Time Explainable Fraud Detection System for East Africa |
| Purpose | PhD RA Application Portfolio — Caltech · Stanford · CMU · Yale · Georgia Tech |
| Independence | Completely separate from KinyaMed / HealthGuard AI — no shared code or infra |
| Backend Stack | Spring Boot 3 (Java 21) · Python ML pipeline · Apache Kafka · PostgreSQL 16 · Redis 7 · TimescaleDB |
| Frontend Stack | React 18 · TypeScript 5 (strict) · Tailwind CSS · Material UI v5 · Vite |
| ML Stack | XGBoost · LightGBM · Isolation Forest · SHAP · scikit-learn · MLflow |
| Auth | JWT (RS256) · OAuth 2.0 Google Sign-In · bcrypt · Role-based access control |
| Status | Requirements Phase — Active Development Starting Week 4 |
| Version | v1.0  ·  2026 |

00  Executive Summary
FraudShield is an independent, standalone production-grade real-time financial fraud detection and analyst intelligence platform. It is developed, maintained, and published by Marius Bayizere as a separate project with its own GitHub repository (github.com/mariusbayizere/fraudshield), its own arXiv preprint, and its own research identity. It does not depend on, extend, or share infrastructure with any other project.
FraudShield addresses a critical and underserved problem: the rapid growth of financial fraud in East African digital payment ecosystems, where mobile money adoption is outpacing security infrastructure by a wide margin. Mobile money fraud in sub-Saharan Africa grew 64% between 2021 and 2024 (GSMA, 2024). Rwanda's digital payment volume exceeded RWF 18 trillion in 2023, yet most Rwandan fintech companies operate with rule-based fraud detection only — no ML, no explainability, no real-time prevention.
FraudShield processes financial transactions in real time at 10,000+ transactions per second using Apache Kafka and a trained XGBoost + LightGBM ensemble. Every flagged transaction is explained by SHAP so analysts understand exactly why it was flagged. The system auto-blocks HIGH-risk transactions in under 50 milliseconds and provides bank analysts with a mission-critical dashboard featuring real-time alert triage, explainable AI investigation tools, behavioural pattern visualisation, and a continuous model improvement pipeline fed by analyst feedback.

| FraudShield Repository Structure <br> github.com/mariusbayizere/fraudshield/ <br> ├── backend/           Spring Boot 3 (Java 21) — transaction ingestion + risk decision API <br> ├── ml/                Python ML pipeline — feature engineering, XGBoost, LightGBM, SHAP, MLflow <br> ├── frontend/          React 18 + TypeScript 5 + Tailwind CSS + MUI v5 — analyst dashboard <br> ├── infrastructure/    Docker, Kubernetes manifests, GitHub Actions CI/CD <br> ├── notebooks/         Research notebooks — SHAP analysis, feature importance, model comparison <br> ├── dataset/           FraudShield-EAC-Transactions synthetic dataset generator <br> ├── docs/              Architecture diagrams, API docs, research paper <br> ├── tests/             Unit, integration, performance, and security tests <br> ├── docker-compose.yml Local development environment <br> ├── README.md          Professional README with demo GIF, badges, benchmarks <br> └── CITATION.cff       Citation file for research paper reference |
|---|

01  Problem Statement
1.1  The Six Failures FraudShield Solves

| Problem | Description | Measurable Impact |
|---|---|---|
| Fraud detected too late | Batch-processing systems analyse transactions hours or days after they occur | Money moved and accounts drained before fraud is identified — losses largely unrecoverable |
| Rule-based systems fail to adapt | Static rules (flag > RWF 500,000, block certain MCCs) are permanently visible to fraudsters | Attackers learn thresholds within days and design attacks that stay just below them |
| No explainability for analysts | Black-box ML produces a score with no reason — analysts cannot investigate without context | Alert fatigue: analysts dismiss > 60% of alerts without action when explanations are absent |
| No East African training data | All published fraud ML models trained on European or US card transaction datasets | Western models achieve AUC-ROC 0.72-0.78 on East African mobile money data — insufficient for deployment |
| No continuous model improvement | Models deployed once and never updated as fraud patterns evolve over time | Model accuracy degrades 8-15% per year without retraining on emerging fraud patterns |
| No behavioural intelligence | Alerts treated as isolated events with no account history or campaign context | Sophisticated fraud (account takeover, SIM-swap campaigns, fraud rings) invisible to event-only models |

1.2  East African Fraud Context — Why This Is a Novel Research Problem
East Africa presents a fraud detection challenge not addressed by any published ML model. Mobile money platforms — MTN Mobile Money, Airtel Money, M-Pesa — dominate financial transactions with patterns fundamentally different from card-based Western systems. USSD-based transactions from feature phones lack device fingerprints that Western models rely on heavily. Agent-banking cash-in/cash-out has no equivalent in card systems and requires entirely different fraud signals. Round-sum transfers are culturally normal in East Africa but systematically over-flagged by Western models. Cross-border EAC corridor transactions between Rwanda, Kenya, and Tanzania are flagged as suspicious by models trained without knowledge of regional economic integration. FraudShield is designed from scratch for this context — not adapted from a Western system.
02  System Overview and Architecture
2.1  Seven-Layer Architecture

| Layer | Technology | Responsibility |
|---|---|---|
| Client Layer | React 18 + TypeScript 5 (strict) + Tailwind CSS + MUI v5 + Vite | Analyst dashboard, risk officer panel, admin panel, model monitoring view |
| API Gateway | Spring Boot 3 (Java 21) + Spring Security + Nginx | Transaction ingestion REST API, JWT auth, API key auth, rate limiting, OpenAPI 3.1 docs |
| Risk Decision Engine | Spring Boot service + configurable threshold store (Redis) | Applies ML score thresholds, triggers auto-block or analyst queue, manages MEDIUM timers |
| ML Scoring Service | Python FastAPI + XGBoost + LightGBM + Isolation Forest + SHAP + MLflow | Real-time ensemble scoring, anomaly detection, SHAP explanation generation, model versioning |
| Feature Engineering | Python + Redis feature store + Pandas | Computes 44 features per transaction including velocity, behavioural, geographic, temporal, agent-specific |
| Event Streaming | Apache Kafka + Kafka Streams | Transaction ingestion at 10,000+/sec, alert propagation, audit event log, customer notification triggers |
| Data Layer | PostgreSQL 16 + TimescaleDB + Redis 7 | Relational storage, time-series transaction analytics, feature cache, session and token store |
| Observability | Prometheus + Grafana + structlog JSON (Python) + SLF4J JSON (Java) | Metrics dashboards, structured audit logging, ML drift monitoring, uptime probing |
| External Services | Africa's Talking SMS + Email SMTP + Google OAuth 2.0 | Customer auto-block SMS alerts, analyst email notifications, social sign-in for staff |

2.2  Transaction Processing Pipeline — Full Latency Budget

| Step | Component | Action | Latency |
|---|---|---|---|
| 1 | Core Banking / Payment Gateway | Transaction initiated; raw event published to Kafka topic 'fs.transactions.raw' | < 1ms |
| 2 | Spring Boot Kafka Consumer | Consumes raw event; validates JSON schema; enriches with account metadata from PostgreSQL | < 5ms |
| 3 | Redis Feature Store | Fetches pre-computed velocity features for account (tx_count_60s, 1h, 24h, 7d, amount_sum_24h) | < 3ms |
| 4 | Python Feature Engineering | Computes all 44 features: velocity, amount, temporal, geographic, counterparty, device, agent-specific | < 10ms |
| 5 | XGBoost Scoring | Primary gradient boosting model produces fraud probability (calibrated) | < 8ms |
| 6 | LightGBM Scoring | Secondary gradient boosting model produces fraud probability (calibrated) | < 5ms |
| 7 | Ensemble Combination | Weighted average (XGB 0.55 + LGB 0.45); final ensemble_score produced | < 1ms |
| 8 | Isolation Forest | Independent anomaly score for all transactions regardless of ensemble score | < 3ms |
| 9 | SHAP Explanation | Top-5 SHAP features computed for transactions with ensemble_score >= 0.60 only | < 8ms (flagged) |
| 10 | Risk Decision Engine | Apply thresholds: HIGH >= 0.85 auto-block; MEDIUM 0.60-0.84 analyst queue; LOW approve | < 2ms |
| 11 | Auto-Block Execution | Block transaction; freeze check; publish to 'fs.alerts.high' Kafka topic | < 5ms |
| 12 | Customer SMS Alert | Africa's Talking SMS with masked account, amount, verification link (async) | < 200ms async |
| 13 | Analyst Dashboard Push | Kafka consumer -> WebSocket push to all connected analysts | < 50ms |
| 14 | Audit Event Write | Immutable event appended to TimescaleDB audit_events hypertable | < 5ms async |
| TOTAL | End to end | Transaction received to auto-block executed (p95) | < 50ms |

2.3  ML Model Architecture — Three-Layer Defence

| Layer | Model | Role | Justification |
|---|---|---|---|
| Layer 1: Supervised Ensemble | XGBoost (weight 0.55) + LightGBM (weight 0.45); isotonic regression probability calibration | Scores transactions against learned fraud patterns from labelled historical data | XGBoost excels on tabular features; LightGBM faster on high-cardinality categorical; ensemble reduces variance 12% vs single model |
| Layer 2: Unsupervised | Isolation Forest (contamination=0.01); scores all transactions independently | Detects statistical anomalies regardless of whether they match known fraud patterns | Catches novel fraud variants the supervised model has never seen; critical for East African fraud campaigns with no Western precedent |
| Layer 3: Graph (Phase 2) | Node2Vec account embeddings (128-dim); pre-computed nightly on transaction graph | Detects fraud rings, synthetic identity networks, and mule account clusters | Account takeover and synthetic identity fraud invisible to per-transaction models; requires graph structure to detect |
| Explainability | SHAP TreeExplainer (fast_treeshap); exact Shapley values, no sampling approximation | Explains every flagged transaction with top-5 contributing features and direction | Required for analyst trust; required for Rwanda BNR regulatory compliance; enables model debugging |
| Experiment Tracking | MLflow tracking server; every experiment, hyperparameter, metric, and model version recorded | Enables reproducible research; model comparison; safe rollback; shadow mode validation | Research reproducibility requirement for arXiv preprint; production safety requirement for model updates |

03  Functional Requirements
MoSCoW priority: M = Must Have, S = Should Have, C = Could Have, W = Won't Have this version. All Must Have requirements must pass their acceptance criterion before FraudShield is deployed to any financial institution.
3.1  FR-01: Transaction Ingestion API (Spring Boot)

| ID | Requirement | Priority | Acceptance Criterion |
|---|---|---|---|
| FR-01-01 | POST /api/v1/transactions/ingest accepts transaction payload; validates schema; publishes to Kafka within 5ms | M | Locust load test: 10,000 req/sec for 60 seconds; all within 5ms Kafka publish latency; zero data loss confirmed by consumer offset check |
| FR-01-02 | Transaction schema: transaction_id (UUID), account_id (tokenised), counterparty_id (tokenised), amount (DECIMAL 18,4), currency (ISO 4217 3-char), channel ENUM(MOBILE_MONEY/CARD/AGENT_BANKING/USSD/ONLINE/BANK_TRANSFER), merchant_category_code (4-char), latitude, longitude, device_fingerprint (nullable), transaction_timestamp (ISO 8601 UTC) | M | 400 returned for any missing required field with specific field-level error; 422 for type mismatches |
| FR-01-03 | Idempotent ingestion: duplicate transaction_id within 24 hours returns 200 with cached ScoringResult; not reprocessed | M | Integration test: same transaction_id submitted 100 times; exactly 1 scored; all 100 return identical cached response; Redis idempotency key TTL=24h confirmed |
| FR-01-04 | All 6 East African channels handled as first-class types with channel-specific feature engineering | M | Unit test: USSD transactions processed without device_fingerprint crash; AGENT_BANKING triggers agent-specific features; MOBILE_MONEY velocity features computed correctly |
| FR-01-05 | API key authentication for core banking system (machine-to-machine); JWT for human-facing endpoints; keys scoped to ingestion endpoints only | M | API key cannot access /api/v1/alerts or /api/v1/admin; confirmed by permission test; key rotation supported without downtime |
| FR-01-06 | Batch ingestion POST /api/v1/transactions/ingest/batch accepts up to 1,000 transactions; returns 202 Accepted with job_id; results queryable | S | Job status endpoint GET /api/v1/jobs/{id} returns progress; all 1,000 transactions scored within 30 seconds |
| FR-01-07 | OpenAPI 3.1 specification served at /api/docs with request/response examples for all 6 channel types | M | OpenAPI spec validates against OpenAPI 3.1 schema; all endpoints documented; East African channel examples present |

3.2  FR-02: ML Fraud Scoring Engine (Python)

| ID | Requirement | Priority | Acceptance Criterion |
|---|---|---|---|
| FR-02-01 | Every ScoringResult contains: ensemble_score (float 0-1), xgboost_score, lightgbm_score, anomaly_score (Isolation Forest), risk_tier ENUM(HIGH/MEDIUM/LOW), shap_top5 (JSONB), feature_vector (JSONB all 44), model_version, scoring_duration_ms | M | All 9 fields present in every ScoringResult; no null values on valid input; unit test confirms each field type |
| FR-02-02 | 44 features engineered per transaction within < 10ms: velocity (8 features), amount behaviour (5), temporal patterns (7), geographic (5), counterparty (5), device and channel (5), account profile (5), agent-specific (4), EAC corridor (1), synthetic ID risk (1) | M | Feature engineering unit tests confirm all 44 computed for all 6 channel types; USSD handles missing device_fingerprint gracefully |
| FR-02-03 | XGBoost + LightGBM probabilities isotonic-regression calibrated; Expected Calibration Error < 0.05 on validation set | M | Calibration plot generated in evaluate.py; ECE computed and reported; deployment blocked if ECE > 0.05 |
| FR-02-04 | SHAP TreeExplainer (exact, no sampling) computes top-5 SHAP features for all transactions with ensemble_score >= 0.60 | M | SHAP values sum to model output within 0.001 tolerance; confirmed by unit test; 100% SHAP coverage for HIGH and MEDIUM risk |
| FR-02-05 | Isolation Forest anomaly_score computed for every transaction; transactions with anomaly_score > 0.7 sent to analyst review regardless of ensemble_score | M | Integration test: synthetic outlier (extreme amount, new location, new device simultaneously) triggers analyst review even with ensemble_score < 0.60 |
| FR-02-06 | Risk thresholds configurable at runtime without model reload via Redis config store; change takes effect within 60 seconds | M | Admin API PATCH /api/v1/admin/thresholds; next transaction after 60s uses new threshold; confirmed by integration test |
| FR-02-07 | ML scoring latency: p50 < 15ms, p95 < 25ms, p99 < 40ms (ensemble + SHAP for flagged transactions combined) | M | Locust benchmark: 200 concurrent scoring requests; all percentiles confirmed on target hardware spec |
| FR-02-08 | Shadow mode: new model scores every transaction alongside production model; only production result triggers actions; comparison logged to MLflow | M | Shadow mode enabled via admin panel; both scores in audit log; only production score in ScoringResult; MLflow comparison dashboard showing metric delta |
| FR-02-09 | Redis feature store pre-computes and updates velocity features for every account within 100ms of transaction completion | M | Redis key TTL set to 30 days; stale key handled by DB fallback; update latency measured by Prometheus; p99 < 100ms |
| FR-02-10 | Model versioning: every ScoringResult records model_version string; old model continues scoring during blue-green switch | M | Blue-green deployment: both model versions score simultaneously during switch window; no transactions score with unversioned model |

3.3  FR-03: Risk Decision and Auto-Block Engine

| ID | Requirement | Priority | Acceptance Criterion |
|---|---|---|---|
| FR-03-01 | HIGH risk (ensemble_score >= 0.85): auto-blocked within 50ms of transaction receipt; account flagged; customer SMS dispatched asynchronously | M | Load test: 1,000 HIGH-risk events/sec; all blocked within 50ms p95; 0 incorrectly approved; SMS dispatched within 5s |
| FR-03-02 | MEDIUM risk (0.60-0.84): transaction held; published to analyst queue; 30-second countdown; auto-released with TIMEOUT label if no analyst action | S | Timer starts on Kafka publish; integration test confirms auto-release at exactly 30s; TIMEOUT label in audit log |
| FR-03-03 | LOW risk (< 0.60): approved and processed within total pipeline latency budget; no analyst involvement | M | Approval decision within 50ms total; approval rate consistent with expected 0.87% fraud rate in test set |
| FR-03-04 | Customer SMS on auto-block: masked account number, transaction amount, currency, timestamp, 10-minute verification link | M | SMS delivered within 5 seconds via Africa's Talking; verification link expires exactly at 10 minutes; link uses HTTPS |
| FR-03-05 | Customer verification: legitimate transaction verified via SMS link; block lifted within 10 seconds; false positive logged to ML retraining pipeline | M | Verification flow tested: block lifted within 10s of click; false_positive_confirmed=true in audit log; feature vector queued for retraining |
| FR-03-06 | Account freeze logic: 3+ HIGH-risk transactions from same account within 1 hour triggers full account freeze + risk officer notification | S | Third HIGH transaction triggers freeze at exactly 3; risk officer email dispatched; audit log records freeze with reason |
| FR-03-07 | MCC circuit breaker: fraud rate > 5% from specific MCC in 15-minute rolling window flags all transactions to that MCC for analyst review | S | Circuit breaker triggers within 60s of threshold breach; resets after 60-minute clean window; circuit state visible in admin panel |
| FR-03-08 | All auto-block decisions append-only in audit log; no decision deletable or modifiable; override creates new audit entry referencing original | M | Attempted UPDATE on auto_block_events returns PostgreSQL permission denied; confirmed by database security test |

3.4  FR-04: Analyst Dashboard

| ID | Requirement | Priority | Acceptance Criterion |
|---|---|---|---|
| FR-04-01 | Analyst authenticates with email + password OR Google OAuth 2.0; JWT issued; first name, last name, avatar displayed in header | M | Both auth paths issue ANALYST JWT; Google profile (first name, last name, email, avatar) stored and displayed correctly |
| FR-04-02 | Real-time alert feed via WebSocket; sorted fraud_probability descending; HIGH pinned at top; MEDIUM shows countdown timer | M | New alert on all connected dashboards within 1 second; MUI DataGrid virtualised for 10,000+ rows without performance degradation |
| FR-04-03 | Alert card shows: risk badge (HIGH/MEDIUM), fraud probability MUI CircularProgress gauge, amount + currency, channel icon + label, masked account name, merchant, timestamp, top-3 SHAP feature pills | M | All 8 elements present on every alert card; SHAP pills show feature name + direction arrow + contribution value |
| FR-04-04 | One-click CONFIRM FRAUD or MARK LEGITIMATE with mandatory comment field (min 10 characters); actions complete within 2 seconds | M | Buttons disabled until comment >= 10 chars; submission shows MUI CircularProgress; success removes alert from feed; undo Snackbar 5 seconds |
| FR-04-05 | SHAP waterfall chart in investigation panel: all 44 features ranked by absolute SHAP value; positive contributions red, negative green; base value and final score annotated | M | Waterfall chart renders within 500ms; Recharts HorizontalBarChart; MUI Tooltip per bar showing plain-English description specific to East African context |
| FR-04-06 | Account history timeline: last 30 transactions with amount, channel icon, timestamp, risk score MUI Chip, outcome badge (APPROVED/BLOCKED/PENDING) | M | Timeline renders within 500ms; accurate against TimescaleDB; scrollable; sorted most recent first |
| FR-04-07 | Behavioural fingerprint: 4-panel view: hourly transaction pattern heatmap, amount distribution histogram, channel breakdown pie chart, 'This transaction vs your normal' comparison table | S | All 4 panels render within 1 second; comparison table highlights anomalous fields in amber; accurate against TimescaleDB history |
| FR-04-08 | Transaction network graph: flagged account + counterparty + 2nd-degree connections; known fraud nodes highlighted red with glow | C | Graph renders up to 50 nodes within 2 seconds using vis-network or D3; fraud nodes correctly coloured; zoom and pan supported |
| FR-04-09 | Alert filter and search: by transaction_id, account_id, amount range, channel, date range, risk tier, analyst, outcome | M | Composable filters; results within 500ms; URL updates so filtered view is bookmark-shareable |
| FR-04-10 | Priority escalation: ANALYST escalates to SENIOR_ANALYST or RISK_OFFICER with mandatory escalation reason | M | Escalation creates new alert entry at higher tier; original linked; escalation reason stored; target role analyst notified via WebSocket |
| FR-04-11 | Analyst performance dashboard: alerts reviewed today, average review time (seconds), accuracy rate from customer verifications, backlog size | S | Metrics update in real time; false positive rate correctly calculated from customer_verifications table |
| FR-04-12 | WCAG 2.1 AA: risk tier conveyed by text label not colour alone; all controls keyboard-navigable; SHAP chart readable by screen reader | S | 0 critical Axe violations; keyboard navigation confirmed; NVDA announces risk tier text correctly |

3.5  FR-05: Risk Officer Panel

| ID | Requirement | Priority | Acceptance Criterion |
|---|---|---|---|
| FR-05-01 | Risk Officer sees all analyst alerts plus escalated alerts; can senior-override any analyst decision with mandatory reason | M | Override creates immutable audit entry; both analyst and customer notified; original decision preserved in audit log |
| FR-05-02 | Portfolio risk dashboard: fraud prevented today/week/month (RWF amount + count), fraud rate by channel, fraud rate by MCC, geographic fraud heatmap (Leaflet.js), top-10 fraud counterparty accounts | M | All metrics accurate against TimescaleDB; Leaflet heatmap renders within 2 seconds; update in real time |
| FR-05-03 | Model performance monitoring: live AUC-ROC from analyst labels, precision, recall, F1, false positive rate trend, drift indicator vs deployment baseline | M | Metrics updated hourly; drift alert if AUC-ROC drops > 0.03 from baseline; MLflow comparison chart embedded in panel |
| FR-05-04 | Fraud campaign detection: clusters of related fraudulent transactions grouped by shared feature (device, counterparty, geography, MCC) within rolling time window | S | Campaign grouping job runs every 15 minutes; clusters visible with shared feature highlighted; campaign status (ACTIVE/CLOSED) manageable |
| FR-05-05 | Custom rule management: create, enable, disable, delete alert rules using structured rule DSL without code deployment | S | Rule change takes effect within 60 seconds; rule audit log records creator, change history; rule engine unit tests cover all DSL operators |
| FR-05-06 | SAR (Suspicious Activity Report) auto-draft for confirmed fraud transactions in Rwanda BNR reporting format; exportable as PDF | S | SAR draft generated within 30 seconds of fraud confirmation; all required BNR fields populated; PDF export produces valid document |
| FR-05-07 | Per-channel threshold management: different HIGH/MEDIUM/LOW thresholds for each of 6 channel types | M | Per-channel thresholds configurable in admin UI; take effect within 60 seconds; current thresholds visible per channel in risk officer view |

3.6  FR-06: Admin Panel

| ID | Requirement | Priority | Acceptance Criterion |
|---|---|---|---|
| FR-06-01 | Admin creates staff accounts: first name, last name, email, phone (country code flag + dial code + local number stored as E.164), employee ID, department, role (ANALYST/SENIOR_ANALYST/RISK_OFFICER/ADMIN) | M | All fields stored; UNIQUE(email); UNIQUE(employee_id); new account receives welcome email with temporary password + Google OAuth setup link |
| FR-06-02 | Admin views, updates, and deactivates any account; deactivated accounts blocked from all auth methods including Google OAuth | M | Deactivated OAuth account blocked server-side within 5 seconds of deactivation; confirmed by login attempt test |
| FR-06-03 | Model management: list all MLflow model versions with training date, dataset size, AUC-ROC, precision, recall, F1; promote to production; rollback to previous; enable/disable shadow mode | M | Promotion takes effect within 60 seconds via MLflow model registry; rollback within 30 seconds; no traffic interruption during switch; shadow mode toggle confirmed |
| FR-06-04 | Dataset management: upload new labelled transaction CSV for retraining; trigger retraining job; monitor progress as percentage in real time | S | Retraining starts within 60 seconds of trigger; progress visible; new model auto-evaluated by evaluate.py before promotion option appears |
| FR-06-05 | System health panel: Kafka consumer lag per topic, ML scoring p50/p95/p99 latency, API error rate, Redis hit rate, DB pool usage, auto-block rate, alert queue depth, analyst response time distribution | M | All metrics from Prometheus; 30-day history in Grafana embedded in panel; configurable alert thresholds with colour indicators |
| FR-06-06 | Full audit log: every system action with user first name + last name, role, timestamp, action type, entity, before/after values; searchable; immutable | M | 12 action types logged; all write operations audited; no DELETE or UPDATE on audit_events; searchable by user, date range, action type |
| FR-06-07 | API key management: create, rotate, revoke keys for core banking integrations; each key scoped to specific endpoint groups; key name and last-4 chars visible in UI | M | Revoked key returns 401 within 5 seconds; rotation creates new key with 24-hour overlap period; raw key shown exactly once at creation |

3.7  FR-07: Authentication and Authorisation

| ID | Requirement | Priority | Acceptance Criterion |
|---|---|---|---|
| FR-07-01 | Four roles with distinct access scopes: ANALYST, SENIOR_ANALYST, RISK_OFFICER, ADMIN; role embedded in JWT claims; cannot self-elevate | M | Role-based tests confirm 403 on all wrong-role endpoint combinations; role claim immutable after issue |
| FR-07-02 | Email + password registration: first name, last name, country-code phone (flag + dial code selector, 249 countries), employee ID, department, password + confirm password; strength meter and complexity requirements | M | Confirm password mismatch blocks submit; strength meter 4 levels; specific failure reasons listed; submit disabled until all validations pass |
| FR-07-03 | Google OAuth 2.0 sign-in on login and registration pages; Google ID token verified server-side against Google JWKS; account linked by email | M | OAuth flow < 3 seconds; invalid token returns 401; account linking by email confirmed; first name, last name, avatar from Google stored |
| FR-07-04 | JWT access tokens: 15-minute expiry; RS256; containing user_id, role, first_name, email; refresh tokens: 7-day expiry in httpOnly SameSite=Strict cookie | M | Expired access token returns 401; RS256 signature verified; refresh token rotation confirmed; httpOnly cookie confirmed in browser DevTools |
| FR-07-05 | Machine-to-machine API keys for transaction ingestion; keys scoped to ingestion endpoints only; cannot access analyst, admin, or ML endpoints | M | API key returns 403 on /api/v1/alerts and /api/v1/admin; confirmed by explicit permission test for every key-accessible endpoint combination |
| FR-07-06 | Login rate limited: 10 attempts per 15 minutes per IP; 5 failed attempts locks analyst account with unlock email sent to account email | M | 11th IP attempt returns 429 with Retry-After header; 5th failed attempt triggers account lock; unlock email sent within 30 seconds |
| FR-07-07 | Password policy: minimum 8 characters, 1 uppercase, 1 lowercase, 1 digit, 1 special character; bcrypt cost factor 12 | M | Weak password returns 400 with specific failure reasons; bcrypt cost 12 confirmed by timing test (> 100ms per hash) |
| FR-07-08 | Password reset: email OTP (6 digits, 10-minute expiry); OTP verified server-side; single-use; triggers password change form | S | OTP email delivered within 30 seconds; expired OTP returns 400; used OTP cannot be reused; confirmed by integration test |
| FR-07-09 | All sessions invalidated on password change; Google OAuth session revoked via Google token revocation API on logout | M | Login with old JWT after password change returns 401; Google revocation API call confirmed by mock in integration test |

04  Non-Functional Requirements
4.1  Performance — Financial System Grade

| Metric | Target | Measurement Method |
|---|---|---|
| Transaction ingestion throughput | 10,000+ TPS sustained for 5 minutes | Locust test: 10k TPS; Kafka consumer lag < 500 msgs; 0 data loss |
| Auto-block end-to-end latency | p50 < 30ms  \|  p95 < 50ms  \|  p99 < 80ms | Prometheus histogram: transaction_received to block_decision events |
| ML ensemble scoring latency | p50 < 15ms  \|  p95 < 25ms  \|  p99 < 40ms (including SHAP for flagged) | Per-request timing in structlog; Grafana time-series; MLflow tracking |
| Feature engineering latency | p95 < 10ms including Redis feature store fetch | Redis response time + computation time measured separately in Prometheus |
| Analyst dashboard real-time update | < 1 second from Kafka event to browser DOM update | End-to-end integration test with millisecond-precision timestamps |
| SHAP waterfall chart render | < 500ms from investigation panel open to chart interactive | Playwright performance test; Recharts render timing measured |
| Alert feed initial load | < 1 second for 500 alerts in virtualised MUI DataGrid | Playwright test with 500 seeded alerts; time to interactive measured |
| Google OAuth login flow | < 3 seconds from button click to authenticated dashboard | Playwright E2E with Google test account; wall-clock timing |
| System availability | 99.9% monthly (< 9 hours downtime per year) | Prometheus blackbox uptime probe every 30 seconds; PagerDuty integration |
| API ingestion error rate | < 0.1% errors at 10,000 TPS | Locust: error percentage monitored; target < 0.1% at peak load |

4.2  Security — Financial Institution Grade

| Requirement | Implementation | Verification |
|---|---|---|
| Encryption at rest | PostgreSQL TDE + application-layer AES-256 for PII fields; Redis encryption at rest | TDE configuration audit; field encryption verified: raw PII not visible in any SQL query from application role |
| Encryption in transit | TLS 1.3 for all external connections; mTLS for service-to-service (Spring Boot to Python ML scorer) | SSL Labs A+ rating; mTLS certificate exchange verified in integration test |
| PII tokenisation | Account holder name and phone stored as opaque tokens; actual PII in separate encrypted PII store; no raw PII in analyst-facing API responses | API response audit: account_name field in all analyst endpoints shows masked token only; confirmed by inspector test |
| No secrets in version control | All secrets in Kubernetes Secrets or environment variables; Gitleaks in every CI run | CI fails on any Gitleaks pattern match; no credential string in any committed file |
| Audit log immutability | audit_events PostgreSQL table: INSERT-only permissions granted to application role; no UPDATE or DELETE ever granted | Attempted UPDATE on audit_events returns permission denied from application DB user; confirmed by security test |
| SQL injection prevention | Spring Data JPA parameterised queries; Python SQLAlchemy ORM throughout; zero raw SQL string concatenation | OWASP ZAP automated scan: 0 SQL injection findings; SonarQube: 0 injection hotspots |
| XSS prevention | React DOM escaping default; strict Content-Security-Policy header; DOMPurify on all dynamic HTML; MUI XSS-safe | OWASP ZAP: 0 XSS findings; CSP header present with report-uri |
| API key security | Keys stored as bcrypt hash; raw key displayed once at creation; rotation creates new key before old expiry | Raw key not recoverable from DB; key rotation integration test passes |
| Data residency | All data in Rwanda/EAC region; no export to non-EAC jurisdictions without BNR authorisation | Cloud region af-south-1 or equivalent; storage audit confirms location |
| Annual penetration test | Third-party pen test; OWASP Top 10 + PCI DSS relevant controls | Zero critical findings required before production; pen test report stored in docs/security/ |

4.3  Reliability — Failure Scenarios

| Failure | System Behaviour | Recovery | Test |
|---|---|---|---|
| ML scoring service down | Rule-based fallback engine activates within 5 seconds; transactions continue with ML_UNAVAILABLE label | ML service restart resumes scoring; backlog caught up within 2 minutes | Integration: ML service pod killed; fallback activates; Kafka lag clears after restart |
| Kafka broker failure | Spring Boot producer retries with exponential backoff (max 30s total); up to 10,000 transactions buffered in memory | Kafka restart resumes within 60 seconds; buffered transactions replayed in order; no data loss | Integration: Kafka pod killed; producer buffers; replay confirmed after restart |
| Redis feature store down | Feature engineering uses DB fallback for velocity features; performance degrades but system continues | Redis reconnects automatically; feature store rebuilt from PostgreSQL within 5 minutes | Unit: all Redis calls wrapped in try/except with DB fallback path tested |
| PostgreSQL primary failure | TimescaleDB replica promoted within 30 seconds via Kubernetes operator; brief write pause | Automatic failover; < 30 second write downtime; synchronous replica ensures zero data loss | Chaos engineering: DB primary pod deleted; replica promotion timed |
| Duplicate transaction storm | Idempotency key in Redis (24h TTL) ensures each transaction_id scored exactly once | Cache hit returns in < 1ms; no reprocessing; no duplicate blocks | Load test: 10,000 identical transaction_ids; exactly 1 scored; all return cached response |
| Google OAuth service unavailable | Email + password auth continues; Google OAuth button shows 'Temporarily unavailable'; no system downtime | OAuth-only users prompted to set password via email OTP; all other auth unaffected | Integration: Google JWKS endpoint mocked as 503; email auth confirmed working |

05  UI/UX Design Requirements
5.1  Design Philosophy — Mission-Critical Finance Interface
FraudShield's interface serves fraud analysts working under extreme time pressure. A HIGH-risk alert that goes unreviewed for 30 seconds may result in a customer's account being compromised. The interface must communicate risk severity, the reason for the alert, and the action required in under 3 seconds of reading time. There is no room for ambiguity or decorative complexity.
Three non-negotiable design principles: Risk hierarchy — the risk tier (HIGH/MEDIUM) is the most prominent visual element on every screen; nothing competes with it visually. Explainability first — every ML decision is explained in plain English before the analyst takes action; the score alone is never sufficient. Density without clutter — analysts are professionals who need maximum information; every pixel is used purposefully.
5.2  Design System Tokens

| Token | Hex | MUI Override | Usage in FraudShield |
|---|---|---|---|
| risk.high.bg | #FEF2F2 | — | HIGH risk alert card and drawer background |
| risk.high.border | #DC2626 | palette.error.main | HIGH risk card border — Tailwind animate-pulse 1200ms infinite |
| risk.high.badge | #DC2626 | — | 'HIGH RISK' MUI Chip; text always included for accessibility |
| risk.medium.bg | #FFFBEB | — | MEDIUM risk alert card background |
| risk.medium.border | #D97706 | palette.warning.main | MEDIUM risk card border; static (not pulsing) |
| risk.medium.badge | #D97706 | — | 'REVIEW' MUI Chip |
| risk.low.bg | #F0FDF4 | — | Approved transaction card (read-only history view) |
| risk.low.badge | #059669 | palette.success.main | 'APPROVED' MUI Chip |
| shap.increases_fraud | #DC2626 | — | SHAP Recharts bar — feature increases fraud probability |
| shap.decreases_fraud | #059669 | — | SHAP Recharts bar — feature decreases fraud probability |
| channel.mobile_money | #7C3AED | — | MOBILE_MONEY MUI Chip — purple |
| channel.card | #1A56DB | — | CARD MUI Chip — blue |
| channel.ussd | #D97706 | — | USSD MUI Chip — amber |
| channel.agent_banking | #0D9488 | — | AGENT_BANKING MUI Chip — teal |
| channel.online | #6B7280 | — | ONLINE MUI Chip — grey |
| channel.bank_transfer | #374151 | — | BANK_TRANSFER MUI Chip — dark grey |
| brand.navy | #0A2540 | palette.primary.main | Navigation, headings, primary buttons |
| brand.accent | #1A56DB | palette.secondary.main | Links, active states, focus rings |

5.3  Registration Form Specification
All FraudShield staff registration forms (Analyst, Senior Analyst, Risk Officer, Admin) use the following fields and components — consistent with industry standards for financial institution staff onboarding:

| Field | MUI Component | Validation | UX Behaviour |
|---|---|---|---|
| First Name | TextField required | Min 2, max 50 chars; letters + hyphens + apostrophes only | Validates on blur; inline MUI FormHelperText error |
| Last Name | TextField required | Min 2, max 50 chars; letters + hyphens + apostrophes only | Validates on blur; inline error |
| Email | TextField type='email' required | RFC 5322; async duplicate check 500ms debounce | 'Checking availability...' -> tick or 'Email already registered' |
| Phone Number | Select (flag + country name + dial code, 249 countries, virtualised) + TextField local number | E.164 stored; digits only after removing leading zero; 6-12 digits | Flags from flagcdn.com; keyboard navigable; aria-label for screen readers |
| Employee ID | TextField required | Alphanumeric 4-20 chars; UNIQUE per institution; async check | Async uniqueness check; error if duplicate within institution |
| Department | Select required | ANALYST/SENIOR_ANALYST/RISK_OFFICER/ADMIN match role | Dropdown; keyboard accessible; pre-fills role field |
| Password | TextField type='password' required + show/hide IconButton | Min 8; 1 upper; 1 lower; 1 digit; 1 special char (!@#$%^&*) | MUI LinearProgress strength meter; 4 levels; failure reasons listed below field |
| Confirm Password | TextField type='password' required + show/hide toggle | Must match Password; real-time validation on every keystroke in either field | Submit Button disabled until match; immediate error on mismatch |
| OR Divider | MUI Divider with 'or' text centred | — | Separates Google Sign-In from email/password form |
| Google Sign-In | MUI Button + Google SVG logo + 'Continue with Google'; full-width | Google ID token verified server-side against JWKS | Per Google brand guidelines; disabled + CircularProgress during OAuth loading |

5.4  Analyst Dashboard — Key Component Specification

| Component | Specification | Interaction Behaviour |
|---|---|---|
| Stats Header (sticky MUI AppBar) | [HIGH N — red badge] [MEDIUM N — amber] [Auto-blocked N] [Reviewed N] [Fraud Prevented RWF X,XXX,XXX] [Avg Review Xs] [Analysts Online N] | Real-time WebSocket; click any badge to filter; fraud amount animates on update; currency formatted with RWF prefix |
| Alert Feed (MUI DataGrid virtualised) | Columns: risk badge \| fraud score gauge (MUI CircularProgress) \| amount + currency \| channel MUI Chip \| masked account name \| merchant \| timestamp \| countdown (MEDIUM) \| action buttons | Sorted fraud_probability DESC; HIGH pinned to top; new alert animates in from right (Framer Motion); MEDIUM countdown in red at < 10s; virtualised for 10,000+ rows |
| Risk Score Gauge | MUI CircularProgress variant='determinate' value=score*100; colour: >= 0.85 red, 0.60-0.84 amber, < 0.60 green; percentage in centre | MUI Tooltip: 'XGBoost + LightGBM ensemble. 0.85+ = HIGH risk auto-block threshold.' |
| Investigation Drawer (MUI Drawer anchor='right' width=560px) | MUI Tabs: Summary \| SHAP Explanation \| Account History \| Behavioural Fingerprint \| Network Graph | Opens within 200ms; drawer stays open while alert feed remains interactive behind it |
| SHAP Waterfall Chart | Recharts HorizontalBarChart; features sorted by \|SHAP\| descending; positive=red bars, negative=green bars; base value and final score annotated as text | MUI Tooltip per bar: feature name + raw value + plain-English description ('This account made 12 transactions in the last 60 seconds — 8.3x its usual rate.') |
| Action Panel (bottom of drawer) | Full-width: MUI Button 'CONFIRM FRAUD' (red, outlined) + MUI Button 'MARK LEGITIMATE' (green) + MUI TextField comment required min 10 chars + MUI Select escalation | Buttons disabled until comment >= 10 chars; MUI CircularProgress on submit; success closes drawer + removes from feed; MUI Snackbar undo 5 seconds |
| MEDIUM Timer Badge | MUI Chip with countdown seconds; turns red at < 10s; Web Audio API chime at < 5s if notifications enabled | setInterval every second; audio via Web Audio API only if user has explicitly enabled browser notifications |
| Account History Timeline | MUI Timeline (vertical); each event: channel MUI Chip + amount + timestamp + risk score MUI Chip + outcome badge | Most recent first; scrollable within panel; 30-transaction limit with 'Load more' button |
| Behavioural Fingerprint (4 panels) | MUI Grid 2x2: (1) Recharts BarChart hourly tx pattern; (2) Recharts Histogram amounts; (3) Recharts PieChart channels; (4) MUI Table 'This transaction vs your normal' | Comparison table highlights anomalous cells in amber with amber border; 'normal' values computed from last 90 days |

5.5  Responsive Breakpoints — Summary (Full cross-device spec in Section 05B)

| Breakpoint | Tailwind / MUI | Min Width | Layout |
|---|---|---|---|
| Mobile | sm | 375px | Alert feed full width; investigation in full-screen MUI Dialog; stats bar collapses to icon row with MUI Badge |
| Tablet | md | 768px | Two panels: alert feed 55% + investigation drawer 45%; stats bar visible |
| Laptop | lg | 1024px | Three panels: MUI Drawer sidebar nav (collapsed) + alert feed + investigation drawer; sticky stats AppBar |
| Desktop | xl | 1280px | Full layout; sidebar nav expanded; analytics sidebar visible alongside investigation drawer |
| Large Monitor | 2xl | 1536px | Max content width 1440px; MUI Container maxWidth='xl'; wider investigation drawer for graph tab |

06  Data Model — Standalone FraudShield Schema
FraudShield has its own independent PostgreSQL database. It shares no schema, no tables, and no data with any other project. All tables use TimestampedModel base (id, created_at UTC, updated_at). TimescaleDB hypertables used for transactions and audit_events for time-series performance. All PII fields stored as opaque tokens — raw PII in separate encrypted store inaccessible from application layer.

| FraudShield Database Independence <br> Database name    :  fraudshield_db <br> Database host    :  Managed PostgreSQL 16 + TimescaleDB extension <br> Shares schema with any other project  :  NO — completely independent <br> Shares tables with any other project  :  NO <br> Shares database server                :  NO (separate PostgreSQL instance) <br> Data flows to/from other projects     :  NO <br> This is a standalone schema for a standalone project. |
|---|


| Table | Complete Fields | Relationships | Critical Constraints |
|---|---|---|---|
| users | id, first_name, last_name, email, phone (E.164 nullable), password_hash (nullable for OAuth users), role ENUM(ANALYST/SENIOR_ANALYST/RISK_OFFICER/ADMIN), is_active, avatar_url, oauth_provider (nullable), oauth_id (nullable), employee_id, department, last_login_at, failed_login_count (int default 0) | Standalone FraudShield auth table; referenced by alert_decisions.analyst_id, audit_events.user_id | UNIQUE(email); UNIQUE(employee_id); UNIQUE(oauth_provider,oauth_id); failed_login_count CHECK >= 0; account locked if failed_login_count >= 5 |
| transactions (TimescaleDB hypertable) | id, transaction_id (UUID UNIQUE), account_id (tokenised VARCHAR), counterparty_id (tokenised VARCHAR), amount (DECIMAL 18,4), currency (CHAR 3), channel ENUM(6 values), merchant_category_code (CHAR 4), latitude, longitude, device_fingerprint (nullable), transaction_timestamp (TIMESTAMPTZ), received_at (TIMESTAMPTZ), processing_duration_ms (INT) | TimescaleDB partitioned by transaction_timestamp (monthly chunks); 1:1 fraud_scores | UNIQUE(transaction_id); amount CHECK > 0; transaction_timestamp NOT NULL; composite index (account_id, transaction_timestamp) for velocity queries |
| fraud_scores | id, transaction_id (FK UNIQUE), ensemble_score (FLOAT), xgboost_score (FLOAT), lightgbm_score (FLOAT), anomaly_score (FLOAT), risk_tier ENUM(HIGH/MEDIUM/LOW), shap_top5 (JSONB), feature_vector (JSONB), model_version (VARCHAR), scoring_duration_ms (INT), requires_analyst_review (BOOL), ml_unavailable_fallback (BOOL default false) | transaction_id -> transactions.transaction_id; 1:1 alert_queue_entries; 1:1 auto_block_events | ensemble_score CHECK 0.0-1.0; shap_top5 NOT NULL when risk_tier IN (HIGH,MEDIUM); model_version NOT NULL |
| alert_queue_entries | id, fraud_score_id (FK), assigned_analyst_id (FK nullable), priority_tier ENUM(HIGH/MEDIUM), status ENUM(PENDING/IN_REVIEW/CONFIRMED_FRAUD/MARKED_LEGITIMATE/AUTO_RELEASED/ESCALATED/OVERRIDDEN), assigned_at (TIMESTAMPTZ nullable), review_deadline_at (TIMESTAMPTZ nullable), escalated_to_tier (VARCHAR nullable), escalation_reason (TEXT nullable) | fraud_score_id -> fraud_scores.id; assigned_analyst_id -> users.id; 1:N alert_decisions | status transitions enforced by application service; review_deadline_at set on MEDIUM entry creation (NOW() + 30s) |
| alert_decisions | id, alert_queue_entry_id (FK), analyst_id (FK), decision ENUM(CONFIRM_FRAUD/MARK_LEGITIMATE/ESCALATE/AUTO_RELEASED/AUTO_BLOCKED/SENIOR_OVERRIDE), analyst_comment (TEXT nullable), decision_duration_seconds (INT), false_positive_confirmed (BOOL nullable), override_of_decision_id (FK nullable) | alert_queue_entry_id -> alert_queue_entries.id; analyst_id -> users.id; override_of -> alert_decisions.id | analyst_comment NOT NULL and LENGTH >= 10 for CONFIRM_FRAUD and MARK_LEGITIMATE; IMMUTABLE after creation |
| auto_block_events | id, transaction_id (VARCHAR FK), fraud_score_id (FK), blocked_at (TIMESTAMPTZ), block_reason (VARCHAR), customer_sms_sent (BOOL), customer_sms_sent_at (TIMESTAMPTZ nullable), customer_verified (BOOL default false), verified_at (TIMESTAMPTZ nullable), unblocked_at (TIMESTAMPTZ nullable), account_frozen (BOOL default false), account_frozen_at (TIMESTAMPTZ nullable) | transaction_id -> transactions.transaction_id; fraud_score_id -> fraud_scores.id; 1:1 customer_verifications | blocked_at NOT NULL; IMMUTABLE after creation; unblock recorded in separate unblock_events entry |
| account_velocity_cache | id, account_id (VARCHAR UNIQUE), tx_count_60s, tx_count_1h, tx_count_24h, tx_count_7d, amount_sum_24h, amount_sum_7d, unique_counterparties_24h, last_tx_latitude, last_tx_longitude, last_tx_channel, last_tx_at (TIMESTAMPTZ), updated_at (TIMESTAMPTZ) | No FK to transactions — performance design decision; Redis is primary; DB is fallback only | UNIQUE(account_id); updated atomically; stale entries: TTL enforced via updated_at + 30-day cleanup job |
| model_versions | id, model_version (VARCHAR UNIQUE), algorithm, xgb_weight (FLOAT), lgb_weight (FLOAT), training_dataset_size, training_date, auc_roc, precision_at_1pct_fpr, recall, f1_score, ece (calibration error), feature_importance (JSONB), mlflow_run_id, is_production (BOOL), is_shadow (BOOL), deployed_at (TIMESTAMPTZ nullable), retired_at (TIMESTAMPTZ nullable) | Standalone ML audit table | UNIQUE(model_version); partial unique index: at most 1 row with is_production=True; at most 1 with is_shadow=True; mlflow_run_id indexed |
| fraud_campaigns | id, campaign_name (VARCHAR UNIQUE), detected_at (TIMESTAMPTZ), shared_feature_type ENUM(DEVICE/COUNTERPARTY/GEOGRAPHIC/MCC), shared_feature_value, transaction_count, total_amount (DECIMAL), status ENUM(ACTIVE/CLOSED), closed_at (TIMESTAMPTZ nullable), closed_by_analyst_id (FK nullable) | closed_by_analyst_id -> users.id; M:N to transactions via fraud_campaign_transactions join table | UNIQUE(campaign_name); shared_feature_value NOT NULL; status NOT NULL |
| alert_rules | id, rule_name (VARCHAR UNIQUE), description (TEXT), rule_expression (JSONB structured DSL), risk_tier_override ENUM(HIGH/MEDIUM), is_active (BOOL default true), created_by_id (FK), last_triggered_at (TIMESTAMPTZ nullable), trigger_count (INT default 0) | created_by_id -> users.id | UNIQUE(rule_name); rule_expression validated against JSON schema on creation; immutable after creation — disable/enable only |
| audit_events (TimescaleDB hypertable) | id, user_id (FK nullable — null for system events), user_first_name (VARCHAR), user_last_name (VARCHAR), user_role (VARCHAR), event_type (VARCHAR), entity_type (VARCHAR), entity_id (VARCHAR), before_value (JSONB nullable), after_value (JSONB nullable), ip_address (INET), user_agent (TEXT), event_at (TIMESTAMPTZ) | user_id -> users.id (nullable for system-generated events) | APPEND-ONLY; no UPDATE or DELETE permissions on application DB role; index on (event_type, event_at) and (user_id, event_at); 7-year BNR regulatory retention |
| customer_verifications | id, auto_block_event_id (FK UNIQUE), verification_token_hash (VARCHAR UNIQUE), expires_at (TIMESTAMPTZ), verified_at (TIMESTAMPTZ nullable), verification_channel ENUM(SMS_LINK/PUSH/IVR), ip_address_verified_from (INET nullable) | auto_block_event_id -> auto_block_events.id | UNIQUE(auto_block_event_id) — one verification per block event; UNIQUE(verification_token_hash); single-use token; expires_at CHECK > created_at |
| refresh_tokens | id, user_id (FK), token_hash (VARCHAR UNIQUE), expires_at (TIMESTAMPTZ), is_revoked (BOOL default false), created_by_ip (INET), oauth_provider (VARCHAR nullable), user_agent (TEXT nullable) | user_id -> users.id | UNIQUE(token_hash); expires_at indexed for cleanup job; composite index (user_id, is_revoked) for fast token validation |

07  Machine Learning Requirements
7.1  FraudShield-EAC-Transactions Dataset

| Requirement | Specification | Rationale |
|---|---|---|
| Total size | >= 5,000,000 labelled transactions (4.16M train / 520K val / 520K test) | Sufficient for XGBoost/LightGBM to learn rare fraud at realistic 0.87% fraud rate |
| Fraud rate | 0.87% overall; test set slightly higher (0.91%) to reflect growing fraud trend | Realistic imbalance; no artificial oversampling of training set |
| Channel distribution | MOBILE_MONEY >= 40%, USSD >= 18%, AGENT_BANKING >= 14%, CARD 12%, ONLINE 9%, BANK_TRANSFER 6% | Reflects East African payment landscape; essential for generalisability |
| Fraud pattern diversity | 8 distinct types: SIM-swap, account-takeover, agent-fraud, velocity-fraud, card-not-present, mule-account, merchant-fraud, synthetic-identity | Prevents model from overfitting to single dominant pattern |
| Geographic coverage | Rwanda 42%, Kenya 28%, Tanzania 15%, Uganda 10%, DRC 5% | Covers all EAC primary financial markets; enables per-country evaluation |
| Temporal coverage | 24 months simulation; test set uses final 1.5 months (temporal holdout) | Prevents temporal data leakage; tests generalisation to future fraud patterns |
| Feature completeness | All 44 features computable for >= 98% of records; < 2% any missing feature | Missing features handled by median imputation; completeness verified in pipeline |
| Dataset release | FraudShield-EAC-Transactions; CC BY 4.0; HuggingFace Datasets Hub + Zenodo DOI | First public East African financial fraud benchmark dataset — standalone research contribution |

7.2  Model Deployment Gate — Minimum Acceptable Metrics
All metrics MUST be confirmed by evaluate.py before CI/CD permits model promotion. Shadow mode mandatory minimum 24 hours before full promotion to production.

| Metric | Threshold | Clinical / Financial Justification |
|---|---|---|
| AUC-ROC (last-3-month test set) | 0.940 | Primary ranking performance metric; measures quality across all thresholds |
| Precision at 1% FPR | 0.720 | At 1% false positive rate; controls analyst alert volume to manageable level |
| Recall (fraud capture rate) | 0.880 | Minimum proportion of actual fraud caught; balance against FPR |
| F1 Score | 0.800 | Harmonic mean; balanced measure for imbalanced classes |
| False Positive Rate at threshold 0.85 | < 1.5% | At auto-block threshold; acceptable customer friction rate |
| False Negative Rate | < 12.0% | Maximum fraud passing through undetected at threshold 0.85 |
| MOBILE_MONEY channel AUC-ROC | 0.920 | East African dominant channel requires explicit validation |
| USSD channel AUC-ROC | 0.900 | USSD lacks device fingerprint; harder problem; separate validation required |
| AGENT_BANKING channel AUC-ROC | 0.910 | Agent fraud is East African-specific; novel validation requirement |
| SHAP coverage | 100% for HIGH and MEDIUM risk transactions | Non-negotiable for analyst trust and BNR regulatory explainability requirement |
| Expected Calibration Error (ECE) | < 0.050 | Probability scores must be calibrated; 0.85 score = ~85% fraud probability |
| Inference latency p99 | < 40ms (ensemble + SHAP for flagged) | Financial system requirement; exceeding causes auto-block latency breach |
| Shadow mode AUC-ROC delta | New model within 1% of production before promotion | Prevents regression during model updates; minimum 24-hour shadow period |

08  Infrastructure and DevOps — Standalone Project

| FraudShield Infrastructure Independence <br> FraudShield has its own independent infrastructure. It does NOT share: <br> - Docker Compose configuration with any other project <br> - Kubernetes namespace or cluster with any other project <br> - GitHub Actions workflows with any other project <br> - Prometheus / Grafana instance with any other project <br> - Database server with any other project <br> Every infrastructure file lives in github.com/mariusbayizere/fraudshield/infrastructure/ |
|---|

8.1  CI/CD Pipeline — github.com/mariusbayizere/fraudshield

| Stage | Trigger | Steps | Gate Criteria |
|---|---|---|---|
| Lint + Static Analysis | Every push to any branch | Checkstyle + SpotBugs (Java); ruff + mypy strict (Python); eslint + tsc --noEmit (TypeScript); SonarQube gate | Zero lint errors; zero type errors; SonarQube quality gate passes; zero critical security hotspots |
| Unit Tests | Every push | JUnit 5 (Spring Boot, target >= 85% coverage); pytest (Python ML pipeline, target >= 90%); vitest (React components) | All tests pass; no new failures; coverage thresholds met |
| Integration Tests | Push to develop/main | Spring Boot Testcontainers (PostgreSQL + TimescaleDB + Redis + Kafka); ML pipeline end-to-end; OAuth mock; full transaction pipeline test | All integration tests pass; transaction pipeline latency within target; no Kafka message loss |
| Security Scan | Push to main | OWASP Dependency Check (Java + Python + Node); Gitleaks; trivy container scan; OWASP ZAP API scan against staging | Zero high-severity CVEs; no secrets; 0 critical ZAP findings; no PII in API responses confirmed |
| ML Evaluation Gate | Change to fraudshield/ml/models/ directory | evaluate.py on held-out last-3-months test set; all 13 metrics in Section 7.2 validated; shadow mode comparison report | Deployment blocked if any metric below threshold; LaTeX metric table generated for research paper as CI artifact |
| Docker Build | Push to main | docker build: fraudshield-api (Spring Boot), fraudshield-ml (Python), fraudshield-frontend (React); push to GitHub Container Registry | All 3 images build; api < 500MB; ml < 1.2GB; frontend < 150MB |
| Staging Deploy | Merge to main | Kubernetes rolling update to fraudshield-staging namespace; smoke test suite; Spring Boot actuator health | Smoke tests pass; /actuator/health = UP; /api/v1/health/ml = UP; /api/v1/health/kafka = UP |
| Production Deploy | Manual approval after staging + ML gate | 10% Kubernetes canary for 30 minutes; auto-rollback if error rate > 0.5% or p99 > 80ms | Canary passes; full rollout; zero downtime confirmed; PagerDuty alert if rollback triggered |

8.2  Observability Stack

| Signal | Tool | Key Metrics | Alert Threshold |
|---|---|---|---|
| Application Metrics | Prometheus + Grafana (fraudshield-specific dashboards) | Transaction TPS, scoring p50/p95/p99, auto-block rate, false positive rate, Kafka consumer lag per topic, analyst review time distribution, model version in use | TPS drop > 20%; p99 > 80ms; false positive rate > 5%; Kafka lag > 5,000 messages |
| Structured Logs | Python structlog JSON + Java SLF4J Logback JSON | Every transaction scored (transaction_id, ensemble_score, risk_tier, channel, model_version, duration_ms), every auto-block, every analyst decision, every model promotion, every threshold change | Any ERROR log triggers PagerDuty; auto-block rate anomaly (> 3x baseline) triggers immediate risk officer alert |
| ML Drift Monitoring | Hourly MLflow job + Grafana | Feature PSI per feature (Population Stability Index), model AUC-ROC from analyst labels, false positive rate trend, fraud rate by channel trend | PSI > 0.2 any top-10 feature triggers model review; AUC-ROC drop > 0.03 from baseline triggers retraining evaluation |
| Regulatory Audit Trail | TimescaleDB audit_events read-only replica | Every transaction decision, auto-block, analyst action, model change, threshold change, rule change — immutable, timestamped | 7-year retention; accessible to BNR compliance officers; no modifications possible |
| Uptime | Prometheus blackbox exporter + PagerDuty | HTTP probe /actuator/health every 30 seconds; /api/v1/health/ml every 60 seconds | 2 consecutive failures trigger PagerDuty P1 alert; measured against 99.9% monthly SLA |
| Fraud Rate Anomaly | TimescaleDB continuous aggregate + alert rule | Hourly fraud rate vs 30-day moving average per channel | Fraud rate > 3 standard deviations from mean triggers Risk Officer notification within 5 minutes |

09  Testing Requirements

| Test Type | Framework | Coverage Target | Key Scenarios |
|---|---|---|---|
| Unit: Feature Engineering | pytest | All 44 features; all 6 channel types; edge cases | USSD: 40 features computed without device_fingerprint; AGENT_BANKING: agent-specific features only computed for AGENT channel; round_sum_flag: correct for EAC cultural norms; velocity: correct with 0 historical transactions |
| Unit: ML Scoring | pytest + unittest.mock | All scoring paths; calibration; SHAP coverage; fallback | Ensemble weighted average = XGB*0.55 + LGB*0.45 to 5 decimal places; Isolation Forest score in [-1,1]; SHAP sum = model output ± 0.001; fallback rule-based engine activates when ML scorer returns 503 |
| Unit: Risk Decision Engine | pytest | All threshold combinations; circuit breakers; idempotency | HIGH >= 0.85 always auto-blocks; MEDIUM timer starts at exactly 30s; LOW always approves; MCC circuit breaker at exactly 5% fraud rate in 15-min window; idempotency key prevents double-block |
| Unit: Auth (JWT + OAuth + API Key) | pytest + JUnit 5 | All auth paths; role scoping; key scoping | RS256 JWT verified; expired token 401; API key returns 403 on analyst endpoints; Google mock verified; account linking confirmed; bcrypt cost 12 timing > 100ms |
| Integration: Transaction Pipeline | JUnit 5 + Testcontainers | End-to-end from HTTP to analyst dashboard | Transaction ingested -> Kafka published -> ML scored -> risk decision -> auto-block OR analyst alert; p95 < 50ms confirmed; duplicate idempotency: 100 identical submissions = 1 scored |
| Integration: Analyst Decision Flow | pytest-asyncio + Playwright | Complete alert lifecycle | CONFIRM_FRAUD: block + audit entry + analyst comment stored + false_positive_confirmed=null; MARK_LEGITIMATE: approve + false_positive_confirmed=true + customer verification link generated; ESCALATE: new entry at SENIOR_ANALYST tier |
| Integration: OAuth Flow | pytest + responses mock | Google OAuth happy path + failure paths | Google JWKS verification succeeds; invalid token 401; account linking by matching email; new Google user creates ANALYST role account; Google outage: email auth unaffected |
| Integration: Model Shadow Mode | pytest | Shadow model runs alongside production | Shadow model scores every transaction; only production triggers actions; shadow score in audit log; MLflow comparison updated after each transaction |
| Performance: Load | Locust | 10,000 TPS for 5 minutes | All within latency SLA; Kafka consumer lag < 500 msgs; 0 data loss; auto-block rate matches expected fraud rate; idempotency holds under load |
| Performance: ML Throughput | Custom benchmark (benchmark.py) | 200 concurrent scoring requests | p50 < 15ms; p99 < 40ms; no memory growth > 50MB over 10,000 consecutive scorings; model in memory (not reloaded) |
| Frontend: Unit | Vitest + React Testing Library | All analyst dashboard components | RiskGauge: correct colour at all score ranges; ChannelChip: all 6 channels correct colour and label; SHAPWaterfallChart: positive bars red, negative green, sum annotated; ActionPanel: disabled until comment >= 10 chars; TimerBadge: countdown accurate to second |
| Frontend: E2E | Playwright (Chromium + Firefox + WebKit) | 5 critical analyst journeys | (1) Google OAuth login -> alert feed loads -> HIGH alert pulsing -> open drawer -> SHAP chart renders within 500ms -> CONFIRM FRAUD with comment -> alert removed; (2) MEDIUM countdown to 0 -> auto-release label; (3) Analyst escalates to Risk Officer; (4) Admin promotes new model version; (5) Risk Officer views fraud geographic heatmap |
| Security | OWASP ZAP + Gitleaks + Trivy + DB permission test | OWASP Top 10 + financial controls | 0 SQL injection; 0 XSS; PII tokenisation: raw account name not in any API response; audit log: UPDATE returns permission denied; API key: returns 403 on analyst endpoints; 0 secrets in committed code |
| ML Evaluation Gate | evaluate.py | All 13 metrics in Section 7.2 | Automated CI gate; LaTeX table generated as artifact; shadow mode AUC-ROC delta computed and logged to MLflow |

10  Research and Publication Requirements
All research artifacts are published under the FraudShield project identity independently. No co-authorship with any other project. Repository: github.com/mariusbayizere/fraudshield

| Artifact | Format | Licence | Platform |
|---|---|---|---|
| FraudShield-EAC-Transactions dataset | CSV + Parquet; 5M+ transactions; 44 features; 8 fraud types; 6 channels; 5 EAC countries | CC BY 4.0 | HuggingFace: mariusbayizere/fraudshield-eac-transactions + Zenodo archival DOI |
| Dataset datasheet | Markdown — Gebru et al. (2021) Datasheets for Datasets template | CC BY 4.0 | GitHub fraudshield repo docs/ + arXiv appendix |
| Trained model weights | ONNX (XGBoost + LightGBM); MLflow model registry; scikit-learn compatible | Apache 2.0 | HuggingFace: mariusbayizere/fraudshield-eac-model + MLflow registry |
| SHAP analysis notebook | Jupyter notebook: feature importance, partial dependence plots, per-channel SHAP, EAC-specific feature analysis | Apache 2.0 | github.com/mariusbayizere/fraudshield/notebooks/shap_analysis.ipynb |
| Training and evaluation pipeline | train.py, evaluate.py (with LaTeX table output), feature_engineering.py, benchmark.py | Apache 2.0 | github.com/mariusbayizere/fraudshield/ml/ |
| Full system source code | Spring Boot backend, Python ML service, React TypeScript frontend, infrastructure | Apache 2.0 | github.com/mariusbayizere/fraudshield/  (STANDALONE repository) |
| Citation file | CITATION.cff with paper title, DOI, author, year | CC0 | github.com/mariusbayizere/fraudshield/CITATION.cff |


| Venue | Type | Deadline | Fit and Strategy |
|---|---|---|---|
| arXiv cs.LG + q-fin.RM | Preprint (no limit) | Immediately after model training | Dual-category submission; prior claim; visible to ACL, KDD, IEEE S&P programme committees |
| ACM KDD 2027 Applied Data Science Track | Full paper (9 pages) | February 2027 | Primary target — top data mining venue; Applied DS track designed for production systems with real impact |
| IEEE S&P 2027 (Security and Privacy) | Full paper (13 pages) | November 2026 | Top security venue; fraud detection + explainability + East African context is a strong novel combination |
| NeurIPS 2027 Datasets and Benchmarks | Full paper (9 pages) | June 2027 | Reach — FraudShield-EAC-Transactions is first East African financial fraud benchmark; novelty argument is strong |
| ACM CCS 2027 | Full paper (12 pages) | May 2027 | Security and financial fraud; East African fintech context is novel; SHAP explainability angle strong |
| Journal of Financial Crime | Journal article | Rolling submission | Specialist practitioner venue; East African focus highly novel; practitioner audience for deployment impact |

11  Implementation Roadmap — Standalone FraudShield
FraudShield is built in its own repository starting Week 4 — after KinyaMed backend is stable. It runs on its own timeline and its own CI/CD pipeline. No dependency on KinyaMed progress.

| Phase | Timeline | Focus | Deliverables | Gate Criteria |
|---|---|---|---|---|
| Phase 0 | Week 4 | Standalone repository + Spring Boot scaffold | github.com/mariusbayizere/fraudshield initialised; Spring Boot 3 transaction ingestion API; Kafka topics configured; PostgreSQL + TimescaleDB schema; docker-compose.yml for local dev | Ingestion API accepts 1,000 TPS; Kafka consumer lag < 100 msgs; /actuator/health = UP |
| Phase 1 | Week 4 | Feature engineering pipeline (Python) | All 44 features computed for all 6 channel types; Redis feature store populated; unit tests for every feature; feature documentation in docs/features.md | All 44 feature unit tests pass; p95 feature computation < 10ms confirmed by benchmark |
| Phase 2 | Week 4-5 | ML model training + MLflow | XGBoost + LightGBM ensemble trained on synthetic 5M-transaction dataset; Isolation Forest trained; SHAP values verified; MLflow tracking configured; evaluate.py with LaTeX output | All 13 metrics in Section 7.2 met; SHAP coverage 100% HIGH/MEDIUM; AUC-ROC >= 0.94; ECE < 0.05 |
| Phase 3 | Week 5 | Risk decision engine + auto-block | Auto-block in < 50ms; MEDIUM 30-second timer; customer SMS via Africa's Talking; false positive verification flow; MCC circuit breaker; account freeze logic | Load test: 1,000 HIGH-risk/sec all blocked within 50ms p95; SMS within 5s; circuit breaker triggers correctly |
| Phase 4 | Week 5 | React TypeScript + MUI analyst dashboard | Alert feed with real-time WebSocket; SHAP waterfall chart (Recharts); account history timeline; behavioural fingerprint panels; action panel with comment; Google OAuth login | E2E Playwright tests pass 3 browsers; SHAP chart < 500ms; Lighthouse > 85; Google OAuth < 3 seconds |
| Phase 5 | Week 6 | Risk officer + admin + model management | Portfolio dashboard with Leaflet heatmap; model promotion/rollback UI; custom rule management; SAR report generation; shadow mode UI; fraud campaign detection | All FR-05 and FR-06 acceptance criteria confirmed; SAR PDF export verified |
| Phase 6 | Week 6 | Production infrastructure (standalone) | Kubernetes manifests (fraudshield/ namespace); GitHub Actions CI/CD with ML evaluation gate; Prometheus + Grafana dashboards; PagerDuty integration; security scan clean | Zero-downtime canary deploy confirmed; all health checks pass; OWASP ZAP: 0 critical findings; Gitleaks: 0 secrets |
| Phase 7 | Week 6 | Research artifacts (independent release) | arXiv preprint cs.LG + q-fin.RM submitted independently; HuggingFace dataset + model published independently; GitHub fraudshield repo polished; CITATION.cff; README with demo GIF and benchmark table | arXiv ID assigned; HuggingFace pages live; model card complete; GitHub README production-ready |

12  Glossary

| Term | Definition |
|---|---|
| AUC-ROC | Area Under the Receiver Operating Characteristic Curve; primary performance metric for FraudShield; measures ranking quality of fraud probability scores across all decision thresholds |
| Agent Banking | Financial service delivery through retail agents who conduct cash-in/cash-out on behalf of banks; dominant rural payment channel in East Africa with unique fraud patterns |
| Auto-block | Automatic transaction blocking triggered when ensemble_score >= HIGH threshold (default 0.85); executed within 50ms without analyst involvement |
| BNR | Banque Nationale du Rwanda (National Bank of Rwanda); regulatory authority requiring transaction monitoring and SAR reporting from all financial institutions |
| Calibration | Property of an ML model where predicted probability scores match observed fraud rates; ECE < 0.05 required; a calibrated model with score 0.85 means ~85% of such transactions are fraudulent |
| EAC | East African Community; regional intergovernmental organisation comprising Rwanda, Kenya, Tanzania, Uganda, DRC, Burundi, Somalia, and South Sudan; defines FraudShield's geographic scope |
| ECE | Expected Calibration Error; measures how well fraud probability scores reflect actual fraud rates; lower is better; deployment gate threshold < 0.05 |
| Ensemble Model | Weighted combination of XGBoost (0.55) and LightGBM (0.45) fraud probability outputs; reduces prediction variance vs single model |
| Feature Store | Redis-backed cache of pre-computed account velocity features (tx_count_60s, tx_count_1h, tx_count_24h, tx_count_7d, amount_sum_24h); updated after every transaction; eliminates per-request historical DB scans |
| FPR | False Positive Rate; proportion of legitimate transactions incorrectly flagged as fraud; directly impacts analyst workload and customer friction; controlled by risk tier thresholds |
| FraudShield | This standalone independent project; a real-time explainable financial fraud detection system for East African digital payments; github.com/mariusbayizere/fraudshield |
| FraudShield-EAC-Transactions | The synthetic benchmark dataset of 5M+ East African financial transactions introduced by this project; first public East African fraud detection benchmark |
| Isolation Forest | Unsupervised anomaly detection algorithm that identifies statistical outliers independently of supervised labels; catches novel fraud patterns not in XGBoost/LightGBM training data |
| LightGBM | Light Gradient Boosting Machine by Microsoft; second model in FraudShield ensemble (weight 0.45); faster than XGBoost on high-cardinality categorical features |
| MCC | Merchant Category Code; 4-digit ISO 18245 code; used as fraud signal (some MCCs have elevated fraud rates); MCC circuit breaker triggers when MCC fraud rate > 5% in 15 minutes |
| MLflow | Open-source ML lifecycle platform; tracks FraudShield experiments, hyperparameters, metrics, and all model versions; required for research reproducibility and safe model updates |
| Mobile Money | Mobile phone-based money transfer; dominant East African payment method (MTN Mobile Money, Airtel Money, M-Pesa); 40%+ of FraudShield transactions |
| PSI | Population Stability Index; measures feature distribution shift between training and production data; PSI > 0.2 for any top-10 feature triggers model review |
| SAR | Suspicious Activity Report; regulatory document required by BNR when fraud is confirmed; FraudShield auto-generates SAR draft from confirmed fraud data |
| Shadow Mode | New model scores every transaction alongside production model; only production result triggers actions; results compared in MLflow before promotion decision |
| SHAP | SHapley Additive Explanations; explains ML predictions by assigning each feature a contribution value; required for analyst trust, regulatory explainability, and model debugging |
| SIM-swap Fraud | Fraudster obtains duplicate SIM card for victim's phone number; bypasses SMS 2FA; takes over mobile money account; dominant fraud type in Rwanda and Kenya |
| TimescaleDB | PostgreSQL extension for time-series data; used for transactions and audit_events tables; enables efficient time-based queries over billions of records |
| USSD | Unstructured Supplementary Service Data; enables basic financial transactions without internet on feature phones; lacks device fingerprint — makes fraud detection harder |
| XGBoost | eXtreme Gradient Boosting; primary model in FraudShield ensemble (weight 0.55); excels on tabular financial data with engineered features |

FraudShield
Real-Time Financial Fraud Detection & Analyst Intelligence Platform
Software Requirements Specification  v1.0  ·  2026
Marius Bayizere  |  bayizeremarius119@gmail.com
github.com/mariusbayizere/fraudshield  ·  STANDALONE INDEPENDENT PROJECT
Independent Researcher, Kigali, Rwanda
05B  Cross-Device and Mobile Requirements
This section defines requirements for cross-device support. The system must function fully and correctly on every device a user may carry — from the cheapest Android smartphone in Rwanda (entry-level, 1GB RAM, Android Go, 320px screen) to a desktop workstation. No feature available on desktop may be unavailable on mobile. No layout may break, truncate, or require horizontal scrolling on any supported viewport. In Rwanda and East Africa, the smartphone IS the primary computing device for most users — a system that only works on desktop serves a minority.

| Mobile-First Engineering Principle <br> Every UI component is designed for 320px (smallest supported screen) FIRST. <br> Desktop is an enhancement — not the baseline. <br> If a feature cannot be usable on a 320px touchscreen, it is redesigned, not hidden. <br> Network baseline: 3G (1.6 Mbps, 150ms RTT) — system must be usable at this speed. <br> Device baseline: 1GB RAM, Android 10 Go Edition — system must not crash or lag. <br> Battery baseline: no continuous polling, heavy background JS, or infinite animations on mobile. |
|---|

A.1  Supported Device Matrix — All Required to Pass Testing

| Device Category | Screen Width | OS / Browser | Example Devices | Network Assumed |
|---|---|---|---|---|
| Feature phone browser | 240 – 319px | KaiOS, Opera Mini, basic Android browser | Nokia 3310 (KaiOS), Tecno Pop 1 (Opera Mini) | 2G EDGE (0.1 Mbps); SMS fallback required |
| Entry Android (Android Go) | 320 – 374px | Android 10 Go, Chrome Lite Mode | Tecno Spark Go 2022, Itel A23 Pro, Samsung Galaxy A03 Core | 3G (1.6 Mbps, 150ms RTT) |
| Mid-range Android | 375 – 413px | Android 11-13, Chrome, Samsung Internet | Samsung Galaxy A14, Tecno Camon 19, Infinix Hot 20i | 3G to 4G (5-20 Mbps) |
| iPhone SE (smallest iOS) | 375px | iOS 15+, Safari, Chrome iOS | iPhone SE 2020 and 2022 — narrowest mainstream iOS device | LTE / WiFi |
| Standard smartphone | 414 – 430px | Android 12-14, iOS 16+ | Samsung Galaxy S21 FE, iPhone 13, Google Pixel 6a | 4G / LTE |
| Large smartphone | 430 – 480px | Android / iOS | Samsung Galaxy A54, iPhone 15 Plus, Tecno Phantom X2 | 4G / LTE |
| Small tablet | 600 – 767px | Android tablet, Chrome; iPad mini, Safari | Samsung Galaxy Tab A7 Lite, iPad mini 6 | WiFi / 4G |
| Standard tablet | 768 – 1023px | iPadOS, Android tablet, Chrome | iPad Air, Samsung Galaxy Tab S6 Lite | WiFi / 4G |
| Laptop | 1024 – 1279px | Chrome, Firefox, Edge, Safari macOS | 13-inch MacBook, HP Pavilion (developer's machine) | WiFi / Ethernet |
| Desktop workstation | 1280 – 1535px | Chrome, Firefox, Edge, Safari | Standard clinic or bank office PC | Ethernet / WiFi |
| Large monitor | 1536px+ | Any modern browser | 27-inch clinic reception or bank trading desk display | Ethernet |

A.2  Complete Responsive Breakpoint Specification

| Breakpoint | Tailwind | MUI | Width Range | Layout Strategy |
|---|---|---|---|---|
| Feature phone | custom xs: | xs | 240 – 319px | Single column; bottom tab bar (3 tabs max); all text >= 16px; touch targets 48px; images hidden; SMS CTA prominent; Opera Mini compatible HTML fallback |
| Entry mobile | sm: | xs | 320 – 374px | Single column; DESIGN BASELINE — every component built here first; full-width buttons; MUI BottomNavigation; FAB for primary action; no horizontal scroll anywhere |
| Standard mobile | md: | sm | 375 – 413px | Single column; MUI BottomNavigation; collapsible MUI Accordion filters; sticky top bar (3 stats max); FAB; swipe gestures on cards |
| Large mobile | — | sm | 414 – 599px | Single column; optional 2-column card grid for lists; side drawer available but not default; critical for Samsung Galaxy A-series common in Rwanda |
| Small tablet | lg: | md | 600 – 767px | 2-column split: list (40%) + detail (60%); MUI Drawer anchor='bottom'; top navigation replaces bottom nav; this breakpoint is the mobile-to-tablet transition |
| Tablet | xl: | lg | 768 – 1023px | 2-column persistent split: list (45%) + detail (55%); MUI Drawer permanent left sidebar; MUI AppBar full stats bar; no bottom navigation |
| Laptop | 2xl: | xl | 1024 – 1279px | 3-column: sidebar (240px) + main content + detail panel; full stats bar; all charts visible; developer's HP Pavilion primary test device |
| Desktop | — | — | 1280 – 1535px | Full layout; expanded sidebar; analytics visible by default; 480px detail panel; hospital and bank office PC target |
| Large display | — | — | 1536px+ | Max-width 1440px centred; MUI Container maxWidth='xl'; all content readable; no dead whitespace; reception desk and clinic waiting screen target |

A.3  Progressive Web App (PWA) Requirements
Both systems must be installable as Progressive Web Apps on Android and iOS. PWA is the correct distribution strategy for Rwanda: app store installs face storage constraints on entry-level 16GB devices, and many rural users cannot access Google Play reliably. A PWA provides a home screen icon, full-screen launch, and offline capability without an app store.

| PWA Requirement | Specification | Verification |
|---|---|---|
| Web App Manifest | manifest.json: name, short_name, description, start_url, display='standalone', background_color, theme_color, icons 192x192 and 512x512 PNG | Lighthouse PWA audit: Installable criterion passes; Android Chrome 'Add to Home Screen' prompt appears after 2 visits |
| Service Worker | Workbox service worker registered on first load; cache-first strategy for app shell (HTML, CSS, JS bundle < 200KB) | DevTools Application tab shows service worker Activated and Running; app shell loads from cache on offline simulation |
| Offline App Shell | When network unavailable, app shell loads from cache; offline banner appears ('No internet — some features unavailable'); cached data readable | Playwright: network disabled after load; app shell still renders within 1 second; offline banner appears |
| Offline Data Cache | TanStack Query persists last successful API response to IndexedDB; stale data shown with 'Last updated: HH:MM' label when offline | IndexedDB populated after first successful call; confirmed by DevTools; stale data renders offline with timestamp banner |
| Background Sync | Actions taken offline (triage submission, analyst decision) queued in IndexedDB; replayed automatically on connectivity restoration | Background Sync API; queue persists across page reload; sync fires within 30 seconds of connectivity restoration |
| Install Prompt | Custom 'Install App' banner after 30 seconds on mobile; dismissible; uses beforeinstallprompt event; never shown on desktop | Banner appears on Android Chrome after 30s; dismissed state persisted 30 days to localStorage; desktop never shows banner |
| iOS PWA | apple-mobile-web-app-capable, apple-mobile-web-app-status-bar-style, apple-touch-icon 180x180 meta tags | PWA installs from Safari Share menu on iOS 16+; launches full-screen; status bar colour matches brand.primary |
| Push Notifications | Web Push API for CRITICAL patient alerts (KinyaMed) and HIGH fraud alerts (FraudShield); requires user permission; works when app is backgrounded | Push received on Android when app backgrounded; shows urgency level and description; tap opens relevant item |
| Lighthouse PWA Score | >= 90 on mobile throttling (Moto G4 profile) | Lighthouse in Playwright CI; score stored as CI artifact; build fails if < 90 |

A.4  Touch Interaction Requirements

| Requirement | Specification | Implementation |
|---|---|---|
| Minimum touch target | 48x48px for all interactive elements — buttons, links, icons, form controls, list items, badges | Tailwind: min-h-[48px] min-w-[48px] on all interactive elements; MUI theme override: MuiButtonBase minHeight: 48 |
| Touch feedback | Visual feedback within 100ms of touch: MUI ripple, colour change, or scale(0.97) press animation | MUI TouchRipple on all MuiButtonBase; Tailwind active:scale-[0.97] transition-transform duration-100 on custom elements |
| Swipe on cards | Left swipe on patient card (KinyaMed) or alert card (FraudShield) reveals quick actions; right swipe dismisses | react-swipeable; threshold 80px; navigator.vibrate(10) haptic on action reveal; snap back on release without action |
| Pull-to-refresh | Queue (KinyaMed) and alert feed (FraudShield) support pull-to-refresh on mobile | Custom touch handler or react-pull-to-refresh; triggers TanStack Query invalidation; spinner during refresh |
| Pinch-to-zoom allowed | All pages allow native browser pinch-to-zoom — viewport meta does NOT set user-scalable=no or maximum-scale | <meta name='viewport' content='width=device-width, initial-scale=1'> only; confirmed in Playwright mobile test |
| No hover-only features | No feature requires hover to discover or activate; all hover tooltips also accessible by tap | Playwright mobile test: all features discoverable and usable by tap alone; no hover: CSS used for functionality |
| Virtual keyboard handling | Form inputs scroll into view when keyboard opens; no input hidden behind keyboard | MUI TextField: scrollIntoView on focus; tested on Android Chrome with virtual keyboard; input visible above keyboard |
| Haptic feedback | Critical actions (CRITICAL triage, CONFIRM FRAUD) trigger short haptic feedback on supported devices | navigator.vibrate([100,50,100]) on critical actions; navigator.vibrate(200) on critical urgency result; graceful no-op where unsupported |

A.5  Network Resilience — Rwanda Context

| Network Condition | Speed / Latency | Required System Behaviour |
|---|---|---|
| 4G / WiFi (urban Kigali) | 10-50 Mbps / 20-50ms | Full features; real-time WebSocket; all animations; all charts — standard behaviour |
| 3G (peri-urban Rwanda) | 1-5 Mbps / 100-200ms | Full features; images lazy-loaded with blur placeholder; WebSocket with 60s heartbeat; bundle served from service worker cache |
| 2G EDGE (rural health centre) | 0.1-0.3 Mbps / 300-1000ms | App shell from service worker cache (instant); no real-time WebSocket; 60-second polling fallback; no images; Framer Motion animations skipped |
| Intermittent / offline | 0 Mbps | App shell usable; queued actions persist in IndexedDB via Background Sync; 'You are offline' banner; silent reconnect every 30 seconds |
| SMS only (no data, feature phone) | N/A — SMS only | KinyaMed: complete triage via 3-exchange SMS; FraudShield: auto-block SMS to customer; both fully functional via Africa's Talking SMS regardless of app status |

A.6  Mobile Performance Targets

| Core Web Vital / Metric | Target on 3G (Moto G4) | Target on 4G / WiFi | How Measured |
|---|---|---|---|
| First Contentful Paint (FCP) | < 2.5 seconds | < 1.2 seconds | Lighthouse; Playwright CI with CPU 4x throttle + 3G network throttle |
| Largest Contentful Paint (LCP) | < 4.0 seconds | < 2.0 seconds | Lighthouse; must be image or text block — not spinner |
| Time to Interactive (TTI) | < 5.0 seconds | < 2.5 seconds | Lighthouse; main thread unblocked for user input |
| Total Blocking Time (TBT) | < 300ms | < 100ms | Lighthouse; no long JS tasks (> 50ms) during page load |
| Cumulative Layout Shift (CLS) | < 0.1 | < 0.05 | Lighthouse; skeleton loaders prevent layout shift on data load |
| JS bundle (initial, gzipped) | < 200KB | < 200KB | Vite bundle analyser in CI; build fails if initial bundle > 200KB gzipped; MUI tree-shaking required |
| Offline app shell load | < 1.0 seconds | < 1.0 seconds | Service worker cache-first; Playwright with offline simulation |
| Memory on entry-level device | < 150MB RAM | < 300MB RAM | Chrome DevTools Memory tab; Playwright leak test over 50 interactions |

A.7  Mobile-Specific Component Behaviour

| Component | Desktop Behaviour | Mobile (< 600px) Behaviour | How Switched |
|---|---|---|---|
| Navigation | MUI Drawer sidebar permanent left 240px | MUI BottomNavigation (3-4 tabs); hamburger for secondary items | useMediaQuery('(max-width:600px)') toggles Drawer vs BottomNavigation |
| Detail Panels | MUI Drawer anchor='right' slides in; main content still visible | Full-screen MUI Dialog; back button closes; swipe down to dismiss | Drawer on lg+; Dialog on xs-md; same content, different shell |
| Stats Header | Sticky MUI AppBar; all 6-7 stats as MUI Chips | Horizontal scrollable chip row; or collapsed to 3 most critical with expand button | Chip row overflow-x:auto hidden scrollbar on mobile; expand on tap |
| Data Tables | MUI DataGrid with columns, sorting, inline actions | Card list (MUI List); each item a MUI Card; swipe left for quick actions | DataGrid hidden at xs-sm breakpoints; MUI List shown instead; same data source |
| Charts (Recharts) | Full chart with axes, legend, tooltips | Simplified: no legend (info in title); full-width; larger touch targets; tap opens MUI Tooltip | ResponsiveContainer always; mobile Recharts config simplifies axes; tap-to-tooltip |
| Forms (all) | 2-column layout for paired fields (first + last name) | Single column; full-width; submit button position:sticky bottom of screen above keyboard | MUI Grid: xs=12 always; md=6 for 2-column; sticky submit tested on Android with keyboard open |
| Country Code Selector | MUI Select Popover inline dropdown | MUI SwipeableDrawer full-screen with search at top; large touch-friendly country list | Select triggers custom SwipeableDrawer on mobile breakpoint; same data and validation |
| SHAP Waterfall Chart | All features; horizontal bars; rich tooltips | Top-5 features only; vertical orientation; 'Show all' expands to bottom sheet | ResponsiveContainer; mobile renders top-5 config; expand opens MUI SwipeableDrawer with full chart |
| Password Strength Meter | MUI LinearProgress + text labels for all 4 levels | Same component; text labels shortened to icons + colour only at < 375px | Conditional label: text on md+; icon-only on sm- ; colour always present for accessibility |

A.8  Cross-Device Testing Matrix — Required Before Any Production Deployment

| Device | Test Method | Critical Journeys | Pass Criteria |
|---|---|---|---|
| Tecno Spark Go / Itel A23 (320px, Android Go, Chrome Lite) | Playwright emulation + physical device for SMS test | Registration; symptom submission / fraud alert; queue position; SMS confirmation | No horizontal scroll; all text readable; touch targets >= 48px; SMS delivered |
| iPhone SE 2022 (375px, Safari iOS) | Playwright --browser=webkit device='iPhone SE' | Registration with phone flag selector; password strength; Google OAuth popup; triage/alert result | Safari CSS confirmed; OAuth popup works in Safari; no iOS scroll bounce on forms |
| Samsung Galaxy A14 (412px, Chrome Android) | Playwright device='Pixel 5' | Doctor queue view on mobile; patient detail full-screen; analyst alert card; swipe action | WebSocket reconnects after tab switch; SHAP chart renders; countdown accurate |
| iPad mini 6 (768px, Safari iPadOS) | Playwright device='iPad Mini' | Split panel; filter panel; charts; consultation notes; admin analytics | Both panels visible simultaneously; no layout overflow; charts render correctly |
| HP Pavilion / 13-inch laptop (1280px, Chrome) | Playwright desktop Chrome | Full admin panel; model management; all charts; complete keyboard navigation | All features accessible; Lighthouse PWA >= 90; 0 Axe critical violations |
| Desktop 1440px (Firefox) | Playwright desktop Firefox | Full dashboard; SHAP waterfall; account history; all tabs in detail panel | Firefox rendering identical to Chrome; no Chrome-only CSS without Firefox fallback |
| Opera Mini / KaiOS (feature phone) | Manual on physical device or Opera Mini browser emulation | SMS fallback CTA visible; essential info readable; no JS-required critical features | Page loads < 15 seconds on EDGE; core info readable without JS; SMS link prominent |

A.9  Native Mobile App Strategy — Phase 2
The PWA is the Phase 1 mobile delivery strategy and is sufficient for the research portfolio and initial deployment. In Phase 2, a React Native app will wrap the same API client and business logic for Android-first distribution via Google Play Store. The shared logic strategy means API hooks, state management, and validation schemas are reused — only rendering primitives change from React DOM to React Native components.

| Native App Requirement | Phase | Specification |
|---|---|---|
| Android app (React Native + Expo) | Phase 2 | React Native 0.73+; Expo managed workflow; Android 10+ (API 29+); minimum 1GB RAM; APK < 30MB; Google Play distribution |
| Biometric authentication | Phase 2 | expo-local-authentication; fingerprint replaces password re-entry on session restore; falls back to PIN on unsupported devices |
| Offline-first data sync | Phase 2 | WatermelonDB (SQLite-backed) for local cache; sync with API on reconnection; server-wins conflict resolution |
| Native push notifications | Phase 2 | Expo Push via Firebase Cloud Messaging; CRITICAL / HIGH alerts delivered when app closed; notification taps deep-link to relevant item |
| Background sync | Phase 2 | expo-background-fetch periodic sync every 15 minutes; respects Android battery saver and Doze mode |
| Camera / QR scan (KinyaMed) | Phase 2 | expo-camera for scanning patient phone number QR code at health centre registration desk; reduces manual entry errors |
| iOS app | Phase 3 | React Native iOS build; App Store; iOS 15+; requires Apple Developer Program enrolment (USD 99/year) |

