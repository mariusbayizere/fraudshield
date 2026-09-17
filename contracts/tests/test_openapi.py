from __future__ import annotations

import copy
import json
import re
import unicodedata
from typing import Any

import pytest
from openapi_spec_validator import validate

from fraudshield_contracts import CONTRACTS_ROOT
from fraudshield_contracts.openapi import errors, load, operations

DOC = load()
SCHEMAS = DOC["components"]["schemas"]
EXAMPLES = DOC["components"]["examples"]
CHANNELS = {"MOBILE_MONEY", "CARD", "AGENT_BANKING", "USSD", "ONLINE", "BANK_TRANSFER"}
AUTH_MARKERS = ("x-required-scopes", "x-required-roles", "x-public", "x-refresh-cookie")
MACHINE_PATH_PREFIXES = ("/transactions/ingest", "/jobs/", "/decisions/")


def _example(name: str) -> dict[str, Any]:
    return copy.deepcopy(EXAMPLES[name]["value"])


@pytest.mark.req("FR-01-07")
def test_document_is_valid_openapi_3_1() -> None:
    assert DOC["openapi"].startswith("3.1.")
    validate(DOC)


@pytest.mark.req("FR-01-07")
def test_operations_have_unique_ids_summaries_and_tags() -> None:
    ops = list(operations(DOC))
    ids = [op.operation_id for op in ops]
    assert len(ops) >= 60
    assert all(ids), "every operation needs an operationId"
    assert len(ids) == len(set(ids)), "operationIds must be unique"
    assert all(op.spec.get("summary") and op.spec.get("tags") for op in ops)


@pytest.mark.req("FR-01-05", "FR-07-01", "FR-07-05")
def test_every_operation_declares_exactly_one_authorisation_class() -> None:
    role_enum = set(SCHEMAS["Role"]["enum"])
    scope_enum = set(SCHEMAS["ApiKeyScope"]["enum"])
    for op in operations(DOC):
        where = f"{op.method.upper()} {op.path}"
        markers = [marker for marker in AUTH_MARKERS if marker in op.spec]
        assert len(markers) == 1, f"{where}: declares {markers}"
        security = op.spec.get("security")
        if "x-public" in op.spec:
            assert security == [], f"{where}: public operations must set security: []"
        elif "x-refresh-cookie" in op.spec:
            assert security == [{"refreshCookie": []}], where
        elif "x-required-scopes" in op.spec:
            assert security == [{"apiKeyAuth": []}], where
            assert set(op.spec["x-required-scopes"]) <= scope_enum, where
            assert op.path.startswith(MACHINE_PATH_PREFIXES), (
                f"{where}: API keys are ingestion-only"
            )
        else:
            assert security in (None, [{"bearerAuth": []}]), (
                f"{where}: staff operations use bearer JWTs"
            )
            roles = op.spec["x-required-roles"]
            assert roles, where
            assert set(roles) <= role_enum, where


@pytest.mark.req("FR-01-05", "FR-07-05")
def test_api_keys_cannot_reach_staff_or_admin_operations() -> None:
    for op in operations(DOC):
        uses_api_key = any("apiKeyAuth" in entry for entry in op.spec.get("security") or [])
        if op.path.startswith(("/alerts", "/admin", "/accounts", "/rules", "/portfolio", "/auth")):
            assert not uses_api_key, f"{op.method.upper()} {op.path} accepts API keys"


def test_secured_operations_document_401_and_403_and_json_ops_have_problem_default() -> None:
    for op in operations(DOC):
        where = f"{op.method.upper()} {op.path}"
        responses = op.spec["responses"]
        if "x-required-scopes" in op.spec or "x-required-roles" in op.spec:
            assert {"401", "403"} <= responses.keys(), where
        if op.path.startswith(("/health", "/verify", "/auth/jwks")):
            continue
        assert "default" in responses, f"{where}: missing default problem response"


@pytest.mark.req("FR-01-07", "FR-01-04", "D-04")
def test_ingest_examples_cover_all_six_channels_and_validate() -> None:
    ingest = DOC["paths"]["/transactions/ingest"]["post"]
    refs = ingest["requestBody"]["content"]["application/json"]["examples"]
    payloads = [_example(ref["$ref"].rsplit("/", 1)[1]) for ref in refs.values()]

    assert {p["channel"] for p in payloads} == CHANNELS
    assert len(payloads) == 6
    for payload in payloads:
        assert errors(DOC, "TransactionIngestRequest", payload) == [], payload["channel"]
    by_channel = {p["channel"]: p for p in payloads}
    assert by_channel["USSD"]["device_fingerprint"] is None
    assert "agent_id" in by_channel["AGENT_BANKING"]


def _mutated(**changes: object) -> dict[str, Any]:
    payload = _example("IngestMobileMoney")
    for key, value in changes.items():
        if value is _REMOVE:
            payload.pop(key)
        else:
            payload[key] = value
    return payload


_REMOVE = object()


@pytest.mark.req("FR-01-02", "NFR-SEC-03")
@pytest.mark.parametrize(
    ("label", "payload"),
    [
        ("missing currency", _mutated(currency=_REMOVE)),
        ("missing transaction_id", _mutated(transaction_id=_REMOVE)),
        ("amount as JSON number", _mutated(amount=15000)),
        ("amount with 5 decimals", _mutated(amount="1.00001")),
        ("zero amount", _mutated(amount="0.0000")),
        ("negative amount", _mutated(amount="-5")),
        ("15 integer digits", _mutated(amount="100000000000000")),
        ("raw MSISDN account", _mutated(account_id="+250788123456")),
        ("raw account number counterparty", _mutated(counterparty_id="0788123456")),
        ("unsupported currency", _mutated(currency="XYZ")),
        ("3-digit MCC", _mutated(merchant_category_code="481")),
        ("latitude out of range", _mutated(latitude=91)),
        ("longitude out of range", _mutated(longitude=-181)),
        ("unknown channel", _mutated(channel="CRYPTO")),
        ("non-UTC timestamp", _mutated(transaction_timestamp="2026-09-17T10:15:30+02:00")),
        ("not a UUID", _mutated(transaction_id="12345")),
        ("unexpected field", _mutated(customer_name="Synthetic Person")),
        ("agent banking without agent_id", _mutated(channel="AGENT_BANKING")),
    ],
)
def test_invalid_ingest_payloads_are_rejected(label: str, payload: dict[str, Any]) -> None:
    assert errors(DOC, "TransactionIngestRequest", payload), f"accepted: {label}"


@pytest.mark.req("FR-01-02", "D-43")
@pytest.mark.parametrize(
    ("amount", "valid"),
    [
        ("0.0001", True),
        ("99999999999999.9999", True),
        ("15000", True),
        ("100000000000000", False),
        ("1.00001", False),
        ("01", False),
        ("1.", False),
        ("1e3", False),
    ],
)
def test_amount_format_matches_decimal_18_4_storage(amount: str, valid: bool) -> None:
    assert (errors(DOC, "PositiveAmount", amount) == []) is valid


@pytest.mark.req("D-43")
def test_currency_enum_matches_the_java_money_type() -> None:
    java = (
        CONTRACTS_ROOT.parent
        / "backend/common/src/main/java/io/github/mariusbayizere/fraudshield/common/money"
        / "CurrencyCode.java"
    ).read_text(encoding="utf-8")
    java_codes = set(re.findall(r"^\s+([A-Z]{3})\(\d\)[,;]", java, re.MULTILINE))
    assert java_codes
    assert set(SCHEMAS["CurrencyCode"]["enum"]) == java_codes


@pytest.mark.req("D-12")
def test_ingest_response_exposes_no_model_internals() -> None:
    response = SCHEMAS["DecisionResponse"]
    assert set(response["required"]) == {
        "transaction_id",
        "decision",
        "risk_tier",
        "reason_codes",
        "scoring_result_id",
        "model_version",
        "decision_latency_ms",
        "review_deadline_at",
        "ml_unavailable_fallback",
    }
    assert response["additionalProperties"] is False
    assert response["properties"]["reason_codes"]["maxItems"] == 3
    forbidden = {
        "feature_vector",
        "xgboost_score",
        "lightgbm_score",
        "anomaly_score",
        "shap_top5",
        "ensemble_score",
    }
    assert forbidden.isdisjoint(response["properties"])


@pytest.mark.req("D-12", "D-14")
def test_decision_examples_validate_and_hold_requires_a_deadline() -> None:
    for name in ("DecisionDeclined", "DecisionHold", "DecisionApproved"):
        assert errors(DOC, "DecisionResponse", _example(name)) == [], name
    hold_without_deadline = _example("DecisionHold")
    hold_without_deadline["review_deadline_at"] = None
    assert errors(DOC, "DecisionResponse", hold_without_deadline)
    too_many_reasons = _example("DecisionDeclined")
    too_many_reasons["reason_codes"] = ["A_ONE", "B_TWO", "C_THREE", "D_FOUR"]
    assert errors(DOC, "DecisionResponse", too_many_reasons)


@pytest.mark.req("FR-02-01")
def test_scoring_result_carries_all_nine_required_fields() -> None:
    required = set(SCHEMAS["ScoringResult"]["required"])
    assert {
        "ensemble_score",
        "xgboost_score",
        "lightgbm_score",
        "anomaly_score",
        "risk_tier",
        "shap_top5",
        "feature_vector",
        "model_version",
        "scoring_duration_ms",
    } <= required
    vector = SCHEMAS["ScoringResult"]["properties"]["feature_vector"]
    assert vector["minProperties"] == vector["maxProperties"] == 44


@pytest.mark.req("FR-01-06", "FR-02-06")
@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("post", "/transactions/ingest"),
        ("post", "/transactions/ingest/batch"),
        ("get", "/jobs/{job_id}"),
        ("get", "/decisions/{transaction_id}"),
        ("patch", "/admin/thresholds"),
        ("get", "/alerts"),
        ("get", "/health/ml"),
        ("get", "/health/kafka"),
    ],
)
def test_srs_named_routes_exist(method: str, path: str) -> None:
    assert method in DOC["paths"].get(path, {}), f"{method.upper()} {path}"


def test_batch_limit_is_one_thousand() -> None:
    assert SCHEMAS["TransactionBatchRequest"]["properties"]["transactions"]["maxItems"] == 1000


@pytest.mark.req("D-32")
def test_audit_event_types_are_exactly_the_twelve() -> None:
    assert len(SCHEMAS["AuditEventType"]["enum"]) == 12


def test_rule_dsl_operators_match_e6() -> None:
    comparison = SCHEMAS["RuleExpression"]["oneOf"][3]
    assert set(comparison["properties"]["op"]["enum"]) == {
        "eq",
        "ne",
        "gt",
        "gte",
        "lt",
        "lte",
        "in",
        "not_in",
        "between",
        "is_null",
        "is_not_null",
    }


@pytest.mark.req("FR-07-02", "D-24")
def test_registration_separates_department_from_requested_role() -> None:
    registration = SCHEMAS["RegistrationRequest"]["properties"]
    assert "requested_role" in registration
    assert "role" not in registration
    assert set(registration["department"]["enum"]).isdisjoint(set(SCHEMAS["Role"]["enum"]))


@pytest.mark.req("FR-07-07")
@pytest.mark.parametrize(
    ("password", "valid"),
    [
        ("Str0ng!pass", True),
        ("short1!A", True),
        ("nouppercase1!", False),
        ("NoDigits!!", False),
        ("NoSpecial123", False),
        ("Sh0rt!", False),
    ],
)
def test_password_policy(password: str, valid: bool) -> None:
    assert (errors(DOC, "Password", password) == []) is valid


@pytest.mark.req("NFR-SEC-03")
def test_no_raw_personal_data_fields_in_any_schema() -> None:
    banned = {"account_name", "customer_name", "full_name", "msisdn", "phone_number", "national_id"}

    def walk(node: object) -> None:
        if isinstance(node, dict):
            assert banned.isdisjoint(node.get("properties", {})), node.get("properties", {}).keys()
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(SCHEMAS)


def test_problem_examples_validate() -> None:
    example = DOC["components"]["responses"]["ValidationProblem"]["content"][
        "application/problem+json"
    ]["examples"]["missingField"]["value"]
    assert errors(DOC, "ValidationProblem", example) == []


@pytest.mark.req("FR-07-02", "UX-REG-01", "UX-REG-02")
def test_person_name_schema_defers_to_the_shared_unicode_rule() -> None:
    name = SCHEMAS["PersonName"]
    assert (name["minLength"], name["maxLength"]) == (2, 100)
    assert "pattern" not in name, "regex engines differ in Unicode support (ADR 0013)"
    assert name["x-validation"] == "person-name"
    vectors_path = CONTRACTS_ROOT / "validation" / "person-name-vectors.json"
    raw = vectors_path.read_bytes()
    assert raw.isascii(), (
        "vectors are stored with JSON escapes so no hidden characters are committed"
    )
    vectors = json.loads(raw)
    assert (vectors["min_code_points"], vectors["max_code_points"]) == (2, 100)
    assert {"WHITESPACE", "LENGTH", "CHARACTERS"} == {v["reason"] for v in vectors["reject"]}
    accepted = [v["normalised"] for v in vectors["accept"]]
    assert all(unicodedata.is_normalized("NFC", value) for value in accepted)
    assert any(not unicodedata.is_normalized("NFC", v["input"]) for v in vectors["accept"])
