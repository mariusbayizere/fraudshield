# tools

Governance tooling (`fraudshield_tools`), run through the uv workspace.

| Command | Purpose |
|---|---|
| `uv run fs-defect-register [--check]` | Generate `docs/srs/defect_register.md` from build prompt Part B |
| `uv run python -m fraudshield_tools.traceability_seed` | Seed/refresh `docs/traceability/requirements.yaml` from the SRS |
| `uv run fs-traceability check` / `render` | Enforce and render the traceability matrix (ADR 0004) |
| `uv run fs-scope-guard` | Fail on out-of-scope terms from the unrelated project in SRS 05B (D-47) |

**Test.** `uv run pytest -q` from `tools/`.
