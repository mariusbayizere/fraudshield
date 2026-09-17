# tests

Cross-component suites. Unit tests live next to each component (`backend/*/src/test`,
`ml/tests`, `frontend/src/**/*.test.ts`, `tools/tests`).

| Path | Suite | Milestone |
|---|---|---|
| `contract/` | Java ↔ Python contract tests generated from the proto | M5 |
| `performance/` | Locust load tests | M6, M10 |
| `chaos/` | Toxiproxy scenarios and Chaos Mesh manifests | M6, M10 |
| `security/` | DB permission, authorisation matrix, ZAP configuration | M1, M7, M9 |
| `e2e/` | Playwright journeys (Chromium, Firefox, WebKit) | M8 |
