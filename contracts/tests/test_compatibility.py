from __future__ import annotations

import copy
import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from fraudshield_contracts.compatibility import (
    breaking_changes,
    breaking_changes_between,
    fragile_definitions,
)
from fraudshield_contracts.events import KAFKA_ROOT

BASELINE = KAFKA_ROOT / "baseline"
CURRENT = KAFKA_ROOT / "schemas"
BASELINE_FILES = sorted(BASELINE.glob("*.schema.json"))


def _load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def test_every_published_schema_has_a_baseline() -> None:
    assert {p.name for p in BASELINE_FILES} == {p.name for p in CURRENT.glob("*.schema.json")}


def _set(directory: Path) -> dict[str, Any]:
    return {
        schema["$id"]: schema
        for schema in (_load(path) for path in sorted(directory.glob("*.schema.json")))
    }


@pytest.mark.req("D-14")
def test_current_schemas_are_backward_compatible_with_their_baseline() -> None:
    changes = breaking_changes_between(_set(BASELINE), _set(CURRENT))
    assert len(changes) == len(BASELINE_FILES)
    assert {schema_id: found for schema_id, found in changes.items() if found} == {}


def test_definitions_used_inside_oneof_are_fragile() -> None:
    fragile = fragile_definitions(_set(CURRENT))
    assert "urn:fraudshield:kafka:common#/$defs/Uuid" in fragile
    assert "urn:fraudshield:kafka:common#/$defs/Timestamp" in fragile


@pytest.mark.parametrize(
    ("label", "schema_id", "mutate"),
    [
        (
            "a oneOf branch widened so null matches two branches (review R11)",
            "urn:fraudshield:kafka:notification-staff",
            lambda s: s["properties"]["recipient_user_id"]["oneOf"][1].update(
                type=["null", "string"]
            ),
        ),
        (
            "a shared definition used inside oneOf made nullable (review R11b)",
            "urn:fraudshield:kafka:common",
            lambda s: s["$defs"]["Uuid"].update(type=["string", "null"]),
        ),
        (
            "a constrained property added to the open envelope payload (review R12)",
            "urn:fraudshield:kafka:envelope",
            lambda s: (
                s["properties"]["payload"]
                .setdefault("properties", {})
                .update(notification_id={"type": "integer"})
            ),
        ),
    ],
)
def test_set_checker_reports_changes_that_break_other_schemas(
    label: str, schema_id: str, mutate: Callable[[Any], None]
) -> None:
    old = _set(BASELINE)
    new = copy.deepcopy(old)
    mutate(new[schema_id])
    assert breaking_changes_between(old, new)[schema_id], label


def test_set_checker_reports_a_removed_schema() -> None:
    old = _set(BASELINE)
    new = copy.deepcopy(old)
    del new["urn:fraudshield:kafka:label"]
    assert breaking_changes_between(old, new)["urn:fraudshield:kafka:label"] == [
        "urn:fraudshield:kafka:label: schema removed"
    ]


def _decision_final() -> Any:
    return _load(BASELINE / "decision-final.schema.json")


def _alert() -> Any:
    return _load(BASELINE / "alert.schema.json")


def _common() -> Any:
    return _load(BASELINE / "common.schema.json")


BREAKING: list[tuple[str, Callable[[], Any], Callable[[Any], Any]]] = [
    # The review's M4b edits plus every kind of narrowing ADR 0012 lists.
    ("remove a property", _decision_final, lambda s: s["properties"].pop("reason_codes") and s),
    (
        "newly required property",
        _alert,
        lambda s: s["required"].append("escalated_from_alert_id") or s,
    ),
    (
        "type change",
        _decision_final,
        lambda s: s["properties"].update(decision_sequence={"type": "string"}) or s,
    ),
    (
        "enum value removed",
        _decision_final,
        lambda s: s["properties"]["decided_by"]["enum"].remove("ANALYST") or s,
    ),
    (
        "pattern tightened",
        _common,
        lambda s: s["$defs"]["ReasonCode"].update(pattern="^[A-Z]{3,10}$") or s,
    ),
    (
        "maxLength tightened",
        _common,
        lambda s: s["$defs"]["Token"].update(maxLength=30) or s,
    ),
    (
        "minimum tightened",
        _decision_final,
        lambda s: s["properties"]["decision_sequence"].update(minimum=2) or s,
    ),
    (
        "unevaluatedProperties closed",
        lambda: {"allOf": [{"type": "object"}]},
        lambda s: s.update(unevaluatedProperties=False) or s,
    ),
    (
        "additionalProperties closed",
        lambda: {"type": "object"},
        lambda s: s.update(additionalProperties=False) or s,
    ),
    (
        "$ref target changed",
        _decision_final,
        lambda s: (
            s["properties"]["transaction_id"].update(
                {"$ref": "urn:fraudshield:kafka:common#/$defs/Token"}
            )
            or s
        ),
    ),
    ("definition removed", _common, lambda s: s["$defs"].pop("Probability") and s),
    ("conditional changed", _decision_final, lambda s: s["allOf"].pop() and s),
    (
        "array bound tightened",
        _decision_final,
        lambda s: s["properties"]["reason_codes"].update(maxItems=2) or s,
    ),
]


@pytest.mark.parametrize(("label", "load", "mutate"), BREAKING, ids=[b[0] for b in BREAKING])
def test_checker_reports_breaking_changes(
    label: str, load: Callable[[], Any], mutate: Callable[[Any], Any]
) -> None:
    old = load()
    new = mutate(copy.deepcopy(old))
    assert isinstance(new, dict)
    assert breaking_changes(old, new), label


@pytest.mark.parametrize(
    ("label", "mutate"),
    [
        ("optional property added", lambda s: s["properties"].update(note={"type": "string"})),
        ("enum value added", lambda s: s["properties"]["decided_by"]["enum"].append("RULE")),
        ("bound relaxed", lambda s: s["properties"]["reason_codes"].update(maxItems=5)),
        ("description edited", lambda s: s.update(description="clearer wording")),
        # Consumers deploy first and tolerate absence, so making a field optional is compatible;
        # the original review's M4 edit (dropping required fields) is therefore accepted.
        ("required field made optional", lambda s: s["required"].remove("reason_codes")),
    ],
)
def test_checker_accepts_compatible_changes(label: str, mutate: Callable[[Any], None]) -> None:
    old = _decision_final()
    new = copy.deepcopy(old)
    mutate(new)
    assert breaking_changes(old, new) == [], label
