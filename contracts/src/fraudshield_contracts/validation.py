"""Map JSON Schema errors to API validation error codes (ADR 0011 section 6).

The mapping is the executable form of the ADR table. Contract tests run it over the shared request
vectors, so a vector cannot claim a code or status that the schema does not produce; the M6 and M7
controllers must produce the same (field, code) pairs.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from typing import Any

from jsonschema.exceptions import ValidationError

from fraudshield_contracts.openapi import load, validator

KEYWORD_CODES = {
    "required": "required",
    "minProperties": "required",
    "additionalProperties": "unknown_field",
    "type": "type_mismatch",
    "format": "invalid_format",
    "pattern": "invalid_format",
    "enum": "unsupported_value",
    "const": "unsupported_value",
    "minimum": "out_of_range",
    "maximum": "out_of_range",
    "exclusiveMinimum": "out_of_range",
    "exclusiveMaximum": "out_of_range",
    # `not` appears in request schemas only to exclude zero amounts (PositiveAmount).
    "not": "out_of_range",
    "minLength": "length_out_of_range",
    "maxLength": "length_out_of_range",
    "minItems": "item_count_out_of_range",
    "maxItems": "item_count_out_of_range",
    "maxProperties": "item_count_out_of_range",
    "uniqueItems": "duplicate_items",
}
# A decimal string in canonical form (no leading zeros, at most four decimals) that still fails the
# DecimalAmount pattern is out of range (negative, or more than 14 integer digits), not malformed.
_CANONICAL_DECIMAL = re.compile(r"-?(0|[1-9][0-9]*)(\.[0-9]{1,4})?")
_QUOTED = re.compile(r"'([^']+)'")


def field_path(parts: Iterable[str | int]) -> str:
    path = ""
    for part in parts:
        path += f"[{part}]" if isinstance(part, int) else (f".{part}" if path else str(part))
    return path


def _join(base: str, name: str) -> str:
    return f"{base}.{name}" if base else name


def _leaf_codes(error: ValidationError, schemas: dict[str, Any]) -> set[tuple[str, str]]:
    path = field_path(error.absolute_path)
    keyword = str(error.validator)
    if keyword in ("oneOf", "anyOf"):
        return _branch_codes(error, schemas, path)
    if keyword == "required":
        return {(_join(path, name), "required") for name in _QUOTED.findall(error.message)[:1]}
    if keyword == "additionalProperties":
        names = _QUOTED.findall(error.message)
        return {(_join(path, name), "unknown_field") for name in names}
    if keyword == "pattern":
        if error.validator_value == schemas["Token"]["pattern"]:
            return {(path, "not_a_token")}
        if error.validator_value == schemas["DecimalAmount"]["pattern"] and isinstance(
            error.instance, str
        ):
            canonical = _CANONICAL_DECIMAL.fullmatch(error.instance)
            return {(path, "out_of_range" if canonical else "invalid_format")}
    if keyword not in KEYWORD_CODES:
        raise ValueError(f"no error code mapped for JSON Schema keyword {keyword!r} at {path}")
    return {(path, KEYWORD_CODES[keyword])}


def _branch_codes(
    error: ValidationError, schemas: dict[str, Any], path: str
) -> set[tuple[str, str]]:
    """For oneOf/anyOf, report the branch whose JSON type matched, or a type mismatch."""
    branches: dict[int, list[ValidationError]] = {}
    for sub in error.context or []:
        branches.setdefault(int(sub.relative_schema_path[0]), []).append(sub)
    matching = [
        errors
        for errors in branches.values()
        if not any(e.validator == "type" and not e.relative_path for e in errors)
    ]
    if not matching:
        return {(path, "type_mismatch")}
    codes: set[tuple[str, str]] = set()
    for sub in matching[0]:
        codes |= _leaf_codes(sub, schemas)
    return codes


def error_codes(schema_name: str, payload: object) -> set[tuple[str, str]]:
    """The (field, code) pairs the API reports for ``payload`` against a component schema."""
    document = load()
    schemas = document["components"]["schemas"]
    codes: set[tuple[str, str]] = set()
    for error in validator(document, schema_name).iter_errors(payload):
        codes |= _leaf_codes(error, schemas)
    # A value of the wrong JSON type gets only type_mismatch: keywords for other types pass
    # vacuously and `not` would then misreport it as out of range.
    mistyped = {field for field, code in codes if code == "type_mismatch"}
    return {
        (field, code) for field, code in codes if field not in mistyped or code == "type_mismatch"
    }
