# v5 coverage — every v5 requirement against the traceability register

**Nothing here is adopted.** This is a reading of SRS v5 against the register as it stands on
`m8/frontend` at 4f6828e. No requirement, schema or code was changed to match v5. The companion
documents are `docs/srs/v5_delta.md` (what differs) and the review record for M8.

**How to read the statuses.** They are the register's own values, and they describe *this tree*.
M5, M6 and M7 are being built on unmerged branches, so a row reading `NOT_STARTED` here may be
in progress elsewhere; `docs/parallel/` carries each branch's own status.

Read-only analysis. Nothing in the repository was modified.

## 0. Provenance

| Item | Value |
|---|---|
| Worktree | `<repository root>` |
| Branch / HEAD | `m8/frontend` @ `4f6828ee71914714ca466e9cbdc9879811e24b0a` |
| New SRS | `docs/srs/FraudShield_SRS_v5_0.md` (960 lines) — **untracked in git** (`git status` = `?? docs/srs/FraudShield_SRS_v5_0.md`), so it is not part of any commit and not yet a source of truth for the register |
| Old SRS | `docs/srs/FraudShield_SRS_v1_0.md` (600 lines) |
| Register | `docs/traceability/requirements.yaml` (4363 lines, 258 rows) |
| Generated matrix | `docs/traceability/requirements_matrix.md` (267 lines, header states `Rows: 258`) |
| Milestone state | `docs/traceability/milestones.yaml`:5 — `completed: [M0, M1, M2, M3, M4]`, `current: M8` |
| Milestone definitions | `docs/prompts/FraudShield_Master_Build_Prompt.md`:342-389 (D.3, M0…M12) |

Caveats that bound everything below:

- The v5 document's own cover block and section-00 table say **v4.0** and list only sections 00–16 (`FraudShield_SRS_v5_0.md`:3, :23-40); sections 17–23 and the FR-08 table exist in the file but are **not** in that contents table (`:740`, `:878`, `:908`). The closing colophon does say `Complete SRS v5.0` (`:957`). Treat the version metadata as internally inconsistent — **unverified** which label is intended.
- The register was seeded from **v1** (its `section:` fields read e.g. `3.1 FR-01 …`, v1's numbering; v5 renumbers the same table to `4.1`). No register row references v5.
- This worktree is `m8/frontend`. Work on other unmerged branches (e.g. `m7/staff-auth`) is **not** reflected in these statuses. FR-07-* reading `NOT_STARTED` here is a statement about this tree only.

## 1. Requirement extraction from v5

### 1.1 Functional requirements

v5 numbers its FRs explicitly. **68 distinct IDs**, all with the form `FR-NN-NN`:

- `FR-01-01…07` (7) — Transaction Ingestion API, v5 §4.1 (`:118-128`)
- `FR-02-01…10` (10) — ML Fraud Scoring Engine, v5 §4.2 (`:130-143`)
- `FR-03-01…08` (8) — Risk Decision and Auto-Block Engine, v5 §4.3 (`:145-156`)
- `FR-04-01…12` (12) — Analyst Dashboard, v5 §4.4 (`:158-173`)
- `FR-05-01…07` (7) — Risk Officer Panel, v5 §4.5 (`:175-185`)
- `FR-06-01…07` (7) — Admin Panel, v5 §4.6 (`:187-197`)
- `FR-07-01…09` (9) — Authentication and Authorisation, v5 §4.7 (`:199-211`)
- `FR-08-01…08` (8) — **Reporting**, v5 §20.5 (`:878-889`) — new in v5; absent from v1

v1 contains exactly the first 60 (`FR-01-01`…`FR-07-09`). The FR delta v1→v5 is therefore **FR-08-01…FR-08-08 only**.

### 1.2 Non-functional requirements

**v5 does not number its section-05 rows** — §5.1/5.2/5.3 are unlabelled tables keyed by `Metric` / `Requirement` / `Failure Scenario` (`:216`, `:231`, `:246`). 
I therefore use the **register's own existing labels**, which already cover these rows 1:1 in document order: `NFR-PERF-01…10`, `NFR-SEC-01…10`, `NFR-REL-01…06` (26 rows). 
I verified the alignment row by row: all 26 register titles match the v5 first-column text, with three immaterial wording differences (`ML scoring latency`/`ML ensemble scoring latency`, `Analyst dashboard WebSocket update`/`Analyst dashboard real-time update`, `Google OAuth unavailable`/`Google OAuth service unavailable`). Same subject, same order, same count.

**Total v5 requirements in scope for this analysis: 68 FR + 26 NFR = 94.**

> Out of scope by the task's framing, but present in v5 and in the register: UX-REG/UX-DASH (20), ML-DATA/ML-GATE (21), OPS-CI/OPS-OBS (14), TEST (14), RES (7), MOB-* (46) and the 51 `D-xx` defect rows. Counts for those are in §5.

## 2. Per-requirement status

Status values are the register's own (`NOT_STARTED`, `IN_PROGRESS`, `DONE`, `DONE_WITH_DEVIATION`, `VERIFIED_AT_REDUCED_SCALE`, `REQUIRES_EXTERNAL_PARTY`, `BLOCKED` — defined at `FraudShield_Master_Build_Prompt.md`:336). 
`Register row id` is the register's `id:` key. Citations are `path:line`, relative to the worktree root; the two files are `docs/traceability/requirements.yaml` and `docs/traceability/requirements_matrix.md`.

| v5 ID | v5 line | Requirement (one line) | Pri | Register row id | Milestone | Register status | Cite (yaml:line ; matrix:line) |
|---|---|---|---|---|---|---|---|
| FR-01-01 | 122 | POST /api/v1/transactions/ingest accepts payload; validates schema; publishes to Kafka within 5ms | M | FR-01-01 | M6 | NOT_STARTED | requirements.yaml:17; requirements_matrix.md:10 |
| FR-01-02 | 123 | Transaction schema: transaction_id (UUID), account_id (tokenised), counterparty_id (tokenised), amount (DECIMAL), currency (ISO 4217), channel ENUM(6… | M | FR-01-02 | M6 | NOT_STARTED | requirements.yaml:35; requirements_matrix.md:11 |
| FR-01-03 | 124 | Idempotent ingestion: duplicate transaction_id within 24h returns 200 with cached result — not reprocessed | M | FR-01-03 | M6 | NOT_STARTED | requirements.yaml:50; requirements_matrix.md:12 |
| FR-01-04 | 125 | All 6 East African channels handled as first-class types: MOBILE_MONEY, CARD, AGENT_BANKING, USSD, ONLINE, BANK_TRANSFER | M | FR-01-04 | M6 | NOT_STARTED | requirements.yaml:64; requirements_matrix.md:13 |
| FR-01-05 | 126 | API key authentication for core banking (machine-to-machine); JWT for human users; keys scoped to ingestion endpoints only | M | FR-01-05 | M6 | NOT_STARTED | requirements.yaml:82; requirements_matrix.md:14 |
| FR-01-06 | 127 | Batch ingestion POST /api/v1/transactions/ingest/batch accepts up to 1,000 transactions; returns 202 with job_id; results queryable via GET /api/v1/j… | S | FR-01-06 | M6 | NOT_STARTED | requirements.yaml:96; requirements_matrix.md:15 |
| FR-01-07 | 128 | OpenAPI 3.1 spec served at /api/docs with request/response examples for all 6 channel types | M | FR-01-07 | M1 | DONE | requirements.yaml:112; requirements_matrix.md:16 |
| FR-02-01 | 134 | Every ScoringResult: ensemble_score (0.0–1.0), risk_tier (HIGH/MEDIUM/LOW), shap_top5 (JSONB, nullable for LOW), model_version, requires_analyst_revi… | M | FR-02-01 | M5 | NOT_STARTED | requirements.yaml:137; requirements_matrix.md:17 |
| FR-02-02 | 135 | 44 features engineered per transaction within < 10ms: velocity (tx_count_60s/1h/24h, amount_sum_24h), geographic (lat/lon distance, EAC corridor flag… | M | FR-02-02 | M3 | DONE_WITH_DEVIATION | requirements.yaml:154; requirements_matrix.md:18 |
| FR-02-03 | 136 | XGBoost (weight 0.55) + LightGBM (weight 0.45) ensemble; isotonic regression probability calibration; ECE < 0.05 | M | FR-02-03 | M4 | DONE_WITH_DEVIATION | requirements.yaml:190; requirements_matrix.md:19 |
| FR-02-04 | 137 | SHAP TreeExplainer (exact — no sampling) computes top-5 SHAP features for all transactions with ensemble_score >= 0.60 | M | FR-02-04 | M4 | DONE | requirements.yaml:223; requirements_matrix.md:20 |
| FR-02-05 | 138 | Isolation Forest anomaly score independent of supervised model; anomaly_score > 0.7 sets requires_analyst_review = true regardless of ensemble_score | M | FR-02-05 | M5 | NOT_STARTED | requirements.yaml:245; requirements_matrix.md:21 |
| FR-02-06 | 139 | Risk thresholds configurable at runtime via Redis config without model reload; change takes effect within 60 seconds | M | FR-02-06 | M5 | NOT_STARTED | requirements.yaml:259; requirements_matrix.md:22 |
| FR-02-07 | 140 | ML scoring latency: p50 < 15ms, p95 < 25ms, p99 < 40ms (ensemble + SHAP for flagged transactions combined) | M | FR-02-07 | M5 | NOT_STARTED | requirements.yaml:278; requirements_matrix.md:23 |
| FR-02-08 | 141 | Shadow mode: new model scores every transaction alongside production; only production result triggers actions; comparison logged to MLflow | M | FR-02-08 | M5 | NOT_STARTED | requirements.yaml:295; requirements_matrix.md:24 |
| FR-02-09 | 142 | Redis feature store updates velocity features within 100ms of each transaction; DB row upserted every 5 minutes for durability | M | FR-02-09 | M5 | NOT_STARTED | requirements.yaml:309; requirements_matrix.md:25 |
| FR-02-10 | 143 | Every ScoringResult records model_version string matching a row in model_versions table | M | FR-02-10 | M5 | NOT_STARTED | requirements.yaml:325; requirements_matrix.md:26 |
| FR-03-01 | 149 | HIGH (ensemble_score >= 0.85): auto-blocked within 50ms; account flagged; customer SMS dispatched asynchronously via Africa's Talking | M | FR-03-01 | M6 | NOT_STARTED | requirements.yaml:340; requirements_matrix.md:27 |
| FR-03-02 | 150 | MEDIUM (0.60–0.84): transaction held; 30-second countdown; auto-released with TIMEOUT label if no analyst action | S | FR-03-02 | M6 | NOT_STARTED | requirements.yaml:359; requirements_matrix.md:28 |
| FR-03-03 | 151 | LOW (< 0.60): approved within total pipeline latency budget; no analyst involvement | M | FR-03-03 | M6 | NOT_STARTED | requirements.yaml:372; requirements_matrix.md:29 |
| FR-03-04 | 152 | Customer SMS: masked account number, transaction amount, currency, timestamp, 10-minute HTTPS verification link | M | FR-03-04 | M6 | NOT_STARTED | requirements.yaml:390; requirements_matrix.md:30 |
| FR-03-05 | 153 | Customer verification via SMS link: block lifted within 10s; false_positive_confirmed = true; feature vector queued for ML retraining | M | FR-03-05 | M6 | NOT_STARTED | requirements.yaml:405; requirements_matrix.md:31 |
| FR-03-06 | 154 | Account freeze: 3+ HIGH-risk transactions from same account within 1 hour triggers freeze + risk officer notification | S | FR-03-06 | M6 | NOT_STARTED | requirements.yaml:419; requirements_matrix.md:32 |
| FR-03-07 | 155 | MCC circuit breaker: fraud rate > 5% from specific MCC in 15-minute window flags all transactions to that MCC for review | S | FR-03-07 | M6 | NOT_STARTED | requirements.yaml:434; requirements_matrix.md:33 |
| FR-03-08 | 156 | All auto-block decisions append-only; DB INSERT-only permission on auto_block_events; no deletions ever | M | FR-03-08 | M6 | NOT_STARTED | requirements.yaml:452; requirements_matrix.md:34 |
| FR-04-01 | 162 | Analyst authenticates via email+password OR Google OAuth 2.0; JWT issued; dashboard accessible on all devices 320px to 1536px | M | FR-04-01 | M8 | NOT_STARTED | requirements.yaml:466; requirements_matrix.md:35 |
| FR-04-02 | 163 | Real-time alert feed via WebSocket STOMP; sorted fraud_probability descending; HIGH pinned at top; MEDIUM shows countdown timer | M | FR-04-02 | M8 | NOT_STARTED | requirements.yaml:482; requirements_matrix.md:36 |
| FR-04-03 | 164 | Alert card shows: risk tier badge, ensemble_score gauge (MUI CircularProgress), amount + currency, channel MUI Chip, masked account token, merchant, … | M | FR-04-03 | M8 | NOT_STARTED | requirements.yaml:496; requirements_matrix.md:37 |
| FR-04-04 | 165 | One-click CONFIRM FRAUD or MARK LEGITIMATE with mandatory comment field (min 10 characters); both complete within 2 seconds | M | FR-04-04 | M8 | NOT_STARTED | requirements.yaml:511; requirements_matrix.md:38 |
| FR-04-05 | 166 | SHAP waterfall chart: all features sorted by \ | SHAP\ | FR-04-05 | M8 | NOT_STARTED | requirements.yaml:526; requirements_matrix.md:39 |
| FR-04-06 | 167 | Account history timeline: last 30 transactions with amount, channel icon, timestamp, risk score MUI Chip, outcome badge | M | FR-04-06 | M8 | NOT_STARTED | requirements.yaml:540; requirements_matrix.md:40 |
| FR-04-07 | 168 | Behavioural fingerprint: hourly heatmap, amount histogram, channel pie chart, 'This transaction vs normal' comparison table | S | FR-04-07 | M8 | NOT_STARTED | requirements.yaml:554; requirements_matrix.md:41 |
| FR-04-08 | 169 | Transaction network graph: flagged account + counterparty + 2nd-degree connections; known fraud nodes highlighted red | C | FR-04-08 | M8 | NOT_STARTED | requirements.yaml:568; requirements_matrix.md:42 |
| FR-04-09 | 170 | Filter and search: by transaction_id, account_id, amount range, channel, date range, risk tier, analyst, outcome | M | FR-04-09 | M8 | NOT_STARTED | requirements.yaml:582; requirements_matrix.md:43 |
| FR-04-10 | 171 | Escalation: ANALYST escalates to SENIOR_ANALYST or RISK_OFFICER with mandatory reason | M | FR-04-10 | M8 | NOT_STARTED | requirements.yaml:596; requirements_matrix.md:44 |
| FR-04-11 | 172 | Analyst performance panel: alerts reviewed today, average review time, accuracy rate from customer verifications, backlog size | S | FR-04-11 | M8 | NOT_STARTED | requirements.yaml:610; requirements_matrix.md:45 |
| FR-04-12 | 173 | WCAG 2.1 AA: risk tier conveyed by text label not colour alone; all controls keyboard-navigable | S | FR-04-12 | M8 | NOT_STARTED | requirements.yaml:624; requirements_matrix.md:46 |
| FR-05-01 | 179 | Risk Officer sees all analyst alerts plus escalated alerts; can senior-override any analyst decision with mandatory reason | M | FR-05-01 | M8 | NOT_STARTED | requirements.yaml:638; requirements_matrix.md:47 |
| FR-05-02 | 180 | Portfolio risk dashboard: fraud prevented (RWF amount + count) today/week/month; fraud rate by channel; by MCC; geographic heatmap (Leaflet.js); top-… | M | FR-05-02 | M8 | NOT_STARTED | requirements.yaml:653; requirements_matrix.md:48 |
| FR-05-03 | 181 | Model performance monitoring: live AUC-ROC from analyst labels; precision, recall, F1 trends; drift indicator vs baseline; per-channel AUC-ROC | M | FR-05-03 | M8 | NOT_STARTED | requirements.yaml:667; requirements_matrix.md:49 |
| FR-05-04 | 182 | Fraud campaign detection panel: clusters by shared DEVICE, COUNTERPARTY, GEOGRAPHIC, or MCC feature; campaign list; transaction count and total amoun… | S | FR-05-04 | M8 | NOT_STARTED | requirements.yaml:681; requirements_matrix.md:50 |
| FR-05-05 | 183 | Custom rule management: create alert rules via structured form (no code); enable/disable; rule DSL validated on save | S | FR-05-05 | M8 | NOT_STARTED | requirements.yaml:695; requirements_matrix.md:51 |
| FR-05-06 | 184 | SAR auto-draft from CONFIRM_FRAUD decision in Rwanda BNR format; edit draft; export PDF | S | FR-05-06 | M8 | NOT_STARTED | requirements.yaml:711; requirements_matrix.md:52 |
| FR-05-07 | 185 | Per-channel threshold management: different HIGH/MEDIUM/LOW thresholds for each of 6 channels | M | FR-05-07 | M8 | NOT_STARTED | requirements.yaml:725; requirements_matrix.md:53 |
| FR-06-01 | 191 | Create staff accounts: first name, last name, email, phone (country code flag + dial code + local number stored as E.164), employee ID, department, r… | M | FR-06-01 | M7 | NOT_STARTED | requirements.yaml:743; requirements_matrix.md:54 |
| FR-06-02 | 192 | View, update, and deactivate any account; deactivated accounts blocked from all auth methods (password + Google OAuth) within 5 seconds | M | FR-06-02 | M7 | NOT_STARTED | requirements.yaml:758; requirements_matrix.md:55 |
| FR-06-03 | 193 | Model management: list all model versions with training date, dataset size, AUC-ROC, precision, recall, F1; promote to production (gate enforced); ro… | M | FR-06-03 | M8 | NOT_STARTED | requirements.yaml:773; requirements_matrix.md:56 |
| FR-06-04 | 194 | Dataset upload: upload labelled CSV; trigger retraining; monitor real-time progress via WebSocket; new model auto-evaluated before promotion option a… | S | FR-06-04 | M8 | NOT_STARTED | requirements.yaml:787; requirements_matrix.md:57 |
| FR-06-05 | 195 | System health panel: Kafka consumer lag per topic, ML scoring p50/p95/p99, API error rate, Redis hit rate, DB pool, alert queue depth, auto-block rate | M | FR-06-05 | M9 | NOT_STARTED | requirements.yaml:801; requirements_matrix.md:58 |
| FR-06-06 | 196 | Full audit log: every state-changing operation with user first name + last name, role, timestamp, event type, entity type, entity ID; searchable; imm… | M | FR-06-06 | M7 | NOT_STARTED | requirements.yaml:816; requirements_matrix.md:59 |
| FR-06-07 | 197 | API key management: create, view (name + last-4 chars only), revoke; each key scoped to endpoint groups; raw key shown once at creation | M | FR-06-07 | M7 | NOT_STARTED | requirements.yaml:832; requirements_matrix.md:60 |
| FR-07-01 | 203 | Four roles enforced at API method level: ANALYST, SENIOR_ANALYST, RISK_OFFICER, ADMIN; role embedded in JWT claims; cannot self-elevate | M | FR-07-01 | M7 | NOT_STARTED | requirements.yaml:847; requirements_matrix.md:61 |
| FR-07-02 | 204 | Email + password registration: first name, last name, country-code phone (flag + dial code selector 249 countries), employee ID, department, password… | M | FR-07-02 | M7 | NOT_STARTED | requirements.yaml:863; requirements_matrix.md:62 |
| FR-07-03 | 205 | Google OAuth 2.0 sign-in on login and registration; Google ID token verified server-side against JWKS; account linked by email | M | FR-07-03 | M7 | NOT_STARTED | requirements.yaml:878; requirements_matrix.md:63 |
| FR-07-04 | 206 | JWT access tokens: 15-minute expiry, RS256, containing user_id, role, first_name, email; refresh tokens: 7-day expiry in httpOnly SameSite=Strict coo… | M | FR-07-04 | M7 | NOT_STARTED | requirements.yaml:893; requirements_matrix.md:64 |
| FR-07-05 | 207 | Machine-to-machine API keys for transaction ingestion scoped to ingestion endpoints only; cannot access analyst, admin, or ML endpoints | M | FR-07-05 | M7 | NOT_STARTED | requirements.yaml:907; requirements_matrix.md:65 |
| FR-07-06 | 208 | Login rate limited: 10 attempts per 15 minutes per IP; 5 failed attempts locks account; unlock email sent | M | FR-07-06 | M7 | NOT_STARTED | requirements.yaml:922; requirements_matrix.md:66 |
| FR-07-07 | 209 | BCrypt cost factor 12; minimum 8 chars, 1 uppercase, 1 lowercase, 1 digit, 1 special character | M | FR-07-07 | M7 | NOT_STARTED | requirements.yaml:936; requirements_matrix.md:67 |
| FR-07-08 | 210 | Password reset via email OTP: 6 digits, 10-minute expiry, single-use | S | FR-07-08 | M7 | NOT_STARTED | requirements.yaml:951; requirements_matrix.md:68 |
| FR-07-09 | 211 | All sessions invalidated on password change; Google OAuth session revoked via Google revocation API on logout | M | FR-07-09 | M7 | NOT_STARTED | requirements.yaml:966; requirements_matrix.md:69 |
| FR-08-01 | 882 | Daily report auto-generated at 06:00 Rwanda time; covers previous calendar day; delivered by email to all RISK_OFFICER and ADMIN users | M | — (**not in register**) | — | not in register | — |
| FR-08-02 | 883 | Weekly report auto-generated every Monday at 07:00 Rwanda time; covers previous Mon-Sun; delivered by email as PDF | M | — (**not in register**) | — | not in register | — |
| FR-08-03 | 884 | Monthly report auto-generated on 1st of month at 08:00 Rwanda time; BNR-formatted; all 9 sections present including SAR summary table | M | — (**not in register**) | — | not in register | — |
| FR-08-04 | 885 | Admin Panel 'Reports' tab: list all reports by date; download any report; trigger custom date range report | M | — (**not in register**) | — | not in register | — |
| FR-08-05 | 886 | Custom date range report: any date range up to 90 days; triggered from Admin Panel; results in same format as daily report but spanning the custom ra… | S | — (**not in register**) | — | not in register | — |
| FR-08-06 | 887 | All reports stored in S3-compatible object storage with correct retention periods: daily 90 days, weekly 1 year, monthly 7 years | M | — (**not in register**) | — | not in register | — |
| FR-08-07 | 888 | Report generation API: GET /api/v1/reports/{type}?from=&to= returns JSON; requires RISK_OFFICER+ JWT; supports daily, weekly, monthly, custom | S | — (**not in register**) | — | not in register | — |
| FR-08-08 | 889 | Grafana dashboard 'Daily/Weekly/Monthly Report' auto-refreshes and shows current period data; accessible at https://grafana.fraudshield.rw | S | — (**not in register**) | — | not in register | — |
| NFR-PERF-01 | 218 | Transaction ingestion throughput — 10,000+ TPS sustained 5 minutes | M | NFR-PERF-01 | M10 | NOT_STARTED | requirements.yaml:979; requirements_matrix.md:70 |
| NFR-PERF-02 | 219 | Auto-block end-to-end latency — p50 < 30ms \ | M | NFR-PERF-02 | M10 | NOT_STARTED | requirements.yaml:995; requirements_matrix.md:71 |
| NFR-PERF-03 | 220 | ML scoring latency — p50 < 15ms \ | M | NFR-PERF-03 | M10 | NOT_STARTED | requirements.yaml:1010; requirements_matrix.md:72 |
| NFR-PERF-04 | 221 | Feature engineering latency — p95 < 10ms including Redis feature store fetch | M | NFR-PERF-04 | M10 | NOT_STARTED | requirements.yaml:1025; requirements_matrix.md:73 |
| NFR-PERF-05 | 222 | Analyst dashboard WebSocket update — < 1 second from Kafka event to browser DOM update | M | NFR-PERF-05 | M10 | NOT_STARTED | requirements.yaml:1040; requirements_matrix.md:74 |
| NFR-PERF-06 | 223 | SHAP waterfall chart render — < 500ms from panel open to chart interactive | M | NFR-PERF-06 | M10 | NOT_STARTED | requirements.yaml:1055; requirements_matrix.md:75 |
| NFR-PERF-07 | 224 | Alert feed initial load (500 alerts) — < 1 second with MUI DataGrid virtualisation | M | NFR-PERF-07 | M10 | NOT_STARTED | requirements.yaml:1070; requirements_matrix.md:76 |
| NFR-PERF-08 | 225 | Google OAuth login flow — < 3 seconds from button click to authenticated dashboard | M | NFR-PERF-08 | M10 | NOT_STARTED | requirements.yaml:1085; requirements_matrix.md:77 |
| NFR-PERF-09 | 226 | System availability — 99.9% monthly (< 9 hours downtime/year) | M | NFR-PERF-09 | M10 | NOT_STARTED | requirements.yaml:1100; requirements_matrix.md:78 |
| NFR-PERF-10 | 227 | API ingestion error rate — < 0.1% at 10,000 TPS | M | NFR-PERF-10 | M10 | NOT_STARTED | requirements.yaml:1113; requirements_matrix.md:79 |
| NFR-SEC-01 | 233 | Encryption at rest — PostgreSQL TDE + AES-256 application-layer for PII fields; Redis encryption at rest | M | NFR-SEC-01 | M9 | NOT_STARTED | requirements.yaml:1130; requirements_matrix.md:80 |
| NFR-SEC-02 | 234 | Encryption in transit — TLS 1.3 for all external; mTLS for Spring Boot → Python ML scorer service-to-service | M | NFR-SEC-02 | M9 | NOT_STARTED | requirements.yaml:1144; requirements_matrix.md:81 |
| NFR-SEC-03 | 235 | PII tokenisation — account_id and counterparty_id stored as opaque tokens — never raw; analysts see masked tokens only | M | NFR-SEC-03 | M7 | IN_PROGRESS | requirements.yaml:1158; requirements_matrix.md:82 |
| NFR-SEC-04 | 236 | No secrets in version control — All secrets in Kubernetes Secrets or environment variables; Gitleaks in every CI run | M | NFR-SEC-04 | M9 | NOT_STARTED | requirements.yaml:1178; requirements_matrix.md:83 |
| NFR-SEC-05 | 237 | Audit log immutability — audit_events: INSERT-only permission granted to application DB role; no UPDATE or DELETE ever | M | NFR-SEC-05 | M1 | DONE | requirements.yaml:1193; requirements_matrix.md:84 |
| NFR-SEC-06 | 238 | SQL injection prevention — Spring Data JPA parameterised queries throughout; Python SQLAlchemy ORM; zero raw SQL concatenation | M | NFR-SEC-06 | M6 | NOT_STARTED | requirements.yaml:1214; requirements_matrix.md:85 |
| NFR-SEC-07 | 239 | XSS prevention — React DOM escaping default; strict Content-Security-Policy header; DOMPurify on dynamic HTML | M | NFR-SEC-07 | M9 | NOT_STARTED | requirements.yaml:1228; requirements_matrix.md:86 |
| NFR-SEC-08 | 240 | API key security — Keys stored as BCrypt hash; raw key shown once at creation only; rotation creates new key | M | NFR-SEC-08 | M9 | NOT_STARTED | requirements.yaml:1243; requirements_matrix.md:87 |
| NFR-SEC-09 | 241 | Data residency — All data stored in Rwanda/EAC region; no export to non-EAC jurisdictions without BNR authorisation | M | NFR-SEC-09 | M9 | NOT_STARTED | requirements.yaml:1257; requirements_matrix.md:88 |
| NFR-SEC-10 | 242 | Annual penetration test — Third-party pen test; OWASP Top 10 + PCI DSS relevant controls | M | NFR-SEC-10 | M9 | NOT_STARTED | requirements.yaml:1271; requirements_matrix.md:89 |
| NFR-REL-01 | 248 | ML scoring service down — Rule-based fallback activates within 5 seconds; transactions continue with requires_analyst_review=true | M | NFR-REL-01 | M6 | NOT_STARTED | requirements.yaml:1285; requirements_matrix.md:90 |
| NFR-REL-02 | 249 | Kafka broker failure — Spring Boot producer retries with exponential backoff; up to 10,000 transactions buffered in memory | M | NFR-REL-02 | M6 | NOT_STARTED | requirements.yaml:1305; requirements_matrix.md:91 |
| NFR-REL-03 | 250 | Redis feature store down — Feature engineering uses DB fallback for velocity features; degraded performance logged | M | NFR-REL-03 | M6 | NOT_STARTED | requirements.yaml:1320; requirements_matrix.md:92 |
| NFR-REL-04 | 251 | PostgreSQL primary failure — TimescaleDB replica promoted within 30 seconds via Kubernetes operator | M | NFR-REL-04 | M6 | NOT_STARTED | requirements.yaml:1335; requirements_matrix.md:93 |
| NFR-REL-05 | 252 | Duplicate transaction storm — Idempotency key in Redis (24h TTL) ensures each transaction_id scored exactly once | M | NFR-REL-05 | M6 | NOT_STARTED | requirements.yaml:1349; requirements_matrix.md:94 |
| NFR-REL-06 | 253 | Google OAuth unavailable — Email+password auth continues; Google OAuth button shows 'Temporarily unavailable' | M | NFR-REL-06 | M6 | NOT_STARTED | requirements.yaml:1367; requirements_matrix.md:95 |

## 3. Counts per milestone (v5's 94 FR+NFR rows only)

"Done" = `DONE` + `DONE_WITH_DEVIATION` + `VERIFIED_AT_REDUCED_SCALE`. Milestone names are the build prompt's (D.3, `:342-389`).

| Milestone | Build-prompt name | v5 reqs mapped | DONE | DONE_WITH_DEVIATION | VERIFIED_AT_REDUCED_SCALE | IN_PROGRESS | NOT_STARTED | REQUIRES_EXTERNAL_PARTY | "Done" total |
|---|---|---|---|---|---|---|---|---|---|
| M1 | Contracts and data model | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 2 |
| M3 | Feature engineering + feature store | 1 | 0 | 1 | 0 | 0 | 0 | 0 | 1 |
| M4 | Models, calibration, explainability, evaluation | 2 | 1 | 1 | 0 | 0 | 0 | 0 | 2 |
| M5 | Scoring service | 7 | 0 | 0 | 0 | 0 | 7 | 0 | 0 |
| M6 | Ingestion and decision engine | 21 | 0 | 0 | 0 | 0 | 21 | 0 | 0 |
| M7 | Staff identity, authz, admin, audit | 14 | 0 | 0 | 0 | 1 | 13 | 0 | 0 |
| M8 | Front-end | 21 | 0 | 0 | 0 | 0 | 21 | 0 | 0 |
| M9 | Observability, infra, CI/CD, security | 8 | 0 | 0 | 0 | 0 | 8 | 0 | 0 |
| M10 | Verification campaign | 10 | 0 | 0 | 0 | 0 | 10 | 0 | 0 |
| — | (unassigned — not in register) | 8 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| **Total** | | **94** | **3** | **2** | **0** | **1** | **80** | **0** | **5** |

- v5 requirements extracted: **94** (68 FR + 26 NFR)
- Present in the register: **86**
- Not in the register: **8** (all of FR-08-01…FR-08-08)
- "Done" by the register's own values: **5** (3 DONE + 2 DONE_WITH_DEVIATION + 0 VERIFIED_AT_REDUCED_SCALE)
- **M12 has zero v5 requirement rows** — see §4.3.
## 4. What remains for M6, M10 and M12

These lists cover **every register row** filed under the milestone (not just the 94 FR/NFR rows), because that is what the milestone gate has to clear. Rows whose status is already in {`DONE`, `DONE_WITH_DEVIATION`, `VERIFIED_AT_REDUCED_SCALE`} are excluded and counted separately.

### 4.1  M6 — Ingestion and decision engine (Part E.6)

Milestone status: **not started as a gate** — `milestones.yaml`:5 lists `completed: [M0, M1, M2, M3, M4]` and `current: M8`, so M6 has not been gated.

Gate definition: `FraudShield_Master_Build_Prompt.md`:368-369 — *Gate:* FR-01-*, FR-03-* tests pass; idempotency test (100 identical submissions → 1 scored, 100 identical responses; 10,000 duplicate storm); end-to-end p95 decision latency < 50 ms at the largest achievable load; chaos tests for ML, Kafka, Redis, PostgreSQL failure pass.

Register rows filed under M6: **34** — 0 already done, **34 remaining**.

**D** (10)

- `D-10` — NOT_STARTED — Alert volume will overwhelm analysts; the 30-second MEDIUM timer then auto-releases most risky transactions.  <br>*(`docs/traceability/requirements.yaml`:3672)*
- `D-12` — NOT_STARTED — Model extraction and privacy risk: returning the full ScoringResult (feature_vector, per-model scores, SHAP) to the core-banking …  <br>*(`docs/traceability/requirements.yaml`:3704)*
- `D-13` — NOT_STARTED — The latency budget sums to 51 ms and puts PostgreSQL and Kafka round-trips in the synchronous path, but the p95 target is 50 ms.  <br>*(`docs/traceability/requirements.yaml`:3721)*
- `D-14` — NOT_STARTED — MEDIUM "hold for 30 seconds" cannot be an open HTTP request.  <br>*(`docs/traceability/requirements.yaml`:3735)*
- `D-15` — NOT_STARTED — Buffer up to 10,000 transactions in memory" loses data if the pod dies.  <br>*(`docs/traceability/requirements.yaml`:3749)*
- `D-17` — NOT_STARTED — pytest is listed for the Risk Decision Engine, which is a Spring Boot service.  <br>*(`docs/traceability/requirements.yaml`:3779)*
- `D-18` — NOT_STARTED — Exactly 30 s" and "exactly at 5%" need tolerances and minimum volume.  <br>*(`docs/traceability/requirements.yaml`:3797)*
- `D-20` — NOT_STARTED — PostgreSQL TDE" does not exist in community PostgreSQL 16.  <br>*(`docs/traceability/requirements.yaml`:3826)*
- `D-25` — NOT_STARTED — Customer verification by SMS link defeats itself in SIM-swap fraud,  <br>*(`docs/traceability/requirements.yaml`:3917)*
- `D-51` — NOT_STARTED — External services must work locally and in CI without real credentials.  <br>*(`docs/traceability/requirements.yaml`:4359)*

**FR-01** (6)

- `FR-01-01` — NOT_STARTED — POST /api/v1/transactions/ingest accepts transaction payload; validates schema; publishes to Kafka within 5ms  <br>*(`docs/traceability/requirements.yaml`:17)*
- `FR-01-02` — NOT_STARTED — Transaction schema: transaction_id (UUID), account_id (tokenised), counterparty_id (tokenised), amount (DECIMAL 18,4), currency (…  <br>*(`docs/traceability/requirements.yaml`:35)*
- `FR-01-03` — NOT_STARTED — Idempotent ingestion: duplicate transaction_id within 24 hours returns 200 with cached ScoringResult; not reprocessed'  <br>*(`docs/traceability/requirements.yaml`:50)*
- `FR-01-04` — NOT_STARTED — All 6 East African channels handled as first-class types with channel-specific feature engineering  <br>*(`docs/traceability/requirements.yaml`:64)*
- `FR-01-05` — NOT_STARTED — API key authentication for core banking system (machine-to-machine); JWT for human-facing endpoints; keys scoped to ingestion end…  <br>*(`docs/traceability/requirements.yaml`:82)*
- `FR-01-06` — NOT_STARTED — Batch ingestion POST /api/v1/transactions/ingest/batch accepts up to 1,000 transactions; returns 202 Accepted with job_id; result…  <br>*(`docs/traceability/requirements.yaml`:96)*

**FR-03** (8)

- `FR-03-01` — NOT_STARTED — HIGH risk (ensemble_score >= 0.85): auto-blocked within 50ms of transaction receipt; account flagged; customer SMS dispatched asy…  <br>*(`docs/traceability/requirements.yaml`:340)*
- `FR-03-02` — NOT_STARTED — MEDIUM risk (0.60-0.84): transaction held; published to analyst queue; 30-second countdown; auto-released with TIMEOUT label if n…  <br>*(`docs/traceability/requirements.yaml`:359)*
- `FR-03-03` — NOT_STARTED — LOW risk (< 0.60): approved and processed within total pipeline latency budget; no analyst involvement  <br>*(`docs/traceability/requirements.yaml`:372)*
- `FR-03-04` — NOT_STARTED — Customer SMS on auto-block: masked account number, transaction amount, currency, timestamp, 10-minute verification link'  <br>*(`docs/traceability/requirements.yaml`:390)*
- `FR-03-05` — NOT_STARTED — Customer verification: legitimate transaction verified via SMS link; block lifted within 10 seconds; false positive logged to ML …  <br>*(`docs/traceability/requirements.yaml`:405)*
- `FR-03-06` — NOT_STARTED — Account freeze logic: 3+ HIGH-risk transactions from same account within 1 hour triggers full account freeze + risk officer notif…  <br>*(`docs/traceability/requirements.yaml`:419)*
- `FR-03-07` — NOT_STARTED — MCC circuit breaker: fraud rate > 5% from specific MCC in 15-minute rolling window flags all transactions to that MCC for analyst…  <br>*(`docs/traceability/requirements.yaml`:434)*
- `FR-03-08` — NOT_STARTED — All auto-block decisions append-only in audit log; no decision deletable or modifiable; override creates new audit entry referenc…  <br>*(`docs/traceability/requirements.yaml`:452)*

**ML-DATA** (1)

- `ML-DATA-07` — NOT_STARTED — Feature completeness  <br>*(`docs/traceability/requirements.yaml`:2425)*

**NFR-REL** (6)

- `NFR-REL-01` — NOT_STARTED — ML scoring service down  <br>*(`docs/traceability/requirements.yaml`:1285)*
- `NFR-REL-02` — NOT_STARTED — Kafka broker failure  <br>*(`docs/traceability/requirements.yaml`:1305)*
- `NFR-REL-03` — NOT_STARTED — Redis feature store down  <br>*(`docs/traceability/requirements.yaml`:1320)*
- `NFR-REL-04` — NOT_STARTED — PostgreSQL primary failure  <br>*(`docs/traceability/requirements.yaml`:1335)*
- `NFR-REL-05` — NOT_STARTED — Duplicate transaction storm  <br>*(`docs/traceability/requirements.yaml`:1349)*
- `NFR-REL-06` — NOT_STARTED — Google OAuth service unavailable  <br>*(`docs/traceability/requirements.yaml`:1367)*

**NFR-SEC** (1)

- `NFR-SEC-06` — NOT_STARTED — SQL injection prevention  <br>*(`docs/traceability/requirements.yaml`:1214)*

**TEST** (2)

- `TEST-03` — NOT_STARTED — Unit: Risk Decision Engine  <br>*(`docs/traceability/requirements.yaml`:3158)*
- `TEST-05` — NOT_STARTED — Integration: Transaction Pipeline  <br>*(`docs/traceability/requirements.yaml`:3186)*

### 4.2  M10 — Verification campaign

Milestone status: **not started as a gate** — `milestones.yaml`:5 lists `completed: [M0, M1, M2, M3, M4]` and `current: M8`, so M10 has not been gated.

Gate definition: `FraudShield_Master_Build_Prompt.md`:381-383 — Distributed Locust at the largest achievable scale (target 10,000 TPS for 5 minutes); chaos suite; full E2E and device matrix; security suite; ML gate; produce `docs/benchmarks/verification_report.md`. *Gate:* every matrix row has a final status with evidence.

Register rows filed under M10: **18** — 0 already done, **18 remaining**.

**MOB-DEV** (7)

- `MOB-DEV-01` — NOT_STARTED — Tecno Spark Go / Itel A23 (320px, Android Go, Chrome Lite)  <br>*(`docs/traceability/requirements.yaml`:2206)*
- `MOB-DEV-02` — NOT_STARTED — iPhone SE 2022 (375px, Safari iOS)  <br>*(`docs/traceability/requirements.yaml`:2220)*
- `MOB-DEV-03` — NOT_STARTED — Samsung Galaxy A14 (412px, Chrome Android)  <br>*(`docs/traceability/requirements.yaml`:2234)*
- `MOB-DEV-04` — NOT_STARTED — iPad mini 6 (768px, Safari iPadOS)  <br>*(`docs/traceability/requirements.yaml`:2248)*
- `MOB-DEV-05` — NOT_STARTED — HP Pavilion / 13-inch laptop (1280px, Chrome)  <br>*(`docs/traceability/requirements.yaml`:2263)*
- `MOB-DEV-06` — NOT_STARTED — Desktop 1440px (Firefox)  <br>*(`docs/traceability/requirements.yaml`:2277)*
- `MOB-DEV-07` — NOT_STARTED — Opera Mini / KaiOS (feature phone)  <br>*(`docs/traceability/requirements.yaml`:2292)*

**NFR-PERF** (10)

- `NFR-PERF-01` — NOT_STARTED — Transaction ingestion throughput  <br>*(`docs/traceability/requirements.yaml`:979)*
- `NFR-PERF-02` — NOT_STARTED — Auto-block end-to-end latency  <br>*(`docs/traceability/requirements.yaml`:995)*
- `NFR-PERF-03` — NOT_STARTED — ML ensemble scoring latency  <br>*(`docs/traceability/requirements.yaml`:1010)*
- `NFR-PERF-04` — NOT_STARTED — Feature engineering latency  <br>*(`docs/traceability/requirements.yaml`:1025)*
- `NFR-PERF-05` — NOT_STARTED — Analyst dashboard real-time update  <br>*(`docs/traceability/requirements.yaml`:1040)*
- `NFR-PERF-06` — NOT_STARTED — SHAP waterfall chart render  <br>*(`docs/traceability/requirements.yaml`:1055)*
- `NFR-PERF-07` — NOT_STARTED — Alert feed initial load  <br>*(`docs/traceability/requirements.yaml`:1070)*
- `NFR-PERF-08` — NOT_STARTED — Google OAuth login flow  <br>*(`docs/traceability/requirements.yaml`:1085)*
- `NFR-PERF-09` — NOT_STARTED — System availability  <br>*(`docs/traceability/requirements.yaml`:1100)*
- `NFR-PERF-10` — NOT_STARTED — API ingestion error rate  <br>*(`docs/traceability/requirements.yaml`:1113)*

**TEST** (1)

- `TEST-09` — NOT_STARTED — Performance: Load  <br>*(`docs/traceability/requirements.yaml`:3246)*

### 4.3  M12 — Final audit and handover report

**M12 exists in the build prompt but has ZERO rows in the register.**

- Build prompt: `docs/prompts/FraudShield_Master_Build_Prompt.md`:388-389 — "**M12 — Final audit and handover report (Parts F and I.4)** / Full-system Principal Review from a fresh clone; final report; `v1.0.0` release tag pushed." Also referenced at `:765` ("I.4 Final fresh-clone system review (M12)").
- Register: `grep -c 'milestone: M12' docs/traceability/requirements.yaml` = **0**. The register's milestone values stop at M11 (`M0` 2, `M1` 4, `M2` 11, `M3` 3, `M4` 20, `M5` 14, `M6` 34, `M7` 22, `M8` 98, `M9` 26, `M10` 18, `M11` 6 = 258).
- `docs/traceability/milestones.yaml` records only gate state (`current`/`completed`); it does not enumerate M0…M12 at all (file is 5 lines).

So M12 is a **process milestone, not a requirement milestone**: it carries no traceable requirement rows and therefore no v5 requirement maps to it. What it owes is defined procedurally:

- Full-system Principal Review from a fresh clone (`FraudShield_Master_Build_Prompt.md`:765-773, Part I.4 — 7 steps, ending in `docs/reviews/FINAL_SYSTEM_REVIEW.md`).
- `docs/FINAL_REPORT.md` final handover report (`:549`, Part F.2).
- `v1.0.0` release tag pushed (`:389`; tagging rule at `:648`, Part G.7: "`v1.0.0` at the end of M12").

**What v5 assigns to M10/M12-equivalent work.** v5 does not use the M-numbering at all — it uses its own phase scheme, so any M10/M12 mapping is an interpretation, not something v5 states. Marking the mapping itself **unverified**:

- v5 §15 "Implementation Roadmap" (`FraudShield_SRS_v5_0.md`:684-696) defines **Phase 0…Phase 7** (`:689-696`), keyed to Weeks 4–6. The closest analogues: *Phase 6 — Production infrastructure* (`:695`, gate "Zero-downtime canary deploy; /actuator/health passes; ML gate blocks deliberately bad model; OWASP ZAP: 0 critical") and *Phase 7 — Research artifacts (independent release)* (`:696`, gate "arXiv ID assigned; HuggingFace pages live; model card complete; GitHub README production-ready"). Phase 7 corresponds to the register's **M11**, not M12.
- v5 §23 "Updated Infrastructure Roadmap — All Technologies" (`:908-919`) explicitly **supersedes §15 for infrastructure phases** (`:909`) and defines **Infra Phase 0…Infra Phase 6** (`:913-919`). The last two are the M10/M12-shaped ones:
  - *Infra Phase 5 — Production Hardening* (`:918`): GitHub Actions ML evaluation gate; security scans (OWASP ZAP, Gitleaks, trivy); 10% Kubernetes canary; auto-rollback on p99 > 80ms or error rate > 0.5%; Helm rollback tested; disaster recovery runbook. Gate: "Zero-downtime canary deploy confirmed; auto-rollback triggered by deliberately bad deployment; disaster recovery: full restore from backup < 1 hour."
  - *Infra Phase 6 — Production Go-Live* (`:919`): production cluster deployment via `helm upgrade` with `values-prod.yaml`; all monitoring verified; on-call runbook published; first daily report generated and reviewed; BNR monthly report format approved. Gate: "All health checks passing in production; first daily report delivered and verified; Grafana Operations Overview showing live data; on-call team has access to runbook and PagerDuty."
- v5 has **no section that corresponds to M12's fresh-clone audit / final report / v1.0.0 tag**. That obligation exists only in the build prompt. Conversely, v5's Infra Phase 6 ("Production Go-Live", BNR format approval, first live daily report) has **no** counterpart anywhere in the register or the build prompt's M0…M12 — it is unassigned work.

## 5. Whole-register context (all 258 rows, all families)

| Milestone | Rows | DONE | DONE_WITH_DEVIATION | VERIFIED_AT_REDUCED_SCALE | IN_PROGRESS | REQUIRES_EXTERNAL_PARTY | NOT_STARTED |
|---|---|---|---|---|---|---|---|
| M0 | 2 | 2 | 0 | 0 | 0 | 0 | 0 |
| M1 | 4 | 3 | 1 | 0 | 0 | 0 | 0 |
| M2 | 11 | 8 | 0 | 2 | 0 | 1 | 0 |
| M3 | 3 | 2 | 1 | 0 | 0 | 0 | 0 |
| M4 | 20 | 14 | 6 | 0 | 0 | 0 | 0 |
| M5 | 14 | 0 | 0 | 0 | 0 | 0 | 14 |
| M6 | 34 | 0 | 0 | 0 | 0 | 0 | 34 |
| M7 | 22 | 0 | 0 | 0 | 2 | 0 | 20 |
| M8 | 98 | 0 | 0 | 0 | 2 | 0 | 96 |
| M9 | 26 | 0 | 0 | 0 | 2 | 0 | 24 |
| M10 | 18 | 0 | 0 | 0 | 0 | 0 | 18 |
| M11 | 6 | 0 | 0 | 0 | 1 | 0 | 5 |
| **Total** | **258** | **29** | **8** | **2** | **7** | **1** | **211** |

These totals match the generated matrix header verbatim (`docs/traceability/requirements_matrix.md`:6 — "Rows: 258. Status counts: DONE 29, DONE_WITH_DEVIATION 8, IN_PROGRESS 7, NOT_STARTED 211, REQUIRES_EXTERNAL_PARTY 1, VERIFIED_AT_REDUCED_SCALE 2."), so YAML and matrix are in sync.

## 6. Gaps and unverified items

1. **FR-08-01…08 (v5 §20.5, reporting) are entirely absent from the register.** `grep -rn 'FR-08' docs/traceability/` returns nothing. Build prompt D.2 (`:338`) enumerates the rows the register must carry and stops at "every `FR-01-01` … `FR-07-09`" — it predates FR-08. The whole reporting subsystem (Celery beat, WeasyPrint PDF, S3 lifecycle retention 90d/1y/7y, `GET /api/v1/reports/`, Admin "Reports" tab, Grafana report dashboard) is untracked. v5 puts it in *Infra Phase 4* (`:917`).
2. **v5 sections 17–23 are not in the register's scope statement.** Docker (§17), Kubernetes (§18), Prometheus/Grafana (§19), operational reporting (§20), Helm (§21), OpenTelemetry/Jaeger (§22), infra roadmap (§23). The register's `OPS-CI` (8) and `OPS-OBS` (6) rows derive from v1 §8.1/§8.2 and do **not** enumerate Helm, OTel/Jaeger or cert-manager. Whether those are considered covered by the existing M9 rows is **unverified** — no register row cites v5 §17–23.
3. **v5's own version metadata is inconsistent** (cover says v4.0, colophon says v5.0, contents table stops at §16 while §17–23 exist). Unverified which is authoritative.
4. **The v5 file is untracked.** It is not in any commit on `m8/frontend` @ `4f6828e`, so no register row can cite it as evidence yet, and the seeding tool has not been run against it.
5. **The seeding tool has no v5 awareness.** `tools/src/fraudshield_tools/traceability_seed.py` is the refresher named in the register header (`requirements.yaml`:2); whether it can parse v5's layout is **unverified** — I did not run it (read-only task).
6. **M12 has no register rows** (§4.3). Build prompt D.2's `traceability-check` CI job asserts M-priority rows have tagged tests and DONE rows have evidence; it does not assert that every build-prompt milestone has rows, so this gap is not caught automatically. **Unverified** whether that is intentional.
7. **Branch-local view.** FR-07-* and the M7 rows read `NOT_STARTED`/`IN_PROGRESS` in this worktree; `m7/staff-auth` is unmerged. Statuses for M7 are therefore **not** a claim about the project as a whole.
