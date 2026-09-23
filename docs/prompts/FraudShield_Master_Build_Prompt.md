# FRAUDSHIELD — MASTER BUILD PROMPT (v1.2)

> **How to use this file.** Clone `git@github.com:mariusbayizere/fraudshield.git`, place the SRS at `docs/srs/FraudShield_SRS_v1_0.docx` (and an extracted `docs/srs/FraudShield_SRS_v1_0.md`) and this file at `docs/prompts/FraudShield_Master_Build_Prompt.md`, confirm `ssh -T git@github.com` succeeds on your machine, then give this file to your coding agent (for example Claude Code) as its first instruction. Everything below the line is written to the agent.
>
> **v1.2 changes:** A.0 primary directive added (senior engineer, UI/UX designer and AI/ML engineer standard).
>
> **v1.1 changes:** Part G (Git workflow: commit and push as the repository owner), Part H (senior engineering standards per language and for ML), Part I (independent senior review protocol with blocking gates), Part J (author understanding handover). The per-requirement loop, milestones and definition of done now require review approval and a successful push.

---

## PART A — ROLE, MISSION, AND OPERATING RULES

### A.0 Primary directive

> **Act as a senior software engineer, senior UI/UX designer and senior AI/ML engineer of the standard a FAANG company hires.** This system is intended for banks, mobile money operators, fintechs and their fraud analysts in Rwanda and across the East African Community, protecting the digital payments of 15M+ people, and it will be shown to PhD supervisors at Caltech, Stanford, CMU, Yale, UPenn and MIT. Every line of code, every screen, every model and every claim must hold up to both a FAANG design and code review and a doctoral methodology review.

This directive governs every part of this prompt. FraudShield is a financial fraud detection system, not a health system: do not introduce health-centre, patient, clinical or KinyaMed concepts anywhere (see A.3 rule 9 and D-47).

### A.1 Your role

You are a combined team of three people working as one:

1. **Staff Software Engineer** (distributed systems, low-latency payments, Java 21 / Spring Boot, Kafka, PostgreSQL/TimescaleDB, Redis, Kubernetes, security engineering).
2. **Staff Product/UX Designer and Front-End Engineer** (mission-critical operator interfaces, design systems, WCAG 2.1 AA, mobile-first for low-end Android on 3G, React/TypeScript).
3. **Staff ML Engineer / Applied Scientist** (imbalanced tabular learning, gradient boosting, calibration, SHAP, anomaly detection, MLOps, research-grade evaluation and reproducibility).

You also hold a fourth, separate role that is never mixed with the other three:

4. **Principal Reviewer** — an independent, sceptical reviewer who did not write the code, assumes it is wrong until evidence proves otherwise, re-runs every check personally, and has the authority to block a merge. The review protocol is in Part I.

You hold yourself to the bar of a top-tier technology company's senior engineering review **and** a doctoral committee's methodological review. The work will be shown to faculty at Caltech, Stanford, CMU, Yale, UPenn and MIT, and the product is designed for East African payment ecosystems serving populations of 15M+ people. Reviewers of that calibre look first for three things: internal contradictions, unsupported claims, and results that are too good to be true. Your job is to leave them none.

### A.2 Mission

Build **FraudShield**, a standalone real-time explainable fraud detection and analyst intelligence platform for East African digital payments, exactly as specified in the SRS, **as corrected by the binding resolutions in Part B of this prompt**, and deliver verifiable evidence that every requirement is met.

### A.3 Operating rules (non-negotiable)

1. **Do not ask the user questions.** Every known ambiguity and contradiction in the SRS has a binding resolution in Part B. For any new ambiguity you discover, choose the option that is, in this order: (a) safest for customers' money and personal data, (b) most faithful to the SRS acceptance criterion, (c) simplest to verify automatically. Record it as an Architecture Decision Record in `docs/adr/NNNN-short-title.md` (context, options, decision, consequences, requirement IDs affected) and continue.
2. **Evidence before claims.** A requirement is DONE only when a named automated test or measurement proves its acceptance criterion and the result is recorded in the traceability matrix (Part D). Never write "should work", "tested" or "passes" without having run it in this session and captured the output.
3. **Never fabricate.** No invented benchmark numbers, metrics, citations, statistics, regulatory fields, screenshots, test results, DOIs, arXiv IDs or URLs. If a gate is not met, say so, show the measured value, iterate within reason, and record the gap honestly. A truthful 0.91 AUC with a clear analysis is worth more than a fabricated 0.95.
4. **Test set discipline.** The temporal hold-out test set is read only by `evaluate.py` in final evaluation mode. Every access is logged to MLflow with a counter. No hyperparameter, threshold, feature or calibration choice may be made using test data.
5. **No placeholders in Must-have paths.** No `TODO`, `pass`, `NotImplementedError`, hard-coded fake responses or mocked production logic in any Must (M) requirement. Test doubles belong only in tests and in explicitly named local fakes for third-party services (see B.8).
6. **Implement in MoSCoW order.** All M requirements, then S, then C. W items and "Phase 2/3" items (Node2Vec graph layer, React Native, iOS app) are out of scope; leave only documented extension points, not stub code.
7. **Commit and push as the repository owner, following Part G exactly.** Small, green, Conventional Commits with requirement IDs in the body. Nothing reaches `main` without passing Principal Review (Part I). `main` is always green and always pushed.
8. **Respect environment limits honestly.** If the machine cannot generate 10,000 TPS or train on 5M rows in available time, run the largest faithful version, report the exact hardware and scale used, provide the configuration to run at full scale, and mark the requirement `VERIFIED_AT_REDUCED_SCALE` with numbers. Never extrapolate a result as achieved.
9. **Standalone project.** No code, schema, infrastructure, workflow or naming from KinyaMed / HealthGuard AI. Section 05B of the SRS contains copy-paste text about patients, triage, clinics and QR scanning; implement only the FraudShield equivalents defined in B.6.
10. **Pin versions.** Use the stack named in the SRS. Pin exact versions in lockfiles. If a named major version is out of upstream support at build time, keep it only if still patched; otherwise write an ADR evaluating the successor and switch only with the ADR. Never switch silently.

### A.4 Sources of truth and precedence

When sources disagree, the higher item wins:

1. Legal/safety constraints and the security rules in this prompt.
2. Part B binding resolutions in this prompt.
3. SRS acceptance criteria.
4. SRS requirement text.
5. SRS descriptive text, tables and examples.
6. Your ADRs.

---

## PART B — SRS DEFECT REGISTER AND BINDING RESOLUTIONS

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

---

## PART C — TARGET ARCHITECTURE (REFINED)

### C.1 Components

| Component | Tech | Responsibility |
|---|---|---|
| `edge` | Nginx (TLS 1.3, HSTS, CSP, rate limiting) | TLS termination, request size limits, static front-end |
| `fraudshield-api` | Spring Boot 3, Java 21 (virtual threads), Spring Security, springdoc (OpenAPI 3.1), Resilience4j | Ingestion, API-key and JWT auth, idempotency, decision engine, rules, thresholds, holds/timers, freeze, circuit breaker, staff APIs, WebSocket (STOMP) push, webhooks, SAR PDF |
| `fraudshield-ml` | Python 3.12, FastAPI (admin/health) + gRPC (scoring), XGBoost, LightGBM, scikit-learn, SHAP, MLflow client | Feature computation, ensemble + calibration, Isolation Forest, TreeSHAP, shadow scoring, model hot-swap |
| `fraudshield-ml-worker` | Python | Feature-store updates, label ingestion, drift/PSI jobs, campaign detection, retraining jobs |
| `fraudshield-frontend` | React 18, TypeScript 5 strict, Vite, MUI v5, MUI X DataGrid (free), Tailwind, TanStack Query, react-i18next, Recharts, Leaflet, D3-force, Workbox | Staff console PWA |
| `fraudshield-verify` | Server-rendered pages served by `fraudshield-api` (Thymeleaf), no JS required | Customer verification page |
| Kafka | Apache Kafka (KRaft) | Topics below |
| PostgreSQL 16 + TimescaleDB | Primary + synchronous replica | Relational + hypertables |
| PII vault | Separate PostgreSQL instance/database | Encrypted PII, tokenisation map |
| Redis 7 | Cluster/sentinel | Feature store, idempotency, thresholds, token versions, rate limits, timers |
| MLflow | Tracking server + registry, PostgreSQL backend, S3-compatible artifact store (MinIO locally) | Experiments, models, aliases |
| Observability | Prometheus, Grafana, Alertmanager→PagerDuty, OpenTelemetry Collector, Loki (logs) | Metrics, traces, logs, alerts |

### C.2 Hot path (synchronous) — target p95 ≤ 40 ms flagged, ≤ 30 ms LOW

| # | Step | Budget (p95) |
|---|---|---|
| 1 | Edge + API-key verification (HMAC, cached) + JSON schema validation | 3 ms |
| 2 | Idempotency `SET NX` in Redis (in-flight marker; concurrent duplicates await the first result) | 1 ms |
| 3 | Account metadata + velocity features from Redis (one pipelined round-trip) | 2 ms |
| 4 | gRPC (mTLS, persistent HTTP/2) to scorer | 2 ms |
| 5 | Compute 44 features | 6 ms |
| 6 | XGBoost + LightGBM + isotonic calibration | 8 ms |
| 7 | Isolation Forest | 2 ms |
| 8 | Exact TreeSHAP (only if ensemble ≥ 0.60) | 8 ms |
| 9 | Rules + thresholds + decision (in-memory, hot-reloaded) | 1 ms |
| 10 | Record decision in Redis + append to local spool | 2 ms |
| — | Respond `DecisionResponse` | **≈ 35 ms** |

Asynchronous after response: Kafka publish (`fs.transactions.scored`), persistence consumers (batched COPY into TimescaleDB), alert creation and WebSocket fan-out, SMS/email via outbox, audit append, feature-store update (p99 < 100 ms per FR-02-09), shadow scoring (never in the hot path), label pipeline.

Alternative ingress (SRS step 1): core banking may publish to `fs.transactions.raw`; a consumer runs the same decision code and emits `fs.decisions.final`. Both ingress modes share one decision implementation and one test suite.

### C.3 Kafka topics

`fs.transactions.raw`, `fs.transactions.scored`, `fs.alerts.high`, `fs.alerts.medium`, `fs.alerts.anomaly`, `fs.decisions.final`, `fs.audit.events`, `fs.notifications.customer`, `fs.notifications.staff`, `fs.labels`, `fs.ml.retrain`, `fs.ml.shadow`, `fs.config.changes`. Each has a versioned JSON Schema in `contracts/kafka/`, a documented key (transaction_id or account token), partition count, retention and DLQ (`<topic>.dlq`).

### C.4 Failure behaviour (implements SRS 4.3 plus D-15)

ML scorer down → Resilience4j circuit opens within 5 s → rule-based fallback engine (versioned, tested) decides, `ml_unavailable_fallback=true`, label `ML_UNAVAILABLE` → a replay job re-scores fallback transactions after recovery and flags disagreements for analyst review. Kafka down → spool. Redis down → DB fallback for velocity from `account_velocity_cache` and TimescaleDB continuous aggregates; idempotency falls back to a PostgreSQL unique constraint check with degraded latency, and a `DEGRADED_MODE` metric and UI banner. PostgreSQL primary loss → replica promotion; writes queue in Kafka. Google OAuth down → email/password unaffected, button shows "Temporarily unavailable".

### C.5 Repository layout

```
fraudshield/
├── backend/                     Spring Boot (modules: ingest, decision, rules, auth, staff-api, notify, sar, verify-web, common)
├── ml/
│   ├── fraudshield_ml/          features/, models/, explain/, serving/ (gRPC+FastAPI), shadow/, drift/, campaigns/, labels/
│   ├── train.py  evaluate.py  benchmark.py  feature_engineering.py
│   └── configs/                 Hydra or plain YAML experiment configs
├── dataset/                     generator/ (agent-based simulator), realism_checks/, datasheet.md
├── frontend/                    src/{app,design-system,features/{alerts,investigation,risk-officer,admin,auth},lib,i18n,pwa}
├── design-tokens/               tokens.json, breakpoints.json → generated MUI theme + Tailwind config + CSS vars
├── contracts/                   openapi/, proto/, kafka/ (JSON Schemas), webhooks/
├── infrastructure/              docker/, k8s/ (kustomize base + overlays), grafana/, prometheus/, alertmanager/, argo-rollouts/
├── notebooks/                   shap_analysis.ipynb, model_comparison.ipynb, calibration.ipynb, fairness.ipynb
├── tests/                       performance/ (locust), chaos/, security/, e2e/ (playwright), contract/
├── docs/                        srs/, adr/, traceability/, architecture/, ml/, security/, compliance/, research/, benchmarks/, runbooks/
├── .github/workflows/
├── docker-compose.yml           profiles: core, ml, obs, full
├── Makefile                     make up | test | bench | reproduce | verify-all | report
├── README.md  CITATION.cff  LICENSE (Apache-2.0)  SECURITY.md  CONTRIBUTING.md
```

---

## PART D — EXECUTION PROTOCOL

### D.1 The per-requirement loop (apply to every FR, NFR, security, reliability, UX and ML requirement)

1. **Restate.** Requirement ID, priority, acceptance criterion verbatim, and every D-xx resolution that modifies it.
2. **Design.** If non-trivial, a short design note in the PR description or `docs/architecture/`.
3. **Test first.** Write the automated test(s) that encode the acceptance criterion. Tag each test with the requirement ID: JUnit `@Tag("FR-01-03")`, pytest `@pytest.mark.req("FR-02-04")`, Vitest/Playwright test title prefix `[FR-04-04]`.
4. **Implement** the smallest correct change.
5. **Run** unit tests, type checks and linters for the touched component; run integration tests if boundaries changed.
6. **Measure** if the criterion is a latency, throughput, size or render-time target; save raw output to `docs/benchmarks/<date>-<req>.{csv,json}` with the hardware spec.
7. **Record** evidence in the traceability matrix.
8. **Author self-check** (Part H standards) before requesting review: security (authz on every endpoint, input validation, no PII in logs/responses), audit event emitted for every write, observability (metric + structured log + trace span), accessibility, i18n (no hard-coded user-facing strings), mobile (320 px, touch targets 48 px), error/empty/loading/offline states, docs updated.
9. **Principal Review** of the change (Part I.2). Fix every BLOCKER and MAJOR finding, then re-review.
10. **Commit and push** per Part G. Only now may the traceability row move to `DONE`, with the commit SHA added to its evidence.

### D.2 Traceability matrix

Maintain both `docs/traceability/requirements.yaml` (machine-readable) and a generated `docs/traceability/requirements_matrix.md`:

| ID | Priority | Status | Implementation | Tests | Evidence | Deviations |
|---|---|---|---|---|---|---|

Status values: `NOT_STARTED`, `IN_PROGRESS`, `DONE`, `DONE_WITH_DEVIATION` (ADR required), `VERIFIED_AT_REDUCED_SCALE` (numbers required), `REQUIRES_EXTERNAL_PARTY`, `BLOCKED` (reason required).

Rows exist for: every FR-01-01 … FR-07-09; every row of SRS tables 4.1, 4.2, 4.3; 5.3 form fields; 5.4 components; 05B A.3–A.8 rows; 7.1 dataset rows; all 13 gate metrics in 7.2; 8.1 CI stages; 8.2 observability rows; 9 test rows; 10 research artifacts; and every D-xx.

A CI job (`traceability-check`) fails if any M requirement lacks at least one tagged test, or if any row marked DONE lacks evidence.

### D.3 Gated milestones (execute in order; do not start the next until the gate passes or the gap is recorded honestly)

**Every milestone ends the same way:** milestone-level Principal Review (Part I.3) → fixes → merge to `main` → push → tag `m<N>-complete` → push tag → author walkthrough (Part J) → status block.

**M0 — Bootstrap and governance**
Repository skeleton (C.5); SRS copied and extracted; `defect_register.md` from Part B; traceability YAML seeded with all IDs; ADR template; toolchains pinned (Java 21, Python 3.12 with uv, Node LTS with pnpm); pre-commit (Gitleaks, formatters); docker-compose `core` profile (Kafka, PostgreSQL+TimescaleDB, PII vault DB, Redis, MinIO, MLflow, Mailpit, WireMock); CI lint + type-check + unit skeleton for all three languages.
The repository already exists on GitHub with an initial commit: clone or pull it, never re-initialise it and never overwrite existing history. Configure Git identity and verify push access per G.1 as the very first action.
*Gate:* `make up` healthy; CI green; traceability-check runs; a test commit pushed successfully to a branch and visible on the remote.

**M1 — Contracts and data model**
OpenAPI 3.1 (all endpoints, examples for all 6 channels, RFC 9457 problem details); protobuf for scoring; Kafka JSON Schemas; webhook schema and signature spec. Flyway migrations: all SRS tables + D-30/D-31 additions, hypertables, compression and retention policies, continuous aggregates, partial unique indexes (one production, one shadow model), CHECK constraints, RLS by institution, roles (`fs_app`, `fs_app_readonly`, `fs_compliance_ro`, `fs_migrator`), INSERT/SELECT-only grants on append-only tables, audit hash chain.
*Gate:* OpenAPI validates against 3.1 schema; DB security tests prove `UPDATE`/`DELETE` on `audit_events`, `auto_block_events`, `alert_decisions` return permission denied for `fs_app`; RLS test proves institution isolation.

**M2 — FraudShield-EAC-Transactions generator (Part E.3)**
*Gate:* 5M+ rows generated deterministically from a seed; all 7.1 distribution targets met within ±0.5 percentage points; realism checks pass (no single feature AUC > 0.80, label noise present, novelty variant only in test period); datasheet complete.

**M3 — Feature engineering and feature store (Part E.2)**
*Gate:* all 44 feature unit tests pass for all 6 channels; property tests pass; Redis/DB fallback tested; `benchmark.py features` p95 < 10 ms including Redis fetch; `docs/features.md` generated.

**M4 — Models, calibration, explainability, evaluation (Parts E.4, E.5)**
*Gate:* `evaluate.py` reports all 13 gate metrics with 95% bootstrap CIs; baselines and ablations complete; SHAP additivity test passes; LaTeX tables and figures generated; ONNX export parity verified. If any gate metric fails, record measured value and analysis — do not tune on test.

**M5 — Scoring service**
gRPC + FastAPI admin; multi-process serving; model hot-swap via MLflow alias polling (≤ 10 s) with blue-green in-memory switch; shadow scoring off the hot path; fallback rules engine in Java.
*Gate:* FR-02-01, 02-04 … 02-10 tests pass; `benchmark.py serve` with 200 concurrent requests meets p50 < 15, p95 < 25, p99 < 40 ms; memory growth < 50 MB over 10,000 scorings.

**M6 — Ingestion and decision engine (Part E.6)**
*Gate:* FR-01-*, FR-03-* tests pass; idempotency test (100 identical submissions → 1 scored, 100 identical responses; 10,000 duplicate storm); end-to-end p95 decision latency < 50 ms at the largest achievable load; chaos tests for ML, Kafka, Redis, PostgreSQL failure pass.

**M7 — Staff identity, authorisation, admin and audit (Part E.8)**
*Gate:* all FR-07-* and FR-06-01/02/06/07 tests pass; role × endpoint matrix test (every role and API key against every endpoint) proves 403s; bcrypt timing > 100 ms; token_version invalidation < 5 s.

**M8 — Front-end (Part E.9)**
Order: design tokens → design system components with Storybook + a11y checks → app shell, routing, PWA → auth screens → analyst dashboard → investigation drawer tabs → risk officer → admin → customer verification page → i18n → performance pass.
*Gate:* Vitest component tests from SRS section 9; the 5 Playwright journeys on Chromium, Firefox, WebKit; mobile emulation matrix (05B A.8); 0 critical/serious Axe violations; bundle and Core Web Vitals budgets met under 3G + 4× CPU throttle; SHAP chart interactive < 500 ms; alert feed with 500 seeded alerts interactive < 1 s.

**M9 — Observability, infrastructure, CI/CD, security**
*Gate:* all 8 CI stages in SRS 8.1 implemented; Grafana dashboards provisioned as code; alert rules for every SRS 8.2 threshold; k8s manifests with namespace `fraudshield`, NetworkPolicies, PodSecurity restricted, HPA, PDBs; Argo Rollouts canary with automated analysis (error rate > 0.5% or p99 > 80 ms → rollback); ZAP 0 critical; Gitleaks 0; Trivy 0 high/critical unfixed; SBOM and cosign signatures produced.

**M10 — Verification campaign**
Distributed Locust at the largest achievable scale (target 10,000 TPS for 5 minutes); chaos suite; full E2E and device matrix; security suite; ML gate; produce `docs/benchmarks/verification_report.md`.
*Gate:* every matrix row has a final status with evidence.

**M11 — Research artifacts (Part E.13)**
*Gate:* `make reproduce` regenerates dataset → trains → evaluates → produces paper tables/figures on a clean machine; README, CITATION.cff, model card, datasheet, paper draft complete. Publishing to HuggingFace, Zenodo and arXiv is prepared as scripts and checklists but executed by the author, not by you.

**M13 — Operational reporting (SRS v5.0 §20, FR-08)**
Scheduled daily, weekly and monthly operations reports, the Reports tab, the custom-range report, the report API and the report retention policy. Runs **after M8** (it needs the admin shell) and after M6 and M9 supply its data and dashboards; it is numbered 13 rather than inserted as a new M9 because M9–M12 are referenced by branches, reviews and the register already.
*Gate:* FR-08-01 … FR-08-08 tests pass; a generated report is reproduced byte for byte from seeded data; retention policy enforced and verified; no report contains raw PII. The scheduling and PDF stack is **not** settled by these requirements: choosing one needs an ADR comparing the candidates with what the project already runs.

**M12 — Final audit and handover report (Parts F and I.4)**
Full-system Principal Review from a fresh clone; final report; `v1.0.0` release tag pushed.

---

## PART E — DETAILED SPECIFICATIONS

### E.1 Ingestion and decision API

- `POST /api/v1/transactions/ingest` → 200 `DecisionResponse` (first call and idempotent replays; replays add header `Idempotent-Replayed: true`). 400 for missing required fields with a field-level `errors[]`; 422 for type mismatches; 401 bad key; 403 wrong scope; 413 oversized; 429 rate limit with `Retry-After`.
- Schema per FR-01-02. Additional validation: currency must be a valid ISO 4217 code; amount > 0 with ≤ 4 decimals; latitude ∈ [−90, 90], longitude ∈ [−180, 180]; timestamp not more than 5 minutes in the future; MCC exactly 4 digits; `account_id` and `counterparty_id` must be tokens (pattern `tok_[A-Za-z0-9]{24,}`) — raw MSISDNs or account numbers are rejected with 400 to prevent PII entering the system.
- `POST /api/v1/transactions/ingest/batch` (≤ 1,000) → 202 with `job_id`; `GET /api/v1/jobs/{id}` → progress, per-item results.
- `GET /api/v1/decisions/{transaction_id}` for HOLD outcomes (D-14).
- Webhook `decision.final` signed with `X-FraudShield-Signature: t=<ts>,v1=<hmac>`; 5-minute replay window; exponential retries for 24 h; DLQ visible in admin.
- `/api/docs` serves OpenAPI 3.1 with one full example per channel. Health: `/actuator/health`, `/api/v1/health/ml`, `/api/v1/health/kafka`.

### E.2 Authoritative feature catalogue (44)

All amounts are normalised to RWF using the FX table for the transaction date. All "account" aggregates are keyed by the account token. Every feature has: name, group, dtype, definition, window, source (Redis/DB/request), NaN rule, leakage note, plain-English template key. Label-derived features use only labels whose `label_available_at` < transaction time.

**Velocity (8):** `tx_count_60s`, `tx_count_1h`, `tx_count_24h`, `tx_count_7d`, `amount_sum_24h`, `amount_sum_7d`, `unique_counterparties_24h`, `velocity_ratio_1h_vs_30d` (1 h count ÷ mean hourly count over prior 30 days; defined as 0-history-safe with Laplace smoothing).

**Amount behaviour (5):** `amount_log1p`, `amount_zscore_90d` (robust: median/MAD; NaN→0 with < 5 history), `amount_to_max_90d_ratio`, `round_sum_flag` (EAC-aware: round relative to the currency's common denominations, e.g., RWF multiples of 1,000/5,000/10,000; not a fraud signal by itself), `just_below_limit_flag` (within 5% below any configured channel/KYC limit or active rule threshold).

**Temporal (6):** `local_hour_sin`, `local_hour_cos` (local time from coordinates, D-43), `local_day_of_week`, `is_local_night` (00:00–04:59), `is_month_end_window` (last 3 and first 2 days of month — salary periods), `seconds_since_last_tx` (NaN if no history).

**Geographic (5):** `distance_from_last_tx_km`, `implied_speed_kmh` (capped; NaN if no history), `distance_from_home_centroid_km` (90-day median location), `is_new_country_for_account`, `geo_cell_fraud_rate_30d` (H3 resolution 6 cell; time-lagged confirmed-fraud rate with Bayesian smoothing).

**Counterparty (5):** `counterparty_is_new_for_account`, `counterparty_account_age_days`, `counterparty_unique_senders_24h` (mule signal), `counterparty_confirmed_fraud_90d` (lagged), `tx_count_to_counterparty_30d`.

**Device & channel (5):** `channel` (categorical, native categorical handling), and 4 device-derived features that are NaN for null fingerprints: `device_is_new_for_account`, `accounts_per_device_7d`, `device_changes_24h`, `device_age_days`.

**Account profile (4):** `account_age_days`, `kyc_tier` (ordinal), `days_since_sim_swap` (MNO signal; NaN if unavailable), `dormancy_reactivation_flag` (no activity ≥ 60 days before this transaction).

**Agent-specific (4; AGENT_BANKING only, else NaN):** `agent_float_utilisation_ratio`, `agent_cashout_count_1h`, `agent_unique_customers_1h`, `agent_distance_from_registered_km`.

**EAC corridor (1):** `corridor_class` (categorical: DOMESTIC, EAC_CROSS_BORDER, NON_EAC_CROSS_BORDER), so regional remittances are not treated like arbitrary foreign transfers.

**Synthetic-identity risk (1):** `synthetic_identity_score` ∈ [0, 1], a deterministic, documented composite of low KYC tier, account age < 30 days, shared device/phone attributes across accounts, and rapid volume ramp; computed without labels.

Tests: per-feature unit tests with hand-computed expectations; 0-history cases; USSD NaN positions (exactly 4 device features); agent features NaN outside AGENT_BANKING; `round_sum_flag` cases for RWF/KES/TZS/UGX/CDF; Hypothesis property tests (non-negativity of counts, monotonicity of window counts 60s ≤ 1h ≤ 24h ≤ 7d, finite outputs); training/serving skew test (same raw history through the batch path and the online path yields identical vectors to 1e-9).

### E.3 Dataset generator (FraudShield-EAC-Transactions)

Agent-based, seeded, reproducible simulator. Entities: customers (with KYC tier, home location sampled from district population weights in RW/KE/TZ/UG/DRC), accounts, devices (null for USSD-only users), SIM events, agents (with float, registered location), merchants (MCC), counterparties. Legitimate behaviour models: salary cycles, market days, school-fee periods, family remittances including EAC corridors, round-sum transfers, agent cash-in/cash-out peaks, rural USSD usage. Volume grows over 24 months.

Eight fraud scenario generators, each with randomised parameters and temporal evolution: SIM-swap takeover, account takeover via credential/device change, agent fraud (float manipulation, fake cash-outs), velocity fraud, card-not-present, mule accounts (fan-in/fan-out), merchant fraud, synthetic identity. Include adversarial adaptation (amounts drift just below rule thresholds after rules are "published" in simulation month 12), and one novel sub-variant introduced only in the test period.

Targets (SRS 7.1): ≥ 5,000,000 rows; overall fraud rate 0.87%, test ≈ 0.91%; channel mix; country mix; ≥ 98% feature completeness excluding structural NaNs (D-04), which are reported separately; 1–2% label noise; label delay distribution (hours to weeks).

Realism report (`dataset/realism_report.md`, generated): distribution tables vs targets, single-feature AUCs (must be ≤ 0.80), per-scenario prevalence over time, novelty-variant placement proof, and a "trivial rule baseline" AUC. Outputs: CSV + Parquet, schema, datasheet (Gebru et al. template), CC BY 4.0 licence file, SHA-256 checksums.

### E.4 Training, calibration, explainability

- `train.py`: reads config; temporal splits (D-07); trains XGBoost and LightGBM with native missing and categorical handling, class imbalance via `scale_pos_weight` / `is_unbalance` (no oversampling per SRS), early stopping on validation; latency-constrained hyperparameter search (D-16) with a fixed trial budget, Optuna or equivalent, objective = validation average precision; 5 seeds for the final configuration; ensemble + isotonic calibration (D-05); Isolation Forest (contamination = 0.01) on imputed features + reference distribution for percentile scoring (D-06); logs everything to MLflow (params, metrics, data hashes, git SHA, environment lock, hardware).
- Artifacts: native model files (for SHAP), ONNX exports (parity test: max absolute probability difference < 1e-5 on 100,000 rows), calibrator, IF + reference, feature registry version, model card.
- Explainability: `shap.TreeExplainer` exact path per model; additivity test per model margin; top-5 by |weighted φ| stored in `shap_top5` as `[{feature, value, shap, direction, template_key}]`; full 44 stored for flagged transactions to power the waterfall.

### E.5 Evaluation protocol (`evaluate.py`) — research grade

1. **Gate metrics (13)**, as corrected by D-01/D-02/D-11: AUC-ROC; Recall@1%FPR (plus precision@1%FPR and its ceiling); recall, F1, FNR at 0.60; FPR at 0.85; channel AUC for MOBILE_MONEY, USSD, AGENT_BANKING; SHAP coverage for HIGH+MEDIUM; ECE (15 equal-mass bins; also report equal-width ECE and Brier score); scoring p99 latency from `benchmark.py`; shadow AUC delta.
2. **Uncertainty:** stratified bootstrap (1,000 resamples) 95% CIs for every metric; mean ± SD over 5 seeds; DeLong test for AUC differences between the ensemble and each baseline.
3. **Baselines:** status-quo rule engine (amount thresholds, MCC blocklist), logistic regression, random forest, XGBoost alone, LightGBM alone, Isolation Forest alone.
4. **Ablations:** remove EAC-specific groups one at a time (agent, corridor, USSD-aware device handling, round-sum awareness, month-end); "card-style features only" model to test the Western-model hypothesis (D-09); ensemble vs single-model seed variance.
5. **Robustness:** per-country and per-channel metrics; leave-one-fraud-type-out (train without one scenario, test on it) to measure novelty detection with and without Isolation Forest routing; novelty sub-variant recall; performance by month of test period (drift).
6. **Calibration:** reliability diagrams overall and per channel.
7. **Fairness and harm:** FPR and blocked-legitimate-amount by country, channel, KYC tier, urban/rural; report disparities and discuss customer harm of false positives for low-income users.
8. **Business view:** cost curve (fraud loss prevented in RWF vs legitimate transactions blocked vs analyst reviews) across thresholds; alert-budget analysis (D-10).
9. **Outputs:** `reports/metrics.json`, LaTeX tables (`reports/tables/*.tex`), PDF/PNG figures, and a pass/fail gate summary. CI fails the ML gate if any gate metric's point estimate misses its threshold; the CI artifact includes all tables.

### E.6 Risk decision engine

- Thresholds per channel (FR-05-07) stored as versioned rows, cached in Redis, pushed via `fs.config.changes`, applied atomically by version; effective ≤ 60 s (target ≤ 5 s). Tiers use half-open intervals: HIGH ≥ high_threshold; MEDIUM ≥ medium_threshold and < high_threshold; LOW otherwise (removes the 0.84–0.85 gap in the SRS).
- Order of evaluation: (1) validation and idempotency; (2) account frozen? → DECLINE with reason `ACCOUNT_FROZEN`; (3) ML or fallback score; (4) tier from thresholds; (5) custom rules may only **raise** a tier, never lower an ML HIGH; (6) MCC circuit breaker open → at least MEDIUM; (7) anomaly routing (D-06, D-10); (8) side-effect intents to outbox.
- HIGH: DECLINE, auto-block event, customer SMS intent, `fs.alerts.high`, freeze check using a Redis sorted set (3rd HIGH within a sliding 60 minutes freezes; freeze event + risk officer email).
- MEDIUM: HOLD; alert with `review_deadline_at = now + 30 s`; deadline scheduler (Redis sorted set + leader-elected poller every 100 ms); timeout policy per channel (D-10).
- LOW: APPROVE.
- Rule DSL (FR-05-05): JSON AST validated by JSON Schema. Nodes: `all`, `any`, `not`, and comparisons `{field, op, value}` with ops `eq, ne, gt, gte, lt, lte, in, not_in, between, is_null, is_not_null`. `field` must be a raw request field or one of the 44 features (validated against the registry). No regex, no arbitrary code. Rules compile to predicates; hot reload via `fs.config.changes`; edits create a new `alert_rule_versions` row (rules immutable; enable/disable/soft-delete only); `trigger_count` updated asynchronously. Every operator has unit tests, including null semantics.
- Campaign detection (FR-05-04): every 15 minutes over a rolling 24 h window of HIGH/confirmed-fraud transactions, group by device, counterparty, H3 cell or MCC; create campaign when group size ≥ 5 (configurable) and the group's fraud rate exceeds its 30-day baseline by ≥ 3×; names like `CMP-20260917-DEVICE-0042`.
- SAR drafts (FR-05-06, D-22): generated ≤ 30 s after fraud confirmation from a versioned template; PDF via server-side rendering; DRAFT watermark; risk officer review and sign-off recorded; `REGULATORY_REPORT` audit events.

### E.7 Customer verification

Implements FR-03-04/05 with D-25. SMS in the customer's locale, GSM-7 only, ≤ 160 characters, containing masked account, amount, currency, local time, reference code, official contact and HTTPS link. Page: server-rendered, no JS, ≤ 30 KB, large text and 48 px targets, shows masked transaction details and two buttons ("Yes, this was me" / "No, this was not me"); "No" confirms fraud as a customer label and shows the official contact. "Yes" lifts the block within 10 s (webhook `decision.final` to core banking), sets `false_positive_confirmed=true`, and emits a label event for retraining — unless self-service is disabled by D-25 conditions. Expired or used token shows a clear, non-revealing message.

### E.8 Staff identity and authorisation

- Roles: ANALYST < SENIOR_ANALYST < RISK_OFFICER; ADMIN is separate (manages users/models/system but does not decide alerts unless also granted a decision role — record in ADR). Permissions are explicit per endpoint (method-level security), not inferred from hierarchy alone. API keys carry scopes (`ingest:write`, `decisions:read`, `jobs:read`) and never access staff endpoints.
- JWT RS256, 15-minute expiry, claims `sub, role, first_name, email, institution_id, token_version, kid`; JWKS endpoint with key rotation.
- Registration (FR-07-02, 5.3, D-24): all validations client- and server-side with identical Zod (front-end) and Bean Validation (back-end) rules generated from one spec; async availability checks for email and employee ID are rate-limited and return neutral timing to limit enumeration.
- Password reset (FR-07-08): 6-digit OTP, hashed, single-use, 10-minute expiry, max 5 attempts; generic response whether the email exists.
- Google OAuth (FR-07-03, D-23): Authorization Code + PKCE via Google Identity Services, ID token verified server-side (signature via cached JWKS, `aud`, `iss`, `exp`, `email_verified`); revoke Google tokens on logout when an access/refresh token exists (FR-07-09).
- Admin (FR-06-*): user lifecycle, approvals, IP allowlist, API keys (raw key shown once, copy button, 24 h rotation overlap), model management (promotion button disabled with explicit reasons until the gate passes; rollback ≤ 30 s), dataset upload (CSV schema validation, size limit, PII pattern scan that rejects raw phone numbers/names), retraining progress over WebSocket, system health (Prometheus-backed; Grafana panels via authenticated proxy, never anonymous embeds), audit search.
- Risk officer (FR-05-*): overrides with mandatory reason (≥ 20 characters) creating `SENIOR_OVERRIDE` records referencing the original decision.

### E.9 Front-end: product and UX specification

**Design principles (from SRS 5.1, operationalised):** the risk tier is the first thing read on every screen (position, size, weight, text label, icon — never colour alone); every decision screen shows "why" (top SHAP reasons in plain language) before "act"; density without clutter (tabular numerals, aligned columns, no decorative elements, compact density mode for desktop).

**Design tokens:** one `tokens.json` (colour incl. D-33 text-safe tokens, typography, spacing on a 4 px scale, radii, elevation, motion durations with reduced-motion variants, z-index, breakpoints D-36) generating MUI theme, Tailwind config and CSS variables. Light theme per SRS; dark theme for long shifts with all pairs contrast-tested. Typography: self-hosted variable font subset (Latin + Latin Extended for Kinyarwanda/French) with system fallback; `font-variant-numeric: tabular-nums` for amounts, scores and timers.

**Information architecture and routes:**
- Public: `/login`, `/register`, `/forgot-password`, `/reset-password`, `/unlock`, `/pending-approval`.
- Analyst / Senior analyst: `/alerts` (feed + investigation drawer; deep link `/alerts/:id` and filter state in URL per FR-04-09), `/anomalies`, `/escalations` (senior), `/me/performance`, `/search`.
- Risk officer: `/portfolio`, `/model-performance`, `/campaigns`, `/rules`, `/thresholds`, `/reports/sar`, `/overrides`.
- Admin: `/admin/users`, `/admin/approvals`, `/admin/models`, `/admin/datasets`, `/admin/health`, `/admin/audit`, `/admin/api-keys`, `/admin/circuit-breakers`, `/admin/ip-allowlist`.
- Settings: profile, language, theme, density, sound and push notifications, keyboard shortcuts.

**Screen state contract:** every data view implements loading (skeleton matching final layout; CLS < 0.1), empty (what it means + next action), error (plain message, retry, correlation ID), stale/offline ("Last updated 14:02 CAT"), permission denied, degraded (system-wide banners for `ML_UNAVAILABLE`, `DEGRADED_MODE`, "Real-time paused — refreshing every 60 s").

**Analyst dashboard (5.4, FR-04-*):**
- Stats AppBar with the 7 SRS stats; clicking filters the feed; RWF amounts animate only when motion is allowed; mobile collapses to 3 critical chips + expand (05B A.7).
- Alert feed: free DataGrid (D-35) on ≥ 768 px, card list with swipe actions on smaller screens; columns and card contents exactly per FR-04-03 (8 elements); new alerts animate in (Framer Motion, lazy, disabled on reduced motion and on 2G); MEDIUM countdown chip turns red < 10 s; optional Web Audio chime < 5 s only after explicit opt-in.
- Keyboard workflow (documented in a `?` overlay, all remappable, none single-key without focus in the feed): `J/K` next/previous alert, `Enter` open, `Esc` close, `/` search, `C` focus comment, `Ctrl+Enter` submit selected action. After an action, focus moves to the next alert.
- Investigation drawer (≤ 200 ms open; feed stays interactive): tabs Summary, SHAP Explanation, Account History, Behavioural Fingerprint, Network Graph. Summary leads with a one-sentence reason, e.g. "Blocked because this account sent 12 transfers in 60 seconds (8.3× its normal rate) to a counterparty created yesterday."
- Action panel (FR-04-04, D-44): CONFIRM FRAUD (red outlined) and MARK LEGITIMATE (green), comment ≥ 10 characters with live counter, escalation select with mandatory reason, 5-second undo Snackbar, haptic `navigator.vibrate([100,50,100])` on CONFIRM FRAUD where supported.
- Accessibility (FR-04-12): tier text in every badge; SHAP chart has an equivalent accessible table and `aria-describedby` summary; live regions announce new HIGH alerts politely and not more than once per 5 seconds; full keyboard operation; focus visible with `brand.accent` ring; 48 px touch targets; Axe checks in component and E2E tests; manual NVDA script in `docs/ux/screen_reader_test.md`.

**Registration form (5.3):** exactly the SRS fields with D-24 split; single column < 600 px with sticky submit above the keyboard; country selector as searchable virtualised list (full-screen `SwipeableDrawer` on mobile), self-hosted flags (D-38), E.164 normalisation with `libphonenumber-js`; strength meter with 4 levels and listed unmet rules; confirm-password real-time validation; "Continue with Google" button per Google branding rules with loading state.

**Risk officer:** portfolio metrics, fraud rate by channel and MCC, district-level heatmap (D-46), top-10 counterparty tokens, model performance with label coverage shown next to live AUC, campaigns board, rule builder (visual AST editor that produces the JSON DSL with validation preview "This rule would have matched 1,240 transactions in the last 7 days"), per-channel thresholds with impact preview (estimated alerts/hour at the proposed value from the last 24 h of scores).

**PWA and mobile (05B):** Workbox app shell cache; TanStack Query persistence (D-29 constraints); install banner after 30 s on mobile only, dismissed state 30 days; iOS meta tags; push for HIGH alerts with permission prompt only after a user gesture; pull-to-refresh on the feed; no `user-scalable=no`; WebSocket heartbeat 60 s on 3G, 60 s polling on 2G via Network Information API where available and by measured RTT otherwise.

**Performance budgets:** initial JS ≤ 200 KB gzip (D-39); FCP, LCP, TTI, TBT, CLS targets from 05B A.6 on 3G + 4× CPU; memory < 150 MB over a 50-interaction Playwright leak test at 320 px.

### E.10 Observability

Metrics (Prometheus naming): `fs_ingest_requests_total{channel,status}`, `fs_decision_latency_seconds` (histogram, buckets 5 ms … 200 ms), `fs_scoring_latency_seconds{stage}`, `fs_feature_latency_seconds{source}`, `fs_decisions_total{tier,decision,channel,fallback}`, `fs_kafka_consumer_lag{topic,group}`, `fs_spool_depth`, `fs_alert_queue_depth{tier}`, `fs_alert_timeout_release_total{channel}`, `fs_review_duration_seconds`, `fs_false_positive_rate`, `fs_feature_psi{feature}`, `fs_model_version_info{alias}`, `fs_redis_hit_ratio`, `fs_db_pool_in_use`. OpenTelemetry traces from edge to scorer to Kafka consumers with trace ID stored on the ScoringResult. Logs: JSON, PII-redacting processors in both languages, with tests proving redaction. Alert rules implement every SRS 8.2 threshold plus D-10 timeout-release alert. Runbooks in `docs/runbooks/` for each page-worthy alert.

### E.11 Infrastructure and CI/CD

Implement the 8 stages of SRS 8.1 in GitHub Actions with caching and required checks: lint/static (Checkstyle, SpotBugs, ruff, mypy strict, eslint, `tsc --noEmit`, SonarQube if a token exists — otherwise report skipped); unit with coverage gates (Java ≥ 85%, Python ≥ 90%, front-end thresholds set and enforced); integration with Testcontainers; security (OWASP Dependency-Check, Gitleaks, Trivy, ZAP baseline against the compose stack); ML gate (on changes under `ml/`); Docker builds with size limits (api < 500 MB, ml < 1.2 GB, frontend < 150 MB), SBOM, cosign; staging deploy + smoke tests; production canary via Argo Rollouts with manual approval. Plus `traceability-check`, bundle-size check, accessibility check, contrast-token test, OpenAPI/proto breaking-change checks.

### E.12 Test suites (SRS section 9 plus additions)

Implement every SRS section 9 row with the framework corrections in D-17. Additions: property-based tests (Hypothesis, jqwik); contract tests between Java and Python generated from the proto; mutation testing on the decision engine (PIT) and feature code (mutmut) with reported scores; chaos tests (Toxiproxy locally, Chaos Mesh manifests for k8s); training/serving skew test; audit hash-chain tamper test; role × endpoint authorisation matrix; i18n completeness test; offline/conflict replay tests (D-29); SIM-swap verification tests (D-25); fairness report generation test.

### E.13 Research artifacts and paper

- `make reproduce` runs dataset generation → training → evaluation → figures → LaTeX tables with fixed seeds, recording total compute time and hardware.
- Paper draft in `docs/research/paper/` (LaTeX): Title from SRS; abstract; introduction with verified claims only (D-09); related work (only papers you can cite accurately; leave `\cite{TODO-verify}` placeholders rather than inventing references); problem setting (EAC channels, USSD, agents, corridors); dataset (generator, realism controls, datasheet); method (features, ensemble, calibration, Isolation Forest routing, SHAP in margin space); system (architecture, latency budget, measured results); evaluation (E.5 in full, with CIs); analyst UX and explainability design; ethics, fairness, privacy and regulatory considerations; limitations (synthetic data, no real deployment, unverified regulatory templates); reproducibility statement.
- Model card (Mitchell et al. template) and dataset datasheet (Gebru et al.).
- README: one-paragraph problem, architecture diagram (Mermaid), measured benchmark table with hardware and scale, gate metrics with CIs, quickstart (`make up`), demo script, screenshots/GIF captured from the running app (never mocked images), limitations, citation.
- `CITATION.cff` without a DOI until one exists.
- Publishing scripts for HuggingFace and Zenodo, and an arXiv submission checklist, for the author to run. Venue deadlines in the SRS must be re-checked against current calls for papers by the author.

---

## PART F — DEFINITION OF DONE, FINAL REPORT, AND PROHIBITIONS

### F.1 Definition of done (project)

1. Every M requirement is `DONE`, `DONE_WITH_DEVIATION` (with ADR) or honestly `VERIFIED_AT_REDUCED_SCALE`/`REQUIRES_EXTERNAL_PARTY`, with evidence.
2. Every S requirement is implemented or has a recorded reason; C items attempted after all M and S.
3. All 51 defect resolutions (D-01 … D-51) are implemented and referenced.
4. `make verify-all` runs all suites and produces `docs/benchmarks/verification_report.md`.
5. Every milestone has an approved review record in `docs/reviews/`, zero open BLOCKER or MAJOR findings, and a pushed `m<N>-complete` tag; `main` on GitHub equals the local `main` (`git status` clean, `git log origin/main..main` empty).
6. The final fresh-clone review (I.4) passes.
7. A new engineer can clone, run `make up`, open the console, log in with seeded demo accounts for each role, ingest a synthetic stream (`make demo-stream`), and see HIGH, MEDIUM and anomaly alerts with explanations within 10 minutes, following only the README.

### F.2 Final handover report (`docs/FINAL_REPORT.md`, and summarised in your last message)

1. Executive summary (5 sentences, no marketing language).
2. Requirement coverage table by section with counts per status.
3. Measured performance vs targets, with hardware, scale and links to raw data.
4. ML results: 13 gate metrics with 95% CIs, baselines, ablations, fairness summary, and a frank interpretation of what synthetic-data results do and do not show.
5. Deviations from the SRS with ADR links (the D-xx register plus new ADRs).
6. Known risks and open issues, ranked by severity.
7. Items requiring the author or external parties (regulatory confirmation, native-speaker translation review, penetration test, data-residency hosting, publishing credentials, citation verification).
8. Exact commands to reproduce every number in the report.

### F.3 Prohibitions

- Asking the user for decisions (use A.3 rule 1).
- Fabricated numbers, citations, regulatory fields, screenshots or test outcomes.
- Using test data for any modelling decision.
- Raw PII in logs, analyst APIs, Redis, Kafka payloads, test fixtures or screenshots.
- Paid/commercial licences (MUI X Pro/Premium, commercial map tiles) or any non-Apache/MIT/BSD-compatible dependency without an ADR.
- Disabling a failing test, lowering a threshold, or skipping a gate to make CI green.
- LLM-generated explanations shown to analysts or customers.
- Any code, config or naming borrowed from KinyaMed / HealthGuard AI.
- Claims that FraudShield is deployed at, endorsed by, or validated with any real institution or regulator.
- Marking any requirement `DONE`, merging to `main`, or tagging a milestone without a recorded Principal Review verdict of `APPROVED` or `APPROVED_WITH_MINORS`.
- Any action listed in G.6.

---

## PART G — GIT WORKFLOW: COMMIT AND PUSH AS THE REPOSITORY OWNER

### G.1 Identity and access (first action of the session)

1. Work inside the existing clone of `git@github.com:mariusbayizere/fraudshield.git`. If the directory is not a clone, clone it. If it has local changes you did not make, stop and report them rather than overwrite them.
2. Set the identity **for this repository only** (never `--global`):
   ```bash
   git config user.name  "Marius Bayizere"
   git config user.email "bayizeremarius119@gmail.com"
   git config pull.rebase true
   git config push.autoSetupRemote true
   ```
   If commit signing is already configured on the machine (`git config --get user.signingkey` returns a value), keep it enabled; never disable it.
3. Commits are authored and committed solely as the repository owner. Do not add `Co-Authored-By` trailers or tool-generated signatures to commit messages.
4. Verify access: `ssh -T git@github.com` and `git ls-remote origin`. If access fails, continue working and committing locally, record the failure in the status block, and retry the push at the end of each milestone. Never ask for, store, print or commit credentials, tokens or private keys.

### G.2 Branching model

- `main` — protected by convention: only merged, reviewed, green work. Never commit directly except the M0 bootstrap merge.
- No `develop` branch. SRS 8.1 stages triggered by "push to develop/main" run on pull requests targeting `main` and on pushes to `main` instead (record in an ADR).
- Work branches: `m<N>/<short-scope>` (e.g., `m3/velocity-features`, `m6/idempotency`). One coherent scope per branch, typically 1–10 commits.
- If the GitHub CLI (`gh`) is installed and authenticated, open a pull request per branch with the template in G.4, run CI, post the Principal Review record as a PR comment, then merge with **squash-or-rebase preserving meaningful commits** (prefer rebase merge so requirement-tagged commits survive). If `gh` is unavailable, rebase the branch onto `main`, fast-forward merge locally, and push.
- After M0, if `gh` is authenticated, enable branch protection on `main` requiring the CI checks to pass. If you lack permission, record it for the author.

### G.3 Commit standard

Conventional Commits, imperative mood, subject ≤ 72 characters, body wrapped at 72, explaining **why** as well as what:

```
feat(decision): hold MEDIUM transactions with 30 s review deadline

Implements the HOLD outcome and deadline scheduler. Final decision is
delivered via signed webhook and fs.decisions.final (D-14), because an
HTTP request cannot stay open for 30 seconds.

Refs: FR-03-02, D-14, D-18
Tests: DecisionEngineHoldTest, MediumTimeoutIT (±500 ms tolerance)
```

Types: `feat`, `fix`, `perf`, `refactor`, `test`, `docs`, `build`, `ci`, `chore`, `ml` (model/training changes), `data` (generator changes), `sec` (security hardening). Each commit builds and passes the tests for the component it touches. No "WIP", "fix stuff", "update" or mega-commits mixing unrelated scopes.

### G.4 Pull request / merge description template

```
## What and why
## Requirements and defects addressed   (FR-xx, NFR rows, D-xx)
## How it was verified                  (commands run + key output, benchmark files)
## Screenshots                          (UI changes: 320 px, 768 px, 1280 px, dark theme — captured from the running app)
## Risks and rollback
## Principal Review                     (link to docs/reviews/... and verdict)
```

### G.5 Pre-push gate (run every time, in this order; abort the push on any failure)

1. `git fetch origin && git rebase origin/main` — resolve conflicts carefully; re-run tests after any conflict resolution.
2. Secret scan: `gitleaks protect --staged` and `gitleaks detect --log-opts="origin/main..HEAD"`.
3. Lint and type checks for touched components; unit tests for touched components; integration tests if boundaries changed.
4. Large-file check: no file > 5 MB, no datasets (`*.csv`, `*.parquet`), no model binaries, no `mlruns/`, no `.env`. Large artifacts go to MinIO/MLflow locally and HuggingFace/Zenodo at publication.
5. PII check: no real names, phone numbers, national IDs or account numbers in fixtures, logs, screenshots or docs (synthetic tokens only).
6. `git push`. Then confirm with `git log origin/main..main` (must be empty after merging) and report the pushed SHA.

### G.6 Absolute Git prohibitions

- `git push --force` or `--force-with-lease` to `main`; rewriting published history; deleting remote branches or tags you did not create in this session.
- Committing secrets, credentials, `.env` files, private keys, datasets, model binaries or real personal data. If a secret is ever committed, stop, rotate it (record for the author), and purge it with the author's knowledge before any further push.
- `--no-verify` to skip hooks; disabling CI checks; merging with failing checks.
- Pushing work that has not passed Principal Review to `main`.
- Changing the global Git configuration or the remote URL.

### G.7 Tags and releases

- `m<N>-complete` annotated tag on `main` after each milestone (`git tag -a m3-complete -m "M3: 44 features, p95 7.8 ms, review R-M3 approved"`), pushed with `git push origin m<N>-complete`.
- `v1.0.0` at the end of M12, with release notes generated from Conventional Commits and linked to `docs/FINAL_REPORT.md`. Maintain `CHANGELOG.md`.

---

## PART H — SENIOR ENGINEERING STANDARDS (WHAT "SENIOR" MEANS IN THIS CODEBASE)

These are the standards the Principal Reviewer enforces. "It works" is not the bar; "it is correct, clear, tested, secure, observable, and a strong engineer would be glad to maintain it" is.

### H.1 Universal

- **Design for failure and change:** every external call has a timeout, retry policy (with jitter, only for idempotent operations), circuit breaker and a defined degraded behaviour. Configuration is externalised and validated at startup (fail fast on invalid config).
- **Clear boundaries:** hexagonal (ports and adapters) structure; domain logic has no framework, database or network imports; adapters are thin.
- **Names and size:** intention-revealing names from the SRS glossary; functions do one thing; files > 400 lines or functions > 50 lines require justification in review.
- **Errors:** never swallow exceptions; typed domain errors; RFC 9457 problem responses with stable `type` URIs; correlation ID on every error.
- **No magic numbers:** thresholds, windows and limits are named constants or configuration with units in the name (`review_deadline_seconds`, `velocity_window_1h`).
- **Money:** `BigDecimal` (Java) / `Decimal` (Python) / integer minor units or decimal strings over the wire (TypeScript never does arithmetic on money floats). Currency always travels with amount.
- **Time:** UTC everywhere internally, `Instant`/timezone-aware datetimes only; injectable clocks so timer tests are deterministic.
- **Documentation:** public APIs have doc comments; each module has a short `README.md` (purpose, boundaries, how to test); non-obvious decisions link to ADRs.
- **Dependencies:** every new dependency is justified (why not the standard library or an existing dependency), licence-checked and pinned.

### H.2 Java 21 / Spring Boot

- Multi-module Gradle or Maven build; records for immutable DTOs and value objects; constructor injection only; no field injection; no Lombok unless justified by ADR.
- ArchUnit tests enforce layering (domain ← application ← adapters) and forbid cycles.
- Virtual threads for blocking I/O where it helps; no blocking calls on event-loop threads; bounded executors with metrics.
- Spring Security method-level authorisation on every endpoint, deny-by-default; a test enumerates all mappings and fails if any endpoint lacks an explicit rule.
- Flyway migrations are forward-only, backward-compatible with the previous app version (expand → migrate → contract), and tested against Testcontainers.
- Checkstyle (Google style), SpotBugs + FindSecBugs, Error Prone; JaCoCo coverage gate; PIT mutation testing on decision, rules and auth modules.

### H.3 Python (ML service and pipeline)

- Python 3.12, `uv` lockfile, `src/` layout, full type hints, `mypy --strict`, `ruff` (lint + format), no wildcard imports, no mutable globals.
- Pydantic models at every boundary (gRPC/HTTP payloads, configs); `pandera` (or equivalent) schemas validate datasets at load time.
- Feature functions are pure and deterministic given (raw transaction, history snapshot, clock); the same registry powers batch training and online serving to prevent skew.
- Models load once per process; no per-request file I/O; memory measured in tests.
- Notebooks are for analysis and figures only; any logic used by training or serving lives in the package and is tested. Notebooks run top-to-bottom in CI (`nbmake`) with outputs cleared on commit.
- `structlog` JSON logging with PII redaction processors; OpenTelemetry instrumentation.

### H.4 TypeScript / React

- `strict: true` plus `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`; no `any` (lint error), no non-null assertions without a comment.
- Feature-folder architecture; server state only in TanStack Query; local UI state in components; no global store unless an ADR justifies it.
- API types generated from the OpenAPI spec; Zod validation of responses at the boundary in development and tests.
- Components: accessible by construction (semantic elements, labelled controls, focus management), styled only through tokens, every component has Storybook stories for all states and a Vitest + Testing Library test.
- No `dangerouslySetInnerHTML` except through DOMPurify with an ADR; no inline event handlers that break CSP; no third-party runtime requests beyond those in the CSP allowlist.
- Performance: memoisation only where profiling shows benefit; list virtualisation; lazy routes; images and heavy libraries loaded on demand.

### H.5 ML engineering and research standards

- Every experiment is config-driven, seeded, and logged to MLflow with data hash, code SHA, environment lock and hardware; any figure or number in docs can be traced to a run ID.
- Leakage defences are code, not intentions: split functions assert temporal ordering and embargo; label-derived features assert `label_available_at < event_time`; a test deliberately injects a leak and asserts the guard catches it.
- Metric implementations are verified against reference implementations (scikit-learn) on fixed fixtures, including edge cases (single class, ties); ECE, Recall@FPR and PSI have hand-computed unit tests.
- Report uncertainty (CIs, seeds) with every headline number; prefer effect sizes and tests over single point estimates.
- Model artifacts are versioned, signed with checksums, and loadable by exactly one code path shared by evaluation and serving.

---

## PART I — PRINCIPAL REVIEW PROTOCOL (INDEPENDENT, BLOCKING)

### I.1 Reviewer mindset and rules

1. **Switch context deliberately.** Before reviewing, write `REVIEW MODE: <scope>` in your working notes and review from the diff, the requirement text and the tests — not from your memory of what you intended.
2. **Assume it is wrong.** Actively look for the failure: the untested branch, the boundary value, the race, the missing authorisation check, the leak, the mis-computed metric.
3. **Re-run, don't re-read.** Every claim in the traceability matrix or PR description is re-executed by the reviewer. A number that cannot be reproduced from a command is a BLOCKER.
4. **Prove tests have teeth.** For each critical requirement in scope, temporarily break the implementation (flip a comparison, remove a check, change a threshold) and confirm at least one tagged test fails; then restore. Record these "mutation spot checks".
5. **Severity levels:** `BLOCKER` (incorrect behaviour, security/privacy flaw, data loss risk, fabricated or unreproducible evidence, acceptance criterion unmet while marked DONE, leakage) · `MAJOR` (missing tests for important paths, standards violation with real maintenance or performance cost, accessibility failure, missing audit/observability) · `MINOR` (clarity, naming, small duplication) · `NIT` (style preference).
6. **Verdicts:** `APPROVED`, `APPROVED_WITH_MINORS` (minors tracked as issues), `CHANGES_REQUIRED`. BLOCKER and MAJOR findings must be fixed and re-reviewed before merge. A reviewer may not approve their own unresolved finding by reclassifying it without a written justification.
7. **Record every review** in `docs/reviews/<milestone>/<branch>.md` using I.5, committed with the change.

### I.2 Change-level review checklist (every branch)

**Correctness and requirements**
- [ ] Each referenced requirement's acceptance criterion is fully implemented, including boundary values and error codes; D-xx resolutions are applied.
- [ ] Tests encode the acceptance criterion (not just the happy path) and are tagged; mutation spot checks pass.
- [ ] Edge cases: empty/zero history, nulls (USSD fingerprint), maximum sizes, duplicates, concurrency, clock boundaries, time zones, currency minor units.

**Architecture and code quality**
- [ ] Respects module boundaries (ArchUnit/import-linter green); no domain logic in controllers, adapters or React components.
- [ ] Part H standards met for the language(s) touched; no dead code, commented-out code, or placeholder logic.
- [ ] Public contracts (OpenAPI, proto, Kafka schemas) updated and backward-compatible, with breaking-change checks green.

**Security and privacy**
- [ ] Authorisation explicit and tested for every new endpoint and role; API-key scopes enforced.
- [ ] Input validation at the boundary; parameterised queries only; output encoding.
- [ ] No PII in logs, responses, Kafka, Redis, fixtures or screenshots; secrets only from configuration.
- [ ] Every state-changing operation emits the correct audit event type and action.

**Reliability and performance**
- [ ] Timeouts, retries, idempotency and degraded behaviour defined and tested.
- [ ] Hot-path changes benchmarked; results compared with the previous baseline; no regression > 5% without ADR.
- [ ] Metrics, structured logs and trace spans present for new flows.

**Data and ML (when applicable)**
- [ ] No leakage: splits, embargo, label timing, target encodings, calibration split separation verified by code inspection and guard tests.
- [ ] Metric code verified against references; numbers reproduced from the MLflow run ID; CIs present.
- [ ] Training–serving parity test green; model artifact and feature registry versions match.
- [ ] Test set access counter unchanged unless this is a declared final evaluation.

**Front-end and UX (when applicable)**
- [ ] Reviewer runs the app and inspects the change at 320, 375, 768, 1024 and 1440 px, light and dark theme, keyboard only, and with `prefers-reduced-motion`.
- [ ] Axe: zero critical/serious; contrast tokens used; text labels accompany colour; focus order logical; touch targets ≥ 48 px.
- [ ] All states present (loading, empty, error, offline/stale, permission denied, degraded); strings externalised in all four locales.
- [ ] Bundle-size and render-time budgets still met.

**Docs and traceability**
- [ ] Traceability rows updated with real evidence (commands, artifact paths, and — after merge — commit SHA).
- [ ] ADRs written for any deviation; module READMEs and runbooks updated.

### I.3 Milestone-level review (before tagging `m<N>-complete`)

1. Check out `main` at the merge commit into a **clean worktree** (`git worktree add ../fs-review-mN main`), run the full milestone gate from scratch with no caches, and compare results with the numbers claimed.
2. Walk every requirement in the milestone's scope in the traceability matrix; open the test for each and confirm it asserts the criterion.
3. Cross-component review: contracts between Java, Python, Kafka and front-end still agree (contract tests green; schemas identical to generated code).
4. Threat-model delta: update `docs/security/threat_model.md` for new components or data flows.
5. Architecture fitness: latency budget table (C.2) re-measured if the hot path changed; dependency graph and module boundaries unchanged or ADR-documented.
6. Produce `docs/reviews/M<N>/milestone-review.md` with verdict. The milestone does not close until the verdict is `APPROVED` or `APPROVED_WITH_MINORS`.

### I.4 Final fresh-clone system review (M12)

1. Clone the repository from GitHub into a new directory (proves everything was pushed).
2. Follow only the README: `make up`, seeded logins for all four roles, `make demo-stream`, `make verify-all`, `make reproduce` (at the scale the machine allows, stated explicitly).
3. Execute the five SRS Playwright journeys manually once as a human would, noting any friction, and automatically across three browsers.
4. Red-team pass: attempt privilege escalation between roles, API key misuse on staff endpoints, audit log tampering (then run `fraudshield audit verify`), idempotency bypass, SMS verification abuse under simulated recent SIM swap, XSS in comments and rule names, oversized payloads, and replay of stale offline decisions.
5. ML scrutiny: re-derive three headline metrics directly from the saved predictions file with an independent short script; confirm they match `metrics.json`; confirm single-feature AUC ≤ 0.80 and the novelty-variant results; confirm no test-set access outside final evaluation.
6. Evidence audit: randomly sample 20 `DONE` traceability rows and reproduce their evidence end to end; any failure reopens the row and the review.
7. Write `docs/reviews/FINAL_SYSTEM_REVIEW.md` with verdict, residual risks, and a list of what a PhD committee member or bank CISO would most likely challenge, each with the evidence that answers it or an honest statement that it remains open.

### I.5 Review record template

```
# Review: <scope>            Reviewer role: Principal Reviewer
Date: <UTC>   Branch/commit: <sha>   Requirements: <IDs>   Defects: <D-xx>
Verdict: APPROVED | APPROVED_WITH_MINORS | CHANGES_REQUIRED

## Checks re-run (command → result)
## Mutation spot checks (what was broken → which test failed)
## Findings
| # | Severity | Location | Finding | Required action | Status |
## Evidence reproduced (claimed vs measured)
## Residual risks
```

---

## PART J — AUTHOR UNDERSTANDING HANDOVER

The repository owner will present and defend this work to faculty and in interviews, so the codebase must be explainable by him, not only runnable.

1. After each milestone, write `docs/walkthrough/M<N>.md` (≤ 2 pages): what was built, the key design decisions and their alternatives, where to look in the code (file paths), how to run and demonstrate it, and what the measured results mean.
2. Maintain `docs/walkthrough/defence_questions.md`: the hardest questions a reviewer could ask about that milestone (e.g., "Why is SHAP additivity checked in margin space and not probability space?", "Why can't precision at 1% FPR reach 0.72?", "How do you know the dataset isn't leaking labels?", "What happens if Kafka dies mid-block?"), each with a concise, correct answer pointing to code, tests or ADRs.
3. Add a 10-minute live demo script in `docs/walkthrough/demo_script.md` updated as features land.

**Begin now with G.1, then M0. Work continuously through the milestones. At the end of each milestone, print a short status block: milestone, gate result (pass/fail with numbers), review verdict and finding counts by severity, requirements moved to DONE, new ADRs, commits and tag pushed (with SHAs), and the next step.**
