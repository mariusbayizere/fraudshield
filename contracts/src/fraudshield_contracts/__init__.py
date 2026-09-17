"""Loading and validation helpers for FraudShield's interface contracts (ADR 0011)."""

from __future__ import annotations

from pathlib import Path

CONTRACTS_ROOT = Path(__file__).resolve().parents[2]
OPENAPI_PATH = CONTRACTS_ROOT / "openapi" / "fraudshield-api.yaml"
