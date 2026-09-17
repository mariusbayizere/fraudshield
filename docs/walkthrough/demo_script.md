# 10-minute demo script

Updated as features land. Each step lists what to say and what to show; nothing is shown that
has not been run from the repository.

## Current state (after M0) — governance demo, about 4 minutes

1. **The problem with the requirements document (1 min).** Open
   `docs/srs/defect_register.md` at D-01. Explain the precision ceiling, then run:
   `cd ml && uv run pytest -q tests/metrics -k ceiling -v`.
2. **Requirements are data, and tests are linked to them (1 min).** Open
   `docs/traceability/requirements_matrix.md`, filter for `D-33`, and show the tagged test titles
   in `frontend/src/design-system/color/contrast.test.ts`.
3. **CI refuses unsupported claims (1 min).** Temporarily set a row's status to `DONE` in
   `docs/traceability/requirements.yaml`, run `uv run fs-traceability check`, show the failure,
   then revert with `git checkout -- docs/traceability/requirements.yaml`.
4. **Local platform (1 min, requires Docker).** `make up`, then `make ps` to show every service
   healthy.

Later milestones will add: synthetic transaction stream, live HIGH/MEDIUM alerts with SHAP
explanations, analyst decision with undo, risk officer heatmap, model promotion gate.
