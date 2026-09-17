from __future__ import annotations

import copy
import json
import shutil
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from fraudshield_contracts import proto_compat
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


# --- protobuf -------------------------------------------------------------------------------------


def test_proto_matches_its_committed_baseline() -> None:
    current = proto_compat.summarise(proto_compat.compile_descriptor())
    baseline = json.loads(proto_compat.BASELINE_PATH.read_text(encoding="utf-8"))
    assert proto_compat.breaking_changes(baseline, current) == []
    assert current == baseline, (
        "compatible proto change: regenerate contracts/proto/baseline/scoring-v1.json and review it"
    )


def _compile_edited(tmp_path: Path, old: str, new: str) -> dict[str, Any]:
    root = tmp_path / "proto"
    shutil.copytree(proto_compat.PROTO_ROOT, root, ignore=shutil.ignore_patterns("baseline"))
    target = root / proto_compat.SCORING_PROTO
    text = target.read_text(encoding="utf-8")
    assert text.count(old) == 1, old
    target.write_text(text.replace(old, new), encoding="utf-8")
    return proto_compat.summarise(proto_compat.compile_descriptor(root))


PROTO_BREAKING = [
    ("renumbered field", "string model_version = 14;", "string model_version = 18;"),
    ("field deleted without reserved", "  double shap_base_value = 12;\n", ""),
    ("type change", "  Money amount = 5;", "  string amount = 5;"),
    ("renamed field", "string traceparent = 4;", "string trace_parent = 4;"),
    ("label change", "optional string device_token = 10;", "repeated string device_token = 10;"),
    ("enum value renamed", "CHANNEL_USSD = 4;", "CHANNEL_FEATURE_PHONE = 4;"),
    (
        "response made server-streaming (review R5b)",
        "rpc Score(ScoreRequest) returns (ScoreResponse);",
        "rpc Score(ScoreRequest) returns (stream ScoreResponse);",
    ),
    (
        "method removed",
        "  rpc GetModelStatus(ModelStatusRequest) returns (ModelStatusResponse);\n",
        "",
    ),
    (
        "reserved number reused",
        "  double shap_base_value = 12;\n",
        '  reserved 12;\n  reserved "shap_base_value";\n  double shap_base = 12;\n',
    ),
]


@pytest.mark.parametrize(
    ("label", "old", "new"), PROTO_BREAKING, ids=[p[0] for p in PROTO_BREAKING]
)
def test_proto_checker_reports_breaking_changes(
    tmp_path: Path, label: str, old: str, new: str
) -> None:
    baseline = json.loads(proto_compat.BASELINE_PATH.read_text(encoding="utf-8"))
    if label == "reserved number reused":
        # protoc rejects this within one file; the checker's own reuse rule (a later version that
        # drops the reservation) is exercised on summaries in the next test.
        with pytest.raises(RuntimeError):
            _compile_edited(tmp_path, old, new)
        return
    assert proto_compat.breaking_changes(baseline, _compile_edited(tmp_path, old, new)), label


def test_proto_checker_accepts_an_added_field_and_a_properly_reserved_removal(
    tmp_path: Path,
) -> None:
    baseline = json.loads(proto_compat.BASELINE_PATH.read_text(encoding="utf-8"))
    added = _compile_edited(
        tmp_path / "added",
        "  repeated StageTiming stage_timings = 17;\n",
        "  repeated StageTiming stage_timings = 17;\n  string trace_id = 18;\n",
    )
    assert proto_compat.breaking_changes(baseline, added) == []
    reserved = _compile_edited(
        tmp_path / "reserved",
        "  double shap_base_value = 12;\n",
        '  reserved 12;\n  reserved "shap_base_value";\n',
    )
    assert proto_compat.breaking_changes(baseline, reserved) == []
    reused = copy.deepcopy(reserved)
    message = reused["messages"][".fraudshield.scoring.v1.ScoringResult"]
    message["fields"]["12"] = {
        "name": "shap_base",
        "type": "TYPE_DOUBLE",
        "type_name": "",
        "label": "LABEL_OPTIONAL",
        "oneof": None,
        "proto3_optional": False,
    }
    assert proto_compat.breaking_changes(reserved, reused) == [
        ".fraudshield.scoring.v1.ScoringResult field 12: reuses a reserved number"
    ]


def test_proto_checker_rejects_dropping_a_reservation_to_reuse_it(tmp_path: Path) -> None:
    """Review R5c: reserve, then unreserve, then reuse must fail at the unreserve step."""
    reserved = _compile_edited(
        tmp_path / "reserved",
        "  double shap_base_value = 12;\n",
        '  reserved 12;\n  reserved "shap_base_value";\n',
    )
    unreserved = _compile_edited(tmp_path / "unreserved", "  double shap_base_value = 12;\n", "")
    changes = proto_compat.breaking_changes(reserved, unreserved)
    assert (
        ".fraudshield.scoring.v1.ScoringResult: reserved numbers 12-12 no longer reserved"
        in changes
    )
    assert (
        ".fraudshield.scoring.v1.ScoringResult: reserved name shap_base_value no longer reserved"
        in changes
    )


def test_proto_checker_rejects_enum_value_reuse() -> None:
    baseline = json.loads(proto_compat.BASELINE_PATH.read_text(encoding="utf-8"))
    old = copy.deepcopy(baseline)
    channel = old["enums"][".fraudshield.scoring.v1.Channel"]
    del channel["values"]["6"]
    channel["reserved_numbers"] = [[6, 6]]
    channel["reserved_names"] = ["CHANNEL_BANK_TRANSFER"]
    new = copy.deepcopy(old)
    new["enums"][".fraudshield.scoring.v1.Channel"]["values"]["6"] = "CHANNEL_WALLET"
    new["enums"][".fraudshield.scoring.v1.Channel"]["reserved_numbers"] = []
    changes = proto_compat.breaking_changes(old, new)
    assert any("reuses a reserved number or name" in c for c in changes)
    assert any("no longer reserved" in c for c in changes)
    moved = copy.deepcopy(baseline)
    moved["enums"][".fraudshield.scoring.v1.Channel"]["values"]["7"] = "CHANNEL_USSD"
    del moved["enums"][".fraudshield.scoring.v1.Channel"]["values"]["4"]
    assert any("renumbered" in c for c in proto_compat.breaking_changes(baseline, moved))
