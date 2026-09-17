from __future__ import annotations

import copy
from typing import Any

import pytest
import yaml

from fraudshield_contracts import CONTRACTS_ROOT
from fraudshield_contracts.openapi import errors, load, operations

DOC = load()
SCHEMAS = DOC["components"]["schemas"]
RESPONSES = DOC["components"]["responses"]
EXAMPLES: dict[str, list[Any]] = yaml.safe_load(
    (CONTRACTS_ROOT / "openapi" / "schema-examples.yaml").read_text(encoding="utf-8")
)["examples"]

# RFC 9457 lets problem details carry extension members, so problem schemas stay open.
OPEN_BY_DESIGN = {"Problem", "ValidationProblem", "StaleAlertProblem", "PromotionGateProblem"}
COMPOSITION = ("allOf", "anyOf", "oneOf")


def _schema_refs(node: object, found: set[str]) -> None:
    if isinstance(node, dict):
        ref = node.get("$ref", "")
        if ref.startswith("#/components/schemas/"):
            found.add(ref.rsplit("/", 1)[1])
        elif ref.startswith("#/components/responses/"):
            _schema_refs(RESPONSES[ref.rsplit("/", 1)[1]], found)
        for key, value in node.items():
            if key != "$ref":
                _schema_refs(value, found)
    elif isinstance(node, list):
        for value in node:
            _schema_refs(value, found)


def _operation_schemas() -> set[str]:
    found: set[str] = set()
    for op in operations(DOC):
        _schema_refs(op.spec.get("requestBody", {}), found)
        _schema_refs(op.spec.get("responses", {}), found)
    return found


def _object_like(schema: dict[str, Any]) -> bool:
    if "type" in schema:
        return bool(schema["type"] == "object" or schema["type"] == ["object", "null"])
    return any(key in schema for key in COMPOSITION)


OBJECT_SCHEMAS = sorted(name for name in _operation_schemas() if _object_like(SCHEMAS[name]))


def _operation_media_examples() -> dict[str, list[Any]]:
    """Examples attached to operation media types (ingest requests, decision responses)."""
    found: dict[str, list[Any]] = {}
    media: list[dict[str, Any]] = []
    for op in operations(DOC):
        media.extend(op.spec.get("requestBody", {}).get("content", {}).values())
        for response in op.spec.get("responses", {}).values():
            media.extend(response.get("content", {}).values())
    for entry in media:
        ref = entry.get("schema", {}).get("$ref", "")
        for example in entry.get("examples", {}).values():
            name = example["$ref"].rsplit("/", 1)[1]
            value = DOC["components"]["examples"][name]["value"]
            found.setdefault(ref.rsplit("/", 1)[-1], []).append(value)
    return found


for _name, _values in _operation_media_examples().items():
    EXAMPLES.setdefault(_name, []).extend(_values)


def test_every_object_schema_used_by_an_operation_has_an_example() -> None:
    assert len(OBJECT_SCHEMAS) >= 55
    missing = [name for name in OBJECT_SCHEMAS if not EXAMPLES.get(name)]
    assert missing == []
    assert set(EXAMPLES) <= set(SCHEMAS), set(EXAMPLES) - set(SCHEMAS)


@pytest.mark.req("FR-01-07")
@pytest.mark.parametrize("name", sorted(EXAMPLES))
def test_example_is_valid_so_the_schema_is_satisfiable(name: str) -> None:
    for example in EXAMPLES[name]:
        assert [error.message for error in errors(DOC, name, example)] == []


@pytest.mark.parametrize("name", sorted(set(EXAMPLES) - OPEN_BY_DESIGN))
def test_unknown_property_is_rejected(name: str) -> None:
    for example in EXAMPLES[name]:
        if not isinstance(example, dict):
            continue
        extended = copy.deepcopy(example)
        extended["x_unexpected_property"] = 1
        assert errors(DOC, name, extended), f"{name} accepts unknown properties"


def _member_properties(schema: dict[str, Any]) -> set[str]:
    members = list(schema.get("allOf", []))
    if "$ref" in schema:
        members.append({"$ref": schema["$ref"]})
    found: set[str] = set()
    for member in members:
        target = SCHEMAS[member["$ref"].rsplit("/", 1)[1]] if "$ref" in member else member
        found.update(target.get("properties", {}))
    return found


def test_composed_schemas_are_closed_with_exactly_their_members_properties() -> None:
    """A closed base inside allOf is unsatisfiable, and unevaluatedProperties reports every field
    as unexpected when one value is bad (NF-01); composing schemas list the names instead."""
    composed = 0
    for name, schema in SCHEMAS.items():
        assert "unevaluatedProperties" not in schema, name
        members = [m for m in schema.get("allOf", []) if "$ref" in m]
        if "$ref" in schema:
            members.append(schema)
        for member in members:
            target = SCHEMAS[member["$ref"].rsplit("/", 1)[1]]
            if target.get("properties"):
                assert "additionalProperties" not in target, f"{name} composes a closed schema"
        if members and schema.get("additionalProperties") is False:
            composed += 1
            listed = schema.get("properties", {})
            assert all(value is True for value in listed.values()), name
            assert set(listed) == _member_properties(schema), name
    assert composed == 8


def test_one_bad_value_in_a_composed_schema_reports_one_error() -> None:
    entry = copy.deepcopy(EXAMPLES["IpAllowlistEntryCreate"][0])
    entry["cidr"] = "203.0.113.0"
    assert [error.validator for error in errors(DOC, "IpAllowlistEntryCreate", entry)] == [
        "pattern"
    ]


def test_the_check_rejects_the_unsatisfiable_composition_it_replaced() -> None:
    broken = copy.deepcopy(DOC)
    schemas = broken["components"]["schemas"]
    schemas["ApiKeyBase"]["additionalProperties"] = False
    schemas["ApiKeyCreated"].pop("properties")
    schemas["ApiKeyCreated"].pop("additionalProperties")
    assert errors(broken, "ApiKeyCreated", EXAMPLES["ApiKeyCreated"][0])
