FRAUDSHIELD
Real-Time Financial Fraud Detection Platform
Complete Software Requirements Specification  ·  v4.0  ·  2026

| GitHub | github.com/mariusbayizere/fraudshield   ← STANDALONE INDEPENDENT PROJECT |
|---|---|
| Author | Marius Bayizere — Independent Researcher, Kigali, Rwanda |
| Contact | bayizeremarius119@gmail.com |
| Backend | Spring Boot 3.2 · Java 21 · Spring Security 6 · Spring Data JPA · Hibernate 6 · Maven |
| Frontend | React 18 · TypeScript 5 (strict) · Tailwind CSS · Material UI v5 · Vite 5 |
| ML Service | Python 3.11 · FastAPI · XGBoost · LightGBM · SHAP · MLflow · scikit-learn |
| Streaming | Apache Kafka 3.6 · Kafka Streams |
| Database | PostgreSQL 16 · TimescaleDB · Redis 7 |
| Auth | Spring Security 6 · JWT RS256 · BCrypt cost 12 · Google OAuth 2.0 · RBAC |
| Architecture | MVC: Controller → Service → Repository → Entity · DTO projection · @PreAuthorize RBAC |
| Roles | ANALYST · SENIOR_ANALYST · RISK_OFFICER · ADMIN |
| v4.0 | V2 functional + UI + ML + infra + testing + roadmap COMBINED with V3 engineering-grade schema, Spring Boot architecture, and per-role frontend |

00  Document Overview
This is the complete, authoritative Software Requirements Specification for FraudShield. It combines the functional requirements, non-functional requirements, UI/UX design system, ML requirements, infrastructure, testing, research plan, and implementation roadmap from v2.0 with the engineering-grade schema design rationale, Spring Boot MVC architecture, Spring Security 6 RBAC implementation, lean data model with column justifications, entity relationships, and per-role frontend specifications from v3.0. Every section is included. Nothing is missing.

| Section | Title | Source |
|---|---|---|
| 01 | Problem Statement and System Overview | V2 |
| 02 | Spring Boot MVC Architecture + Service Layer | V3 (Engineering-grade) |
| 03 | Spring Security 6 — Authentication and RBAC | V3 (Engineering-grade) |
| 04 | Functional Requirements — FR-01 to FR-07 | V2 (Complete) |
| 05 | Non-Functional Requirements | V2 (Complete) |
| 06 | UI/UX Design Requirements | V2 (Complete) |
| 07 | Data Model — 13 Lean Tables with Column Justification | V3 (DBA-reviewed) |
| 08 | Entity Relationships | V3 |
| 09 | Machine Learning Requirements | V2 + V3 |
| 10 | Infrastructure and CI/CD | V2 |
| 11 | Testing Requirements | V2 |
| 12 | Per-Role Frontend Specification | V3 (Complete) |
| 13 | Cross-Device and Mobile Requirements | V2 (Complete) |
| 14 | Research and Publication Plan | V2 |
| 15 | Implementation Roadmap | V2 |
| 16 | Glossary | V2 |

01  Problem Statement and System Overview
1.1  The Six Failures FraudShield Solves

| Problem | Description | Measurable Consequence |
|---|---|---|
| Fraud detected too late | Batch-processing systems analyse transactions hours after they occur | Money moved and accounts drained — losses largely unrecoverable by detection time |
| Rule-based systems fail | Static rules are permanently visible to fraudsters who design attacks below thresholds | Attackers circumvent rules within days; fraud rates not reduced |
| No explainability | Black-box ML score with no reason causes analyst alert fatigue — 60%+ alerts dismissed | Fraud that should have been caught is missed because analysts stop trusting the system |
| No East African context | All published fraud ML models trained on European or US card transaction data | Western models achieve AUC-ROC 0.72-0.78 on East African mobile money — insufficient |
| No continuous learning | Models deployed once and never updated as fraud patterns evolve | Model accuracy degrades 8-15% per year without retraining |
| No behavioural intelligence | Alerts treated as isolated events with no account history or campaign context | Organised fraud rings, SIM-swap campaigns, and synthetic identity networks go undetected |

1.2  Seven-Layer Architecture

| Layer | Technology | Responsibility |
|---|---|---|
| Client | React 18 + TypeScript 5 + Tailwind CSS + MUI v5 + Vite | Analyst dashboard, risk officer panel, admin panel, model monitoring |
| API Gateway | Spring Boot 3.2 (Java 21) + Spring Security 6 + Nginx | Transaction ingestion, JWT auth, rate limiting, OpenAPI 3.1 docs |
| Risk Decision Engine | Spring Boot RiskDecisionService + Redis threshold store | Apply ML thresholds, trigger auto-block or analyst queue, MEDIUM timers |
| ML Scoring | Python FastAPI + XGBoost + LightGBM + Isolation Forest + SHAP + MLflow | Real-time ensemble scoring, anomaly detection, SHAP explanation generation |
| Feature Engineering | Python + Redis feature store + Pandas | 44 features per transaction including velocity, geographic, temporal, agent-specific |
| Event Streaming | Apache Kafka 3.6 + Kafka Streams | Transaction ingestion 10,000+/sec, alert propagation, audit event log |
| Data Layer | PostgreSQL 16 + TimescaleDB + Redis 7 | Relational storage, time-series analytics, velocity feature cache, token store |

1.3  Transaction Processing Pipeline — Latency Budget

| Step | Component | Action | Budget |
|---|---|---|---|
| 1 | Core Banking | Transaction initiated; raw event published to Kafka 'fs.transactions.raw' | < 1ms |
| 2 | Spring Boot Consumer | Validate JSON schema; enrich with account metadata | < 5ms |
| 3 | Redis Feature Store | Fetch pre-computed velocity features for account | < 3ms |
| 4 | Python Feature Engineering | Compute all 44 features | < 10ms |
| 5 | XGBoost + LightGBM Ensemble | Calibrated probability scores; weighted average | < 13ms |
| 6 | Isolation Forest | Independent anomaly score for every transaction | < 3ms |
| 7 | SHAP Explanation | Top-5 features for ensemble_score >= 0.60 only | < 8ms (flagged) |
| 8 | Risk Decision Engine | Apply thresholds: HIGH auto-block / MEDIUM queue / LOW approve | < 2ms |
| 9 | Auto-Block Execution | Block + Kafka alert event published | < 5ms |
| 10 | Customer SMS | Africa's Talking SMS with verification link (async) | < 200ms async |
| 11 | WebSocket Push | Kafka consumer → all connected analyst dashboards | < 50ms |
| 12 | Audit Write | Immutable row appended to TimescaleDB audit_events | < 5ms async |
| TOTAL | End to end | Transaction received to auto-block at p95 | < 50ms |

02  Spring Boot 3.2 MVC Architecture and Service Layer
The backend follows a strict four-layer MVC architecture. Each layer has exactly one responsibility. No business logic appears in controllers. No database queries appear in services. Raw JPA entities never cross a layer boundary.

| Package Structure: com.fraudshield <br> ├── controller/      @RestController — HTTP parsing and response only <br> │   ├── AuthController.java, TransactionController.java, AlertController.java <br> │   ├── RiskOfficerController.java, AdminController.java <br> ├── service/         @Service — ALL business logic lives here, nowhere else <br> │   ├── AuthService.java             Login, OAuth, token issuance, password reset <br> │   ├── TransactionService.java      Ingestion, idempotency check, Kafka publish <br> │   ├── ScoringService.java          Calls Python ML scorer, stores FraudScore <br> │   ├── RiskDecisionService.java     Thresholds, auto-block, account freeze logic <br> │   ├── AlertService.java            Alert lifecycle, decisions, escalations, WebSocket push <br> │   ├── ModelService.java            Model version promotion, rollback, shadow mode <br> │   ├── RuleService.java             Alert rule evaluation and management <br> │   ├── CampaignService.java         Fraud campaign detection and lifecycle <br> │   └── AuditService.java            Write immutable audit_events rows <br> ├── repository/      @Repository — Spring Data JPA interfaces, NO business logic <br> ├── entity/          @Entity — JPA/Hibernate mapping ONLY <br> │   ├── BaseEntity.java (abstract: id, created_at) <br> │   └── [13 entity classes — see Section 07] <br> ├── dto/             Java records — never expose raw entities <br> │   ├── request/     Inbound with @Valid Jakarta Bean Validation <br> │   └── response/    Outbound response projections <br> ├── security/        SecurityConfig, JwtAuthenticationFilter, <br> │                    FraudShieldUserDetailsService, OAuth2SuccessHandler <br> ├── exception/       FraudShieldException hierarchy + GlobalExceptionHandler <br> └── kafka/           TransactionProducer, ScoringResultConsumer |
|---|

Service Layer — Business Logic Ownership
Every business rule lives in a @Service class. The AlertService below shows the pattern: @PreAuthorize at the method level, business rule enforcement in the service body, atomic DB writes, audit event, and WebSocket push — all in one place.

| @Service @Transactional @RequiredArgsConstructor <br> public class AlertService { <br> private final AlertQueueEntryRepository alertRepo; <br> private final AlertDecisionRepository   decisionRepo; <br> private final AuditService              auditService; <br> private final SimpMessagingTemplate     websocket; <br> @PreAuthorize("hasAnyRole('ANALYST','SENIOR_ANALYST','RISK_OFFICER','ADMIN')") <br> public Page<AlertResponse> listAlerts(AlertFilter filter, Pageable page, <br> FraudShieldUserDetails principal) { <br> // Business rule: ANALYST sees only own alerts <br> if (principal.getRole() == Role.ANALYST) <br> filter.setAssignedAnalystId(principal.getId()); <br> return alertRepo.findByFilter(filter, page).map(AlertMapper::toResponse); <br> } <br> @PreAuthorize("hasAnyRole('ANALYST','SENIOR_ANALYST','RISK_OFFICER','ADMIN')") <br> public AlertDecisionResponse decide(Long alertId, DecisionRequest req, <br> FraudShieldUserDetails principal) { <br> AlertQueueEntry alert = alertRepo.findById(alertId) <br> .orElseThrow(() -> new ResourceNotFoundException("Alert", alertId)); <br> // Business rule: comment mandatory for CONFIRM_FRAUD / MARK_LEGITIMATE <br> if (req.decision().requiresComment() && <br> (req.comment() == null \|\| req.comment().length() < 10)) <br> throw new ValidationException("comment", "Minimum 10 characters required"); <br> AlertDecision decision = AlertDecision.builder() <br> .alertQueueEntry(alert).analyst(userRef(principal.getId())) <br> .decision(req.decision()).analystComment(req.comment()).build(); <br> decisionRepo.save(decision); <br> alert.setStatus(AlertStatus.fromDecision(req.decision())); <br> alertRepo.save(alert); <br> auditService.log(principal, EventType.ALERT_DECISION, <br> "AlertDecision", decision.getId().toString()); <br> websocket.convertAndSend("/topic/alerts", AlertMapper.toResponse(alert)); <br> return AlertMapper.toDecisionResponse(decision); <br> } <br> // SENIOR_OVERRIDE = new AlertDecision row with Decision.SENIOR_OVERRIDE <br> // No self-referential FK needed — timeline reconstructed by ORDER BY created_at <br> @PreAuthorize("hasAnyRole('RISK_OFFICER','ADMIN')") <br> public AlertDecisionResponse seniorOverride(Long alertId, OverrideRequest req, <br> FraudShieldUserDetails principal) { <br> return decide(alertId, <br> new DecisionRequest(Decision.SENIOR_OVERRIDE, req.reason()), principal); <br> } <br> } |
|---|

03  Spring Security 6 — Authentication and RBAC
3.1  Security Filter Chain

| @Configuration @EnableWebSecurity <br> @EnableMethodSecurity(prePostEnabled = true) <br> public class SecurityConfig { <br> @Bean <br> public SecurityFilterChain filterChain(HttpSecurity http, <br> JwtAuthenticationFilter jwtFilter) throws Exception { <br> return http <br> .csrf(AbstractHttpConfigurer::disable) <br> .cors(c -> c.configurationSource(corsSource())) <br> .sessionManagement(s -> s.sessionCreationPolicy(STATELESS)) <br> .authorizeHttpRequests(a -> a <br> .requestMatchers("/api/v1/auth/**").permitAll() <br> .requestMatchers(POST,"/api/v1/transactions/ingest").hasAuthority("API_KEY") <br> .requestMatchers("/actuator/health").permitAll() <br> .anyRequest().authenticated()) <br> .oauth2Login(o -> o.successHandler(oauth2SuccessHandler)) <br> .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class) <br> .build(); <br> } <br> @Bean <br> public PasswordEncoder passwordEncoder() { <br> return new BCryptPasswordEncoder(12); // cost factor 12 — mandatory <br> } <br> } |
|---|

3.2  Roles and Access Scopes

| Role | Spring Authority | Access Scope |
|---|---|---|
| ANALYST | ROLE_ANALYST | View and review own assigned alerts; CONFIRM_FRAUD / MARK_LEGITIMATE / ESCALATE with mandatory comment; own performance panel; profile management |
| SENIOR_ANALYST | ROLE_SENIOR_ANALYST | All ANALYST access + view all alerts regardless of assignment; reassign alerts; override ANALYST decisions; team performance; fraud campaign view and escalation |
| RISK_OFFICER | ROLE_RISK_OFFICER | All SENIOR_ANALYST access + portfolio risk dashboard; model performance monitoring; close campaigns; create/disable alert rules; per-channel threshold management; SAR generation; senior override any decision |
| ADMIN | ROLE_ADMIN | All RISK_OFFICER access + full user CRUD (create, edit, deactivate, reactivate); model promotion/rollback/shadow mode; dataset upload and retraining; API key management; full audit log; system health; threshold configuration |

3.3  JWT Authentication Filter

| @Component @RequiredArgsConstructor <br> public class JwtAuthenticationFilter extends OncePerRequestFilter { <br> private final JwtDecoder jwtDecoder; <br> private final UserDetailsService userDetailsService; <br> private final RefreshTokenRepository refreshTokenRepo; <br> @Override <br> protected void doFilterInternal(HttpServletRequest req, <br> HttpServletResponse res, FilterChain chain) <br> throws ServletException, IOException { <br> String header = req.getHeader(HttpHeaders.AUTHORIZATION); <br> if (header == null \|\| !header.startsWith("Bearer ")) { <br> chain.doFilter(req, res); return; <br> } <br> try { <br> Jwt jwt = jwtDecoder.decode(header.substring(7)); <br> if (refreshTokenRepo.isBlocklisted(jwt.getId())) { <br> res.setStatus(SC_UNAUTHORIZED); return; <br> } <br> UserDetails user = userDetailsService.loadUserByUsername(jwt.getSubject()); <br> var auth = new UsernamePasswordAuthenticationToken( <br> user, null, user.getAuthorities()); <br> auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req)); <br> SecurityContextHolder.getContext().setAuthentication(auth); <br> } catch (JwtException e) { res.setStatus(SC_UNAUTHORIZED); return; } <br> chain.doFilter(req, res); <br> } <br> } |
|---|

04  Functional Requirements
MoSCoW priority: M = Must Have, S = Should Have, C = Could Have. Every Must Have requires its acceptance criterion to pass before the system is deployed to any financial institution.

4.1  FR-01: Transaction Ingestion API

| ID | Requirement | Pri | Acceptance Criterion |
|---|---|---|---|
| FR-01-01 | POST /api/v1/transactions/ingest accepts payload; validates schema; publishes to Kafka within 5ms | M | Locust: 10,000 TPS for 60s; all within 5ms Kafka publish; zero data loss confirmed by consumer offset check |
| FR-01-02 | Transaction schema: transaction_id (UUID), account_id (tokenised), counterparty_id (tokenised), amount (DECIMAL), currency (ISO 4217), channel ENUM(6 values), device_fingerprint (nullable), latitude, longitude, transaction_timestamp (ISO 8601 UTC) | M | 400 returned for missing required field with field-level error detail; 422 for type mismatches |
| FR-01-03 | Idempotent ingestion: duplicate transaction_id within 24h returns 200 with cached result — not reprocessed | M | Integration: same transaction_id submitted 100x; exactly 1 scored; all 100 return identical cached response; Redis TTL=24h confirmed |
| FR-01-04 | All 6 East African channels handled as first-class types: MOBILE_MONEY, CARD, AGENT_BANKING, USSD, ONLINE, BANK_TRANSFER | M | Unit: USSD processes without device_fingerprint crash; AGENT_BANKING triggers agent-specific features; all 6 channels have unit tests |
| FR-01-05 | API key authentication for core banking (machine-to-machine); JWT for human users; keys scoped to ingestion endpoints only | M | API key cannot access /api/v1/alerts or /api/v1/admin — confirmed by permission test for every protected endpoint |
| FR-01-06 | Batch ingestion POST /api/v1/transactions/ingest/batch accepts up to 1,000 transactions; returns 202 with job_id; results queryable via GET /api/v1/jobs/{id} | S | All 1,000 transactions scored within 30 seconds; job status endpoint returns correct progress percentage |
| FR-01-07 | OpenAPI 3.1 spec served at /api/docs with request/response examples for all 6 channel types | M | OpenAPI spec validates against OpenAPI 3.1 schema; all endpoints documented; East African channel examples present |

4.2  FR-02: ML Fraud Scoring Engine

| ID | Requirement | Pri | Acceptance Criterion |
|---|---|---|---|
| FR-02-01 | Every ScoringResult: ensemble_score (0.0–1.0), risk_tier (HIGH/MEDIUM/LOW), shap_top5 (JSONB, nullable for LOW), model_version, requires_analyst_review | M | All 5 fields present in every ScoringResult; shap_top5 NOT NULL for HIGH and MEDIUM — confirmed by DB constraint |
| FR-02-02 | 44 features engineered per transaction within < 10ms: velocity (tx_count_60s/1h/24h, amount_sum_24h), geographic (lat/lon distance, EAC corridor flag), temporal (hour_of_day, days_since_last_tx), counterparty (known_fraud_flag, new_to_account), device (fingerprint_seen_before), agent-specific (agent_fraud_indicator, agent_cash_out_rate), amount behaviour (z-score, round_sum_flag) | M | Feature unit tests: all 44 computed for all 6 channel types; USSD handles missing device_fingerprint; p95 < 10ms on benchmark |
| FR-02-03 | XGBoost (weight 0.55) + LightGBM (weight 0.45) ensemble; isotonic regression probability calibration; ECE < 0.05 | M | evaluate.py generates calibration plot; ECE computed; CI/CD deployment gate fails if ECE > 0.05 |
| FR-02-04 | SHAP TreeExplainer (exact — no sampling) computes top-5 SHAP features for all transactions with ensemble_score >= 0.60 | M | SHAP values sum to model output within 0.001 tolerance; 100% SHAP coverage for HIGH and MEDIUM confirmed by evaluate.py |
| FR-02-05 | Isolation Forest anomaly score independent of supervised model; anomaly_score > 0.7 sets requires_analyst_review = true regardless of ensemble_score | M | Integration: synthetic extreme outlier triggers analyst review even when ensemble_score < 0.60 |
| FR-02-06 | Risk thresholds configurable at runtime via Redis config without model reload; change takes effect within 60 seconds | M | Admin API PATCH /api/v1/admin/thresholds; next scored transaction after 60s uses new threshold — integration test confirmed |
| FR-02-07 | ML scoring latency: p50 < 15ms, p95 < 25ms, p99 < 40ms (ensemble + SHAP for flagged transactions combined) | M | Locust benchmark: 200 concurrent scoring requests on target hardware; all percentiles confirmed |
| FR-02-08 | Shadow mode: new model scores every transaction alongside production; only production result triggers actions; comparison logged to MLflow | M | Both scores in audit log; only production score in ScoringResult; MLflow comparison dashboard showing AUC-ROC delta |
| FR-02-09 | Redis feature store updates velocity features within 100ms of each transaction; DB row upserted every 5 minutes for durability | M | Redis update p99 < 100ms measured by Prometheus; DB fallback activated when Redis unavailable — integration test |
| FR-02-10 | Every ScoringResult records model_version string matching a row in model_versions table | M | Unit: null model_version throws ValidationException; FK-consistency confirmed by repository test |

4.3  FR-03: Risk Decision and Auto-Block Engine

| ID | Requirement | Pri | Acceptance Criterion |
|---|---|---|---|
| FR-03-01 | HIGH (ensemble_score >= 0.85): auto-blocked within 50ms; account flagged; customer SMS dispatched asynchronously via Africa's Talking | M | Load test 1,000 HIGH events/sec; all blocked within 50ms p95; 0 incorrectly approved; SMS within 5s |
| FR-03-02 | MEDIUM (0.60–0.84): transaction held; 30-second countdown; auto-released with TIMEOUT label if no analyst action | S | Integration: timer starts at creation; auto-release fires at exactly 30s; TIMEOUT label in alert_decisions |
| FR-03-03 | LOW (< 0.60): approved within total pipeline latency budget; no analyst involvement | M | Approval within 50ms; approval rate matches expected 0.87% fraud rate in test set |
| FR-03-04 | Customer SMS: masked account number, transaction amount, currency, timestamp, 10-minute HTTPS verification link | M | SMS delivered within 5s via Africa's Talking; link expires exactly at 10 minutes; HTTPS confirmed |
| FR-03-05 | Customer verification via SMS link: block lifted within 10s; false_positive_confirmed = true; feature vector queued for ML retraining | M | End-to-end test: SMS link click → block lifted within 10s → false_positive_confirmed in alert_decisions |
| FR-03-06 | Account freeze: 3+ HIGH-risk transactions from same account within 1 hour triggers freeze + risk officer notification | S | RiskDecisionService: count auto_block_events per account_id in last 1h; freeze at exactly 3rd HIGH event |
| FR-03-07 | MCC circuit breaker: fraud rate > 5% from specific MCC in 15-minute window flags all transactions to that MCC for review | S | Circuit breaker triggers within 60s of threshold breach; resets after 60-minute clean window |
| FR-03-08 | All auto-block decisions append-only; DB INSERT-only permission on auto_block_events; no deletions ever | M | DB security test: attempted DELETE returns permission denied from application DB role |

4.4  FR-04: Analyst Dashboard

| ID | Requirement | Pri | Acceptance Criterion |
|---|---|---|---|
| FR-04-01 | Analyst authenticates via email+password OR Google OAuth 2.0; JWT issued; dashboard accessible on all devices 320px to 1536px | M | Both auth paths issue ANALYST JWT; Google profile (first_name, last_name) stored; dashboard renders on iPhone SE |
| FR-04-02 | Real-time alert feed via WebSocket STOMP; sorted fraud_probability descending; HIGH pinned at top; MEDIUM shows countdown timer | M | New alert visible on all connected dashboards within 1 second of Kafka event; MUI DataGrid virtualised for 10,000+ rows |
| FR-04-03 | Alert card shows: risk tier badge, ensemble_score gauge (MUI CircularProgress), amount + currency, channel MUI Chip, masked account token, merchant, timestamp, top-3 SHAP feature pills | M | All 8 elements present on every alert card; SHAP pills show feature name + contribution direction + value |
| FR-04-04 | One-click CONFIRM FRAUD or MARK LEGITIMATE with mandatory comment field (min 10 characters); both complete within 2 seconds | M | Comment < 10 chars disables submit button; action completes within 2s; alert removed from feed; undo Snackbar 5s |
| FR-04-05 | SHAP waterfall chart: all features sorted by \|SHAP\| value; positive bars red, negative green; base value and final score annotated; plain-English description per feature | M | Recharts HorizontalBarChart renders within 500ms; MUI Tooltip per bar with East African fraud context description |
| FR-04-06 | Account history timeline: last 30 transactions with amount, channel icon, timestamp, risk score MUI Chip, outcome badge | M | Timeline renders within 500ms; accurate against TimescaleDB; sorted most recent first |
| FR-04-07 | Behavioural fingerprint: hourly heatmap, amount histogram, channel pie chart, 'This transaction vs normal' comparison table | S | All 4 panels render within 1 second; comparison table highlights anomalous cells in amber |
| FR-04-08 | Transaction network graph: flagged account + counterparty + 2nd-degree connections; known fraud nodes highlighted red | C | Graph renders up to 50 nodes within 2 seconds using vis-network or D3; fraud nodes correctly coloured |
| FR-04-09 | Filter and search: by transaction_id, account_id, amount range, channel, date range, risk tier, analyst, outcome | M | Composable filters; results within 500ms; URL updates for shareable filtered views |
| FR-04-10 | Escalation: ANALYST escalates to SENIOR_ANALYST or RISK_OFFICER with mandatory reason | M | Escalation creates new AlertDecision row; target role analyst notified via WebSocket within 1s |
| FR-04-11 | Analyst performance panel: alerts reviewed today, average review time, accuracy rate from customer verifications, backlog size | S | Metrics update in real time; false positive rate computed correctly from customer_verifications table |
| FR-04-12 | WCAG 2.1 AA: risk tier conveyed by text label not colour alone; all controls keyboard-navigable | S | Zero critical Axe violations; keyboard navigation test passes; NVDA announces risk tier text |

4.5  FR-05: Risk Officer Panel

| ID | Requirement | Pri | Acceptance Criterion |
|---|---|---|---|
| FR-05-01 | Risk Officer sees all analyst alerts plus escalated alerts; can senior-override any analyst decision with mandatory reason | M | Override creates immutable AlertDecision row with SENIOR_OVERRIDE; both analyst and customer notified |
| FR-05-02 | Portfolio risk dashboard: fraud prevented (RWF amount + count) today/week/month; fraud rate by channel; by MCC; geographic heatmap (Leaflet.js); top-10 fraud counterparty accounts | M | All metrics accurate against TimescaleDB; heatmap renders within 2 seconds; update in real time |
| FR-05-03 | Model performance monitoring: live AUC-ROC from analyst labels; precision, recall, F1 trends; drift indicator vs baseline; per-channel AUC-ROC | M | Metrics updated hourly; drift alert (MUI Alert) when AUC-ROC drops > 0.03 from baseline |
| FR-05-04 | Fraud campaign detection panel: clusters by shared DEVICE, COUNTERPARTY, GEOGRAPHIC, or MCC feature; campaign list; transaction count and total amount (computed fresh, not stored) | S | Campaign job runs every 15 minutes; counts and amounts computed via JOIN on fraud_campaign_transactions |
| FR-05-05 | Custom rule management: create alert rules via structured form (no code); enable/disable; rule DSL validated on save | S | Rule change takes effect within 60 seconds; invalid DSL expression returns 400 with specific error |
| FR-05-06 | SAR auto-draft from CONFIRM_FRAUD decision in Rwanda BNR format; edit draft; export PDF | S | SAR generated within 30 seconds; all required BNR fields populated; PDF export produces valid document |
| FR-05-07 | Per-channel threshold management: different HIGH/MEDIUM/LOW thresholds for each of 6 channels | M | Per-channel thresholds take effect within 60s; UI shows current threshold per channel; change logged in audit_events |

4.6  FR-06: Admin Panel

| ID | Requirement | Pri | Acceptance Criterion |
|---|---|---|---|
| FR-06-01 | Create staff accounts: first name, last name, email, phone (country code flag + dial code + local number stored as E.164), employee ID, department, role; welcome email dispatched | M | All fields stored; UNIQUE(email); UNIQUE(employee_id); welcome email sent within 30s of creation |
| FR-06-02 | View, update, and deactivate any account; deactivated accounts blocked from all auth methods (password + Google OAuth) within 5 seconds | M | Deactivated OAuth account blocked server-side; login attempt returns 401 within 5s |
| FR-06-03 | Model management: list all model versions with training date, dataset size, AUC-ROC, precision, recall, F1; promote to production (gate enforced); rollback; enable shadow mode | M | Promotion blocked server-side by ModelService if any gate metric fails; rollback within 30s; no traffic interruption |
| FR-06-04 | Dataset upload: upload labelled CSV; trigger retraining; monitor real-time progress via WebSocket; new model auto-evaluated before promotion option appears | S | Retraining starts within 60s; WebSocket progress accurate; model appears in list only after evaluate.py passes |
| FR-06-05 | System health panel: Kafka consumer lag per topic, ML scoring p50/p95/p99, API error rate, Redis hit rate, DB pool, alert queue depth, auto-block rate | M | All Prometheus metrics present; configurable thresholds with colour indicators; 30-day Grafana history |
| FR-06-06 | Full audit log: every state-changing operation with user first name + last name, role, timestamp, event type, entity type, entity ID; searchable; immutable | M | All 6 event types logged; search by user, date, event type; no DELETE shown in UI; attempted DB DELETE returns 403 |
| FR-06-07 | API key management: create, view (name + last-4 chars only), revoke; each key scoped to endpoint groups; raw key shown once at creation | M | Revoked key returns 401 within 5 seconds; raw key not in any subsequent API response |

4.7  FR-07: Authentication and Authorisation

| ID | Requirement | Pri | Acceptance Criterion |
|---|---|---|---|
| FR-07-01 | Four roles enforced at API method level: ANALYST, SENIOR_ANALYST, RISK_OFFICER, ADMIN; role embedded in JWT claims; cannot self-elevate | M | @PreAuthorize tests: 403 on all wrong-role endpoint combinations; role claim cannot be modified by client |
| FR-07-02 | Email + password registration: first name, last name, country-code phone (flag + dial code selector 249 countries), employee ID, department, password + confirm password, strength meter | M | Confirm password mismatch blocks submit; strength meter 4 levels; all required fields validated before submit |
| FR-07-03 | Google OAuth 2.0 sign-in on login and registration; Google ID token verified server-side against JWKS; account linked by email | M | OAuth flow < 3 seconds; invalid token returns 401; account linking by matching email confirmed in integration test |
| FR-07-04 | JWT access tokens: 15-minute expiry, RS256, containing user_id, role, first_name, email; refresh tokens: 7-day expiry in httpOnly SameSite=Strict cookie | M | Expired token returns 401; RS256 verified; refresh rotation confirmed; httpOnly cookie confirmed in browser DevTools |
| FR-07-05 | Machine-to-machine API keys for transaction ingestion scoped to ingestion endpoints only; cannot access analyst, admin, or ML endpoints | M | API key returns 403 on /api/v1/alerts and /api/v1/admin — confirmed by explicit permission test |
| FR-07-06 | Login rate limited: 10 attempts per 15 minutes per IP; 5 failed attempts locks account; unlock email sent | M | 11th IP attempt returns 429 with Retry-After header; account locked after 5th failed attempt; email sent within 30s |
| FR-07-07 | BCrypt cost factor 12; minimum 8 chars, 1 uppercase, 1 lowercase, 1 digit, 1 special character | M | Weak password returns 400 with specific failure reason; BCrypt timing test confirms > 100ms per hash |
| FR-07-08 | Password reset via email OTP: 6 digits, 10-minute expiry, single-use | S | OTP delivered within 30s; expired OTP returns 400; used OTP cannot be reused |
| FR-07-09 | All sessions invalidated on password change; Google OAuth session revoked via Google revocation API on logout | M | Login with old JWT after password change returns 401; Google revocation API call confirmed by mock in integration test |

05  Non-Functional Requirements
5.1  Performance

| Metric | Target | Measurement Method |
|---|---|---|
| Transaction ingestion throughput | 10,000+ TPS sustained 5 minutes | Locust: 10k TPS; Kafka consumer lag < 500 msgs; 0 data loss |
| Auto-block end-to-end latency | p50 < 30ms  \|  p95 < 50ms  \|  p99 < 80ms | Prometheus histogram: transaction_received → block_decision event timestamps |
| ML scoring latency | p50 < 15ms  \|  p95 < 25ms  \|  p99 < 40ms (ensemble + SHAP combined) | Per-request structlog timing; Grafana time-series; Locust benchmark 200 concurrent |
| Feature engineering latency | p95 < 10ms including Redis feature store fetch | Redis response time + computation time measured separately in Prometheus |
| Analyst dashboard WebSocket update | < 1 second from Kafka event to browser DOM update | End-to-end integration test with millisecond-precision timestamps |
| SHAP waterfall chart render | < 500ms from panel open to chart interactive | Playwright performance test; Recharts render timing |
| Alert feed initial load (500 alerts) | < 1 second with MUI DataGrid virtualisation | Playwright test with 500 seeded alerts; time to interactive |
| Google OAuth login flow | < 3 seconds from button click to authenticated dashboard | Playwright E2E with Google test account; wall-clock timing |
| System availability | 99.9% monthly (< 9 hours downtime/year) | Prometheus blackbox probe every 30s; PagerDuty SLA tracking |
| API ingestion error rate | < 0.1% at 10,000 TPS | Locust: error percentage monitored at peak load |

5.2  Security

| Requirement | Implementation | Verification |
|---|---|---|
| Encryption at rest | PostgreSQL TDE + AES-256 application-layer for PII fields; Redis encryption at rest | TDE configuration audit; field encryption verified: raw PII not in any SQL query from application role |
| Encryption in transit | TLS 1.3 for all external; mTLS for Spring Boot → Python ML scorer service-to-service | SSL Labs A+ rating; mTLS certificate exchange verified in integration test |
| PII tokenisation | account_id and counterparty_id stored as opaque tokens — never raw; analysts see masked tokens only | API response audit: no raw account names or phone numbers in any analyst-facing endpoint |
| No secrets in version control | All secrets in Kubernetes Secrets or environment variables; Gitleaks in every CI run | CI pipeline fails on any Gitleaks pattern match; no credential in any committed file |
| Audit log immutability | audit_events: INSERT-only permission granted to application DB role; no UPDATE or DELETE ever | Attempted UPDATE on audit_events returns permission denied — confirmed by security test |
| SQL injection prevention | Spring Data JPA parameterised queries throughout; Python SQLAlchemy ORM; zero raw SQL concatenation | OWASP ZAP automated scan: 0 SQL injection findings; SonarQube: 0 injection hotspots |
| XSS prevention | React DOM escaping default; strict Content-Security-Policy header; DOMPurify on dynamic HTML | OWASP ZAP: 0 XSS findings; CSP header confirmed with report-uri endpoint |
| API key security | Keys stored as BCrypt hash; raw key shown once at creation only; rotation creates new key | Raw key not recoverable from DB; key rotation integration test confirmed |
| Data residency | All data stored in Rwanda/EAC region; no export to non-EAC jurisdictions without BNR authorisation | Cloud region af-south-1 or equivalent; storage audit confirms location |
| Annual penetration test | Third-party pen test; OWASP Top 10 + PCI DSS relevant controls | Zero critical findings required before production deployment; pen test report in docs/security/ |

5.3  Reliability — Failure Scenarios

| Failure Scenario | System Behaviour | Recovery | Test |
|---|---|---|---|
| ML scoring service down | Rule-based fallback activates within 5 seconds; transactions continue with requires_analyst_review=true | ML restart resumes scoring; Kafka backlog clears within 2 minutes | Integration: ML pod killed; fallback activates; transactions continue processing |
| Kafka broker failure | Spring Boot producer retries with exponential backoff; up to 10,000 transactions buffered in memory | Kafka restart resumes within 60 seconds; buffered transactions replayed in order | Integration: Kafka pod killed; replay confirmed after restart; no data loss |
| Redis feature store down | Feature engineering uses DB fallback for velocity features; degraded performance logged | Redis reconnects; feature store rebuilt from PostgreSQL within 5 minutes | Unit: all Redis calls wrapped in try/catch; DB fallback path tested |
| PostgreSQL primary failure | TimescaleDB replica promoted within 30 seconds via Kubernetes operator | < 30 second write downtime; synchronous replica ensures zero data loss | Chaos: DB primary pod deleted; promotion timed; confirmed under 30s |
| Duplicate transaction storm | Idempotency key in Redis (24h TTL) ensures each transaction_id scored exactly once | Cache hit returns in < 1ms; no reprocessing; no duplicate blocks | Load test: 10,000 identical transaction_ids; exactly 1 scored; all return cached result |
| Google OAuth unavailable | Email+password auth continues; Google OAuth button shows 'Temporarily unavailable' | Zero system downtime; OAuth-only users prompted to set password via OTP | Integration: Google JWKS mocked as 503; email auth confirmed working |

06  UI/UX Design Requirements
6.1  Design Philosophy
FraudShield's interface serves analysts working under extreme time pressure. A HIGH-risk alert must be reviewed in seconds. Every design decision prioritises speed of comprehension. Risk tier is always the most visually prominent element. SHAP explanations appear before action buttons — analysts must understand before they act.

6.2  Design System — Colour Tokens

| Token | Hex | MUI Override | Usage |
|---|---|---|---|
| risk.high.bg | #FEF2F2 | — | HIGH risk alert card and investigation drawer background |
| risk.high.border | #DC2626 | palette.error.main | HIGH card border — Tailwind animate-pulse 1200ms infinite |
| risk.high.badge | #DC2626 | — | 'HIGH RISK' MUI Chip; text label always present for WCAG compliance |
| risk.medium.bg | #FFFBEB | — | MEDIUM risk alert card background |
| risk.medium.border | #D97706 | palette.warning.main | MEDIUM card border (static — not pulsing) |
| risk.medium.badge | #D97706 | — | 'REVIEW' MUI Chip |
| risk.low.bg | #F0FDF4 | — | Approved transaction card in read-only history view |
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

6.3  Registration Form — Field Specification
All staff registration forms (all four roles) use these exact fields and MUI components. No field is optional unless stated.

| Field | MUI Component | Validation Rules | UX Behaviour |
|---|---|---|---|
| First Name | TextField required | Min 2, max 50 chars; letters + hyphens + apostrophes | Validates on blur; inline MUI FormHelperText error |
| Last Name | TextField required | Min 2, max 50 chars; letters + hyphens + apostrophes | Validates on blur; inline error |
| Email | TextField type='email' required | RFC 5322; async duplicate check debounced 500ms | 'Checking...' → tick or 'Email already registered' |
| Phone | MUI Select (flag + dial code, 249 countries, virtualised) + TextField local number | E.164 stored; digits only; 6–12 digits local part | flagcdn.com flags; keyboard navigable; aria-label on each option |
| Employee ID | TextField required | Alphanumeric 4–20 chars; UNIQUE; async check | Async uniqueness check; error if duplicate |
| Department | Select required | Options match institution departments | Dropdown; keyboard accessible |
| Password | TextField type='password' + show/hide IconButton | Min 8; 1 uppercase; 1 lowercase; 1 digit; 1 special char | MUI LinearProgress strength meter; 4 levels; failure reasons listed |
| Confirm Password | TextField type='password' + show/hide toggle | Must match Password; real-time validation | Submit disabled until match; immediate mismatch error below field |
| OR Divider | MUI Divider with 'or' text centred | — | Visual separator between Google Sign-In and email/password |
| Google Sign-In | MUI Button + Google SVG + 'Continue with Google'; full-width | Google ID token verified server-side against JWKS | Per Google brand guidelines; disabled + CircularProgress during OAuth loading |

6.4  Analyst Dashboard — Key Components

| Component | Specification | Interaction |
|---|---|---|
| Stats Header (sticky MUI AppBar) | [HIGH N — red] [MEDIUM N — amber] [Auto-blocked N] [Reviewed N] [Fraud Prevented RWF X] [Avg Review Xs] [Analysts Online N] | Real-time via WebSocket; click badge to filter feed; fraud amount animates on update |
| Alert Feed (MUI DataGrid virtualised) | Columns: risk badge \| score gauge \| amount + currency \| channel Chip \| masked account \| merchant \| timestamp \| timer (MEDIUM) \| actions | HIGH pinned top; new alert enters from right (Framer Motion 300ms); MEDIUM timer turns red at < 10s; virtual scroll for 10,000+ rows |
| Investigation Drawer (MUI Drawer right 560px) | MUI Tabs: Summary \| SHAP Explanation \| Account History \| Behavioural Fingerprint \| Network Graph | Opens within 200ms of row click; all actions available from inside drawer; main feed remains interactive |
| SHAP Waterfall Chart | Recharts HorizontalBarChart; features sorted by \|SHAP\| descending; red=increases fraud; green=decreases; base value + final score annotated | MUI Tooltip per bar: feature name + raw value + plain-English East African fraud context description |
| Action Panel (bottom of drawer) | MUI Button 'CONFIRM FRAUD' (red) + MUI Button 'MARK LEGITIMATE' (green) + MUI TextField comment (required min 10 chars) | Both buttons disabled until comment >= 10 chars; MUI CircularProgress on submit; undo Snackbar 5 seconds |
| MEDIUM Timer Badge | MUI Chip with seconds countdown; red at < 10s; Web Audio chime at < 5s if notifications enabled | setInterval every second; audio via Web Audio API only when user explicitly enabled notifications |
| Account History Timeline | MUI Timeline (vertical); channel MUI Chip + amount + timestamp + risk score Chip + outcome badge per event | Scrollable within panel; 30 transactions; sorted most recent first |
| Behavioural Fingerprint (4 panels) | MUI Grid 2x2: Recharts BarChart hourly patterns; Recharts Histogram amounts; Recharts PieChart channels; MUI Table comparison | Comparison table highlights anomalous cells in amber; 'normal' computed from last 90 days of account history |

07  Data Model — 13 Lean Tables (DBA-Reviewed)
Every column passed a senior DBA review. Columns that belong in Prometheus (latency metrics), MLflow (hyperparameters), the frontend (avatar URL), or are derivable by JOIN (COUNT, SUM) were removed. The result is a schema with no ambiguous columns and no stale denormalised data. All entities extend BaseEntity (id BIGSERIAL PK, created_at TIMESTAMPTZ NOT NULL auto-set by @CreatedDate).

| Table | Columns Kept | Key Reason for Each Removal |
|---|---|---|
| users | 11 of 18 | Removed: phone, country_code (analysts are staff — phone not needed for auth); avatar_url (cosmetic, frontend-derivable); employee_id, department (HR data, not fraud ops); last_login_at (analytics only); updated_at (no update workflow) |
| transactions | 11 of 16 | Removed: merchant_category_code (passed to ML as feature — not stored twice); received_at, processing_duration_ms (Prometheus metrics, not DB columns) |
| fraud_scores | 8 of 15 | Removed: xgboost_score, lightgbm_score (internal; ensemble_score is the decision signal); anomaly_score (collapses into requires_analyst_review); feature_vector (44-col JSONB bloats every row; never queried from DB); scoring_duration_ms (Prometheus); ml_unavailable_fallback (collapses into requires_analyst_review=true) |
| alert_queue_entries | 8 of 11 | Removed: assigned_at (derivable from updated_at on IN_REVIEW transition); escalated_to_tier, escalation_reason (belong in alert_decisions.analyst_comment) |
| alert_decisions | 7 of 10 | Removed: decision_duration_seconds (analytics/Prometheus); override_of_decision FK (unnecessary complexity — override is a new row, timeline read by ORDER BY created_at); updated_at (decisions are immutable) |
| auto_block_events | 8 of 14 | Removed: customer_sms_sent, customer_sms_sent_at (SMS delivery status → Kafka events/logs); unblocked_at (derivable from verified_at); account_frozen, account_frozen_at (business rule computed by RiskDecisionService from row count — no column needed) |
| account_velocity_cache | 9 of 15 | Removed: tx_count_7d, amount_sum_7d (computable from transactions on cache miss); unique_counterparties_24h (too specific, computable); last_tx_channel (on the transaction record); last_tx_at (duplicates updated_at); created_at (cache row, creation time meaningless) |
| model_versions | 11 of 20 | Removed: algorithm, xgb_weight, lgb_weight (hyperparameters → MLflow); ece, feature_importance, mlflow_run_id (MLflow artefacts); deployed_at, retired_at (derivable from is_production + updated_at) |
| fraud_campaigns | 8 of 12 | Removed: transaction_count, total_amount (always computed fresh via COUNT/SUM JOIN — storing creates stale data risk); closed_at (derivable from updated_at); updated_at (append-mostly table) |
| alert_rules | 8 of 12 | Removed: channel_scope (channel is a field in rule_expression JSON — separate column overcomplicates); last_triggered_at, trigger_count (Prometheus metrics); updated_at (rules immutable after creation) |
| audit_events | 9 of 13 | Removed: before_value, after_value (large JSONB; event_type + entity_id sufficient for Rwanda BNR compliance); ip_address, user_agent (PII storage without proportionate compliance benefit) |
| customer_verifications | 6 of 9 | Removed: verification_channel (always SMS_LINK — premature generalisation); ip_address_verified_from (PII); updated_at (verification immutable after verified_at is set) |
| refresh_tokens | 6 of 10 | Removed: created_by_ip (security nice-to-have, not core auth); oauth_provider (derivable from user.oauth_provider); user_agent (cosmetic); updated_at (token written once, only revoked) |

Table 1: users

| Column | Type | Constraint | Why This Column Exists |
|---|---|---|---|
| id | BIGSERIAL | PK | Auto-increment PK — BaseEntity |
| first_name | VARCHAR(50) | NOT NULL | Dashboard display and audit log denormalisation |
| last_name | VARCHAR(50) | NOT NULL | Dashboard display and audit log denormalisation |
| email | VARCHAR(254) | UNIQUE NOT NULL | Primary login identifier — RFC 5322 |
| password_hash | VARCHAR(72) | NULLABLE | BCrypt cost-12 hash; NULL for Google OAuth-only accounts |
| role | VARCHAR(30) | NOT NULL | ANALYST\|SENIOR_ANALYST\|RISK_OFFICER\|ADMIN — drives all @PreAuthorize |
| is_active | BOOLEAN | NOT NULL DEFAULT true | False blocks all authentication methods within 5 seconds |
| oauth_provider | VARCHAR(20) | NULLABLE | 'google' for OAuth accounts; NULL for password-only |
| oauth_id | VARCHAR(255) | NULLABLE | Google subject identifier — account lookup on OAuth callback |
| failed_login_count | INTEGER | NOT NULL DEFAULT 0 | Spring Security lockout: >= 5 locks account; resets on success |
| created_at | TIMESTAMPTZ | NOT NULL (auto) | BaseEntity — audit baseline |

| users — Key Indexes <br> UNIQUE INDEX on email <br> UNIQUE PARTIAL INDEX on (oauth_provider, oauth_id) WHERE oauth_provider IS NOT NULL |
|---|

Table 2: transactions  [TimescaleDB Hypertable partitioned by transaction_timestamp]

| Column | Type | Constraint | Why This Column Exists |
|---|---|---|---|
| id | BIGSERIAL | PK | BaseEntity |
| transaction_id | UUID | UNIQUE NOT NULL | External idempotency key from core banking system |
| account_id | VARCHAR(64) | NOT NULL | Tokenised account reference — indexed for velocity queries |
| counterparty_id | VARCHAR(64) | NOT NULL | Tokenised counterparty — counterparty fraud signal |
| amount | DECIMAL(18,4) | NOT NULL CHECK>0 | BigDecimal precision required for financial amounts |
| currency | CHAR(3) | NOT NULL | ISO 4217 — RWF, KES, USD etc |
| channel | VARCHAR(20) | NOT NULL | MOBILE_MONEY\|CARD\|AGENT_BANKING\|USSD\|ONLINE\|BANK_TRANSFER — core fraud feature |
| device_fingerprint | VARCHAR(128) | NULLABLE | Hashed device ID — NULL for USSD and AGENT_BANKING; account takeover signal |
| latitude | DECIMAL(9,6) | NULLABLE | Transaction origin latitude — geographic distance feature |
| longitude | DECIMAL(9,6) | NULLABLE | Transaction origin longitude — geographic distance feature |
| transaction_timestamp | TIMESTAMPTZ | NOT NULL | When payment occurred — TimescaleDB partition key |
| created_at | TIMESTAMPTZ | NOT NULL (auto) | BaseEntity |

Table 3: fraud_scores

| Column | Type | Constraint | Why This Column Exists |
|---|---|---|---|
| id | BIGSERIAL | PK | BaseEntity |
| transaction_id | BIGINT FK | UNIQUE NOT NULL → transactions | 1:1 — one score per transaction; cascade ALL |
| ensemble_score | DOUBLE | NOT NULL CHECK 0.0–1.0 | The decision signal — weighted average of XGBoost + LightGBM |
| risk_tier | VARCHAR(10) | NOT NULL | HIGH\|MEDIUM\|LOW — derived from ensemble_score vs configured thresholds |
| shap_top5 | JSONB | NULLABLE | Top-5 SHAP features for flagged alerts — mandatory for HIGH/MEDIUM by DB constraint |
| model_version | VARCHAR(50) | NOT NULL | Links score to model version for reproducibility and audit |
| requires_analyst_review | BOOLEAN | NOT NULL DEFAULT false | True when confidence low OR anomaly detector flags outlier |
| created_at | TIMESTAMPTZ | NOT NULL (auto) | BaseEntity |

| fraud_scores — CONSTRAINT <br> CHECK (risk_tier = 'LOW' OR shap_top5 IS NOT NULL)  — SHAP mandatory for every analyst-visible alert |
|---|

Table 4: alert_queue_entries

| Column | Type | Constraint | Why This Column Exists |
|---|---|---|---|
| id | BIGSERIAL | PK | BaseEntity |
| fraud_score_id | BIGINT FK | UNIQUE NOT NULL → fraud_scores | 1:1 — one queue entry per fraud score; cascade ALL |
| assigned_analyst_id | BIGINT FK | NULLABLE → users | ANALYST or SENIOR_ANALYST assigned to review; NULL when unassigned |
| priority_tier | VARCHAR(10) | NOT NULL | HIGH\|MEDIUM — copied from risk_tier at creation; LOW never enters queue |
| status | VARCHAR(30) | NOT NULL DEFAULT 'PENDING' | PENDING\|IN_REVIEW\|CONFIRMED_FRAUD\|MARKED_LEGITIMATE\|AUTO_RELEASED\|ESCALATED |
| review_deadline_at | TIMESTAMPTZ | NULLABLE | MEDIUM only: created_at + 30 seconds; NULL for HIGH; drives auto-release job |
| created_at | TIMESTAMPTZ | NOT NULL (auto) | BaseEntity |
| updated_at | TIMESTAMPTZ | NOT NULL (auto-updated) | Status transition timing — essential for SLA tracking and workflow audit |

Table 5: alert_decisions  [Immutable — append-only]

| Column | Type | Constraint | Why This Column Exists |
|---|---|---|---|
| id | BIGSERIAL | PK | BaseEntity |
| alert_queue_entry_id | BIGINT FK | NOT NULL → alert_queue_entries | The alert this decision was made on |
| analyst_id | BIGINT FK | NOT NULL → users | The user who made this decision |
| decision | VARCHAR(30) | NOT NULL | CONFIRM_FRAUD\|MARK_LEGITIMATE\|ESCALATE\|AUTO_RELEASED\|AUTO_BLOCKED\|SENIOR_OVERRIDE |
| analyst_comment | VARCHAR(2000) | NULLABLE | Mandatory (min 10 chars) for CONFIRM_FRAUD and MARK_LEGITIMATE — enforced by AlertService |
| false_positive_confirmed | BOOLEAN | NULLABLE | Set true by CustomerVerificationService — feeds ML retraining pipeline |
| created_at | TIMESTAMPTZ | NOT NULL (auto) | Immutable decision timestamp; no updated_at |

| alert_decisions — SENIOR_OVERRIDE Design <br> A senior override is a NEW ROW with decision=SENIOR_OVERRIDE on the same alert_queue_entry_id <br> No self-referential FK needed — full decision timeline is ORDER BY created_at on the entry <br> Application layer: no UPDATE or DELETE ever executed on this table |
|---|

Table 6: auto_block_events

| Column | Type | Constraint | Why This Column Exists |
|---|---|---|---|
| id | BIGSERIAL | PK | BaseEntity |
| transaction_id | BIGINT FK | UNIQUE NOT NULL → transactions | The transaction that was blocked; 1:1 |
| fraud_score_id | BIGINT FK | UNIQUE NOT NULL → fraud_scores | Score that triggered the block — audit traceability |
| blocked_at | TIMESTAMPTZ | NOT NULL | When auto-block was executed; explicit field (not created_at) for business clarity |
| block_reason | VARCHAR(200) | NOT NULL | e.g. 'ensemble_score 0.91 >= HIGH threshold 0.85' |
| customer_verified | BOOLEAN | NOT NULL DEFAULT false | True when customer completes SMS verification — triggers block lift in RiskDecisionService |
| verified_at | TIMESTAMPTZ | NULLABLE | When customer verified; drives unblock logic; NULL until verification occurs |
| created_at | TIMESTAMPTZ | NOT NULL (auto) | BaseEntity |

| auto_block_events — Account Freeze Note <br> Account freeze is a BUSINESS RULE in RiskDecisionService: <br> SELECT COUNT(*) FROM auto_block_events WHERE account_id=? AND blocked_at > NOW()-INTERVAL '1 hour' <br> IF count >= 3: call core banking freeze API. No column on this table needed. |
|---|

Table 7: account_velocity_cache  [Redis primary, PostgreSQL fallback]

| Column | Type | Constraint | Why This Column Exists |
|---|---|---|---|
| id | BIGSERIAL | PK | BaseEntity |
| account_id | VARCHAR(64) | UNIQUE NOT NULL | Tokenised account — one row per account |
| tx_count_60s | INTEGER | NOT NULL DEFAULT 0 | Highest-signal velocity feature — velocity fraud and account takeover |
| tx_count_1h | INTEGER | NOT NULL DEFAULT 0 | 1-hour velocity — account takeover pattern |
| tx_count_24h | INTEGER | NOT NULL DEFAULT 0 | 24-hour baseline — daily activity comparison |
| amount_sum_24h | DECIMAL(18,4) | NOT NULL DEFAULT 0 | Daily amount total — daily limit circumvention detection |
| last_tx_latitude | DECIMAL(9,6) | NULLABLE | Last known latitude — geographic distance feature for next transaction |
| last_tx_longitude | DECIMAL(9,6) | NULLABLE | Last known longitude — haversine distance computed by Python ML service |
| updated_at | TIMESTAMPTZ | NOT NULL | Cache freshness; rows with updated_at > 30 days purged by nightly job |

Table 8: model_versions

| Column | Type | Constraint | Why This Column Exists |
|---|---|---|---|
| id | BIGSERIAL | PK | BaseEntity |
| model_version | VARCHAR(50) | UNIQUE NOT NULL | Semantic version e.g. '2.1.0' — human-readable identifier; referenced by fraud_scores |
| training_date | TIMESTAMPTZ | NOT NULL | When training completed — model age and drift analysis |
| training_dataset_size | BIGINT | NOT NULL | Training example count — model quality context |
| auc_roc | DOUBLE | NOT NULL | Primary performance metric — deployment gate minimum 0.94 |
| precision_at_1pct_fpr | DOUBLE | NOT NULL | Precision at 1% FPR — controls analyst alert volume |
| recall | DOUBLE | NOT NULL | Fraud capture rate — deployment gate minimum 0.88 |
| f1_score | DOUBLE | NOT NULL | Harmonic mean — deployment gate minimum 0.80 |
| is_production | BOOLEAN | NOT NULL DEFAULT false | Exactly one row TRUE at any time — enforced by partial unique index |
| is_shadow | BOOLEAN | NOT NULL DEFAULT false | At most one row TRUE — shadow mode for canary validation |
| created_at | TIMESTAMPTZ | NOT NULL (auto) | BaseEntity |

| model_versions — Safety Indexes <br> CREATE UNIQUE INDEX uq_one_production ON model_versions(is_production) WHERE is_production = true <br> CREATE UNIQUE INDEX uq_one_shadow     ON model_versions(is_shadow)     WHERE is_shadow = true <br> ModelService.promoteModel() sets old is_production=false and new is_production=true in ONE @Transactional call |
|---|

Tables 9–13: Summary

| Table | Columns Kept | Purpose | Key Columns |
|---|---|---|---|
| fraud_campaigns | 8 | Group related fraudulent transactions into campaign clusters for Risk Officer review | campaign_name, shared_feature_type, shared_feature_value, status, closed_by_id (FK → users) |
| alert_rules | 8 | Allow Risk Officers to define custom detection rules without code deployment; evaluated by RuleService on every transaction | rule_name, rule_expression (JSONB DSL), risk_tier_override, is_active, created_by_id (FK → users) |
| audit_events (TimescaleDB hypertable) | 9 | Immutable compliance trail for BNR; every state-changing action recorded with denormalised user identity | user_id (no FK — denormalised), user_first_name, user_last_name, user_role, event_type, entity_type, entity_id, event_at |
| customer_verifications | 6 | One-time-use SMS verification token for customer to confirm auto-blocked transaction was legitimate | auto_block_event_id (FK UNIQUE), verification_token_hash (BCrypt), expires_at, verified_at |
| refresh_tokens | 6 | Store BCrypt-hashed refresh tokens for stateless JWT auth; revocable on logout or password change | user_id (FK), token_hash (UNIQUE BCrypt), expires_at, is_revoked |

08  Entity Relationships — Complete

| From Entity | Relationship | To Entity | Cascade | Business Rule |
|---|---|---|---|---|
| users | 1:N @OneToMany | refresh_tokens | ALL/LAZY | All tokens deleted when user deleted |
| users | 1:N @OneToMany | alert_queue_entries (analyst) | NONE/LAZY | Alert outlives the analyst assignment |
| users | 1:N @OneToMany | alert_decisions (analyst) | NONE/LAZY | Decisions persist after analyst deactivated |
| users | 1:N @OneToMany | alert_rules (created_by) | NONE/LAZY | Rules persist after creator leaves |
| users | 1:N @OneToMany | fraud_campaigns (closed_by) | NONE/LAZY | Campaigns persist after closer deactivated |
| transactions | 1:1 @OneToOne | fraud_scores | ALL/LAZY | Score cannot exist without transaction |
| transactions | 1:1 @OneToOne | auto_block_events | ALL/LAZY | Block event cannot exist without transaction |
| transactions | M:N @ManyToMany | fraud_campaigns | NONE/LAZY | Via fraud_campaign_transactions join table: (campaign_id, transaction_id, added_at) |
| fraud_scores | 1:1 @OneToOne | alert_queue_entries | ALL/LAZY | Queue entry created only for HIGH and MEDIUM scores |
| alert_queue_entries | 1:N @OneToMany | alert_decisions | ALL/LAZY | Decisions have no meaning without their alert |
| auto_block_events | 1:1 @OneToOne | customer_verifications | ALL/LAZY | Verification cannot exist without the block event |

09  Machine Learning Requirements
9.1  FraudShield-EAC-Transactions Dataset

| Requirement | Specification | Rationale |
|---|---|---|
| Total size | >= 5,000,000 labelled transactions (4.16M train / 520K val / 520K test) | Sufficient for XGBoost/LightGBM to learn rare fraud at realistic 0.87% fraud rate |
| Fraud rate | 0.87% overall; test set slightly higher (0.91%) to reflect growing fraud trend | Realistic imbalance; no SMOTE oversampling — tests model under genuine production conditions |
| Channel distribution | MOBILE_MONEY >= 40%, USSD >= 18%, AGENT_BANKING >= 14%, CARD 12%, ONLINE 9%, BANK_TRANSFER 6% | Reflects East African payment landscape; essential for generalisability across EAC |
| Fraud pattern diversity | 8 types: SIM-swap, account-takeover, agent-fraud, velocity-fraud, card-not-present, mule-account, merchant-fraud, synthetic-identity | Prevents model from overfitting to single dominant pattern; representative of East African threat landscape |
| Geographic coverage | Rwanda 42%, Kenya 28%, Tanzania 15%, Uganda 10%, DRC 5% | Covers all EAC primary financial markets; enables per-country evaluation |
| Temporal coverage | 24 months simulation; test set uses final 1.5 months (temporal holdout) | Prevents temporal leakage; tests generalisation to future fraud patterns |
| Feature completeness | All 44 features computable for >= 98% of records; < 2% missing any feature | Missing features handled by median imputation; completeness verified by pipeline unit test |
| Dataset release | FraudShield-EAC-Transactions; CC BY 4.0; HuggingFace Datasets Hub + Zenodo DOI | First public East African financial fraud benchmark dataset — primary research contribution |

9.2  ML Deployment Gate — All 13 Metrics Required
Every metric MUST pass before CI/CD permits model promotion. Shadow mode mandatory minimum 24 hours before full promotion. evaluate.py generates LaTeX table for the research paper.

| Metric | Threshold | Justification |
|---|---|---|
| AUC-ROC (last-3-month test set) | 0.940 | Primary ranking performance metric across all decision thresholds |
| Precision at 1% FPR | 0.720 | At 1% false positive rate — acceptable analyst alert volume |
| Recall (fraud capture rate) | 0.880 | Minimum proportion of actual fraud caught |
| F1 Score | 0.800 | Harmonic mean — balanced measure for imbalanced classes |
| False Positive Rate at threshold 0.85 | < 1.5% | At auto-block threshold — acceptable customer friction |
| False Negative Rate | < 12.0% | Maximum fraud allowed through at threshold 0.85 |
| MOBILE_MONEY channel AUC-ROC | 0.920 | East African dominant channel — explicit per-channel validation required |
| USSD channel AUC-ROC | 0.900 | USSD lacks device fingerprint — harder problem; must be validated separately |
| AGENT_BANKING channel AUC-ROC | 0.910 | Agent fraud is East African-specific — no published baseline; novel requirement |
| SHAP coverage | 100% for HIGH and MEDIUM | Non-negotiable for analyst trust and Rwanda BNR regulatory explainability |
| Expected Calibration Error (ECE) | < 0.050 | Probability scores must be calibrated — 0.85 score should mean ~85% fraud |
| Inference latency p99 | < 40ms (ensemble + SHAP) | Financial system requirement — exceeding this at load causes auto-block latency breach |
| Shadow mode AUC-ROC delta | New model within 1% of production | Prevents regression during model updates; minimum 24-hour shadow period enforced |

10  Infrastructure, DevOps, Containerisation, and Orchestration

| FraudShield Infrastructure Independence <br> FraudShield has its own independent infrastructure. It does NOT share Docker Compose, <br> Kubernetes namespace, GitHub Actions workflows, Prometheus/Grafana, or database server <br> with any other project. Every infrastructure file lives in: <br> github.com/mariusbayizere/fraudshield/infrastructure/ |
|---|

10.1  CI/CD Pipeline — GitHub Actions

| Stage | Trigger | Steps | Gate Criteria |
|---|---|---|---|
| Lint + Static Analysis | Every push | Checkstyle + SpotBugs (Java); ruff + mypy strict (Python); eslint + tsc (TypeScript); SonarQube quality gate | Zero errors; SonarQube quality gate passes; zero critical security hotspots |
| Unit Tests | Every push | JUnit 5 (Spring Boot >= 85% coverage); pytest (Python >= 90%); vitest (React) | All tests pass; no regressions; coverage thresholds met |
| Integration Tests | Push to develop/main | Spring Boot Testcontainers (PostgreSQL + TimescaleDB + Redis + Kafka); ML pipeline end-to-end; OAuth mock; full transaction pipeline | All integration tests pass; transaction pipeline latency within SLA; no Kafka message loss |
| Security Scan | Push to main | OWASP Dependency Check; Gitleaks secret detection; trivy container scan; OWASP ZAP API scan | Zero high-severity CVEs; no secrets in any file; 0 critical ZAP findings |
| ML Evaluation Gate | Change to ml/models/ directory | evaluate.py on held-out last-3-months test set; all 13 metrics validated | Deployment blocked if any metric below threshold; LaTeX metric table generated as CI artifact |
| Docker Build | Push to main | docker build: fraudshield-api (Spring Boot), fraudshield-ml (Python), fraudshield-frontend (React) | All 3 images build; api < 500MB; ml-scorer < 1.2GB; frontend < 150MB |
| Staging Deploy | Merge to main | Kubernetes rolling update to fraudshield-staging namespace; smoke test suite; /actuator/health check | All smoke tests pass; /actuator/health = UP; /api/v1/health/ml = UP |
| Production Deploy | Manual approval after staging + ML gate | 10% Kubernetes canary 30 minutes; auto-rollback if error rate > 0.5% or p99 > 80ms | Canary passes; full rollout; zero downtime confirmed; rollback if readiness fails within 120s |

10.2  Observability Stack

| Signal | Tool | Key Metrics | Alert Threshold |
|---|---|---|---|
| Application Metrics | Prometheus + Grafana | Transaction TPS, scoring p50/p95/p99, auto-block rate, false positive rate from verifications, Kafka consumer lag, analyst alert review time | TPS drop > 20%; p99 > 80ms; FP rate > 5%; Kafka lag > 5,000 messages |
| Structured Logs | structlog JSON (Python) + SLF4J Logback JSON (Java) | Every transaction scored (id, ensemble_score, risk_tier, model_version, duration_ms), every auto-block, every analyst decision, every model promotion | Any ERROR log triggers PagerDuty; auto-block rate > 3x baseline triggers immediate Risk Officer alert |
| ML Drift Monitoring | Hourly MLflow job + Grafana | Feature PSI per feature (Population Stability Index), model AUC-ROC from analyst labels, false positive rate trend | PSI > 0.2 for any top-10 feature triggers review; AUC-ROC drop > 0.03 triggers retraining evaluation |
| Regulatory Audit Trail | TimescaleDB audit_events read-only replica | Every transaction decision, auto-block, analyst action, model change — immutable, timestamped | 7-year retention; BNR-compliant; accessible to compliance officers; separate from production DB |
| Uptime | Prometheus blackbox + PagerDuty | HTTP probe /actuator/health every 30 seconds | 2 consecutive failures trigger PagerDuty P1 alert; measured against 99.9% SLA |
| Fraud Rate Anomaly | TimescaleDB continuous aggregate + alert | Hourly fraud rate vs 30-day moving average per channel | Fraud rate > 3 standard deviations from mean triggers Risk Officer notification within 5 minutes |

11  Testing Requirements

| Test Type | Framework | Coverage Target | Key Scenarios |
|---|---|---|---|
| Unit: Feature Engineering | pytest | All 44 features; all 6 channel types | USSD: 40 features computed without device_fingerprint; AGENT_BANKING agent features computed; round_sum_flag East African cultural norm handled; velocity correct with 0 historical transactions |
| Unit: ML Scoring | pytest + mock | All scoring paths; calibration; SHAP | Ensemble = XGB*0.55 + LGB*0.45 to 5 decimal places; SHAP sum = model output ± 0.001; SHAP absent for LOW; fallback activates when ML returns 503 |
| Unit: Risk Decision | pytest | All threshold combinations; circuit breakers | HIGH >= 0.85 always auto-blocks; MEDIUM timer starts correctly; LOW always approves; MCC circuit breaker triggers at exactly 5% in 15-minute window; account freeze at exactly 3rd HIGH event |
| Unit: Auth | pytest + JUnit 5 | All auth paths; role scoping; key scoping | JWT RS256 verified; expired token 401; API key returns 403 on analyst endpoints; Google JWKS mock; account linking by email; BCrypt cost 12 timing > 100ms |
| Integration: Transaction Pipeline | JUnit 5 + Testcontainers | Full pipeline end-to-end | Transaction ingested → Kafka → ML scored → risk decision → auto-block OR analyst alert within 50ms p95; duplicate: 100 submissions = 1 scored |
| Integration: Analyst Decision | pytest-asyncio + Playwright | Complete alert lifecycle | CONFIRM_FRAUD: block + audit_events row + comment stored; MARK_LEGITIMATE: false_positive_confirmed=true + customer verification link; ESCALATE: new AlertDecision row + target analyst notified |
| Integration: OAuth Flow | pytest + responses mock | OAuth happy path + failures | Google JWKS success; invalid token 401; account linking by email; new Google user creates ANALYST account; Google outage: email auth unaffected |
| Integration: Shadow Mode | pytest | Shadow alongside production | Both scores in audit log; only production score triggers actions; MLflow comparison updated |
| Performance: Load | Locust | 10,000 TPS for 5 minutes | All within latency SLA; Kafka lag < 500; 0 data loss; idempotency confirmed under load |
| Performance: ML | benchmark.py | 200 concurrent scoring requests | p50 < 15ms; p99 < 40ms; no memory growth > 50MB over 10,000 consecutive scorings |
| Frontend: Unit | Vitest + RTL | All analyst dashboard components | RiskGauge correct colour all score ranges; ChannelChip all 6 channels; SHAPWaterfallChart bars correct colour; ActionPanel disabled until comment >= 10 chars; TimerBadge countdown accurate |
| Frontend: E2E | Playwright (Chromium + Firefox + WebKit) | 5 critical analyst journeys | (1) Google OAuth → alert feed → HIGH pulse → drawer → SHAP renders → CONFIRM FRAUD → removed; (2) MEDIUM countdown → auto-release; (3) Analyst escalates to Risk Officer; (4) Admin promotes model; (5) Risk Officer views fraud heatmap |
| Security | OWASP ZAP + Gitleaks + Trivy + DB permission test | OWASP Top 10 + financial controls | 0 SQL injection; 0 XSS; audit_events UPDATE returns permission denied; API key returns 403 on analyst endpoints; 0 secrets in code |
| ML Evaluation Gate | evaluate.py | All 13 metrics in Section 09 | Automated CI gate; LaTeX table as artifact; shadow AUC-ROC delta computed and logged |

12  Per-Role Frontend Specification
React 18 + TypeScript 5 (strict) + Tailwind CSS + Material UI v5. Four separate dashboard experiences gated by role. JWT role claim extracted on login, stored in Zustand auth store, enforced by React Router v6 route guards. Every protected route re-validates JWT on mount via TanStack Query.

12.1  ANALYST

| Feature | Can Do | Cannot Do | Key MUI Components |
|---|---|---|---|
| Alert Feed | View own assigned alerts; filter by risk tier, date, status; real-time updates | Cannot see alerts assigned to other analysts; cannot see unassigned pool | MUI DataGrid (virtualised); MUI Chip (risk tier); MUI CircularProgress (score) |
| Alert Detail | SHAP waterfall; account history (last 30 tx); behavioural fingerprint; transaction detail | Cannot view raw PII — only masked account token | MUI Drawer (right 560px); Recharts HorizontalBarChart; MUI Timeline |
| Decision Panel | CONFIRM FRAUD or MARK LEGITIMATE (both require comment >= 10 chars); ESCALATE to SENIOR_ANALYST with reason | Cannot senior-override; cannot manage rules or thresholds | MUI Button (red/green); MUI TextField (comment); MUI Select (escalate) |
| Performance Panel | Own: alerts reviewed today, average review time, accuracy rate from customer verifications | Cannot view team performance | MUI Card (stats); Recharts LineChart (daily trend) |
| Profile | Update own first name, last name; change password; link/unlink Google OAuth; notification preferences | Cannot change own role | MUI TextField; MUI Switch; MUI Avatar (initials fallback) |

12.2  SENIOR_ANALYST

| Feature | Additional to ANALYST | Cannot Do | Key MUI Components |
|---|---|---|---|
| All Alerts View | View every alert regardless of assignment; see unassigned pool; filter by assigned analyst | Cannot configure thresholds or model versions | MUI DataGrid with 'Assigned To' column; analyst autocomplete filter |
| Alert Reassignment | Reassign any alert to any on-duty analyst; self-assign from unassigned pool | Cannot override RISK_OFFICER decisions | MUI Select (analyst dropdown) inline in alert card |
| Senior Override | Override any ANALYST decision with SENIOR_OVERRIDE + mandatory reason; creates new AlertDecision row | Cannot close fraud campaigns | MUI Dialog (confirmation); MUI TextField (reason, mandatory) |
| Team Performance | All analysts: reviews/hour, accuracy rate, avg review time; sortable; CSV export | Cannot modify analyst accounts | MUI DataGrid (team stats); MUI Button (CSV export) |
| Fraud Campaigns | View all campaigns; transactions per campaign; add analyst notes; escalate to RISK_OFFICER | Cannot close campaigns | MUI Accordion (campaign list); MUI Chip (transaction count, computed fresh) |

12.3  RISK_OFFICER

| Feature | Additional to SENIOR_ANALYST | Cannot Do | Key MUI Components |
|---|---|---|---|
| Portfolio Dashboard | Fraud prevented (RWF amount + count) today/week/month; fraud rate by channel; by MCC; Leaflet.js geographic heatmap; top-10 fraud counterparties | Cannot promote model versions | Recharts BarChart, PieChart, AreaChart; Leaflet.js (heatmap) |
| Model Performance Monitor | Live AUC-ROC from analyst labels; precision/recall/F1 trend charts; per-channel AUC-ROC; drift indicator vs baseline | Cannot manage user accounts or API keys | Recharts ComposedChart (trend); MUI Alert (drift warning) |
| Campaign Management | Close or reopen fraud campaigns; export transaction list as CSV; generate BNR campaign summary | Cannot promote or retire model versions | MUI DataGrid (campaign list); MUI Button (close/export) |
| Rule Management | Create alert rules via structured form (no code); enable/disable existing rules; view rule audit history | Cannot set HIGH threshold below 0.70 (server guardrail enforced) | MUI Stepper (rule wizard); MUI DataGrid (rule list); MUI Switch (enable/disable) |
| Threshold Management | Set HIGH/MEDIUM/LOW thresholds per channel (6 channels); effective within 60 seconds | Cannot override ML deployment gate | MUI Slider per channel; confirmation MUI Dialog |
| SAR Generation | Auto-generate SAR from CONFIRM_FRAUD; edit draft; export PDF in Rwanda BNR format | Cannot delete SAR drafts | MUI RichTextField (draft editor); MUI Button (export PDF) |

12.4  ADMIN

| Feature | Responsibility | Business Constraint | Key MUI Components |
|---|---|---|---|
| User List | All users: first name, last name, email, role, status, created date; pagination; filter by role/status | Cannot view password hashes — never returned by API | MUI DataGrid (full user list); MUI Chip (role badge); MUI IconButton (actions) |
| User Create | Create any role: first+last name, email, phone (country code flag + dial code selector), employee ID, department, role, password + confirm + strength meter; welcome email dispatched | Cannot create second ADMIN without approval workflow | MUI Stepper; MUI TextField; MUI Select (role); phone flag selector; MUI LinearProgress (strength) |
| User Edit | Update name, email; change role (audit logged); reset password OTP; link/unlink Google OAuth | Cannot edit another ADMIN's account | MUI TextField; MUI Select (role); MUI Button (reset, OAuth) |
| User Deactivate / Reactivate | Deactivate any account (all auth blocked within 5s); reactivate if needed; deactivation logged in audit with reason | Cannot permanently delete — regulatory retention requirement | MUI Switch; MUI Dialog (confirm + reason); MUI Alert (impact warning) |
| Model Management | List model versions with metrics; promote to production (gate enforced server-side); rollback; enable shadow mode | Cannot promote model that fails any Section 09 gate metric | MUI DataGrid (model list); MUI Stepper (promotion wizard); MUI Chip (PRODUCTION/SHADOW/RETIRED) |
| Dataset and Retrain | Upload labelled CSV (drag-drop); trigger retraining; monitor progress via WebSocket; model appears only after evaluate.py passes | Cannot trigger if training job already running | MUI FilePicker; MUI LinearProgress (training); WebSocket real-time progress |
| API Key Management | Create, view (last-4 chars only), revoke; raw key shown once at creation with copy button | Raw key never shown again after creation dialog dismissed | MUI DataGrid; MUI Dialog (key display + copy button); MUI Button (revoke) |
| Audit Log | Full searchable audit log; filter by user, date range, event type; export CSV; immutable (no edit or delete option shown) | Cannot modify audit entries — any attempt returns 403 even for ADMIN | MUI DataGrid (audit); MUI DatePicker (range); MUI TextField (search); MUI Button (CSV) |
| System Health | Kafka lag, ML scoring p50/p95/p99, API error rate, Redis hit rate, DB pool, alert queue depth; configurable alert thresholds | Cannot change infrastructure configuration from UI — requires Kubernetes/Terraform | MUI Grid of Recharts LineCharts; MUI LinearProgress (pool); MUI Alert (threshold breach) |

13  Cross-Device and Mobile Requirements
FraudShield must function fully on every device a user may carry — from a 320px Android Go browser to a 1536px+ desktop workstation. Mobile-first: every component is designed for 320px first; desktop is the enhancement.

13.1  Supported Device Matrix

| Device Category | Screen Width | OS / Browser | Example Devices | Network Assumed |
|---|---|---|---|---|
| Feature phone browser | 240–319px | KaiOS, Opera Mini | Nokia 3310 (KaiOS), Tecno Pop 1 | 2G EDGE (0.1 Mbps); SMS fallback required |
| Entry Android (Go Edition) | 320–374px | Android 10 Go, Chrome Lite | Tecno Spark Go 2022, Itel A23 Pro | 3G (1.6 Mbps, 150ms RTT) |
| Mid-range Android | 375–413px | Android 11-13, Chrome | Samsung Galaxy A14, Tecno Camon 19 | 3G to 4G (5–20 Mbps) |
| iPhone SE (smallest iOS) | 375px | iOS 15+, Safari | iPhone SE 2020 and 2022 | LTE / WiFi |
| Standard smartphone | 414–430px | Android 12-14, iOS 16+ | Galaxy S21 FE, iPhone 13, Pixel 6a | 4G / LTE |
| Large smartphone | 430–480px | Android / iOS | Galaxy A54, iPhone 15 Plus | 4G / LTE |
| Small tablet | 600–767px | Android tablet, Chrome; iPad mini | Samsung Galaxy Tab A7 Lite, iPad mini 6 | WiFi / 4G |
| Standard tablet | 768–1023px | iPadOS, Android, Chrome | iPad Air, Samsung Galaxy Tab S6 Lite | WiFi / 4G |
| Laptop | 1024–1279px | Chrome, Firefox, Edge, Safari | 13-inch MacBook, HP Pavilion | WiFi / Ethernet |
| Desktop workstation | 1280–1535px | Chrome, Firefox, Edge | Standard bank office PC | Ethernet / WiFi |
| Large monitor | 1536px+ | Any modern browser | 27-inch trading or reception desk display | Ethernet |

13.2  PWA Requirements

| PWA Requirement | Specification | Verification |
|---|---|---|
| Web App Manifest | manifest.json: name, short_name, start_url, display='standalone', background_color, theme_color, icons 192x192 and 512x512 PNG | Lighthouse PWA audit: Installable criterion passes; Android Chrome 'Add to Home Screen' appears |
| Service Worker | Workbox service worker; cache-first for app shell (HTML, CSS, JS bundle < 200KB) | DevTools: service worker Activated; app shell loads from cache when offline |
| Offline App Shell | Network unavailable: app shell from cache; offline banner ('No internet — some features unavailable') | Playwright: network disabled; app shell renders within 1 second; banner appears |
| Offline Data Cache | TanStack Query persists last successful API response to IndexedDB; stale data shown with 'Last updated: HH:MM' | IndexedDB populated after first call; stale data renders with banner when offline |
| Background Sync | Analyst decisions taken offline queued in IndexedDB; replayed on connectivity restoration | Background Sync API; sync fires within 30 seconds of reconnection |
| Install Prompt | Custom 'Install App' banner after 30 seconds on mobile; never shown on desktop | Appears after 30s on Android Chrome; dismissed state stored to localStorage for 30 days |
| iOS PWA | apple-mobile-web-app-capable, apple-mobile-web-app-status-bar-style, apple-touch-icon 180x180 | Installs from Safari Share on iOS 16+; full-screen launch; status bar brand colour |
| Push Notifications | Web Push API: HIGH risk alerts delivered when app backgrounded; requires user permission | Push received on Android when app backgrounded; tap opens relevant alert |
| Lighthouse PWA Score | >= 90 on mobile throttling (Moto G4 profile) | Playwright Lighthouse in CI; score stored as artifact; build fails if < 90 |

13.3  Mobile Performance Targets

| Metric | Target on 3G (Moto G4) | Target on 4G / WiFi | Tool |
|---|---|---|---|
| First Contentful Paint (FCP) | < 2.5 seconds | < 1.2 seconds | Lighthouse; Playwright CI with CPU 4x throttle + 3G network |
| Largest Contentful Paint (LCP) | < 4.0 seconds | < 2.0 seconds | Lighthouse; Core Web Vitals in Grafana RUM |
| Time to Interactive (TTI) | < 5.0 seconds | < 2.5 seconds | Lighthouse; main thread unblocked for user input |
| Total Blocking Time (TBT) | < 300ms | < 100ms | Lighthouse; no long JS tasks during page load |
| Cumulative Layout Shift (CLS) | < 0.1 | < 0.05 | Lighthouse; skeleton loaders prevent layout shift on data load |
| JS bundle (initial, gzipped) | < 200KB | < 200KB | Vite bundle analyser in CI; MUI tree-shaking required; build fails if exceeded |
| Offline app shell load | < 1.0 seconds | < 1.0 seconds | Service worker cache-first; Playwright with offline simulation |
| Memory on entry-level device | < 150MB RAM | < 300MB RAM | Chrome DevTools Memory tab; Playwright leak test over 50 interactions |

14  Research and Publication Plan
14.1  Artifact Release — All Open Source

| Artifact | Format | Licence | Platform |
|---|---|---|---|
| FraudShield-EAC-Transactions dataset | CSV + Parquet; 5M+ transactions; 44 features; 8 fraud types; 6 channels; 5 EAC countries | CC BY 4.0 | HuggingFace Datasets: mariusbayizere/fraudshield-eac-transactions + Zenodo DOI |
| Dataset datasheet | Markdown — Gebru et al. (2021) Datasheets for Datasets template | CC BY 4.0 | GitHub fraudshield/docs/ + arXiv appendix |
| Trained model weights | ONNX + MLflow model registry; scikit-learn compatible | Apache 2.0 | HuggingFace Model Hub: mariusbayizere/fraudshield-eac-model |
| SHAP analysis notebook | Jupyter; feature importance; partial dependence plots; per-channel SHAP analysis | Apache 2.0 | github.com/mariusbayizere/fraudshield/notebooks/shap_analysis.ipynb |
| Training + evaluation pipeline | train.py, evaluate.py (LaTeX output), feature_engineering.py, benchmark.py | Apache 2.0 | github.com/mariusbayizere/fraudshield/ml/ |
| Full system source | Spring Boot backend + Python ML + React TypeScript frontend + infrastructure | Apache 2.0 | github.com/mariusbayizere/fraudshield/ (STANDALONE) |
| Citation file | CITATION.cff with paper title, DOI, author, year | CC0 | github.com/mariusbayizere/fraudshield/CITATION.cff |

14.2  Publication Targets

| Venue | Type | Deadline | Strategic Fit |
|---|---|---|---|
| arXiv cs.LG + q-fin.RM | Preprint | Immediately after model training | Prior claim; dual-category; visible to ACL, KDD, IEEE S&P programme committees |
| ACM KDD 2027 Applied Data Science | Full paper (9 pages) | February 2027 | Primary target — top data mining venue; Applied DS track designed for production systems with real impact |
| IEEE S&P 2027 | Full paper (13 pages) | November 2026 | Top security venue; fraud detection + explainability + East African context is a strong novel combination |
| NeurIPS 2027 Datasets and Benchmarks | Full paper (9 pages) | June 2027 | Reach — FraudShield-EAC-Transactions is the first East African fraud benchmark; novelty argument is strong |
| ACM CCS 2027 | Full paper (12 pages) | May 2027 | Security and financial fraud; East African fintech context novel; SHAP explainability angle strong |
| Journal of Financial Crime | Journal article | Rolling | Specialist practitioner venue; East African focus highly novel; practitioner deployment impact |

15  Implementation Roadmap — Standalone FraudShield
FraudShield is built in its own repository starting Week 4 — after KinyaMed backend is stable. Each phase is complete and tested before the next begins.

| Phase | Week | Focus | Deliverables | Gate Criteria |
|---|---|---|---|---|
| Phase 0 | 4 | Repository scaffold + Spring Boot | github.com/mariusbayizere/fraudshield initialised; Spring Boot 3.2; Maven; Flyway migrations (all 13 tables); docker-compose.yml (PostgreSQL + TimescaleDB + Redis + Kafka); application.yml profiles | App starts; /actuator/health = UP; Flyway migrations run clean; all 13 tables exist with correct columns |
| Phase 1 | 4 | Feature engineering (Python) | All 44 features computed for all 6 channel types; Redis feature store populated; unit tests for every feature; feature documentation in docs/features.md | All 44 feature unit tests pass; p95 feature computation < 10ms on benchmark |
| Phase 2 | 4–5 | ML training + MLflow | XGBoost + LightGBM ensemble trained on 5M synthetic dataset; Isolation Forest; SHAP verified; MLflow configured; evaluate.py with LaTeX output | All 13 metrics in Section 09 met; SHAP 100% for HIGH/MEDIUM; AUC-ROC >= 0.94; ECE < 0.05 |
| Phase 3 | 5 | Risk decision engine + auto-block | RiskDecisionService; auto-block in < 50ms; MEDIUM 30-second timer; Africa's Talking SMS; customer verification; account freeze logic; MCC circuit breaker | Load test: 1,000 HIGH/sec all blocked within 50ms p95; SMS within 5s; freeze at exactly 3rd HIGH event |
| Phase 4 | 5 | React TypeScript frontend (all 4 roles) | Auth pages (login, OAuth, password reset); ANALYST alert feed + SHAP waterfall; SENIOR_ANALYST team panel + override; RISK_OFFICER portfolio + rules + SAR; ADMIN user CRUD + model management + API keys; PWA manifest + service worker | E2E Playwright passes all 5 journeys on 3 browsers; Lighthouse PWA >= 90; 0 Axe violations; all 4 role dashboards render on 375px mobile |
| Phase 5 | 6 | Risk officer + admin + model management | Portfolio dashboard (Leaflet heatmap); model promotion/rollback UI; custom rule wizard; SAR PDF; shadow mode toggle; fraud campaign panel | All FR-05 and FR-06 acceptance criteria pass; SAR PDF export verified; model gate blocks bad model |
| Phase 6 | 6 | Production infrastructure (standalone) | Kubernetes manifests (fraudshield/ namespace); GitHub Actions CI/CD with ML gate; Prometheus ServiceMonitor; Grafana dashboards as code; PagerDuty integration; security scan clean | Zero-downtime canary deploy; /actuator/health passes; ML gate blocks deliberately bad model; OWASP ZAP: 0 critical |
| Phase 7 | 6 | Research artifacts (independent release) | arXiv preprint cs.LG + q-fin.RM submitted; HuggingFace dataset + model published; GitHub repo polished; CITATION.cff; README with demo GIF and benchmark table | arXiv ID assigned; HuggingFace pages live; model card complete; GitHub README production-ready |

16  Glossary

| Term | Definition |
|---|---|
| AUC-ROC | Area Under the Receiver Operating Characteristic Curve; primary ML performance metric for FraudShield; measures ranking quality across all decision thresholds |
| Agent Banking | Financial service delivery through retail agents conducting cash-in/cash-out on behalf of banks; dominant rural payment channel in East Africa with unique fraud patterns |
| Auto-block | Automatic transaction blocking when ensemble_score >= HIGH threshold (default 0.85); executed within 50ms without analyst involvement |
| BNR | Banque Nationale du Rwanda; financial regulatory authority; requires transaction monitoring and SAR reporting from all institutions |
| BCrypt | Adaptive password hashing function; FraudShield uses cost factor 12; hashing takes > 100ms making brute-force attacks impractical |
| Calibration | Property where predicted probability scores match observed fraud rates; a calibrated score of 0.85 means ~85% of such transactions are fraudulent; ECE < 0.05 required |
| EAC | East African Community; Rwanda, Kenya, Tanzania, Uganda, DRC, Burundi; defines FraudShield's geographic scope and cross-border transaction normalisation |
| ECE | Expected Calibration Error; measures how well fraud probability scores reflect actual fraud rates; lower is better; deployment gate < 0.05 |
| Ensemble Model | Weighted combination of XGBoost (weight 0.55) + LightGBM (weight 0.45); calibrated probability scores; reduces variance vs single model |
| Feature Store | Redis-backed cache of pre-computed account velocity features updated after every transaction; eliminates per-request historical DB scans |
| FPR | False Positive Rate; proportion of legitimate transactions incorrectly flagged; controls analyst workload and customer friction |
| Hibernate | JPA implementation; manages ORM between Java entity classes and PostgreSQL; generates SQL from JPQL queries; used throughout FraudShield backend |
| Idempotency | Property ensuring duplicate transaction_id submissions within 24 hours return the cached result without reprocessing; enforced by Redis key with TTL |
| Isolation Forest | Unsupervised anomaly detection; identifies statistical outliers independent of supervised labels; catches novel fraud patterns outside training distribution |
| JPA | Java Persistence API; specification for ORM in Java; implemented by Hibernate; FraudShield uses Spring Data JPA repositories throughout |
| LightGBM | Light Gradient Boosting Machine by Microsoft; weight 0.45 in FraudShield ensemble; faster than XGBoost on high-cardinality categorical features |
| MCC | Merchant Category Code; 4-digit ISO 18245 code; used as fraud signal (some MCCs have elevated fraud rates); MCC circuit breaker triggers at 5% fraud rate in 15 minutes |
| MLflow | Open-source ML lifecycle platform; tracks all FraudShield experiments, hyperparameters, metrics, and model versions; enables reproducibility and safe model updates |
| Mobile Money | Mobile phone-based money transfer; dominant East African payment method: MTN Mobile Money, Airtel Money, M-Pesa; 40%+ of FraudShield transactions |
| MVC | Model-View-Controller; Spring Boot architectural pattern: Controller handles HTTP, Service handles business logic, Repository handles data access, Entity handles DB mapping |
| PSI | Population Stability Index; measures feature distribution shift between training and production data; PSI > 0.2 for any top-10 feature triggers model review |
| RBAC | Role-Based Access Control; four roles (ANALYST, SENIOR_ANALYST, RISK_OFFICER, ADMIN) enforced by Spring Security @PreAuthorize on every service method |
| SAR | Suspicious Activity Report; regulatory document required by BNR when fraud is confirmed; FraudShield auto-generates SAR draft from CONFIRM_FRAUD decisions |
| Shadow Mode | New model scores every transaction alongside production model; only production result triggers actions; safe validation before full promotion |
| SHAP | SHapley Additive Explanations; explains ML predictions by assigning feature contribution values; top-5 features stored in fraud_scores.shap_top5; mandatory for all analyst-visible alerts |
| SIM-swap Fraud | Fraudster obtains duplicate SIM card for victim's phone number; bypasses SMS 2FA; takes over mobile money account; dominant fraud type in Rwanda and Kenya |
| Spring Security 6 | Security framework for Spring Boot; handles stateless JWT validation, OAuth 2.0, BCrypt password encoding, and @PreAuthorize method-level RBAC |
| TimescaleDB | PostgreSQL extension for time-series data; used for transactions and audit_events hypertables; enables sub-second range queries over billions of records |
| USSD | Unstructured Supplementary Service Data; enables financial transactions on feature phones without internet; lacks device fingerprint — makes fraud detection harder |
| XGBoost | eXtreme Gradient Boosting; primary model in FraudShield ensemble (weight 0.55); excels on tabular financial data with engineered features |

FraudShield
Complete Software Requirements Specification  v4.0  ·  2026
V2 Functional + UI + ML + Infra + Testing + Roadmap  COMBINED WITH  V3 Engineering-Grade Schema + Spring Boot
Marius Bayizere  |  bayizeremarius119@gmail.com
github.com/mariusbayizere/fraudshield  ·  STANDALONE INDEPENDENT PROJECT
Independent Researcher, Kigali, Rwanda

17  Docker — Containerisation Strategy
Every FraudShield service is packaged as a Docker container. Containers guarantee identical behaviour across development, staging, and production environments. The FraudShield Docker strategy follows a multi-stage build pattern: the build stage compiles and tests; the runtime stage contains only the compiled artefact, no build toolchain. This keeps production images lean and eliminates an entire class of supply-chain vulnerabilities.

17.1  Docker Images — All Six Services

| Service | Base Image | Build Strategy | Max Size | Key Configuration |
|---|---|---|---|---|
| fraudshield-api | eclipse-temurin:21-jre-alpine (runtime) | Multi-stage: maven:3.9-eclipse-temurin-21 (build) → jre-alpine (runtime). Only the JAR is copied across. | < 280MB | Non-root user 'fraudshield' (UID 1000); HEALTHCHECK on /actuator/health; EXPOSE 8080 |
| fraudshield-ml | python:3.11-slim (runtime) | Multi-stage: python:3.11 (build, installs deps) → python:3.11-slim (runtime). Model weights volume-mounted or baked in. | < 1.2GB | Non-root user; HEALTHCHECK on /health; EXPOSE 8000; GPU optional via CUDA base image |
| fraudshield-frontend | node:20-alpine (build) → nginx:1.25-alpine (runtime) | Vite build in Node stage; only /dist served by Nginx. No Node.js in production image. | < 45MB | Nginx gzip enabled; cache headers: JS/CSS 1 year, HTML 0; EXPOSE 80 + 443 |
| fraudshield-worker | python:3.11-slim | Python Kafka consumer and campaign detection worker; same base as ML but no model weights. | < 300MB | Non-root user; no exposed ports (consumer only); HEALTHCHECK via custom health script |
| postgresql | postgres:16-alpine | Official image; init scripts for TimescaleDB extension creation and hypertable setup mounted via ConfigMap. | — | timescaledb-tune applied; data directory on PVC; pg_hba.conf from Secret |
| redis | redis:7-alpine | Official image; custom redis.conf: maxmemory-policy allkeys-lru; appendonly yes for durability. | — | requirepass from Secret; EXPOSE 6379; data directory on PVC |

17.2  Dockerfile Pattern — Spring Boot (fraudshield-api)

| # Stage 1: Build <br> FROM maven:3.9-eclipse-temurin-21 AS build <br> WORKDIR /app <br> COPY pom.xml . <br> # Download dependencies separately — cached layer unless pom.xml changes <br> RUN mvn dependency:go-offline -q <br> COPY src ./src <br> RUN mvn package -DskipTests -q <br> # Stage 2: Runtime — no Maven, no JDK, no source code <br> FROM eclipse-temurin:21-jre-alpine AS runtime <br> RUN addgroup -S fraudshield && adduser -S fraudshield -G fraudshield <br> WORKDIR /app <br> COPY --from=build /app/target/fraudshield-api-*.jar app.jar <br> USER fraudshield <br> EXPOSE 8080 <br> HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \ <br> CMD wget -qO- http://localhost:8080/actuator/health \| grep -q '"status":"UP"' <br> ENTRYPOINT ["java","-XX:+UseContainerSupport","-XX:MaxRAMPercentage=75.0", <br> "-Djava.security.egd=file:/dev/./urandom","-jar","app.jar"] |
|---|

17.3  Docker Compose — Local Development Environment
docker-compose.yml at the repository root starts the complete FraudShield stack on a developer laptop. One command: 'docker compose up -d'. All services start in dependency order. Hot reload is supported for the Spring Boot API and the React frontend.

| # docker-compose.yml — complete local dev environment <br> version: '3.9' <br> services: <br> postgres: <br> image: timescale/timescaledb:latest-pg16 <br> environment: <br> POSTGRES_DB: fraudshield_db <br> POSTGRES_USER: fraudshield <br> POSTGRES_PASSWORD: ${POSTGRES_PASSWORD} <br> volumes: <br> - postgres_data:/var/lib/postgresql/data <br> - ./infrastructure/sql/init.sql:/docker-entrypoint-initdb.d/init.sql <br> ports: ['5432:5432'] <br> healthcheck: <br> test: ['CMD', 'pg_isready', '-U', 'fraudshield'] <br> interval: 10s; timeout: 5s; retries: 5 <br> redis: <br> image: redis:7-alpine <br> command: redis-server --requirepass ${REDIS_PASSWORD} --appendonly yes <br> volumes: [redis_data:/data] <br> ports: ['6379:6379'] <br> kafka: <br> image: confluentinc/cp-kafka:7.6.0 <br> environment: <br> KAFKA_KRAFT_MODE: 'true' <br> KAFKA_PROCESS_ROLES: broker,controller <br> KAFKA_NODE_ID: 1 <br> KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092 <br> KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'true' <br> ports: ['9092:9092'] <br> api: <br> build: {context: ./backend, dockerfile: Dockerfile} <br> environment: <br> SPRING_PROFILES_ACTIVE: dev <br> SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/fraudshield_db <br> SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092 <br> REDIS_HOST: redis <br> JWT_RS256_PRIVATE_KEY: ${JWT_PRIVATE_KEY} <br> ports: ['8080:8080'] <br> depends_on: {postgres: {condition: service_healthy}} <br> volumes: ['./backend/src:/app/src']  # hot reload via spring-boot-devtools <br> ml-scorer: <br> build: {context: ./ml, dockerfile: Dockerfile} <br> environment: <br> MODEL_PATH: /models/current <br> REDIS_HOST: redis <br> volumes: ['./ml/models:/models'] <br> ports: ['8000:8000'] <br> depends_on: [redis] <br> frontend: <br> build: {context: ./frontend, dockerfile: Dockerfile, target: build} <br> command: npm run dev -- --host 0.0.0.0 <br> volumes: ['./frontend/src:/app/src']  # Vite HMR <br> ports: ['3000:3000'] <br> environment: {VITE_API_URL: 'http://localhost:8080'} <br> prometheus: <br> image: prom/prometheus:v2.51.0 <br> volumes: <br> - ./infrastructure/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml <br> ports: ['9090:9090'] <br> grafana: <br> image: grafana/grafana:10.4.0 <br> environment: <br> GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_PASSWORD} <br> volumes: <br> - ./infrastructure/grafana/dashboards:/etc/grafana/provisioning/dashboards <br> - grafana_data:/var/lib/grafana <br> ports: ['3001:3000'] <br> depends_on: [prometheus] <br> volumes: <br> postgres_data: {} <br> redis_data: {} <br> grafana_data: {} |
|---|

18  Kubernetes — Production Orchestration
FraudShield runs on Kubernetes in production. Kubernetes provides automatic horizontal scaling (HPA), zero-downtime rolling deployments, self-healing (automatic pod restart on failure), declarative configuration management, and network isolation between services. All FraudShield Kubernetes resources live in a dedicated 'fraudshield' namespace, completely isolated from any other project.

| Kubernetes Repository Structure: fraudshield/infrastructure/kubernetes/ <br> ├── namespace.yaml              fraudshield namespace definition <br> ├── secrets/                    External secrets — managed by External Secrets Operator <br> │   ├── postgres-secret.yaml    DB credentials from Kubernetes Secret Store <br> │   ├── redis-secret.yaml       Redis password <br> │   └── jwt-secret.yaml         RS256 key pair for JWT signing <br> ├── configmaps/ <br> │   ├── api-config.yaml         Non-sensitive Spring Boot config (Kafka brokers, Redis host) <br> │   └── prometheus-config.yaml  Prometheus scrape configuration <br> ├── deployments/ <br> │   ├── api-deployment.yaml     fraudshield-api: 2 replicas, HPA 2-10 <br> │   ├── ml-deployment.yaml      fraudshield-ml-scorer: 2 replicas, HPA 2-8 <br> │   ├── worker-deployment.yaml  fraudshield-worker: 1 replica (Kafka consumer) <br> │   └── frontend-deployment.yaml fraudshield-frontend: 2 replicas behind Nginx <br> ├── services/ <br> │   ├── api-service.yaml        ClusterIP — internal only <br> │   ├── ml-service.yaml         ClusterIP — internal only <br> │   └── frontend-service.yaml   ClusterIP — exposed via Ingress <br> ├── ingress/ <br> │   └── ingress.yaml            Nginx Ingress Controller + TLS via cert-manager <br> ├── hpa/ <br> │   ├── api-hpa.yaml            Scale 2-10 pods on CPU > 70% or custom Kafka lag metric <br> │   ├── ml-hpa.yaml             Scale 2-8 pods on CPU > 70% or scoring latency p95 > 20ms <br> │   └── worker-hpa.yaml         Scale 1-5 pods on Kafka consumer lag > 1000 messages <br> ├── pvc/ <br> │   ├── postgres-pvc.yaml       100Gi SSD persistent volume for PostgreSQL <br> │   └── redis-pvc.yaml          20Gi persistent volume for Redis AOF <br> ├── network-policies/ <br> │   └── network-policy.yaml     Only api can reach postgres; only ml can reach redis <br> └── monitoring/ <br> ├── servicemonitor-api.yaml  Prometheus ServiceMonitor for fraudshield-api <br> ├── servicemonitor-ml.yaml   Prometheus ServiceMonitor for fraudshield-ml <br> └── prometheusrule.yaml      Alert rules for SLA breach, Kafka lag, model drift |
|---|

18.1  Kubernetes Deployments — Specification

| Service | Replicas (Min/Max) | Resource Requests / Limits | HPA Trigger | Health Probes |
|---|---|---|---|---|
| fraudshield-api (Spring Boot) | 2 / 10 | Request: 500m CPU, 512Mi RAM  Limit: 2 CPU, 1Gi RAM | CPU > 70%  OR  custom metric: Kafka consumer lag > 500 messages | livenessProbe: GET /actuator/health/liveness (10s delay, 30s interval)  readinessProbe: GET /actuator/health/readiness (10s delay, 10s interval) |
| fraudshield-ml-scorer (Python) | 2 / 8 | Request: 1 CPU, 1Gi RAM  Limit: 4 CPU, 2Gi RAM | CPU > 70%  OR  custom metric: ML scoring latency p95 > 20ms (from Prometheus) | livenessProbe: GET /health (30s delay, 30s interval)  readinessProbe: GET /health/ready (30s delay, 10s interval) |
| fraudshield-worker (Kafka consumer) | 1 / 5 | Request: 200m CPU, 256Mi RAM  Limit: 1 CPU, 512Mi RAM | Kafka consumer lag on 'fs.transactions.raw' > 1,000 messages | livenessProbe: custom script checks Kafka consumer is actively processing (30s interval) |
| fraudshield-frontend (Nginx) | 2 / 6 | Request: 100m CPU, 128Mi RAM  Limit: 500m CPU, 256Mi RAM | CPU > 60% | livenessProbe: GET /health (Nginx returns 200)  readinessProbe: GET /health (10s interval) |

18.2  Key Kubernetes Manifest — API Deployment

| # api-deployment.yaml <br> apiVersion: apps/v1 <br> kind: Deployment <br> metadata: <br> name: fraudshield-api <br> namespace: fraudshield <br> labels: {app: fraudshield-api, tier: backend} <br> spec: <br> replicas: 2 <br> selector: <br> matchLabels: {app: fraudshield-api} <br> strategy: <br> type: RollingUpdate <br> rollingUpdate: <br> maxUnavailable: 0      # zero downtime — always full capacity during update <br> maxSurge: 1            # one extra pod during rollout <br> template: <br> metadata: <br> labels: {app: fraudshield-api} <br> annotations: <br> prometheus.io/scrape: 'true' <br> prometheus.io/path: '/actuator/prometheus' <br> prometheus.io/port: '8080' <br> spec: <br> serviceAccountName: fraudshield-api-sa <br> securityContext: <br> runAsNonRoot: true <br> runAsUser: 1000 <br> containers: <br> - name: api <br> image: ghcr.io/mariusbayizere/fraudshield-api:${VERSION} <br> ports: [{containerPort: 8080}] <br> resources: <br> requests: {cpu: 500m, memory: 512Mi} <br> limits:   {cpu: '2', memory: 1Gi} <br> envFrom: <br> - configMapRef: {name: fraudshield-api-config} <br> - secretRef:    {name: fraudshield-api-secrets} <br> livenessProbe: <br> httpGet: {path: /actuator/health/liveness, port: 8080} <br> initialDelaySeconds: 60; periodSeconds: 30; failureThreshold: 3 <br> readinessProbe: <br> httpGet: {path: /actuator/health/readiness, port: 8080} <br> initialDelaySeconds: 30; periodSeconds: 10; failureThreshold: 3 <br> lifecycle: <br> preStop:                 # graceful shutdown — drain in-flight requests <br> exec: {command: ['sleep', '15']} <br> terminationGracePeriodSeconds: 30 |
|---|

18.3  Horizontal Pod Autoscaler — API

| # api-hpa.yaml <br> apiVersion: autoscaling/v2 <br> kind: HorizontalPodAutoscaler <br> metadata: <br> name: fraudshield-api-hpa <br> namespace: fraudshield <br> spec: <br> scaleTargetRef: <br> apiVersion: apps/v1 <br> kind: Deployment <br> name: fraudshield-api <br> minReplicas: 2 <br> maxReplicas: 10 <br> metrics: <br> - type: Resource <br> resource: <br> name: cpu <br> target: {type: Utilization, averageUtilization: 70} <br> - type: External <br> external:  # scale on Kafka consumer lag via Prometheus adapter <br> metric: <br> name: kafka_consumer_lag_sum <br> selector: <br> matchLabels: {topic: fs.transactions.raw, group: fraudshield-api} <br> target: {type: AverageValue, averageValue: '500'} <br> behavior: <br> scaleUp: <br> stabilizationWindowSeconds: 60   # wait 60s before scaling up again <br> policies: <br> - type: Pods; value: 2; periodSeconds: 60  # add max 2 pods per minute <br> scaleDown: <br> stabilizationWindowSeconds: 300  # wait 5min before scaling down <br> policies: <br> - type: Pods; value: 1; periodSeconds: 120 # remove max 1 pod per 2min |
|---|

18.4  Ingress — TLS Termination and Routing

| # ingress.yaml <br> apiVersion: networking.k8s.io/v1 <br> kind: Ingress <br> metadata: <br> name: fraudshield-ingress <br> namespace: fraudshield <br> annotations: <br> kubernetes.io/ingress.class: nginx <br> cert-manager.io/cluster-issuer: letsencrypt-prod <br> nginx.ingress.kubernetes.io/rate-limit: '100'           # 100 req/min per IP <br> nginx.ingress.kubernetes.io/proxy-read-timeout: '300' <br> nginx.ingress.kubernetes.io/enable-cors: 'true' <br> nginx.ingress.kubernetes.io/cors-allow-origin: 'https://fraudshield.rw' <br> spec: <br> tls: <br> - hosts: [fraudshield.rw, api.fraudshield.rw] <br> secretName: fraudshield-tls <br> rules: <br> - host: fraudshield.rw <br> http: <br> paths: <br> - path: / <br> pathType: Prefix <br> backend: {service: {name: fraudshield-frontend, port: {number: 80}}} <br> - host: api.fraudshield.rw <br> http: <br> paths: <br> - path: /api <br> pathType: Prefix <br> backend: {service: {name: fraudshield-api, port: {number: 8080}}} <br> - path: /ws <br> pathType: Prefix <br> backend: {service: {name: fraudshield-api, port: {number: 8080}}} |
|---|

18.5  Network Policy — Zero-Trust Service Isolation

| # network-policy.yaml — only allow necessary traffic between services <br> apiVersion: networking.k8s.io/v1 <br> kind: NetworkPolicy <br> metadata: <br> name: fraudshield-network-policy <br> namespace: fraudshield <br> spec: <br> podSelector: {}  # applies to ALL pods in fraudshield namespace <br> policyTypes: [Ingress, Egress] <br> ingress: <br> # API: allow from Ingress controller + Prometheus only <br> - from: [{namespaceSelector: {matchLabels: {name: ingress-nginx}}}] <br> ports: [{port: 8080}] <br> - from: [{namespaceSelector: {matchLabels: {name: monitoring}}}] <br> ports: [{port: 8080}, {port: 8000}]  # Prometheus scrape <br> egress: <br> # All pods: allow DNS resolution <br> - to: [{namespaceSelector: {}}] <br> ports: [{port: 53, protocol: UDP}] <br> # API: allow to PostgreSQL, Redis, Kafka, ML scorer <br> - to: [{podSelector: {matchLabels: {app: postgres}}}] <br> ports: [{port: 5432}] <br> - to: [{podSelector: {matchLabels: {app: redis}}}] <br> ports: [{port: 6379}] <br> - to: [{podSelector: {matchLabels: {app: kafka}}}] <br> ports: [{port: 9092}] <br> - to: [{podSelector: {matchLabels: {app: fraudshield-ml-scorer}}}] <br> ports: [{port: 8000}] |
|---|

19  Prometheus and Grafana — Observability Platform
FraudShield's observability stack is built on Prometheus for metrics collection and Grafana for visualisation. Both run inside the Kubernetes cluster in a dedicated 'monitoring' namespace. Prometheus scrapes metrics from every FraudShield service via ServiceMonitor resources. Grafana dashboards are provisioned as code — they are stored in the repository and applied automatically on Grafana startup.

19.1  Prometheus Configuration

| What Prometheus Scrapes from FraudShield <br> fraudshield-api      — Spring Boot Actuator /actuator/prometheus (Micrometer metrics) <br> Key metrics: http_server_requests_seconds, jvm_memory_used_bytes, <br> kafka_consumer_lag, fraud_score_ensemble_score_histogram, <br> auto_block_events_total, analyst_decision_duration_seconds <br> fraudshield-ml       — Custom Python /metrics endpoint (prometheus_client library) <br> Key metrics: ml_scoring_duration_seconds, shap_computation_seconds, <br> model_ensemble_score_histogram, feature_engineering_duration_seconds <br> fraudshield-worker   — Custom Python /metrics endpoint <br> Key metrics: kafka_messages_consumed_total, campaign_detection_duration_seconds <br> kafka                — JMX Exporter for Kafka broker metrics <br> Key metrics: kafka_consumer_lag_sum, kafka_network_request_rate <br> postgres             — postgres_exporter sidecar <br> Key metrics: pg_stat_activity_count, pg_slow_queries_total <br> redis                — redis_exporter sidecar <br> Key metrics: redis_memory_used_bytes, redis_keyspace_hits_total <br> node-exporter        — CPU, memory, disk I/O per Kubernetes node |
|---|

19.2  PrometheusRule — Alert Definitions

| # prometheusrule.yaml — all alert definitions as code in the repository <br> apiVersion: monitoring.coreos.com/v1 <br> kind: PrometheusRule <br> metadata: <br> name: fraudshield-alerts <br> namespace: fraudshield <br> spec: <br> groups: <br> - name: fraudshield.sla <br> rules: <br> - alert: AutoBlockLatencyBreached <br> expr: \| <br> histogram_quantile(0.95, <br> rate(fraudshield_auto_block_duration_seconds_bucket[5m])) > 0.050 <br> for: 2m <br> labels: {severity: critical, team: fraud-engineering} <br> annotations: <br> summary: 'Auto-block p95 latency breached 50ms SLA' <br> description: 'Current p95: {{ $value \| humanizeDuration }}' <br> - alert: MLScoringSlaSBreached <br> expr: \| <br> histogram_quantile(0.99, <br> rate(ml_scoring_duration_seconds_bucket[5m])) > 0.040 <br> for: 2m <br> labels: {severity: warning} <br> annotations: {summary: 'ML scoring p99 breached 40ms SLA'} <br> - alert: KafkaConsumerLagCritical <br> expr: kafka_consumer_lag_sum{group='fraudshield-api'} > 5000 <br> for: 5m <br> labels: {severity: critical} <br> annotations: {summary: 'Kafka consumer lag > 5,000 messages for 5 minutes'} <br> - alert: FraudRateAnomaly <br> expr: \| <br> rate(fraudshield_auto_block_events_total[15m]) / <br> rate(fraudshield_transactions_ingested_total[15m]) > 0.05 <br> for: 5m <br> labels: {severity: critical} <br> annotations: {summary: 'Fraud rate exceeds 5% — possible attack in progress'} <br> - alert: ModelDriftDetected <br> expr: fraudshield_model_auc_roc < 0.91 <br> for: 60m <br> labels: {severity: warning} <br> annotations: {summary: 'Model AUC-ROC dropped below 0.91 for 60 minutes'} <br> - alert: PodDown <br> expr: kube_deployment_status_replicas_available{namespace='fraudshield'} == 0 <br> for: 1m <br> labels: {severity: critical} <br> annotations: {summary: 'All replicas of {{ $labels.deployment }} are down'} |
|---|

19.3  Grafana — Dashboards as Code
All Grafana dashboards are stored as JSON files in fraudshield/infrastructure/grafana/dashboards/ and provisioned automatically on startup. No dashboard is created manually. This means every dashboard survives a Grafana pod restart and can be code-reviewed before deployment.

| Dashboard | Panels Included | Refresh Rate | Primary Audience |
|---|---|---|---|
| FraudShield Operations Overview | Transaction TPS (line chart), auto-block rate (gauge), p50/p95/p99 latency (stat panels), Kafka consumer lag (time series), active analyst sessions (stat), fraud rate trend (area chart), system uptime (stat) | 10 seconds | On-call engineer, Risk Officer |
| ML Scoring Performance | Ensemble score distribution (histogram), SHAP computation latency (heatmap), model version in production (stat), AUC-ROC from analyst labels (time series), feature engineering latency percentiles (line chart), ML pod CPU/memory (resource panels) | 30 seconds | ML Engineer, Admin |
| Analyst Operations Dashboard | Alerts reviewed per hour (bar chart), average review time per analyst (bar chart), CONFIRM_FRAUD vs MARK_LEGITIMATE ratio (pie chart), backlog size over time (line chart), escalation rate (stat), false positive rate from verifications (gauge) | 60 seconds | Risk Officer, Admin |
| Infrastructure Health | Kubernetes pod status per service (table), CPU utilisation per pod (heatmap), memory utilisation per pod (heatmap), PostgreSQL connection pool usage (gauge), Redis hit rate (stat), Redis memory usage (gauge), Kafka partition distribution (table) | 30 seconds | On-call engineer, Admin |
| Security and Audit | Failed login attempts per hour (bar chart), API key usage per key (table), audit events by type (pie chart), geographic distribution of API requests (world map), blocked IP addresses (table), suspicious login patterns (time series) | 5 minutes | Security team, Admin |
| Daily/Weekly/Monthly Report | See Section 20 — Operational Reporting for full panel specification | Generated on schedule | Risk Officer, Admin, Management |

20  Operational Reporting — Daily, Weekly, and Monthly
FraudShield generates three tiers of automated operational reports. Daily reports feed the morning operations review. Weekly reports inform Risk Officer strategy decisions. Monthly reports go to senior management and the Rwanda BNR compliance team. All reports are generated from TimescaleDB continuous aggregates and Prometheus query snapshots — no manual data collection required.

20.1  Daily Operations Report
Generated every day at 06:00 Rwanda time (UTC+2) by a Celery scheduled task. Delivered to Risk Officers and the on-call engineer via email and available in the Grafana dashboard 'Daily/Weekly/Monthly Report'. Covers the previous calendar day (00:00 to 23:59 Rwanda time).

| Report Section | Metrics Included | Data Source | Business Purpose |
|---|---|---|---|
| Transaction Volume | Total transactions ingested; breakdown by channel (MOBILE_MONEY, USSD, AGENT_BANKING, CARD, ONLINE, BANK_TRANSFER); peak hour of the day; comparison to 7-day average | TimescaleDB: COUNT(*) FROM transactions WHERE transaction_timestamp::date = yesterday GROUP BY channel | Understand daily volume patterns; detect unusual volume drops (possible data pipeline failure) |
| Fraud Detection Summary | Total HIGH risk auto-blocked (count + RWF amount); total MEDIUM risk sent to analyst review; total LOW risk approved; overall fraud rate (%); comparison to previous 7 days | TimescaleDB: fraud_scores joined with transactions; count by risk_tier | Primary KPI for Risk Officer — how much fraud was caught and how much money was protected |
| Auto-Block Performance | Auto-block events: count, total RWF amount blocked; customer verifications received (count + % of blocks); false positives confirmed (count + % of verifications); average time from block to customer verification | TimescaleDB: auto_block_events + customer_verifications | Measures the auto-block system effectiveness and customer friction level |
| Analyst Performance | Alerts reviewed (total + by analyst: first name, last name); CONFIRM_FRAUD decisions; MARK_LEGITIMATE decisions; average review time (seconds) per analyst; backlog at end of day; escalations | TimescaleDB: alert_decisions + alert_queue_entries JOIN users | Assess analyst workload and identify analysts who may need support or training |
| Top Fraud Patterns | Top-5 fraud types by count; top-5 MCC codes by fraud rate; top-5 counterparty accounts by HIGH risk count; top-5 device fingerprints appearing in multiple accounts | TimescaleDB: fraud_scores + transactions; aggregated by shared feature | Intelligence for Risk Officer to tune alert rules and thresholds |
| System Performance | API p50/p95/p99 latency (from Prometheus); ML scoring p50/p95/p99; Kafka consumer max lag; any SLA breaches during the day; system uptime percentage | Prometheus query API via reporting service | Engineering health check — ensures system performed within SLA all day |
| Active Fraud Campaigns | New campaigns detected during the day; campaigns closed; currently active campaigns (count + total RWF exposure); most active campaign summary | TimescaleDB: fraud_campaigns + fraud_campaign_transactions | Risk intelligence briefing for morning operations review |

20.2  Weekly Operations Report
Generated every Monday at 07:00 Rwanda time by the same Celery task. Covers the previous Monday to Sunday. Delivered to Risk Officers, Admin, and department management via email PDF. Also available as a Grafana snapshot link. Contains all daily report sections in 7-day aggregated form plus the following additional sections:

| Additional Weekly Section | Metrics Included | Data Source | Business Purpose |
|---|---|---|---|
| 7-Day Trend Analysis | Transaction volume trend (7-day bar chart); fraud rate trend (7-day line chart); analyst review time trend; auto-block rate trend; false positive rate trend; comparison to previous 4 weeks | TimescaleDB: daily aggregates from analytics_daily or computed from transactions + fraud_scores | Identify trends that daily reports miss — rising fraud rate, analyst burnout signal, degrading system performance |
| Channel Risk Analysis | Fraud rate per channel this week vs last week vs 4-week average; highest-risk channel; channel with fastest growing fraud rate; RWF amount blocked per channel | TimescaleDB: GROUP BY channel + DATE_TRUNC('week') | Informs per-channel threshold adjustments — which channels need tighter or looser thresholds |
| Alert Rule Performance | Each active alert rule: times triggered this week, true positive count (CONFIRM_FRAUD decisions), false positive count (MARK_LEGITIMATE after rule trigger), precision per rule; rules with precision < 50% flagged for review | TimescaleDB: alert_decisions JOIN alert_queue_entries; filter by rule_triggered_by | Rule governance — prune ineffective rules, strengthen effective ones |
| Model Performance This Week | AUC-ROC from analyst decisions (7-day aggregate); precision and recall trend; false positive rate trend; comparison to model deployment baseline; feature drift PSI summary | TimescaleDB: alert_decisions; Prometheus: ML scoring metrics | Early warning of model degradation before it breaches deployment gate thresholds |
| EAC Geographic Summary | Fraud events by country (Rwanda, Kenya, Tanzania, Uganda, DRC); cross-border transaction volume; cross-border fraud rate; EAC corridor flags triggered | TimescaleDB: transactions with geographic data; fraud_scores JOIN transactions | Regulatory intelligence for BNR reporting and cross-border risk assessment |
| Analyst Team Performance | Full week breakdown per analyst: alerts reviewed, decisions, accuracy rate, average review time, escalations sent and received; comparison to team average | TimescaleDB: alert_decisions JOIN users (first_name, last_name) | Management visibility on team performance; identify outliers (very fast = cutting corners, very slow = needs support) |

20.3  Monthly Compliance and Operations Report
Generated on the 1st of each month at 08:00 Rwanda time. Covers the previous calendar month. This is the primary report for Rwanda BNR regulatory compliance. Delivered as a professionally formatted PDF to: Risk Officers, Admin, department management, and the BNR compliance submission contact. The PDF is auto-generated from a Jinja2 template populated from TimescaleDB and Prometheus data.

| Monthly Report Section | Metrics Included | Regulatory Relevance | Format |
|---|---|---|---|
| Executive Summary | Total transactions processed; total fraud prevented (count + RWF amount); fraud rate (%); comparison to previous month and year-to-date; system uptime; key incidents summary | BNR monthly transaction monitoring summary requirement | Single-page summary table + 3 headline KPI gauges |
| Transaction Volume Analysis | Daily transaction volume (30-day chart); volume by channel (stacked bar); volume by EAC country; peak day and peak hour; month-over-month growth rate; year-over-year comparison | BNR: monthly transaction volume disclosure | Bar charts + summary table |
| Fraud Detection Report | HIGH risk auto-blocked: count, RWF amount, % of total volume; MEDIUM risk reviewed: count, RWF amount; analyst confirmed fraud: count, RWF amount; false positives: count, RWF amount; total fraud prevented (auto-block + analyst confirmed) | BNR: fraud reporting obligation — suspected fraud cases and amounts | Summary table + pie chart (fraud type breakdown) |
| SAR (Suspicious Activity Report) Summary | Number of SARs filed this month; total RWF value reported; SARs by fraud type; SARs by channel; SAR submission dates and reference numbers | BNR: SAR filing requirement for financial institutions — required by law | Table of all SARs filed; status (submitted, pending); export as BNR-format CSV |
| Customer Impact Metrics | Customers whose transactions were auto-blocked (unique count); customers who verified and were unblocked; customers who did not respond (presumed legitimate or unreachable); average time to unblock; customer complaints related to false blocks | Customer protection obligation; BNR service quality monitoring | Table + timeline chart of block-to-resolution times |
| Model Governance Report | Model version active this month; model version changes during month (promotions, rollbacks); AUC-ROC at month start, mid, and end; false positive rate trend; SHAP top-10 features for confirmed fraud; model training data age | BNR: algorithmic accountability for automated fraud decisions | Model card summary table; AUC-ROC trend chart |
| System Availability and SLA | System uptime % (target: 99.9%); SLA breaches: count, duration, impact; auto-block latency SLA compliance (% of auto-blocks within 50ms); ML scoring latency compliance | BNR: system reliability reporting for payment processing systems | SLA compliance table; uptime calculation methodology |
| Alert Rule Changes | Rules created this month (name, created by, date, risk tier); rules disabled; rules triggered count; rules with precision < 50% (flagged for review next month) | Internal governance; audit trail for automated decision changes | Table of rule changes with audit trail reference |
| Access Control and Security Audit | New user accounts created; accounts deactivated; role changes; failed login attempts (trend); API key rotations; Google OAuth sessions; security scan results this month | BNR: access control documentation requirement | Summary table; audit log export (CSV attachment) |

20.4  Report Delivery and Storage

| Delivery Method | Description | Recipients | Retention |
|---|---|---|---|
| Email (PDF attachment) | Celery task generates PDF using WeasyPrint from Jinja2 HTML template; attached to email sent via SMTP; email template includes KPI summary in email body so recipients see headline numbers without opening PDF | Daily: Risk Officers + on-call engineer  Weekly: Risk Officers + Admin + Management  Monthly: Risk Officers + Admin + Management + BNR compliance contact | Emails retained by recipient mail server; PDF also stored in S3-compatible object storage |
| Grafana Dashboard | Grafana Reporting plugin generates PNG snapshot of operational dashboard; snapshot URL included in email; interactive version available at https://grafana.fraudshield.rw (VPN required) | All authenticated Grafana users (Risk Officers and above) | Grafana snapshots retained 90 days; historical Prometheus data retained 365 days |
| Object Storage (S3) | Every generated PDF report saved to S3-compatible bucket: reports/{year}/{month}/{daily\|weekly\|monthly}/fraudshield-report-YYYY-MM-DD.pdf | Admin can download from S3 via Admin Panel 'Reports' tab | Daily reports: 90 days  Weekly reports: 1 year  Monthly reports: 7 years (BNR regulatory requirement) |
| Admin Panel — Reports Tab | New 'Reports' tab in FraudShield Admin Panel allows Admin and Risk Officers to: view all generated reports by date range; download any historical report; trigger on-demand report generation for custom date range; view report generation status | ADMIN and RISK_OFFICER roles | All reports accessible while stored in S3 |
| API Endpoint | GET /api/v1/reports/{type}?from=&to= — returns JSON report data for programmatic consumption; authenticated (RISK_OFFICER+ required); supports type: daily, weekly, monthly, custom | Programmatic consumers (BI tools, BNR reporting system integration) | Same as S3 retention |

20.5  Functional Requirements — Reporting (FR-08)

| ID | Requirement | Priority | Acceptance Criterion |
|---|---|---|---|
| FR-08-01 | Daily report auto-generated at 06:00 Rwanda time; covers previous calendar day; delivered by email to all RISK_OFFICER and ADMIN users | M | Celery task confirmed running at 06:00 UTC+2; email delivered within 5 minutes of trigger; all 7 daily sections present in PDF |
| FR-08-02 | Weekly report auto-generated every Monday at 07:00 Rwanda time; covers previous Mon-Sun; delivered by email as PDF | M | Email delivered with PDF; all 7 additional weekly sections present; 7-day charts accurate against TimescaleDB |
| FR-08-03 | Monthly report auto-generated on 1st of month at 08:00 Rwanda time; BNR-formatted; all 9 sections present including SAR summary table | M | PDF generated within 10 minutes of trigger; SAR summary matches alert_decisions WHERE decision=CONFIRM_FRAUD for the month; BNR format validated against template |
| FR-08-04 | Admin Panel 'Reports' tab: list all reports by date; download any report; trigger custom date range report | M | Reports tab visible for ADMIN and RISK_OFFICER roles; download completes within 10 seconds; custom report generates within 5 minutes |
| FR-08-05 | Custom date range report: any date range up to 90 days; triggered from Admin Panel; results in same format as daily report but spanning the custom range | S | Report generated correctly for any date range; TimescaleDB aggregation accurate; PDF correctly labelled with date range |
| FR-08-06 | All reports stored in S3-compatible object storage with correct retention periods: daily 90 days, weekly 1 year, monthly 7 years | M | S3 lifecycle policy verified; objects deleted after retention period; monthly reports confirmed 7-year retention for BNR compliance |
| FR-08-07 | Report generation API: GET /api/v1/reports/{type}?from=&to= returns JSON; requires RISK_OFFICER+ JWT; supports daily, weekly, monthly, custom | S | API returns correct JSON structure; 403 for ANALYST role; date validation rejects invalid ranges; response within 30 seconds for 30-day range |
| FR-08-08 | Grafana dashboard 'Daily/Weekly/Monthly Report' auto-refreshes and shows current period data; accessible at https://grafana.fraudshield.rw | S | Dashboard renders within 3 seconds; all panels populated with real data; time range selector changes all panels simultaneously |

21  Helm — Kubernetes Package Management
FraudShield uses Helm 3 charts for all Kubernetes deployments. A Helm chart packages all Kubernetes manifests for a service into a single versioned release with configurable values. This enables environment-specific configuration (development, staging, production) without duplicating manifests. The FraudShield Helm chart is stored at fraudshield/infrastructure/helm/fraudshield/.

| Helm Chart Structure: fraudshield/infrastructure/helm/fraudshield/ <br> ├── Chart.yaml                   Chart metadata: name, version, appVersion <br> ├── values.yaml                  Default values for all environments <br> ├── values-dev.yaml              Development overrides (single replica, debug logging) <br> ├── values-staging.yaml          Staging overrides (2 replicas, staging DB) <br> ├── values-prod.yaml             Production overrides (HPA enabled, prod secrets) <br> ├── templates/ <br> │   ├── _helpers.tpl             Reusable template functions <br> │   ├── deployment-api.yaml      Spring Boot API deployment <br> │   ├── deployment-ml.yaml       Python ML scorer deployment <br> │   ├── deployment-worker.yaml   Kafka consumer worker deployment <br> │   ├── deployment-frontend.yaml Frontend Nginx deployment <br> │   ├── service-*.yaml           ClusterIP services (one per deployment) <br> │   ├── ingress.yaml             Nginx Ingress with TLS <br> │   ├── hpa-*.yaml               HPA for api, ml, worker, frontend <br> │   ├── configmap-*.yaml         Non-sensitive configuration <br> │   ├── pvc-*.yaml               Persistent volumes for postgres and redis <br> │   ├── servicemonitor-*.yaml    Prometheus ServiceMonitor resources <br> │   └── prometheusrule.yaml      Alert rules <br> Deploy to development:  helm upgrade --install fraudshield . -f values-dev.yaml -n fraudshield <br> Deploy to staging:      helm upgrade --install fraudshield . -f values-staging.yaml -n fraudshield <br> Deploy to production:   helm upgrade --install fraudshield . -f values-prod.yaml -n fraudshield <br> Rollback:               helm rollback fraudshield 1  (rollback to revision 1) <br> History:                helm history fraudshield  (show all releases) |
|---|

22  OpenTelemetry and Distributed Tracing — Jaeger
At 10,000+ transactions per second across six services (Spring Boot API, Python ML scorer, Kafka consumers, PostgreSQL, Redis, Kafka), understanding the source of latency requires distributed tracing. OpenTelemetry instruments every service with trace context propagation. Jaeger collects and visualises traces. A single transaction can be traced from the Kafka ingestion event through feature engineering, ML scoring, risk decision, and audit write — with timing for every step.

| Tracing Component | Implementation | What It Traces | Business Value |
|---|---|---|---|
| Spring Boot API | OpenTelemetry Java agent (auto-instrumentation); no code changes required; OTLP exporter to Jaeger collector | All HTTP requests, JDBC queries (PostgreSQL), Kafka produce operations, Redis commands, WebSocket frames; each with duration and status | Identifies slow DB queries and Kafka produce latency contributing to auto-block latency breach |
| Python ML Scorer | opentelemetry-sdk + opentelemetry-fastapi; manual spans for feature engineering and SHAP computation | FastAPI request, feature engineering per feature group, XGBoost inference, LightGBM inference, SHAP computation; each as a child span | Identifies which feature or model step exceeds its latency budget |
| Kafka Consumer (Worker) | opentelemetry-sdk with kafka consumer instrumentation; trace context propagated via Kafka message headers | Kafka consumer poll, message deserialization, campaign detection query, fraud_campaign insert | Identifies campaign detection SQL queries that slow down the Kafka consumer group |
| Trace Sampling | 10% sampling in production (1 in 10 transactions fully traced); 100% sampling for ERROR spans and any span > 100ms | Controlled cost; no missed errors; latency outliers always captured | Low overhead at 10,000 TPS (1,000 traces/sec) while ensuring all anomalies are traced |
| Jaeger UI | Jaeger deployed in monitoring namespace; accessible at https://tracing.fraudshield.rw (VPN required); trace search by transaction_id, service, operation, duration | Full trace visualisation; flame graphs; service dependency map; latency distribution | On-call engineer can diagnose any latency incident to the exact line of code within minutes |

23  Updated Infrastructure Roadmap — All Technologies
The roadmap below supersedes Section 15 for infrastructure phases. It integrates Docker, Kubernetes, Helm, Prometheus, Grafana, OpenTelemetry, Jaeger, and the operational reporting system into the build schedule.

| Phase | Week | Technology | Deliverables | Gate Criteria |
|---|---|---|---|---|
| Infra Phase 0 | 4 | Docker + Docker Compose | All 6 services Dockerised with multi-stage builds; docker-compose.yml includes PostgreSQL + TimescaleDB + Redis + Kafka + Prometheus + Grafana; non-root users in all images; HEALTHCHECK on all services | docker compose up brings all services healthy; all container images scan clean (trivy); API reachable at localhost:8080 |
| Infra Phase 1 | 5 | Kubernetes + Helm (staging) | All Kubernetes manifests in helm/fraudshield/; namespace, deployments, services, ConfigMaps, Secrets, PVCs, NetworkPolicy; deployed to staging cluster; HPA configured; Ingress with TLS (cert-manager + Let's Encrypt) | helm upgrade succeeds; all pods Running; /actuator/health readiness = UP; Ingress returns HTTPS; NetworkPolicy blocks cross-namespace traffic |
| Infra Phase 2 | 5 | Prometheus + Grafana + Alerting | Prometheus ServiceMonitor for all 6 services; PrometheusRule with all alert definitions; Grafana provisioned with 5 dashboards as code; PagerDuty integration; Alertmanager routing configured | All alerts fire correctly in test; Grafana dashboards load with real data; PagerDuty receives test alert; all metrics from Section 19.1 visible |
| Infra Phase 3 | 5 | OpenTelemetry + Jaeger | OTel Java agent on Spring Boot API; OTel SDK on Python ML scorer and worker; Jaeger deployed in monitoring namespace; trace context propagated via Kafka message headers; 10% sampling in production | Trace visible in Jaeger for a complete transaction end-to-end; latency breakdown shows all 12 pipeline steps; ERROR spans sampled at 100% |
| Infra Phase 4 | 6 | Reporting System | Celery beat scheduler for daily/weekly/monthly reports; WeasyPrint PDF generation; Jinja2 HTML report templates; email delivery via SMTP; S3 object storage with lifecycle policies; Admin Panel 'Reports' tab; GET /api/v1/reports/ endpoint | Daily report generated at 06:00 Rwanda time with all 7 sections; PDF delivered by email within 5 minutes; report stored in S3; Admin Panel downloads report correctly |
| Infra Phase 5 | 6 | Production Hardening | GitHub Actions: ML evaluation gate; security scans (OWASP ZAP, Gitleaks, trivy); 10% Kubernetes canary deployment; auto-rollback on p99 > 80ms or error rate > 0.5%; Helm rollback tested; disaster recovery runbook documented | Zero-downtime canary deploy confirmed; auto-rollback triggered by deliberately bad deployment; disaster recovery: full restore from backup < 1 hour |
| Infra Phase 6 | 6 | Production Go-Live | Production cluster deployment via helm upgrade with values-prod.yaml; all monitoring verified; on-call runbook published; first daily report generated and reviewed; BNR monthly report format approved | All health checks passing in production; first daily report delivered and verified; Grafana Operations Overview showing live data; on-call team has access to runbook and PagerDuty |

23.1  Technology Stack — Complete Summary

| Category | Technology | Version | Role in FraudShield |
|---|---|---|---|
| Backend API | Spring Boot | 3.2 | REST API, business logic, Spring Security, JWT auth, WebSocket |
| Backend Language | Java | 21 (LTS) | Primary backend language; virtual threads (Project Loom) for Kafka consumer concurrency |
| ORM | Hibernate + Spring Data JPA | 6.4 | Entity mapping, JPQL queries, repository pattern; no raw SQL |
| ML Service | Python + FastAPI | 3.11 / 0.110 | ML inference API; XGBoost + LightGBM + SHAP endpoint |
| ML Framework | XGBoost + LightGBM | 2.0 / 4.3 | Ensemble fraud classifier; isotonic regression calibration |
| Explainability | SHAP (fast_treeshap) | 0.45 | Exact TreeSHAP for analyst explanation; no sampling approximation |
| Experiment Tracking | MLflow | 2.11 | Model versioning, metric tracking, model registry, artifact storage |
| Event Streaming | Apache Kafka | 3.6 | Transaction ingestion 10,000+ TPS; alert propagation; audit events |
| Primary Database | PostgreSQL | 16 | Relational storage; 13 tables; Flyway migrations |
| Time-Series Extension | TimescaleDB | 2.14 | Hypertables for transactions and audit_events; continuous aggregates |
| Cache / Feature Store | Redis | 7.2 | Velocity feature cache; JWT blocklist; idempotency keys; threshold config |
| Frontend Framework | React | 18 | Component model; Concurrent Mode; Suspense for async data loading |
| Frontend Language | TypeScript | 5 (strict) | No 'any' types; all API responses typed with Zod inference |
| CSS Framework | Tailwind CSS | 3 | Utility-first layout, spacing, responsive breakpoints, animations |
| UI Component Library | Material UI (MUI) | v5 | Form controls, DataGrid, Drawer, Tabs, Chips, Dialogs |
| Build Tool | Vite | 5 | Frontend build; tree-shaking; MUI selective import; HMR in dev |
| State Management | Zustand + TanStack Query | 5.0 / 5.0 | Client UI state (Zustand) + server state with caching (TanStack) |
| Containerisation | Docker | 26 | Multi-stage builds; non-root users; HEALTHCHECK; all 6 services |
| Orchestration | Kubernetes | 1.29 | Pod scheduling, HPA, rolling updates, self-healing, namespaces |
| Package Manager (K8s) | Helm | 3 | Chart-based deployment; environment-specific values; rollback |
| Ingress Controller | Nginx Ingress Controller | 1.9 | TLS termination; rate limiting; WebSocket support; CORS headers |
| TLS Management | cert-manager | 1.14 | Automatic Let's Encrypt certificate provisioning and renewal |
| Metrics Collection | Prometheus | 2.51 | Scrapes all services via ServiceMonitor; stores 1-year metric history |
| Metrics Visualisation | Grafana | 10.4 | 6 dashboards as code; Grafana Reporting for PDF reports |
| Alerting | Prometheus Alertmanager + PagerDuty | 0.27 | Alert routing; on-call escalation; incident management |
| Distributed Tracing | OpenTelemetry + Jaeger | 1.37 / 1.55 | End-to-end transaction traces; latency attribution; flame graphs |
| Report Generation | Celery + WeasyPrint | 5.4 / 62 | Scheduled report tasks; PDF generation from HTML templates |
| Security Scanning | OWASP ZAP + Gitleaks + Trivy | — | OWASP Top 10 API scan; secret detection; container vulnerability scan |
| CI/CD | GitHub Actions | — | 8-stage pipeline; ML evaluation gate; automated canary deployment |
| Secret Management | Kubernetes Secrets + External Secrets Operator | — | All credentials from environment; never in code |

FraudShield
Complete SRS v5.0 — Production-Grade with Docker, Kubernetes, Helm, Prometheus, Grafana, OpenTelemetry, Jaeger, and Operational Reporting
Marius Bayizere  |  bayizeremarius119@gmail.com
github.com/mariusbayizere/fraudshield  ·  STANDALONE INDEPENDENT PROJECT
Independent Researcher, Kigali, Rwanda  ·  2026
