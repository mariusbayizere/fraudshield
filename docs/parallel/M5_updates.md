# M5 updates for integration

Written by the M5 agent (branch `m5/scoring`). Under the parallel-work rules the M5 agent does not
edit `docs/traceability/requirements.yaml`, `docs/backlog/`, `lab_notebook.md`, `SESSION_STATE.md`,
or anything outside `ml/`. Everything below is a request for the owner to integrate. Each item says
where it goes and why it cannot be done from `ml/`.

## 1. Licence exceptions: `tools/src/fraudshield_tools/licences.py`, `EXCEPTIONS`

**The CI `licences` job fails on `m5/scoring` until these two entries land.** Both packages declare
only a classifier. The bundled licence text was read in the wheel's dist-info on 2026-09-22.

```python
    "python:cloudpickle@3.1.2": (
        "BSD-3-Clause",
        "classifier says only 'BSD License'; dist-info/licenses/LICENSE is the 3-clause text with "
        "the non-endorsement clause naming the University of California, Berkeley (verified "
        "2026-09-22). Runtime, via scikit-learn -> joblib (M5 IsolationForest, D-06)",
    ),
    "python:sortedcontainers@2.4.0": (
        "Apache-2.0",
        "classifier says only 'Apache Software License'; METADATA 'License: Apache 2.0' and "
        "dist-info/LICENSE is the Apache License, Version 2.0 notice (verified 2026-09-22). Dev "
        "only, via fakeredis (M5 unit tests without Docker)",
    ),
```

**MLflow client deliberately not added.** `mlflow-skinny==3.16.0` was tried first. It brings
`certifi` (MPL-2.0, not allowed at runtime under ADR 0009) plus nine packages with unidentifiable
metadata (`requests`, `databricks-sdk`, `google-auth`, `gitdb`, `smmap`, `sqlparse`, among others).
The scorer needs only a handful of registry REST calls: resolve an alias, download a version's
artifacts, log metrics. So `ml/` talks to the MLflow REST API with the standard library instead,
which also keeps every serving process lighter.

## 2. Generated and shared files changed on this branch

Owner rule (2026-09-22): `uv.lock` and `docs/traceability/requirements_matrix.md` may change on any
branch whose inputs changed. They are not ownership violations. On merge, never hand-resolve a
conflict in either: merge the inputs, then re-run `uv lock` or `fs-traceability render`.
`requirements.yaml` stays untouched. Never use `--no-verify` (G.6).

Each commit on `m5/scoring` that touches one of them:

| Commit | `uv.lock` | matrix | Input that changed |
|---|---|---|---|
| 9e46dfc | yes | | `ml/pyproject.toml`: serving dependencies |
| 18c92ac | | yes | new tagged test `ml/tests/serving/test_generated.py` |
| 5552530 | | yes | new tagged tests `ml/tests/models/` |
| 78dd576 | | yes | new tagged tests `ml/tests/models/` |
| 57fcf7c | | yes | new tagged tests `ml/tests/featurestore/`, `ml/tests/serving/` |
