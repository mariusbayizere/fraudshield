<!-- GENERATED FILE — do not edit.
     Source: docs/prompts/FraudShield_Master_Build_Prompt.md Part B.
     Regenerate with: uv run fs-defect-register -->

# FraudShield SRS Defect Register

Binding resolutions for contradictions, impossibilities and risks found in
`docs/srs/FraudShield_SRS_v1_0.docx`. Precedence (build prompt A.4): these
resolutions override SRS acceptance criteria and requirement text. Every
affected ADR, test and traceability row references the defect ID.

Status of each resolution is tracked in `docs/traceability/requirements.yaml`
(rows `D-01` … `D-51`), not in this file.

A senior review of the SRS found the following contradictions, impossibilities and risks. Each has a binding resolution. Copy this register into `docs/srs/defect_register.md` at the start of the build, and reference the defect ID (D-xx) in every affected ADR, test and traceability row.

### B.1 Machine learning and statistics

**D-01 — "Precision at 1% FPR ≥ 0.720" is mathematically impossible.**
With a 0.87% fraud base rate, at FPR = 1% the false positives are about 0.99% of all transactions while true positives cannot exceed 0.87%. The precision ceiling is 0.0087 / (0.0087 + 0.01 × 0.9913) ≈ **0.467**, even with perfect recall.
*Resolution:* The gate metric becomes **Recall (TPR) at 1% FPR ≥ 0.720**. Precision at 1% FPR is still reported, together with its theoretical ceiling for the test set's actual base rate. `evaluate.py` computes and prints the ceiling. The paper explains the correction in one sentence.

**D-02 — Recall, F1 and FNR thresholds lack a defined operating point.**
*Resolution:* Define two operating points on the calibrated ensemble score. The **flag threshold** is 0.60 (MEDIUM or above) and the **block threshold** is 0.85. Recall ≥ 0.880, F1 ≥ 0.800 and FNR < 12.0% are measured at the flag threshold (FNR = 1 − recall at 0.60, so the two are consistent). FPR < 1.5% is measured at the block threshold. Precision, recall and F1 at both thresholds are always reported. For reference, F1 0.80 with recall 0.88 implies precision ≈ 0.733 and FPR ≈ 0.28% at the flag threshold at this base rate; `evaluate.py` reports this consistency check.

**D-03 — The feature breakdown sums to 46, not 44.**
FR-02-02 lists 8+5+7+5+5+5+5+4+1+1 = 46.
*Resolution:* The authoritative catalogue is the 44 features in Part E.2 (velocity 8, amount 5, temporal 6, geographic 5, counterparty 5, device & channel 5, account profile 4, agent-specific 4, EAC corridor 1, synthetic-identity 1 = 44). `docs/features.md` is generated from the code's feature registry so documentation cannot drift.

**D-04 — "USSD: 40 features computed without device_fingerprint" is undefined.**
*Resolution:* Every transaction always yields a 44-slot feature vector. Exactly 4 features are device-fingerprint-derived; for USSD (or any null fingerprint) they are emitted as NaN and handled by the models' native missing-value logic, so 40 are computed from data. Agent-specific features are NaN for non-AGENT_BANKING channels. The "median imputation" in SRS 7.1 applies only to the Isolation Forest input (which cannot take NaN) and to baseline models, never to the boosted models. Unit tests assert exact NaN positions per channel.

**D-05 — Calibrating two models separately, then averaging, does not yield a calibrated ensemble; SHAP additivity is only exact in each model's margin space.**
*Resolution:* (1) Train XGBoost and LightGBM with early stopping on a validation split. (2) Combine raw probabilities as 0.55·p_xgb + 0.45·p_lgb. (3) Fit **one isotonic regression on the combined score** using a separate, chronologically later calibration split. ECE is measured on this final calibrated `ensemble_score`. `xgboost_score` and `lightgbm_score` in the ScoringResult are the per-model isotonic-calibrated probabilities (fitted on the same calibration split) for display and diagnostics. (4) SHAP: compute exact TreeSHAP per model in log-odds (margin) space. The additivity test (FR-02-04, tolerance 0.001) is run per model in margin space, where it is mathematically exact. The analyst-facing contribution is the weighted combination 0.55·φ_xgb + 0.45·φ_lgb in margin space, with base value and final margin annotated; the UI also shows the final calibrated probability. This is documented in `docs/ml/explainability.md`. Never claim SHAP values sum to the calibrated probability.

**D-06 — Isolation Forest score range and threshold conflict.**
Tests say anomaly score ∈ [−1, 1]; FR-02-05 routes anomaly_score > 0.7 to review; scikit-learn's `score_samples` is negative and unbounded in practice.
*Resolution:* `anomaly_score` ∈ [0, 1] is defined as the empirical CDF (percentile rank) of the negated `score_samples` against the training reference distribution, stored with the model artifact. The raw scikit-learn score is also logged as `anomaly_raw` ∈ roughly [−1, 0]; the SRS [−1, 1] unit test applies to the raw score. With contamination 0.01, the review threshold is **not** fixed at 0.7 in production: 0.7 is the default in the test profile (to satisfy the acceptance test), and production uses a configurable percentile (default 0.995) sized by the alert budget in D-10.

**D-07 — Temporal split definitions disagree** ("final 1.5 months" in 7.1 vs "last-3-month test set" in 7.2 and CI; 520K rows is 10.4% of data).
*Resolution:* The simulator produces 24 months with realistic volume growth. Split strictly by time into train (≈4.16M), validation (≈520K, of which the chronologically last 40% is the calibration split) and test (≈520K = the most recent contiguous period). Insert a 7-day **embargo** gap between validation and test to model label delay. The test period's real span is computed and reported; everywhere in code, docs and paper it is called the "temporal hold-out test set" (with its measured span), replacing both conflicting phrases.

**D-08 — Results on synthetic data can look perfect and prove nothing.** Supervisors will ask whether AUC 0.94 comes from the generator leaking labels.
*Resolution:* Mandatory realism and anti-leakage controls in the generator (Part E.3): no single feature may exceed AUC 0.80 alone; 1–2% label noise; fraud-tactic drift over time; at least one fraud sub-variant that appears only in the test period (novelty test for Isolation Forest); adversarial "just-below-threshold" adaptation; legitimate look-alike behaviour (round sums, EAC cross-border salary remittances, agent cash-out peaks). All claims are worded as results **on the FraudShield-EAC synthetic benchmark**. The paper has an explicit Limitations section and a real-data validation plan. Never state or imply deployment at a real Rwandan institution.

**D-09 — Unsupported numeric claims in the SRS** (GSMA "64% growth 2021–2024", "RWF 18 trillion in 2023", ">60% of alerts dismissed", "8–15% yearly degradation", "ensemble reduces variance 12%", "Western models achieve AUC 0.72–0.78 on East African data").
*Resolution:* Create `docs/research/claims_register.md`. Each claim is either (a) backed by a primary source the author supplies and verifies, (b) replaced by a measurement FraudShield itself produces (the "Western models" claim becomes the measured ablation "card-style feature set only" vs "full EAC feature set"; the "12% variance" claim becomes a measured seed-variance comparison), or (c) removed. Until verified, README and paper must not state these numbers as fact. Do not browse for and insert sources yourself unless you can read the primary source; if you cannot, leave the claim marked `UNVERIFIED`.

**D-10 — Alert volume will overwhelm analysts; the 30-second MEDIUM timer then auto-releases most risky transactions.**
At 10,000 TPS, even 0.3% MEDIUM means 30 alerts/second (108,000/hour). Isolation Forest with contamination 0.01 adds up to 100/second.
*Resolution:* (1) Add `docs/ml/capacity_model.md` with an alert-budget calculation (alerts/hour vs analysts on shift × reviews/analyst-hour). (2) The default feed order stays exactly as FR-04-02 specifies (HIGH first, then fraud probability descending). Analysts can switch to an optional "Sort: expected loss" view, where expected loss = calibrated probability × amount normalised to RWF, so large risky transfers are not buried under small ones. (3) Isolation-Forest-only alerts (ensemble < 0.60) go to a separate **ANOMALY_REVIEW** queue that does **not** hold the transaction and has no timer. (4) The MEDIUM timeout policy is configurable per channel: `RELEASE_WITH_TIMEOUT_LABEL` (SRS default, used in tests) or `DECLINE_AND_VERIFY`. (5) Prometheus alerts fire when timeout-release rate exceeds a configurable level, because that means the system is silently approving risky transactions.

**D-11 — Shadow "within 1%" is ambiguous and labels are delayed.**
*Resolution:* Promotion requires ≥ 24 h shadow **and** ≥ 50,000 shadow-scored transactions **and** absolute AUC-ROC delta ≥ −0.010 on labels available for the shadow window (label coverage reported) **and** score-distribution PSI < 0.2 between shadow and production. If label coverage is below 30%, the gate displays "Insufficient labels" and promotion is blocked.

**D-12 — Model extraction and privacy risk: returning the full ScoringResult (feature_vector, per-model scores, SHAP) to the core-banking caller exposes model internals.**
*Resolution:* The machine-to-machine ingest response is a `DecisionResponse` (transaction_id, decision, risk_tier, reason_codes[≤3], scoring_result_id, model_version, decision_latency_ms). The full FR-02-01 ScoringResult (all 9 fields) is produced for every transaction, persisted, and available on internal and staff APIs. FR-01-03's cached replay returns the cached DecisionResponse. Record in an ADR.

### B.2 Architecture and performance

**D-13 — The latency budget sums to 51 ms and puts PostgreSQL and Kafka round-trips in the synchronous path, but the p95 target is 50 ms.** Also, an HTTP ingest that "publishes to Kafka and returns" cannot deliver an auto-block decision back to the payment in 50 ms.
*Resolution:* A **synchronous decision path** plus an **asynchronous durability and side-effect path** (Part C.2). Account metadata comes from Redis, not PostgreSQL, in the hot path. Kafka publication happens after the decision via an idempotent producer with a local durable spool. New server-side budget targets p95 ≤ 40 ms for flagged transactions, leaving margin.

**D-14 — MEDIUM "hold for 30 seconds" cannot be an open HTTP request.**
*Resolution:* The decision response for MEDIUM is `HOLD` with `review_deadline_at`. The final decision (APPROVE, DECLINE, TIMEOUT_RELEASE) is delivered by signed webhook (HMAC-SHA256, retries with backoff) and on Kafka topic `fs.decisions.final`. Integrators may also poll `GET /api/v1/decisions/{transaction_id}`.

**D-15 — "Buffer up to 10,000 transactions in memory" loses data if the pod dies.**
*Resolution:* Kafka producer with `acks=all`, `enable.idempotence=true`, and a bounded **local disk spool** (append-only, fsync batched every 5 ms) replayed in order on recovery. In-memory buffer only in front of the spool. The chaos test kills both Kafka and the API pod and proves zero loss via transaction_id reconciliation.

**D-16 — Python in the hot path at 10k TPS.**
*Resolution:* The scorer runs as multiple processes (one model copy per process, models loaded once, `nthread=1` per inference) behind gRPC over mTLS. Tree complexity (count, depth, leaves) is a **constrained hyperparameter**: the search rejects any configuration whose single-request p99 (ensemble + exact TreeSHAP on flagged) exceeds 40 ms on the reference hardware. Report the accuracy–latency trade-off curve; it is a strong research figure.

**D-17 — `pytest` is listed for the Risk Decision Engine, which is a Spring Boot service.**
*Resolution:* Decision engine tests use JUnit 5 (plus jqwik property tests). pytest is used for Python components only.

**D-18 — "Exactly 30 s" and "exactly at 5%" need tolerances and minimum volume.**
*Resolution:* Timers: auto-release at 30 s with tolerance ±500 ms, asserted in tests. MCC circuit breaker: triggers when fraud rate > 5.0% over a rolling 15-minute window **and** the window holds ≥ 100 transactions for that MCC (configurable), which prevents 1-in-10 noise from tripping it. Fraud rate numerator = auto-blocked HIGH + analyst-confirmed fraud; denominator = all scored transactions for that MCC. The boundary test uses sufficient volume to hit 5% exactly and 5% + 1 transaction.

### B.3 Security, privacy and compliance

**D-19 — API keys "stored as bcrypt hash" cannot be verified at 10,000 TPS** (bcrypt cost 12 is ~100–300 ms per check by design).
*Resolution:* API keys are 256-bit random secrets in the format `fsk_<env>_<keyId>_<secret>`. Store `HMAC-SHA256(server_pepper, secret)`; look up by keyId; constant-time compare; cache the verified key record in memory with a 2-second TTL plus Redis pub/sub revocation, so a revoked key returns 401 within 5 s (FR-06-07). bcrypt cost 12 remains mandatory for **human passwords**. Record the deviation from SRS 4.2 in an ADR.

**D-20 — "PostgreSQL TDE" does not exist in community PostgreSQL 16.**
*Resolution:* Encrypted volumes / storage-level encryption with KMS-managed keys, plus application-layer AES-256-GCM envelope encryption for PII in a separate **PII vault** database with its own role and credentials. The analyst-facing application role has no grant on the vault. Redis: TLS in transit plus encrypted volumes; no raw PII in Redis ever.

**D-21 — Data residency: SRS says "af-south-1 or equivalent", but af-south-1 is Cape Town, outside the EAC — contradicting its own requirement.**
*Resolution:* Infrastructure code is cloud-agnostic Kubernetes. Production residency must be a Rwanda-located (or legally authorised) facility; record this as a deployment prerequisite, not something the code can satisfy. Development and demo environments may run anywhere **only with synthetic data** and are labelled "SYNTHETIC DATA — NOT FOR PRODUCTION" in the UI banner.

**D-22 — Regulatory mapping is unverified.** Suspicious transaction reporting in Rwanda may be filed with the Financial Intelligence Centre under the AML/CFT framework, while BNR is the prudential supervisor; personal data is governed by Rwanda's data protection law (Law No. 058/2021) supervised by NCSA. These must be confirmed by a compliance professional.
*Resolution:* SAR/STR generation (FR-05-06) uses a **configurable, versioned report template**. Fields not confirmed from a primary regulatory document are marked `UNCONFIRMED_FIELD` in the template schema and rendered with a visible "Draft — requires compliance review" watermark. Reports are never auto-submitted. Produce `docs/compliance/dpia.md` (data protection impact assessment template filled with FraudShield's actual data flows) and `docs/compliance/regulatory_mapping.md` with every legal statement marked "verify".

**D-23 — Google OAuth "new Google user creates ANALYST account" would let any Google user into a bank's fraud console.**
*Resolution:* OAuth sign-in succeeds only for (a) an existing active account linked by verified email, or (b) an email whose domain is on the institution's allowlist, which creates an account with role ANALYST and status `PENDING_APPROVAL` that has **no access** until an ADMIN approves. The integration test "new Google user creates ANALYST role account" asserts role = ANALYST and status = PENDING_APPROVAL. Google `email_verified` must be true.

**D-24 — The registration form lets users pick a role (Department = role); FR-07-01 says users cannot self-elevate.**
*Resolution:* Department and Requested Role are separate fields. Self-registration always creates `PENDING_APPROVAL`. Granted role is set only by an ADMIN. Department is a free institutional list (Fraud Operations, Risk, Compliance, IT, …), not the role enum.

**D-25 — Customer verification by SMS link defeats itself in SIM-swap fraud,** which the SRS calls the dominant fraud type: the fraudster holding the victim's SIM receives the link and "verifies" the fraud. SMS links also train customers to click links (phishing).
*Resolution:* (1) Self-service unblock via link is disabled when any of these hold: `days_since_sim_swap` < 7 or unknown; device changed in last 24 h; ensemble score ≥ 0.95; fraud-type signals indicate account takeover. In those cases the page tells the customer, in their language, to contact the institution by its official number or visit an agent/branch. (2) SIM-swap recency comes from an MNO adapter interface (for example a CAMARA-style SIM Swap API where available); "unavailable" is treated as unsafe. (3) SMS text includes a short reference code and the institution's official number, uses a registered sender ID, never asks for a PIN or password, and links only to the institution's own domain. (4) Token: ≥128-bit random, stored as SHA-256 hash, single use, 10-minute expiry, bound to the block event. FR-03-04/05 tests cover both the self-service path and the blocked-self-service path.

**D-26 — "10 attempts per 15 min per IP" will lock out whole bank offices and mobile users behind carrier-grade NAT; 5-failure account lock enables denial-of-service against analysts.**
*Resolution:* Keep SRS behaviour as default (acceptance tests use it). Add an ADMIN-managed **office egress IP allowlist** with a higher per-IP ceiling, and a per-(IP, email) limiter. Locked accounts auto-unlock after 30 minutes in addition to the email unlock link and admin unlock. All lock/unlock events are audited.

**D-27 — Stateless JWTs cannot be invalidated "within 5 seconds" on deactivation or password change.**
*Resolution:* Users have a `token_version` integer; it is a JWT claim; it increments on password change, deactivation, role change and "log out everywhere". The API checks it against a Redis-cached value (TTL 2 s, pub/sub invalidation). Refresh tokens rotate on every use with **reuse detection** (reuse revokes the whole token family). Access tokens live only in memory in the browser; the refresh cookie is httpOnly, Secure, SameSite=Strict, path-scoped to `/api/v1/auth/refresh`, with a CSRF double-submit token.

**D-28 — A third-party penetration test cannot be performed by the build agent.**
*Resolution:* Deliver `docs/security/threat_model.md` (STRIDE per component and per data flow), automated OWASP ZAP/Gitleaks/Trivy/dependency scans, SBOM (CycloneDX), signed images, and `docs/security/pentest_readiness.md`. Mark the SRS pen-test row `REQUIRES_EXTERNAL_PARTY`.

**D-29 — Offline analyst decisions replayed later (Background Sync) can be stale or dangerous,** and Background Sync is Chromium-only. Cached alert data on shared phones is sensitive.
*Resolution:* Every queued action carries an idempotency key and the alert's `version`. The server rejects stale actions with 409 and the UI shows a conflict card ("This alert was auto-released at 14:02 while you were offline"). Decisions on MEDIUM alerts whose deadline has passed are never silently replayed. Fallback replay on the `online` event for browsers without Background Sync. Offline caches hold masked data only, are encrypted with a WebCrypto key held in memory for the session, expire after 12 hours, and are wiped on logout.

### B.4 Data model

**D-30 — `auto_block_events` is declared immutable but contains mutable columns** (customer_verified, verified_at, unblocked_at, account_frozen…).
*Resolution:* Event-sourced split. `auto_block_events` keeps only facts known at block time. New append-only tables: `customer_notifications`, `unblock_events`, `account_freeze_events`. A view `v_auto_block_status` derives current state. Same pattern wherever the SRS says "immutable" but lists lifecycle fields.

**D-31 — Tables required by functional requirements are missing from the schema.**
*Resolution:* Add: `institutions` (multi-tenancy; `institution_id` on every tenant-scoped table, enforced with PostgreSQL Row-Level Security), `api_keys`, `risk_threshold_versions`, `mcc_circuit_breaker_events`, `sar_reports`, `password_reset_otps`, `email_verification_tokens`, `unblock_events`, `account_freeze_events`, `customer_notifications`, `fraud_campaign_transactions`, `shadow_scores`, `batch_jobs`, `retraining_jobs`, `training_datasets`, `label_events`, `outbox_events`, `office_ip_allowlist`, `alert_rule_versions`. Plus `users.status` (PENDING_APPROVAL/ACTIVE/LOCKED/DEACTIVATED), `users.locked_until`, `users.token_version`, `users.email_verified`, `users.preferred_locale`, `alert_queue_entries.version` (optimistic locking). `UNIQUE(employee_id)` becomes `UNIQUE(institution_id, employee_id)`.

**D-32 — Audit log needs tamper evidence, not only permissions, and "12 action types" are unnamed.**
*Resolution:* Exactly 12 `event_type` values, each with an `action` sub-field: `AUTH`, `USER_ADMIN`, `TRANSACTION_DECISION`, `AUTO_BLOCK` (incl. account freeze), `CUSTOMER_VERIFICATION`, `ANALYST_DECISION` (incl. escalation, undo), `SENIOR_OVERRIDE`, `THRESHOLD_CHANGE` (incl. circuit breaker and timeout policy), `RULE_CHANGE`, `MODEL_LIFECYCLE`, `API_KEY_LIFECYCLE`, `REGULATORY_REPORT`. Each row stores `prev_hash` and `row_hash` (SHA-256 chain per writer partition); a daily job signs the Merkle root of the day's hashes and stores it in `audit_anchors`. A verification CLI (`fraudshield audit verify --from --to`) detects tampering. Application role: INSERT and SELECT only; a separate read-only replica role for compliance. TimescaleDB compression after 30 days; retention 7 years.

### B.5 Front-end and UX

**D-33 — Several SRS colour pairings fail WCAG 2.1 AA text contrast** (measured): white on amber #D97706 = 3.19:1; white on green #059669 = 3.77:1; white on teal #0D9488 = 3.74:1; #D97706 on #FFFBEB = 3.07:1; #DC2626 on #FEF2F2 = 4.41:1. Normal text requires 4.5:1.
*Resolution:* Keep the SRS hues for borders, fills, bars and icons (non-text contrast ≥ 3:1 is satisfied). Add **text-safe tokens** for any text on or in those colours: `risk.high.text #B91C1C` (5.91:1 on #FEF2F2), `risk.medium.text #B45309` (4.84:1 on #FFFBEB; 5.02:1 white-on), `risk.low.text #047857` (5.24:1 on #F0FDF4; 5.48:1 white-on), `channel.agent_banking.text #0F766E` (5.47:1 white-on). Channel and risk chips with white text use the text-safe token as background. A unit test computes contrast for every token pair used in the theme and fails below threshold.

**D-34 — `animate-pulse` "infinite" on HIGH cards violates WCAG 2.2.2 (motion > 5 s needs a pause control), conflicts with the mobile battery principle in 05B, and causes fatigue on long shifts.**
*Resolution:* A HIGH card pulses for 3 cycles (3.6 s) on arrival, then settles to a static 3 px `risk.high.border` with a leading warning icon and the text "HIGH RISK". `prefers-reduced-motion` disables pulsing entirely. The Playwright journey asserts the pulse class on arrival and its removal afterwards.

**D-35 — HIGH "pinned at top" uses MUI X DataGrid row pinning, which is a paid (Pro) feature.**
*Resolution:* Use the free MUI X DataGrid with a composite comparator (tier rank first, then fraud probability DESC). Virtualisation is available in the free tier. No Pro/Premium licence may be introduced.

**D-36 — Breakpoint names and widths conflict** between SRS 5.5, 05B A.2 and Tailwind/MUI defaults (e.g., Tailwind `lg:` used for 600 px).
*Resolution:* One canonical breakpoint set, generated from `design-tokens/breakpoints.json` into both Tailwind `screens` and MUI `theme.breakpoints.values`, with identical names:
`fp: 240, xs: 320, sm: 375, md: 600, lg: 768, xl: 1024, xxl: 1280, xxxl: 1536`.
Layout per range follows 05B A.2's width ranges. Detail panel width: full-screen dialog < 600; bottom drawer 600–767; 45% split 768–1023; 480 px right drawer 1024–1535; 560 px right drawer ≥ 1536 (wider for the network graph tab, per 5.5).

**D-37 — Tailwind and MUI styles fight each other.**
*Resolution:* Disable Tailwind Preflight; MUI `CssBaseline` owns resets; `StyledEngineProvider injectFirst` so Tailwind utilities can override; Tailwind is used for layout and spacing, MUI for components. All colours come from design tokens; no raw hex in components (lint rule).

**D-38 — Flags from flagcdn.com leak staff IP addresses to a third party, break the CSP, fail offline and cost data on 3G.**
*Resolution:* Self-host an optimised SVG flag sprite loaded as a lazy chunk only when the country selector opens; country data and validation from `libphonenumber-js` (metadata subset `min` or `mobile`); ISO 3166-1 list of 249 entries.

**D-39 — 200 KB gzipped initial bundle vs MUI + DataGrid + Recharts + Leaflet + graph library + Framer Motion.**
*Resolution:* Route-level code splitting. Initial route = auth shell. DataGrid, Recharts, Leaflet, graph, Framer Motion and i18n namespaces load lazily per route/tab. Per-icon MUI imports only. CI size-limit check per chunk. Choose **one** graph library (D3-force) via ADR, not both.

**D-40 — Lighthouse removed its PWA category in Lighthouse 12,** so "Lighthouse PWA score ≥ 90" cannot be measured.
*Resolution:* Replace with an automated installability suite: manifest validation, service worker registration and control, offline app-shell render in Playwright, icons and `apple-*` meta presence, plus Lighthouse Performance ≥ 85 and Accessibility ≥ 95 on mobile throttling. Record the substitution in an ADR and the traceability matrix.

**D-41 — iOS web push only works for Home-Screen-installed PWAs** (iOS/iPadOS 16.4+), and Background Sync is not available in Safari or Firefox.
*Resolution:* Feature-detect everything; show "Install to enable alerts on iPhone" guidance; never make push the only alert path.

**D-42 — Feature phones (KaiOS/Opera Mini, 240 px, 2G) will not run a bank analyst console, and analysts are staff with smartphones or PCs.**
*Resolution:* The feature-phone requirement applies to the **customer verification page** (server-rendered, no JavaScript required, inline CSS, total page weight ≤ 30 KB, usable on Opera Mini over EDGE) and to SMS. The staff console baseline is 320 px Android Go on 3G. At 240–319 px the console shows a legible notice with the essential live counts and the SMS/phone escalation contact. Record in ADR.

**D-43 — SRS has no localisation, yet serves Rwanda and the EAC.**
*Resolution:* i18n from day one with `react-i18next`: English (default), Kinyarwanda, French, Kiswahili, lazily loaded namespaces; server-side message catalogues for SMS and email in the same four languages; strings reviewed by a native speaker are marked `reviewed: true` in the catalogue, all others flagged `machine_draft` and visible in a translation-status report. Currency formatting per ISO 4217 minor units (RWF, UGX 0 decimals; KES, TZS, CDF 2 decimals per ISO table in code), amounts stored as DECIMAL(18,4). Time zones: Rwanda CAT (UTC+2); Kenya, Tanzania, Uganda EAT (UTC+3); DRC spans two zones (Kinshasa UTC+1, Lubumbashi UTC+2) — derive local time from transaction coordinates. The UI always shows the zone abbreviation.

**D-44 — "Undo within 5 seconds" conflicts with immutable decisions and immediate side effects** (SMS, SAR drafting, unblock).
*Resolution:* A decision is written immediately with state `PENDING_COMMIT` and a 5-second grace timestamp; side effects are published through the outbox only after grace expires. Undo within grace inserts an `ANALYST_DECISION/UNDO` audit event and a compensating record; the original row is never updated or deleted. Total time to "committed" still ≤ 2 s server processing + 5 s grace, and the button feedback appears within 200 ms.

**D-45 — SHAP chart "all 44 features ranked" overwhelms under time pressure.**
*Resolution:* The chart renders all 44 (meeting FR-04-05) inside a scrollable region whose initial viewport shows the top 10 plus a summary row "Other 34 features: net +0.03". Mobile shows top 5 with "Show all" bottom sheet (05B A.7). Plain-English explanations come from deterministic, localised, unit-tested templates per feature; **never** from an LLM.

**D-46 — Leaflet base-map tiles from public servers break the CSP, data residency and 2G usability; per-point fraud maps can expose individuals.**
*Resolution:* Aggregate the heatmap to administrative districts (GeoJSON boundaries bundled for the five countries) with k-anonymity (cells with < 10 events are merged or suppressed). Base map from self-hosted vector tiles (PMTiles) or, if unavailable, a plain boundary map with no external tiles.

### B.6 Scope cleanup

**D-47 — KinyaMed text in 05B** (patients, triage, clinics, hospitals, QR scanning, "CRITICAL triage").
*Resolution:* Ignore all KinyaMed items. FraudShield equivalents: "patient card" → alert card; "queue" → alert feed; "CRITICAL triage haptic" → CONFIRM FRAUD haptic; "clinic PC" → bank operations workstation. The 7.2 column heading "Clinical / Financial Justification" becomes "Financial Justification".

**D-48 — The roadmap compresses everything into weeks 4–6.**
*Resolution:* Ignore calendar dates. Execute the gated milestones in Part D.3. Do not skip gates to meet time.

**D-49 — TimescaleDB availability and licensing.** Many managed PostgreSQL services do not offer TimescaleDB, and some Timescale features are under the Timescale License rather than Apache 2.0.
*Resolution:* Self-host PostgreSQL 16 + TimescaleDB on Kubernetes (e.g., CloudNativePG with a TimescaleDB image). Write an ADR confirming the licence permits this deployment model; features used (hypertables, compression, continuous aggregates, retention) are listed.

**D-50 — MLflow "stages" are deprecated in favour of registry aliases.**
*Resolution:* Use aliases `@production`, `@shadow`, `@previous_production`. Promotion and rollback move aliases.

### B.7 Third-party services

**D-51 — External services must work locally and in CI without real credentials.**
*Resolution:* Ports-and-adapters for Africa's Talking SMS, SMTP, Google OAuth/JWKS, Google token revocation, PagerDuty, MNO SIM-swap signal, FX rates. Each has (a) a real adapter using environment configuration and the provider's sandbox where one exists, (b) a local fake container (Mailpit for SMTP; WireMock stubs for SMS, Google JWKS/token endpoints, PagerDuty, SIM-swap; a seeded FX table). Integration tests run against fakes; a `contract` test suite runs against sandboxes only when credentials are present and is skipped (reported as skipped, not passed) otherwise.

**D-52 — SRS v5.0's "13 lean tables" would remove the security boundary, the audit chain and a table its own requirements need.**
*Resolution:* The schema stays as built (46 tables across V1–V11, V60–V65, V70); v5 §07 is **not adopted**. Six reasons, in order of severity: (a) v5 carries no `institution_id` on any table, which deletes 35 row-level security policies, four `enable_tenant_view_isolation` hypertable views, `current_institution()` and every composite `(id, institution_id)` foreign key — a tenant boundary, not a simplification; (b) `audit_row_hash()` hashes 20 inputs of which v5 deletes 11, so the function, its trigger, `verify_audit_chain`, `audit_chain_heads` and `audit_anchors` cannot compile; (c) v5 adds mutable `customer_verified` / `verified_at` to `auto_block_events`, which is append-only (`make_append_only`, UPDATE refused for every role), duplicating values the view `v_auto_block_status` already derives; (d) v5 omits `api_keys` entirely while its own FR-07-05 keeps machine-to-machine API keys as MUST — the document contradicts itself; (e) v5 keeps 8 of 19 `fraud_scores` columns, dropping four that the published OpenAPI `ScoringResult` marks required and the analyst console renders; (f) v5's `fraud_scores` CHECK drops the `OR ml_unavailable_fallback` disjunct, so a HIGH or MEDIUM score produced while the model is down — the exact case the reliability section requires — cannot be inserted. Two v5 constraints are worth adopting on their own merits and are filed separately: UNIQUE on `auto_block_events.fraud_score_id` and on `alert_queue_entries.fraud_score_id`, each as `(institution_id, fraud_score_id)`.

**D-53 — SRS v5.0 repeats eight defects this register has already resolved.**
*Resolution:* The resolutions stand unchanged and v5's wording does not reopen them: **D-01** (precision at 1% FPR ≥ 0.720 is unreachable at a 0.87% base rate, ceiling ≈ 0.467; the gate metric is recall at 1% FPR), **D-09** (the unsourced "60%+ dismissed", "AUC 0.72–0.78", "8–15% yearly degradation" figures), **D-19** (API keys cannot be bcrypt-verified at 10,000 TPS; HMAC-SHA256 with a server pepper), **D-20** (community PostgreSQL 16 has no TDE; encrypted volumes plus a separate PII vault), **D-21** (af-south-1 is Cape Town, outside the EAC), **D-29** (offline decisions replayed by Background Sync can be stale; idempotency key plus alert version, 409 on stale, never for an expired MEDIUM alert), **D-40** (Lighthouse 12 removed the PWA category, so "PWA score ≥ 90" cannot be measured), **D-42** (a bank analyst console is not usable on a 240 px feature phone; staff use smartphones and PCs). A future SRS revision repeating a resolved defect does not reopen it; the register is the authority (A.4).

**D-54 — SRS v5.0's Spring Security section disables CSRF and looks tokens up per request.**
*Resolution:* The built design stands: **CSRF double-submit on the refresh cookie** (the `fs_csrf` cookie echoed in `X-CSRF-Token`, required on `/auth/refresh` and `/auth/logout`, which are the only calls that authenticate from a cookie), and **`token_version` with cached revocation** (a JWT claim checked against a Redis-cached value, TTL 2 s with pub/sub invalidation) rather than a database lookup on every request. v5's `csrf.disable()` is safe only if no browser-held credential exists, which is false here: the refresh token is an httpOnly cookie by D-27. v5's `UserDetailsService` lookup on every request adds a database round trip to the hot path for a check `token_version` already answers within the 5 s invalidation budget FR-06-02 sets.
