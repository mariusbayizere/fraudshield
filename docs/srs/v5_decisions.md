# SRS v5.0 — what was adopted, what was rejected, and why

Owner's decisions of 2026-09-23 on the gap analysis in `docs/srs/v5_delta.md`. This file records
them; the **defect register remains the authority** for every rejection, which is why each one is
also a numbered entry there (D-52, D-53, D-54, generated from build prompt Part B).

**About the document itself, for any future reader.** The file is named v5.0 but its cover block
and section-00 description call it **v4.0** (`FraudShield_SRS_v5_0.md:3,:20`), and its contents
table lists only **sections 00–16** (`:23-40`) although the file contains sections **17–23**
(Docker, Kubernetes, Prometheus/Grafana, operational reporting, Helm, OpenTelemetry/Jaeger,
infrastructure roadmap) and the **FR-08** table at `:878-889`. Everything in the file was reviewed,
including the parts its own contents page omits. Where this document cites v5, the line numbers are
those of `docs/srs/FraudShield_SRS_v5_0.md`.

## Decisions

| # | Item | Decision | Reason | Where it now lives |
|---|---|---|---|---|
| 1 | **§02 MVC and service layer, §03 Spring Security** as the written architecture requirement | **ADOPT**, reconciled with what is built | The intent — thin controllers, business rules in services, repositories without business logic, DTOs at every boundary, method-level `@PreAuthorize` — is right and is already met, in most places more strictly than v5 asks. The built layering is hexagonal: `domain/` is pure, `application/` depends on ports, adapters implement them, and ArchUnit enforces it per module. Entities stop at the adapter rather than merely "not crossing a layer", and there is no Lombok. **Nothing is restructured**: v5's package names (`com.fraudshield.controller/service/repository/entity`) are a different spelling of the same separation, and renaming 204 files would be churn with no gain in safety. | ADR 0082 §1–2 |
| 2 | **Hybrid persistence (ADR 0071)** against v5's "JPA everywhere / no raw SQL" | **KEEP the hybrid rule**; v5's wording not adopted | JPA is used for CRUD domains. Explicit SQL stays for four things it cannot express safely: the audit hash chain (insert ordering that Hibernate's flush may reorder), append-only tables (dirty checking fights the triggers), hypertables and security-barrier views, and set-based refresh-token revocation whose statement order the ADR 0070 race fix depends on. A PII vault with its own role is also outside the ORM boundary by design. | ADR 0082 §3; amendment proposed to ADR 0071 (M7 owns it) in `docs/parallel/M8_updates.md` |
| 3 | **§12 per-role frontend specification** | **ADOPT** as M8 requirements | It is the first written statement of what each role may see and do, and M8 is building those screens now. | 25 register rows: `UX-ROLE-AN-01…05`, `UX-ROLE-SN-01…05`, `UX-ROLE-RO-01…06`, `UX-ROLE-AD-01…09`, all M8 |
| 4 | **§13.1 device matrix** | **ADOPT** as M8 requirements | v1's 05B gave testing and behaviour tables but no device matrix; this one names the widths, browsers and network assumptions the console must work on, which is what the breakpoints and the Playwright matrix are judged against. | 11 register rows: `DEV-MATRIX-01…11`, M8 |
| 5 | **FR-08-01…08 operational reporting** | **ADOPT** the 8 requirements; assign to a **new milestone M13** | Scheduled operational reporting is real work with its own gate, and it cannot sit inside M8 (front-end) or M9 (observability). M13 runs **after M8**, and after M6 and M9 supply its data and dashboards. It is numbered 13 rather than inserted as a new M9 because M9–M12 are already referenced by branches, reviews and the register; renumbering them would invalidate those references. | 8 register rows `FR-08-01…08`, milestone M13; milestone defined in build prompt D.3 |
| 6 | **Celery + WeasyPrint + Jinja2 + SMTP + S3** as the reporting stack | **NOT ADOPTED without an ADR** | The requirements say what the reports must contain and when; they do not settle how they are produced. Nothing in the project runs Celery today, and adding a broker plus a worker fleet is an architectural decision to be compared against what already exists (the scoring service's own scheduling, the outbox pattern already in the schema, and the object store chosen in ADR 0005). | M13's gate text in build prompt D.3 says the stack needs an ADR |
| 7 | **§17 Docker, §18 Kubernetes, §19 Prometheus/Grafana, §22 OpenTelemetry** | **ADOPT only where they add something M9 lacks** | M9 already built the Prometheus rules with both-sides unit tests, five generated Grafana dashboards, the k8s manifests with PodSecurity restricted, NetworkPolicies, HPA and PDBs, and 24 break-one-rule policy tests. What v5 adds and M9 lacks: service Dockerfiles with size budgets and non-root users, and distributed tracing (OpenTelemetry SDK on the Python services, agent on the API). Jaeger as the UI is v5's choice; the project's stack row already names an OpenTelemetry Collector, so the backend is an M9 decision, not an SRS one. | Noted for M9 in `docs/parallel/M8_updates.md`; no register rows added, because M9's OPS-* rows already cover observability and the Docker/tracing items belong to M9's own gate |
| 8 | **Helm 3 charts** replacing the kustomize layout | **REJECT; keep kustomize**, deviation recorded | The kustomize base and overlays are built, validated by kubeconform against pinned schemas, and covered by 24 policy tests plus canary analysis queries run through promtool. Helm would re-package all of it and invalidate that validation, for no stated gain. | This row is the record; also noted for M9 |
| 9 | **§07 "13 lean tables" schema** | **REJECT in full** | Six reasons, in severity order: it removes `institution_id` and 35 row-level security policies (a tenant boundary, not a simplification); the audit hash chain cannot compile without the 11 inputs it deletes; it makes the append-only `auto_block_events` mutable, duplicating values `v_auto_block_status` already derives; it deletes `api_keys` while its own FR-07-05 still requires API keys, so the document contradicts itself; it drops four `ScoringResult` fields the published contract marks required and the analyst console renders; and it blocks the ML-fallback insert the reliability section requires. | **D-52**. Two v5 constraints are worth considering on their own merits and are named there: UNIQUE on `auto_block_events.fraud_score_id` and on `alert_queue_entries.fraud_score_id`, each as `(institution_id, fraud_score_id)` |
| 10 | **The eight repeated defects** — D-01, D-09, D-19, D-20, D-21 and the three found in review: D-29, D-40, D-42 | **REJECT; corrections stand unchanged** | A later SRS revision that repeats a resolved defect does not reopen it. Precedence is the build prompt's (A.4): the register governs. | **D-53**, whose affected rows are computed as the union of the eight defects' own rows, so the two cannot drift |
| 11 | **CSRF disabled; per-request database lookup for tokens** (v5 §03) | **REJECT; keep the built design** | `csrf.disable()` is safe only when no browser-held credential exists; here the refresh token is an httpOnly cookie (D-27), so the double-submit token on `/auth/refresh` and `/auth/logout` is load-bearing. And `token_version` as a JWT claim checked against a 2-second Redis cache with pub/sub invalidation already meets FR-06-02's 5-second revocation budget without a database round trip on every request. | **D-54** |

## What changed in the repository for these decisions

| Change | File |
|---|---|
| Three new binding resolutions | `docs/prompts/FraudShield_Master_Build_Prompt.md` Part B (D-52, D-53, D-54) → generated into `docs/srs/defect_register.md` |
| New milestone M13 with its gate | `docs/prompts/FraudShield_Master_Build_Prompt.md` D.3 |
| 47 new register rows (25 per-role, 11 device, 8 FR-08, 3 defects) | `docs/traceability/requirements.yaml`, `docs/traceability/requirements_matrix.md` (258 → 305 rows) |
| Adopted parts of v5 became a seeder source | `tools/src/fraudshield_tools/traceability_seed.py` (`SRS_V5_MD`, `V5_SECTIONS`, FR-08 reader) |
| ID families and milestone range extended | `tools/src/fraudshield_tools/traceability.py` (`UX-ROLE-*`, `DEV-MATRIX-*`, M13) |
| Defect count | `tools/src/fraudshield_tools/defect_register.py` (51 → 54; the count in its messages was hard-coded and is now derived) |
| Architecture decision | `docs/adr/0082-v5-architecture-adoption-and-persistence.md` |

**Nothing else was changed.** No migration, no entity, no contract and no frontend code was touched
for v5: `git diff m4-complete -- backend ml dataset` is empty on this branch.

## What FR-08 still needs before it can be scheduled

1. **A stack ADR** (decision 6): scheduler, PDF renderer, template engine, mail transport and object
   store, compared with what the project already runs. Until it exists, FR-08 has requirements but
   no implementable design.
2. **Its data sources**: FR-08 reports read the decision, alert and campaign tables M6 owns, and the
   Prometheus metrics M9 owns. M13 cannot start its gate before M6 and M9 close.
3. **A retention and privacy decision**: v5 asks for 7-year monthly reports in object storage. The
   reports contain analyst names and counterparty tokens, so retention needs the same treatment as
   the audit trail (ADR 0017's PII rules), and the S3-compatible store is the one ADR 0005 chose.
4. **A recipient list that is not a person's inbox**: v5 mails reports to "all RISK_OFFICER and
   ADMIN users". Who receives a report, and whether it may leave the deployment's jurisdiction, is a
   deployment decision (D-21's residency reasoning applies to report delivery too).
5. **Report reproducibility**: the M13 gate requires a report regenerated byte for byte from seeded
   data. That needs a fixed clock and a seeded dataset, the same discipline the dataset milestone
   uses, decided before the first report is written.
