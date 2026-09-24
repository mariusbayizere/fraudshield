"""Kafka topic catalogue and event schema validation (ADR 0012)."""

from __future__ import annotations

import copy
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
    event_type: str
    schema_version: int
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
            event_type=entry["event_type"],
            schema_version=int(entry["schema_version"]),
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


def tolerant(schema: Any) -> Any:
    """A copy that ignores unknown properties, for consumers (tolerant reader, ADR 0012).

    Producers validate against the closed schemas; consumers deployed before a producer that adds
    an optional field must still accept its messages.
    """
    if isinstance(schema, dict):
        return {
            key: tolerant(value)
            for key, value in schema.items()
            if not (key in ("additionalProperties", "unevaluatedProperties") and value is False)
        }
    if isinstance(schema, list):
        return [tolerant(value) for value in schema]
    return copy.deepcopy(schema)


def _registry(reader: bool = False) -> Registry[Any]:
    registry: Registry[Any] = Registry()
    for schema_id, loaded in schemas().items():
        schema = tolerant(loaded) if reader else loaded
        resource: Resource[Any] = Resource.from_contents(schema, default_specification=DRAFT202012)
        registry = registry.with_resource(schema_id, resource)
    return registry


def schema_id_for(path: Path) -> str:
    return str(json.loads(path.read_text(encoding="utf-8"))["$id"])


def validate_event(topic: Topic, event: object, reader: bool = False) -> list[ValidationError]:
    """Errors for the envelope, its topic binding and the payload against the topic's schema.

    ``reader=True`` validates as a consumer does, ignoring unknown properties.
    """
    registry = _registry(reader)
    envelope = Draft202012Validator(
        {"$ref": ENVELOPE_ID}, registry=registry, format_checker=FormatChecker()
    )
    found = list(envelope.iter_errors(event))
    if isinstance(event, dict):
        for field, expected in (
            ("event_type", topic.event_type),
            ("schema_version", topic.schema_version),
        ):
            if field in event and event[field] != expected:
                found.append(
                    ValidationError(
                        f"{field} {event[field]!r} is not {expected!r} for {topic.name}"
                    )
                )
    if isinstance(event, dict) and "payload" in event:
        payload = Draft202012Validator(
            {"$ref": schema_id_for(topic.payload_schema)},
            registry=registry,
            format_checker=FormatChecker(),
        )
        found.extend(payload.iter_errors(event["payload"]))
    return found
