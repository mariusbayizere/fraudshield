"""Kafka topic catalogue and event schema validation (ADR 0012)."""

from __future__ import annotations

import json
from dataclasses import dataclass
from functools import cache
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import ValidationError
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

from fraudshield_contracts import CONTRACTS_ROOT

KAFKA_ROOT = CONTRACTS_ROOT / "kafka"
ENVELOPE_ID = "urn:fraudshield:kafka:envelope"


@dataclass(frozen=True)
class Topic:
    name: str
    key: str
    partitions: int
    retention: str
    payload_schema: Path
    spec: dict[str, Any]

    @property
    def dlq(self) -> str:
        return f"{self.name}.dlq"


@cache
def catalogue() -> dict[str, Any]:
    loaded = yaml.safe_load((KAFKA_ROOT / "topics.yaml").read_text(encoding="utf-8"))
    if not isinstance(loaded, dict):
        raise ValueError("topics.yaml is not a mapping")
    return loaded


def topics() -> list[Topic]:
    return [
        Topic(
            name=entry["name"],
            key=entry["key"],
            partitions=int(entry["partitions"]),
            retention=entry["retention"],
            payload_schema=KAFKA_ROOT / entry["payload_schema"],
            spec=entry,
        )
        for entry in catalogue()["topics"]
    ]


@cache
def schemas() -> dict[str, dict[str, Any]]:
    """Every schema under kafka/schemas keyed by its ``$id``."""
    loaded: dict[str, dict[str, Any]] = {}
    for path in sorted((KAFKA_ROOT / "schemas").glob("*.schema.json")):
        schema = json.loads(path.read_text(encoding="utf-8"))
        loaded[schema["$id"]] = schema
    return loaded


def _registry() -> Registry[Any]:
    registry: Registry[Any] = Registry()
    for schema_id, schema in schemas().items():
        resource: Resource[Any] = Resource.from_contents(schema, default_specification=DRAFT202012)
        registry = registry.with_resource(schema_id, resource)
    return registry


def schema_id_for(path: Path) -> str:
    return str(json.loads(path.read_text(encoding="utf-8"))["$id"])


def validate_event(topic: Topic, event: object) -> list[ValidationError]:
    """Errors for the envelope and for the payload against the topic's schema."""
    registry = _registry()
    envelope = Draft202012Validator(
        {"$ref": ENVELOPE_ID}, registry=registry, format_checker=FormatChecker()
    )
    found = list(envelope.iter_errors(event))
    if isinstance(event, dict) and "payload" in event:
        payload = Draft202012Validator(
            {"$ref": schema_id_for(topic.payload_schema)},
            registry=registry,
            format_checker=FormatChecker(),
        )
        found.extend(payload.iter_errors(event["payload"]))
    return found
