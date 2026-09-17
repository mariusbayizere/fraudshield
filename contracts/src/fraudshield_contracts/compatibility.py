"""Backward-compatibility check for Kafka JSON Schemas against a committed baseline (ADR 0012).

A change is compatible when every message valid under the baseline schema is still valid under the
current schema, so consumers can be upgraded before producers. The check is deliberately
conservative: a change it cannot prove widening (a new or edited pattern, a changed conditional)
is reported as breaking, and the author must add a new ``schema_version`` instead.
"""

from __future__ import annotations

import json
from typing import Any

# Keywords that do not affect which instances are valid.
ANNOTATIONS = frozenset(
    {"$schema", "$id", "$comment", "title", "description", "examples", "default", "deprecated"}
)
LOWER_BOUNDS = ("minimum", "exclusiveMinimum", "minLength", "minItems", "minProperties")
UPPER_BOUNDS = ("maximum", "exclusiveMaximum", "maxLength", "maxItems", "maxProperties")
CLOSING = ("additionalProperties", "unevaluatedProperties")
HANDLED = frozenset(
    {
        "$ref",
        "type",
        "enum",
        "const",
        "required",
        "properties",
        "$defs",
        "items",
        "allOf",
        "anyOf",
        "oneOf",
        *LOWER_BOUNDS,
        *UPPER_BOUNDS,
        *CLOSING,
    }
)


def _types(value: str | list[str]) -> set[str]:
    return {value} if isinstance(value, str) else set(value)


def _same(a: object, b: object) -> bool:
    return json.dumps(a, sort_keys=True) == json.dumps(b, sort_keys=True)


def breaking_changes(old: Any, new: Any, path: str = "#") -> list[str]:
    """Every change from ``old`` to ``new`` that could reject a message ``old`` accepted."""
    if not isinstance(old, dict) or not isinstance(new, dict):
        return [] if _same(old, new) else [f"{path}: schema changed from {old!r} to {new!r}"]
    return [
        *_value_changes(old, new, path),
        *_object_changes(old, new, path),
        *_nested_changes(old, new, path),
        *[
            f"{path}: {keyword} changed (not provably compatible)"
            for keyword in sorted((set(old) | set(new)) - HANDLED - ANNOTATIONS)
            if not _same(old.get(keyword), new.get(keyword))
        ],
    ]


def _value_changes(old: dict[str, Any], new: dict[str, Any], path: str) -> list[str]:
    found: list[str] = []
    if old.get("$ref") != new.get("$ref"):
        found.append(f"{path}: $ref changed from {old.get('$ref')} to {new.get('$ref')}")
    if "type" in new:
        if "type" not in old:
            found.append(f"{path}: type {new['type']} added")
        elif not _types(old["type"]) <= _types(new["type"]):
            found.append(f"{path}: type narrowed from {old['type']} to {new['type']}")
    if "enum" in new:
        removed = [v for v in old.get("enum", []) if v not in new["enum"]]
        if "enum" not in old:
            found.append(f"{path}: enum added")
        elif removed:
            found.append(f"{path}: enum values removed {removed}")
    if "const" in new and ("const" not in old or not _same(old["const"], new["const"])):
        found.append(f"{path}: const added or changed")
    found.extend(
        f"{path}: {keyword} tightened to {new[keyword]}"
        for keyword in LOWER_BOUNDS
        if keyword in new and (keyword not in old or new[keyword] > old[keyword])
    )
    found.extend(
        f"{path}: {keyword} tightened to {new[keyword]}"
        for keyword in UPPER_BOUNDS
        if keyword in new and (keyword not in old or new[keyword] < old[keyword])
    )
    return found


def _object_changes(old: dict[str, Any], new: dict[str, Any], path: str) -> list[str]:
    found: list[str] = []
    added_required = sorted(set(new.get("required", [])) - set(old.get("required", [])))
    if added_required:
        found.append(f"{path}: newly required {added_required}")
    new_properties = new.get("properties", {})
    for name, schema in old.get("properties", {}).items():
        where = f"{path}/properties/{name}"
        if name not in new_properties:
            found.append(f"{where}: property removed")
        else:
            found.extend(breaking_changes(schema, new_properties[name], where))
    for keyword in CLOSING:
        old_value, new_value = old.get(keyword, True), new.get(keyword, True)
        if new_value is False and old_value is not False:
            found.append(f"{path}: {keyword} closed")
        elif isinstance(old_value, dict) and isinstance(new_value, dict):
            found.extend(breaking_changes(old_value, new_value, f"{path}/{keyword}"))
        elif old_value is True and isinstance(new_value, dict):
            found.append(f"{path}: {keyword} constrained")
    return found


def _nested_changes(old: dict[str, Any], new: dict[str, Any], path: str) -> list[str]:
    found: list[str] = []
    for name, schema in old.get("$defs", {}).items():
        if name not in new.get("$defs", {}):
            found.append(f"{path}/$defs/{name}: definition removed")
        else:
            found.extend(breaking_changes(schema, new["$defs"][name], f"{path}/$defs/{name}"))
    if "items" in old or "items" in new:
        found.extend(breaking_changes(old.get("items", {}), new.get("items", {}), f"{path}/items"))
    for keyword in ("allOf", "anyOf", "oneOf"):
        old_list, new_list = old.get(keyword, []), new.get(keyword, [])
        if keyword == "anyOf" and len(new_list) < len(old_list):
            found.append(f"{path}: {keyword} branches removed")
        elif keyword != "anyOf" and len(new_list) != len(old_list):
            found.append(
                f"{path}: {keyword} changed from {len(old_list)} to {len(new_list)} members"
            )
        for index, (a, b) in enumerate(zip(old_list, new_list, strict=False)):
            found.extend(breaking_changes(a, b, f"{path}/{keyword}/{index}"))
    return found
