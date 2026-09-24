"""Backward-compatibility check for Kafka JSON Schemas against a committed baseline (ADR 0012).

A change is compatible when every message valid under the baseline schema is still valid under the
current schema, so consumers can be upgraded before producers. The check is deliberately
conservative: a change it cannot prove widening (a new or edited pattern, a changed conditional,
any edit inside a ``oneOf`` or to a definition a ``oneOf`` uses, a constrained property added to an
open object) is reported as breaking, and the author must publish a new topic version instead.
Widening a type, an enum or a bound outside a ``oneOf`` is compatible under this mode.
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


def _constrains(schema: object) -> bool:
    return schema is not True and not (isinstance(schema, dict) and set(schema) <= ANNOTATIONS)


def _refs(node: object, schema_id: str) -> set[str]:
    found: set[str] = set()
    if isinstance(node, dict):
        ref = node.get("$ref")
        if isinstance(ref, str):
            found.add(f"{schema_id}{ref}" if ref.startswith("#") else ref)
        for value in node.values():
            found |= _refs(value, schema_id)
    elif isinstance(node, list):
        for value in node:
            found |= _refs(value, schema_id)
    return found


def _oneof_refs(node: object, schema_id: str) -> set[str]:
    found: set[str] = set()
    if isinstance(node, dict):
        if "oneOf" in node:
            found |= _refs(node["oneOf"], schema_id)
        for value in node.values():
            found |= _oneof_refs(value, schema_id)
    elif isinstance(node, list):
        for value in node:
            found |= _oneof_refs(value, schema_id)
    return found


def _resolve(ref: str, schemas: dict[str, Any]) -> Any:
    document, _, pointer = ref.partition("#")
    target: Any = schemas.get(document)
    for part in pointer.strip("/").split("/") if pointer.strip("/") else []:
        if not isinstance(target, dict) or part not in target:
            return None
        target = target[part]
    return target


def fragile_definitions(schemas: dict[str, Any]) -> frozenset[str]:
    """Definitions reachable from a ``oneOf`` branch: widening them can make branches overlap."""
    pending: set[str] = set()
    for schema_id, schema in schemas.items():
        pending |= _oneof_refs(schema, schema_id)
    fragile: set[str] = set()
    while pending:
        ref = pending.pop()
        if ref in fragile:
            continue
        fragile.add(ref)
        document = ref.partition("#")[0]
        pending |= _refs(_resolve(ref, schemas), document) - fragile
    return frozenset(fragile)


def breaking_changes_between(old: dict[str, Any], new: dict[str, Any]) -> dict[str, list[str]]:
    """Breaking changes per schema ``$id`` between two complete schema sets."""
    fragile = fragile_definitions(old) | fragile_definitions(new)
    return {
        schema_id: (
            breaking_changes(schema, new[schema_id], schema_id, fragile)
            if schema_id in new
            else [f"{schema_id}: schema removed"]
        )
        for schema_id, schema in old.items()
    }


def breaking_changes(
    old: Any, new: Any, path: str = "#", fragile: frozenset[str] = frozenset()
) -> list[str]:
    """Every change from ``old`` to ``new`` that could reject a message ``old`` accepted.

    ``path`` starts as the schema's ``$id`` when called for a whole document, so definitions listed
    in ``fragile`` (see :func:`fragile_definitions`) can be recognised.
    """
    if not isinstance(old, dict) or not isinstance(new, dict):
        return [] if _same(old, new) else [f"{path}: schema changed from {old!r} to {new!r}"]
    return [
        *_value_changes(old, new, path),
        *_object_changes(old, new, path, fragile),
        *_nested_changes(old, new, path, fragile),
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


def _object_changes(
    old: dict[str, Any], new: dict[str, Any], path: str, fragile: frozenset[str]
) -> list[str]:
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
            found.extend(breaking_changes(schema, new_properties[name], where, fragile))
    old_open = old.get("additionalProperties", True)
    old_closed = old_open is False or old.get("unevaluatedProperties", True) is False
    for name, schema in new_properties.items():
        if name in old.get("properties", {}) or old_closed:
            continue
        where = f"{path}/properties/{name}"
        if isinstance(old_open, dict):
            found.extend(breaking_changes(old_open, schema, where, fragile))
        elif _constrains(schema):
            found.append(f"{where}: constrained property added to an open object")
    for keyword in CLOSING:
        old_value, new_value = old.get(keyword, True), new.get(keyword, True)
        if new_value is False and old_value is not False:
            found.append(f"{path}: {keyword} closed")
        elif isinstance(old_value, dict) and isinstance(new_value, dict):
            found.extend(breaking_changes(old_value, new_value, f"{path}/{keyword}", fragile))
        elif old_value is True and isinstance(new_value, dict):
            found.append(f"{path}: {keyword} constrained")
    return found


def _nested_changes(
    old: dict[str, Any], new: dict[str, Any], path: str, fragile: frozenset[str]
) -> list[str]:
    found: list[str] = []
    for name, schema in old.get("$defs", {}).items():
        where = f"{path}/$defs/{name}"
        if name not in new.get("$defs", {}):
            found.append(f"{where}: definition removed")
        elif f"{path}#/$defs/{name}" in fragile and not _same(schema, new["$defs"][name]):
            found.append(f"{where}: changed, and a oneOf uses it (branches could overlap)")
        else:
            found.extend(breaking_changes(schema, new["$defs"][name], where, fragile))
    if "items" in old or "items" in new:
        found.extend(
            breaking_changes(old.get("items", {}), new.get("items", {}), f"{path}/items", fragile)
        )
    if ("oneOf" in old or "oneOf" in new) and not _same(old.get("oneOf"), new.get("oneOf")):
        found.append(f"{path}: oneOf changed (branches must stay exactly exclusive)")
    old_any, new_any = old.get("anyOf", []), new.get("anyOf", [])
    if len(new_any) < len(old_any):
        found.append(f"{path}: anyOf branches removed")
    for index, (a, b) in enumerate(zip(old_any, new_any, strict=False)):
        found.extend(breaking_changes(a, b, f"{path}/anyOf/{index}", fragile))
    old_all, new_all = old.get("allOf", []), new.get("allOf", [])
    if len(new_all) != len(old_all):
        found.append(f"{path}: allOf changed from {len(old_all)} to {len(new_all)} members")
    for index, (a, b) in enumerate(zip(old_all, new_all, strict=False)):
        found.extend(breaking_changes(a, b, f"{path}/allOf/{index}", fragile))
    return found
