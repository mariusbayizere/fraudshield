"""Load the OpenAPI document and validate payloads against its component schemas."""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass
from functools import cache
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import ValidationError
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

from fraudshield_contracts import OPENAPI_PATH

HTTP_METHODS = ("get", "put", "post", "delete", "patch", "head", "options", "trace")
_DOCUMENT_URI = "urn:fraudshield:openapi"


@dataclass(frozen=True)
class Operation:
    path: str
    method: str
    spec: dict[str, Any]

    @property
    def operation_id(self) -> str:
        return str(self.spec.get("operationId", ""))


@cache
def load(path: Path = OPENAPI_PATH) -> dict[str, Any]:
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError(f"{path} is not a mapping")
    return document


def operations(document: dict[str, Any]) -> Iterator[Operation]:
    for path, item in document.get("paths", {}).items():
        for method in HTTP_METHODS:
            if method in item:
                yield Operation(path=path, method=method, spec=item[method])


def _registry(document: dict[str, Any]) -> Registry[Any]:
    resource: Resource[Any] = Resource.from_contents(document, default_specification=DRAFT202012)
    registry: Registry[Any] = Registry().with_resource(_DOCUMENT_URI, resource)
    return registry


def validator(document: dict[str, Any], schema_name: str) -> Draft202012Validator:
    """A validator for ``#/components/schemas/<schema_name>`` with all internal refs resolvable."""
    if schema_name not in document.get("components", {}).get("schemas", {}):
        raise KeyError(f"unknown schema {schema_name}")
    schema = {"$ref": f"{_DOCUMENT_URI}#/components/schemas/{schema_name}"}
    return Draft202012Validator(
        schema, registry=_registry(document), format_checker=FormatChecker()
    )


def errors(document: dict[str, Any], schema_name: str, payload: object) -> list[ValidationError]:
    return sorted(validator(document, schema_name).iter_errors(payload), key=lambda e: e.path)
