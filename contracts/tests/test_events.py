from __future__ import annotations

import copy
import json
import re
from typing import Any

import pytest
from jsonschema import Draft202012Validator

from fraudshield_contracts.events import KAFKA_ROOT, catalogue, schemas, topics, validate_event
from fraudshield_contracts.openapi import load

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


def test_every_schema_is_valid_draft_2020_12_with_a_unique_id() -> None:
    for schema_id, schema in schemas().items():
        Draft202012Validator.check_schema(schema)
        assert schema_id.startswith("urn:fraudshield:kafka:")
        assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"


@pytest.mark.parametrize("topic", sorted(C3_TOPICS))
def test_topic_examples_validate(topic: str) -> None:
    [spec] = [t for t in topics() if t.name == topic]
    assert validate_event(spec, _example(topic)) == []


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
    event = _example("fs.notifications.customer")
    event["payload"]["phone"] = "+250788123456"
    assert validate_event(_topic("fs.notifications.customer"), event)
    unmasked = copy.deepcopy(_example("fs.notifications.customer"))
    unmasked["payload"]["parameters"]["masked_account"] = "0788124821"
    assert validate_event(_topic("fs.notifications.customer"), unmasked)


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


@pytest.mark.req("D-43")
@pytest.mark.parametrize(
    "name", ["Token", "DecimalAmount", "CurrencyCode", "Channel", "RiskTier", "Locale", "Role"]
)
def test_shared_primitives_are_identical_to_openapi(name: str) -> None:
    common = schemas()["urn:fraudshield:kafka:common"]["$defs"][name]
    openapi = load()["components"]["schemas"][name]
    for key in ("pattern", "enum", "type"):
        assert common.get(key) == openapi.get(key), f"{name}.{key}"


def test_raw_transaction_payload_matches_the_ingest_request_fields() -> None:
    raw = schemas()["urn:fraudshield:kafka:transaction-raw"]
    ingest = load()["components"]["schemas"]["TransactionIngestRequest"]
    assert set(raw["properties"]) == set(ingest["properties"])
    assert set(raw["required"]) == set(ingest["required"])


@pytest.mark.req("D-15")
def test_envelope_rejects_missing_event_id_so_consumers_can_deduplicate() -> None:
    event = _example("fs.decisions.final")
    del event["event_id"]
    assert validate_event(_topic("fs.decisions.final"), event)
