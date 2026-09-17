from __future__ import annotations

import copy
import json
import re
from typing import Any

import pytest
from jsonschema import Draft202012Validator

from fraudshield_contracts.events import (
    KAFKA_ROOT,
    catalogue,
    schemas,
    tolerant,
    topics,
    validate_event,
)
from fraudshield_contracts.openapi import load

# ITU-T E.164 country code 999 is reserved and assigned to no country, so these have the shape of
# phone and account numbers without being anyone's number.
UNASSIGNED_MSISDN = "+999000000001"
UNASSIGNED_LOCAL_NUMBER = "0000000001"
INTERNAL_SERVICES = {
    "fraudshield-api",
    "fraudshield-ml",
    "fraudshield-ml-worker",
    "persistence",
    "fallback-replay",
    "feature-store-updater",
    "alerting",
    "analytics",
    "websocket-fanout",
    "notification-service",
    "deadline-scheduler",
    "webhook-dispatcher",
    "audit-writer",
    "label-pipeline",
    "model-performance",
    "shadow-comparator",
}

C3_TOPICS = {
    "fs.transactions.raw",
    "fs.transactions.scored",
    "fs.alerts.high",
    "fs.alerts.medium",
    "fs.alerts.anomaly",
    "fs.decisions.final",
    "fs.audit.events",
    "fs.notifications.customer",
    "fs.notifications.staff",
    "fs.labels",
    "fs.ml.retrain",
    "fs.ml.shadow",
    "fs.config.changes",
}


def _example(topic: str) -> dict[str, Any]:
    loaded: dict[str, Any] = json.loads((KAFKA_ROOT / "examples" / f"{topic}.json").read_text())
    return loaded


def test_catalogue_lists_exactly_the_c3_topics_with_key_retention_and_dlq() -> None:
    listed = {t.name for t in topics()}
    assert listed == C3_TOPICS
    assert catalogue()["defaults"]["dlq_suffix"] == ".dlq"
    for topic in topics():
        assert topic.key, topic.name
        assert topic.partitions > 0, topic.name
        assert re.fullmatch(r"P\d+D", topic.retention), topic.name
        assert topic.payload_schema.is_file(), topic.name
        assert topic.dlq == f"{topic.name}.dlq"


def test_if_conditions_require_their_discriminator() -> None:
    """An `if` on an absent property passes vacuously and would apply `then` to every event."""

    def walk(node: object, where: str) -> None:
        if isinstance(node, dict):
            condition = node.get("if")
            if isinstance(condition, dict):
                tested = set(condition.get("properties", {}))
                assert tested <= set(condition.get("required", [])), where
            for key, value in node.items():
                walk(value, f"{where}/{key}")
        elif isinstance(node, list):
            for index, value in enumerate(node):
                walk(value, f"{where}/{index}")

    for schema_id, schema in schemas().items():
        walk(schema, schema_id)


def test_every_schema_is_valid_draft_2020_12_with_a_unique_id() -> None:
    for schema_id, schema in schemas().items():
        Draft202012Validator.check_schema(schema)
        assert schema_id.startswith("urn:fraudshield:kafka:")
        assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"


@pytest.mark.parametrize("topic", sorted(C3_TOPICS))
def test_topic_examples_validate(topic: str) -> None:
    [spec] = [t for t in topics() if t.name == topic]
    assert validate_event(spec, _example(topic)) == []


def test_example_event_ids_are_unique() -> None:
    ids = [_example(topic)["event_id"] for topic in sorted(C3_TOPICS)]
    assert len(ids) == len(set(ids))


@pytest.mark.parametrize("field", ["event_type", "schema_version"])
def test_envelope_must_match_the_topic_binding(field: str) -> None:
    event = _example("fs.decisions.final")
    event[field] = "alert.created" if field == "event_type" else 2
    assert validate_event(_topic("fs.decisions.final"), event)


def test_consumers_tolerate_unknown_fields_that_producers_may_not_send() -> None:
    event = _example("fs.decisions.final")
    event["payload"]["added_in_a_later_producer"] = "value"
    topic = _topic("fs.decisions.final")
    assert validate_event(topic, event), "producers validate against the closed schema"
    assert validate_event(topic, event, reader=True) == []
    missing = copy.deepcopy(_example("fs.decisions.final"))
    del missing["payload"]["decision_sequence"]
    assert validate_event(topic, missing, reader=True), "tolerance never drops required fields"
    assert tolerant(
        {"additionalProperties": False, "items": [{"unevaluatedProperties": False}]}
    ) == {"items": [{}]}


def test_only_internal_services_touch_kafka() -> None:
    for topic in topics():
        principals = set(topic.spec["producers"]) | set(topic.spec["consumers"])
        assert principals <= INTERNAL_SERVICES, (topic.name, principals - INTERNAL_SERVICES)
    envelope = schemas()["urn:fraudshield:kafka:envelope"]
    assert "authenticated principal" in envelope["properties"]["institution_id"]["description"]


def _topic(name: str) -> Any:
    return next(t for t in topics() if t.name == name)


@pytest.mark.req("D-10", "D-14")
def test_alert_payload_rules_for_medium_and_anomaly() -> None:
    medium = _example("fs.alerts.medium")
    medium["payload"]["review_deadline_at"] = None
    assert validate_event(_topic("fs.alerts.medium"), medium), "MEDIUM needs a review deadline"

    anomaly = _example("fs.alerts.anomaly")
    anomaly["payload"]["holds_transaction"] = True
    assert validate_event(_topic("fs.alerts.anomaly"), anomaly), "ANOMALY alerts never hold (D-10)"


@pytest.mark.req("D-25", "NFR-SEC-03")
def test_customer_notifications_carry_no_contact_details() -> None:
    topic = _topic("fs.notifications.customer")
    event = _example("fs.notifications.customer")
    event["payload"]["phone"] = UNASSIGNED_MSISDN
    assert validate_event(topic, event)
    unmasked = copy.deepcopy(_example("fs.notifications.customer"))
    unmasked["payload"]["parameters"]["masked_account"] = UNASSIGNED_LOCAL_NUMBER
    assert validate_event(topic, unmasked)
    in_parameters = copy.deepcopy(_example("fs.notifications.customer"))
    in_parameters["payload"]["parameters"]["phone"] = UNASSIGNED_MSISDN
    assert validate_event(topic, in_parameters)


@pytest.mark.req("D-25")
@pytest.mark.parametrize(
    ("label", "mutate"),
    [
        ("missing verification flag", lambda p: p.pop("verification_link_allowed")),
        ("auto-block SMS without its event", lambda p: p.pop("auto_block_event_id")),
        ("missing reference code", lambda p: p["parameters"].pop("reference_code")),
        ("missing masked account", lambda p: p["parameters"].pop("masked_account")),
        ("local time without a zone", lambda p: p["parameters"].update(local_time="10:15")),
    ],
)
def test_customer_notifications_fail_closed(label: str, mutate: Any) -> None:
    event = _example("fs.notifications.customer")
    mutate(event["payload"])
    assert validate_event(_topic("fs.notifications.customer"), event), label


@pytest.mark.req("NFR-SEC-03")
@pytest.mark.parametrize(
    ("kind", "parameters"),
    [
        ("WELCOME", {"user_id": "5b1f0a2c-3d4e-4f60-8a7b-9c0d1e2f3a4b", "temporary_password": "x"}),
        (
            "PASSWORD_RESET_CODE",
            {"user_id": "5b1f0a2c-3d4e-4f60-8a7b-9c0d1e2f3a4b", "code": "123456"},
        ),
        (
            "EMAIL_VERIFICATION",
            {"user_id": "5b1f0a2c-3d4e-4f60-8a7b-9c0d1e2f3a4b", "link": "https://x"},
        ),
        ("ACCOUNT_LOCKED", {"user_id": "5b1f0a2c-3d4e-4f60-8a7b-9c0d1e2f3a4b"}),
        ("ACCOUNT_FROZEN", {"account_token": "tok_exampleAccount0000000001", "email": "a@b.c"}),
    ],
)
def test_staff_notifications_carry_no_credentials_or_contact_details(
    kind: str, parameters: dict[str, Any]
) -> None:
    event = _example("fs.notifications.staff")
    event["payload"]["kind"] = kind
    event["payload"]["parameters"] = parameters
    assert validate_event(_topic("fs.notifications.staff"), event)


def test_every_staff_notification_kind_has_a_closed_parameter_schema() -> None:
    schema = schemas()["urn:fraudshield:kafka:notification-staff"]
    kinds = set(schema["properties"]["kind"]["enum"])
    bound = {rule["if"]["properties"]["kind"]["const"] for rule in schema["allOf"] if "if" in rule}
    assert bound == kinds
    assert all(d["additionalProperties"] is False for d in schema["$defs"].values())


@pytest.mark.req("NFR-SEC-03")
def test_no_event_schema_defines_personal_data_properties() -> None:
    banned = {
        "phone",
        "msisdn",
        "email",
        "customer_name",
        "account_name",
        "full_name",
        "national_id",
    }
    for schema_id, schema in schemas().items():
        text = json.dumps(schema)
        for field in banned:
            assert f'"{field}":' not in text, f"{schema_id} defines {field}"


@pytest.mark.req("D-32")
def test_audit_event_types_match_the_openapi_contract() -> None:
    common = schemas()["urn:fraudshield:kafka:common"]["$defs"]
    openapi = load()["components"]["schemas"]
    assert common["AuditEventType"]["enum"] == openapi["AuditEventType"]["enum"]


def _resolve(node: Any) -> Any:
    """Inline every $ref from either contract and drop annotations, for structural comparison."""
    if isinstance(node, list):
        return [_resolve(value) for value in node]
    if not isinstance(node, dict):
        return node
    resolved = {k: _resolve(v) for k, v in node.items() if k not in ANNOTATIONS | {"$ref"}}
    ref = node.get("$ref")
    if ref is None:
        return resolved
    if ref.startswith("#/components/schemas/"):
        target = load()["components"]["schemas"][ref.rsplit("/", 1)[1]]
    else:
        document, _, pointer = ref.partition("#")
        target = schemas()[document]
        for part in pointer.strip("/").split("/"):
            target = target[part]
    return _resolve(target) | resolved


ANNOTATIONS = {"$schema", "$id", "title", "description", "x-validation", "examples"}
UUID = {"type": "string", "format": "uuid"}


@pytest.mark.req("D-43")
@pytest.mark.parametrize(
    "name",
    [
        "Token",
        "DecimalAmount",
        "CurrencyCode",
        "Money",
        "Timestamp",
        "Channel",
        "RiskTier",
        "FinalDecisionValue",
        "Probability",
        "ReasonCode",
        "Locale",
        "Role",
        "AuditEventType",
    ],
)
def test_shared_primitives_are_identical_to_openapi(name: str) -> None:
    common = schemas()["urn:fraudshield:kafka:common"]["$defs"][name]
    openapi = load()["components"]["schemas"][name]
    assert _resolve(common) == _resolve(openapi)


@pytest.mark.req("NFR-SEC-03", "FR-01-02")
def test_raw_transaction_payload_has_the_ingest_request_constraints() -> None:
    raw = _resolve(schemas()["urn:fraudshield:kafka:transaction-raw"])
    ingest = _resolve(load()["components"]["schemas"]["TransactionIngestRequest"])
    assert raw == ingest


@pytest.mark.req("D-14")
def test_decision_final_payload_is_the_openapi_final_decision() -> None:
    kafka = _resolve(schemas()["urn:fraudshield:kafka:decision-final"])
    api = _resolve(load()["components"]["schemas"]["FinalDecision"])
    assert kafka == api


def test_structural_comparison_notices_a_weakened_identifier() -> None:
    raw = copy.deepcopy(schemas()["urn:fraudshield:kafka:transaction-raw"])
    raw["properties"]["account_id"] = {"type": "string"}
    ingest = _resolve(load()["components"]["schemas"]["TransactionIngestRequest"])
    assert _resolve(raw) != ingest


def test_envelope_rejects_missing_event_id_so_consumers_can_deduplicate() -> None:
    event = _example("fs.decisions.final")
    del event["event_id"]
    assert validate_event(_topic("fs.decisions.final"), event)
