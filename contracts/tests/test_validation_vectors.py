from __future__ import annotations

import json
from typing import Any

import pytest

from fraudshield_contracts import CONTRACTS_ROOT
from fraudshield_contracts.openapi import errors, load

DOC = load()
SCHEMAS = DOC["components"]["schemas"]
VALIDATION = CONTRACTS_ROOT / "validation"


def _vectors(name: str) -> Any:
    raw = (VALIDATION / name).read_bytes()
    assert raw.isascii(), f"{name} must use JSON escapes so no hidden characters are committed"
    return json.loads(raw)


REQUESTS = _vectors("request-validation-vectors.json")
CASES = [
    pytest.param(schema, case, id=f"{schema}: {case['label']}")
    for schema, cases in REQUESTS["schemas"].items()
    for case in cases
]
STATUS_BY_CODE: dict[str, int] = SCHEMAS["ValidationErrorCode"]["x-status-by-code"]


def test_every_error_code_has_exactly_one_status() -> None:
    assert set(STATUS_BY_CODE) == set(SCHEMAS["ValidationErrorCode"]["enum"])
    assert set(STATUS_BY_CODE.values()) == {400, 422}


@pytest.mark.req("FR-01-02", "NFR-SEC-03")
@pytest.mark.parametrize(("schema", "case"), CASES)
def test_request_vector(schema: str, case: dict[str, Any]) -> None:
    codes = [error["code"] for error in case["expected_errors"]]
    assert set(codes) <= set(STATUS_BY_CODE), codes
    if codes:
        expected_status = 400 if any(STATUS_BY_CODE[c] == 400 for c in codes) else 422
        assert case["expected_status"] == expected_status
    else:
        assert case["expected_status"] in (200, 201)
    if "raw_body" in case:
        assert not case["schema_detectable"]
        with pytest.raises(json.JSONDecodeError):
            json.loads(case["raw_body"])
        return
    schema_errors = errors(DOC, schema, case["body"])
    if case["schema_detectable"]:
        assert bool(schema_errors) is bool(codes), [e.message for e in schema_errors]
    else:
        assert schema_errors == [], "a server-side check must be one the schema cannot make"


@pytest.mark.req("FR-01-02")
def test_ingest_vectors_cover_both_statuses_and_every_ingest_code() -> None:
    cases = REQUESTS["schemas"]["TransactionIngestRequest"]
    assert {c["expected_status"] for c in cases} == {200, 400, 422}
    codes = {e["code"] for c in cases for e in c["expected_errors"]}
    assert {
        "malformed_json",
        "required",
        "unknown_field",
        "not_a_token",
        "type_mismatch",
        "invalid_format",
        "unsupported_value",
        "out_of_range",
        "timestamp_in_future",
    } <= codes


PASSWORDS = _vectors("password-vectors.json")


def _password_rejection(value: str) -> str | None:
    """Reference implementation of the rule in ADR 0014, in the documented check order."""
    if not PASSWORDS["min_characters"] <= len(value) <= PASSWORDS["max_characters"]:
        return "LENGTH"
    if len(value.encode("utf-8")) > PASSWORDS["max_utf8_bytes"]:
        return "BYTES"
    checks = (
        ("UPPER", lambda ch: "A" <= ch <= "Z"),
        ("LOWER", lambda ch: "a" <= ch <= "z"),
        ("DIGIT", lambda ch: "0" <= ch <= "9"),
        ("SPECIAL", lambda ch: not (ch.isascii() and ch.isalnum()) and not ch.isspace()),
    )
    for reason, matches in checks:
        if not any(matches(ch) for ch in value):
            return reason
    return None


@pytest.mark.req("FR-07-07")
def test_password_schema_limits_match_the_vectors() -> None:
    schema = SCHEMAS["Password"]
    assert (schema["minLength"], schema["maxLength"], schema["x-max-utf8-bytes"]) == (
        PASSWORDS["min_characters"],
        PASSWORDS["max_characters"],
        PASSWORDS["max_utf8_bytes"],
    )
    login = SCHEMAS["LoginRequest"]["properties"]["password"]
    assert login["x-max-utf8-bytes"] == PASSWORDS["max_utf8_bytes"]


@pytest.mark.req("FR-07-07")
@pytest.mark.parametrize("vector", PASSWORDS["accept"], ids=lambda v: v["note"])
def test_password_accepted(vector: dict[str, str]) -> None:
    assert _password_rejection(vector["input"]) is None
    assert errors(DOC, "Password", vector["input"]) == []


@pytest.mark.req("FR-07-07")
@pytest.mark.parametrize("vector", PASSWORDS["reject"], ids=lambda v: v["note"])
def test_password_rejected(vector: dict[str, str]) -> None:
    assert _password_rejection(vector["input"]) == vector["reason"]
    schema_rejects = bool(errors(DOC, "Password", vector["input"]))
    # JSON Schema counts characters, not bytes, so only the byte limit is invisible to it.
    assert schema_rejects is (vector["reason"] != "BYTES")


def test_password_vectors_include_both_byte_boundaries() -> None:
    lengths = {len(v["input"].encode("utf-8")) for v in PASSWORDS["accept"]}
    rejected_bytes = {
        len(v["input"].encode("utf-8")) for v in PASSWORDS["reject"] if v["reason"] == "BYTES"
    }
    assert 72 in lengths
    assert min(rejected_bytes) <= 75
    assert any(not v["input"].isascii() for v in PASSWORDS["accept"])
