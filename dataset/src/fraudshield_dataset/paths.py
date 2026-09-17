"""Repository locations used by the dataset package."""

from __future__ import annotations

from pathlib import Path

DATASET_ROOT = Path(__file__).resolve().parents[2]
PARAMS_DIR = DATASET_ROOT / "generator" / "params"
PROVENANCE_MD = DATASET_ROOT / "params_provenance.md"
