"""Compare the OpenAPI authorisation declarations with the reviewed golden matrix (ADR 0014)."""

from __future__ import annotations

from functools import cache
from pathlib import Path
from typing import Any

import yaml

from fraudshield_contracts import CONTRACTS_ROOT
from fraudshield_contracts.openapi import operations

MATRIX_PATH = CONTRACTS_ROOT / "openapi" / "authorisation-matrix.yaml"
ACCESS_KEYS = ("roles", "scopes", "public", "refresh-cookie")


@cache
def load_matrix(path: Path = MATRIX_PATH) -> dict[str, dict[str, Any]]:
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    rows = document.get("operations") if isinstance(document, dict) else None
    if not isinstance(rows, dict):
        raise ValueError(f"{path} has no operations mapping")
    return rows


def declared_access(spec: dict[str, Any]) -> tuple[str, frozenset[str]]:
    """The access class an operation declares, with its roles or scopes."""
    if "x-required-roles" in spec:
        return "roles", frozenset(spec["x-required-roles"])
    if "x-required-scopes" in spec:
        return "scopes", frozenset(spec["x-required-scopes"])
    if spec.get("x-public") is True:
        return "public", frozenset()
    if spec.get("x-refresh-cookie") is True:
        return "refresh-cookie", frozenset()
    return "undeclared", frozenset()


def matrix_access(row: dict[str, Any]) -> tuple[str, frozenset[str]]:
    keys = [key for key in ACCESS_KEYS if key in row]
    if len(keys) != 1:
        return "invalid", frozenset()
    key = keys[0]
    if key in ("roles", "scopes"):
        return key, frozenset(row[key])
    return key, frozenset()


def matrix_differences(document: dict[str, Any], matrix: dict[str, dict[str, Any]]) -> list[str]:
    """Every disagreement between the document and the matrix, in both directions."""
    problems: list[str] = []
    seen: set[str] = set()
    for op in operations(document):
        seen.add(op.operation_id)
        row = matrix.get(op.operation_id)
        if row is None:
            problems.append(f"{op.operation_id}: not in the matrix")
            continue
        if not str(row.get("ref", "")).strip():
            problems.append(f"{op.operation_id}: matrix row has no ref")
        expected = matrix_access(row)
        actual = declared_access(op.spec)
        if expected != actual:
            problems.append(
                f"{op.operation_id}: document declares {actual[0]} {sorted(actual[1])}, "
                f"matrix says {expected[0]} {sorted(expected[1])}"
            )
    problems.extend(
        f"{name}: in the matrix but not in the document" for name in matrix.keys() - seen
    )
    return problems
