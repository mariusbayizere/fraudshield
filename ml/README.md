# ml

Python 3.12 package `fraudshield_ml` (src layout, ADR 0006).

**Purpose.** Feature engineering (44 features, E.2), XGBoost + LightGBM ensemble with isotonic
calibration, Isolation Forest, exact TreeSHAP, evaluation (`evaluate.py`), scoring service
(gRPC + FastAPI admin), shadow scoring, drift and campaign jobs.

| Package | Status | Milestone |
|---|---|---|
| `metrics.operating_points` | precision ceiling at fixed FPR (D-01), implied operating point (D-02) | M0 |
| `features` | planned | M3 |
| `models`, `explain`, evaluation | planned | M4 |
| `serving`, `shadow` | planned | M5 |

**Test.** `uv run pytest -q` (from `ml/`); `uv run mypy ml/src ml/tests`; `uv run ruff check ml`.
