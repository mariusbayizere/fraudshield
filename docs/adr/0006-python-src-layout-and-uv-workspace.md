# 0006 — Python src layout in a single uv workspace

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** RES-05 (training and evaluation pipeline), OPS-CI-01, OPS-CI-02
- **Defects referenced:** —

## Context

Build prompt C.5 draws `ml/fraudshield_ml/`, while H.3 requires a `src/` layout and a `uv`
lockfile. Several Python components exist (`ml`, `dataset` generator, governance `tools`)
and they must resolve one consistent set of pinned versions so that the batch feature path
used for training and the online path used for serving cannot diverge (E.2 skew test).

## Options considered

1. Flat package directories as drawn in C.5 — the package can be imported from the working
   directory without installation, hiding packaging errors.
2. Separate uv projects with separate lockfiles — version drift between training and serving.
3. **One uv workspace** at the repository root (`pyproject.toml`, `uv.lock`) with members
   `tools`, `ml` (and `dataset` from M2), each using `src/<package>/`.

## Decision

Option 3. The ML package lives at `ml/src/fraudshield_ml/`; the scripts named by the SRS
(`ml/train.py`, `ml/evaluate.py`, `ml/benchmark.py`, `ml/feature_engineering.py`) are thin
entry points that import the package. Shared dev tools (ruff, mypy, pytest) are pinned once
in the root dependency group.

## Consequences

- `uv sync --all-packages --locked` reproduces the environment in CI and on a clean machine.
- C.5's `ml/fraudshield_ml/` path is read as `ml/src/fraudshield_ml/` everywhere.
