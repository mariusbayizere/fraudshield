from __future__ import annotations

import json
import unicodedata
from collections.abc import Callable
from typing import Any

import pytest

from fraudshield_contracts import CONTRACTS_ROOT, validation
from fraudshield_contracts.openapi import errors, load, operations
from fraudshield_contracts.validation import error_codes

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
    expected = {(error["field"], error["code"]) for error in case["expected_errors"]}
    derived = error_codes(schema, case["body"])
    if case["schema_detectable"]:
        assert derived == expected, (
            "the schema must produce exactly the vector's (field, code) pairs"
        )
    else:
        assert derived == set(), "a server-side check must be one the schema cannot make"


def _operations_using(schema: str) -> list[Any]:
    ref = f"#/components/schemas/{schema}"
    return [
        op
        for op in operations(DOC)
        if op.spec.get("requestBody", {})
        .get("content", {})
        .get("application/json", {})
        .get("schema", {})
        .get("$ref")
        == ref
    ]


@pytest.mark.req("FR-01-02")
@pytest.mark.parametrize("schema", sorted(REQUESTS["schemas"]))
def test_operations_document_every_status_their_vectors_expect(schema: str) -> None:
    using = _operations_using(schema)
    assert using, schema
    statuses = {str(case["expected_status"]) for case in REQUESTS["schemas"][schema]}
    for op in using:
        assert statuses <= set(op.spec["responses"]), (op.operation_id, statuses)


def test_every_json_request_body_documents_400_and_422_validation_problems() -> None:
    checked = 0
    for op in operations(DOC):
        content = op.spec.get("requestBody", {}).get("content", {})
        if not ({"application/json", "multipart/form-data"} & set(content)):
            continue
        checked += 1
        for status in ("400", "422"):
            ref = op.spec["responses"].get(status, {}).get("$ref", "")
            assert ref.endswith("/ValidationProblem"), (op.operation_id, status)
    assert checked >= 30


@pytest.mark.parametrize(
    ("label", "payload", "expected"),
    [
        (
            "wrong type for a nullable token",
            {"device_fingerprint": 5},
            ("device_fingerprint", "type_mismatch"),
        ),
        (
            "raw number in a nullable token",
            {"device_fingerprint": "0000000001"},
            ("device_fingerprint", "not_a_token"),
        ),
        ("string too long", {"merchant_name": "x" * 101}, ("merchant_name", "length_out_of_range")),
    ],
)
def test_classifier_handles_composed_and_length_keywords(
    label: str, payload: dict[str, Any], expected: tuple[str, str]
) -> None:
    body = REQUESTS["schemas"]["TransactionIngestRequest"][0]["body"] | payload
    assert error_codes("TransactionIngestRequest", body) == {expected}, label


def test_classifier_reports_duplicates_item_counts_and_unmapped_keywords(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    key = {"name": "Core banking", "scopes": ["ingest:write", "ingest:write"]}
    assert error_codes("ApiKeyCreate", key) == {("scopes", "duplicate_items")}
    assert error_codes("ApiKeyCreate", key | {"scopes": []}) == {
        ("scopes", "item_count_out_of_range")
    }
    monkeypatch.delitem(validation.KEYWORD_CODES, "uniqueItems")
    with pytest.raises(ValueError, match="no error code mapped"):
        error_codes("ApiKeyCreate", key)


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
    if any(ch != " " and unicodedata.category(ch)[0] in "CZ" for ch in value):
        return "CHARACTERS"
    checks: tuple[tuple[str, Callable[[str], bool]], ...] = (
        ("UPPER", lambda ch: "A" <= ch <= "Z"),
        ("LOWER", lambda ch: "a" <= ch <= "z"),
        ("DIGIT", lambda ch: "0" <= ch <= "9"),
        ("SPECIAL", lambda ch: ch != " " and not (ch.isascii() and ch.isalnum())),
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
    assert "x-max-utf8-bytes" not in login
    assert login["maxLength"] == 1024, "sign-in answers 401 above 72 bytes, never a 4xx validation"
    change = load()["paths"]["/auth/password"]["put"]["requestBody"]["content"]["application/json"]
    assert change["schema"]["properties"]["current_password"]["maxLength"] == 1024


@pytest.mark.req("FR-07-07")
@pytest.mark.parametrize("vector", PASSWORDS["accept"], ids=lambda v: v["note"])
def test_password_accepted(vector: dict[str, str]) -> None:
    assert _password_rejection(vector["input"]) is None
    assert errors(DOC, "Password", vector["input"]) == []


@pytest.mark.req("FR-07-07")
@pytest.mark.parametrize("vector", PASSWORDS["reject"], ids=lambda v: v["note"])
def test_password_rejected(vector: dict[str, Any]) -> None:
    assert _password_rejection(vector["input"]) == vector["reason"]
    schema_rejects = bool(errors(DOC, "Password", vector["input"]))
    # JSON Schema cannot count bytes or test Unicode categories; the vector says which cases the
    # schema screen catches on its own.
    assert schema_rejects is vector["schema_detectable"]
    if vector["reason"] in ("LENGTH", "UPPER", "LOWER", "DIGIT", "SPECIAL"):
        assert vector["schema_detectable"]


def test_password_vectors_include_both_byte_boundaries() -> None:
    lengths = {len(v["input"].encode("utf-8")) for v in PASSWORDS["accept"]}
    rejected_bytes = {
        len(v["input"].encode("utf-8")) for v in PASSWORDS["reject"] if v["reason"] == "BYTES"
    }
    assert 72 in lengths
    assert min(rejected_bytes) <= 75
    assert any(not v["input"].isascii() for v in PASSWORDS["accept"])
    reasons = {v["reason"] for v in PASSWORDS["reject"]}
    assert reasons == {"LENGTH", "BYTES", "CHARACTERS", "UPPER", "LOWER", "DIGIT", "SPECIAL"}
