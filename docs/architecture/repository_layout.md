# Repository layout: planned directories and the milestone that creates them

Build prompt C.5 describes the full layout. Git does not track empty directories, and M0 does not
create placeholder content, so directories appear when the milestone that fills them lands. This
table is the record of intent (M0 review finding 16).

| C.5 path | Purpose | Created in | Status at M0 |
|---|---|---|---|
| `backend/` (`common`) | Maven multi-module build, money primitives | M0 | present |
| `backend/{ingest,decision,rules,notify,verify-web}` | ingestion, decision engine, rules DSL, notifications, verification page | M6 | planned |
| `backend/{auth,staff-api}` | identity, authorisation, staff APIs | M7 | planned |
| `backend/sar` | SAR draft generation | M8 | planned |
| `ml/src/fraudshield_ml/metrics` | operating-point analysis | M0 | present |
| `ml/src/fraudshield_ml/features`, `ml/feature_engineering.py` | 44-feature registry, feature store | M3 | planned |
| `ml/src/fraudshield_ml/{models,explain}`, `ml/{train,evaluate}.py`, `ml/configs/` | training, calibration, SHAP, evaluation | M4 | planned |
| `ml/src/fraudshield_ml/{serving,shadow}`, `ml/benchmark.py` | scoring service, shadow scoring | M5 | planned |
| `ml/src/fraudshield_ml/{drift,campaigns,labels}` | drift/PSI, campaign detection, label pipeline | M5–M8 | planned |
| `dataset/generator`, `dataset/realism_checks`, `dataset/datasheet.md` | synthetic data generator | M2 | README only |
| `frontend/src/design-system` | contrast measurement | M0 | present |
| `frontend/src/{app,features,lib,i18n,pwa}`, `design-tokens/*.json` | staff console | M8 | planned |
| `contracts/{openapi,proto,kafka,webhooks}` | interface contracts | M1 | README only |
| `infrastructure/docker` | compose support, smoke test, diagnostics | M0 | present |
| `infrastructure/{k8s,prometheus,alertmanager,grafana,argo-rollouts}` | deployment and observability as code | M9 | planned |
| `notebooks/` | SHAP, model comparison, calibration, fairness | M4 | README only |
| `tests/security` | DB permission and authorisation matrix tests | M1, M7 | README only |
| `tests/{contract,performance,chaos}` | cross-component, load and chaos suites | M5, M6, M10 | README only |
| `tests/e2e` | Playwright journeys | M8 | README only |
| `docs/{adr,traceability,srs,prompts,research,benchmarks,walkthrough,reviews}` | governance and evidence | M0 | present |
| `docs/architecture` | this file; design notes as components land | M0 | present |
| `docs/{ml,security,compliance,runbooks,ux}` | explainability and capacity notes, threat model, DPIA and regulatory mapping, runbooks, screen-reader script | M1–M9 (first files: `docs/security/threat_model.md` in M1, `docs/ml/capacity_model.md` in M4) | planned |
