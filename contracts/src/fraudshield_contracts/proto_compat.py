"""Protobuf evolution check against a committed descriptor baseline (ADR 0012).

The baseline is a JSON summary of the compiled descriptor, so a diff is readable in review. Rules:
existing field and enum-value numbers keep their name, type, label and oneof; a removed number and
its name must be reserved, and a reservation is never dropped; a new field or enum value must not
reuse a reserved or removed number or name; messages, enums, services and methods are never
removed, and methods keep their request and response types and streaming mode.
"""

from __future__ import annotations

import tempfile
from pathlib import Path
from typing import Any

import grpc_tools
from google.protobuf import descriptor_pb2
from grpc_tools import protoc

from fraudshield_contracts import CONTRACTS_ROOT

PROTO_ROOT = CONTRACTS_ROOT / "proto"
BASELINE_PATH = PROTO_ROOT / "baseline" / "scoring-v1.json"
SCORING_PROTO = Path("fraudshield/scoring/v1/scoring.proto")


def compile_descriptor(root: Path = PROTO_ROOT, proto: Path = SCORING_PROTO) -> bytes:
    well_known = Path(grpc_tools.__file__).parent / "_proto"
    with tempfile.TemporaryDirectory() as out:
        target = Path(out) / "descriptor.pb"
        status = protoc.main(
            [
                "grpc_tools.protoc",
                f"-I{root}",
                f"-I{well_known}",
                f"--descriptor_set_out={target}",
                str(root / proto),
            ]
        )
        if status != 0:
            raise RuntimeError(f"protoc failed for {proto}")
        return target.read_bytes()


def _reserved(ranges: Any) -> list[list[int]]:
    return [[r.start, r.end - 1] for r in ranges]


def _message(message: Any, prefix: str, messages: dict[str, Any], enums: dict[str, Any]) -> None:
    name = f"{prefix}.{message.name}"
    oneofs = [o.name for o in message.oneof_decl]
    messages[name] = {
        "fields": {
            str(f.number): {
                "name": f.name,
                "type": descriptor_pb2.FieldDescriptorProto.Type.Name(f.type),
                "type_name": f.type_name,
                "label": descriptor_pb2.FieldDescriptorProto.Label.Name(f.label),
                "oneof": (
                    oneofs[f.oneof_index]
                    if f.HasField("oneof_index") and not f.proto3_optional
                    else None
                ),
                "proto3_optional": f.proto3_optional,
            }
            for f in message.field
        },
        "reserved_numbers": _reserved(message.reserved_range),
        "reserved_names": sorted(message.reserved_name),
    }
    for nested in message.nested_type:
        _message(nested, name, messages, enums)
    for enum in message.enum_type:
        _enum(enum, name, enums)


def _enum(enum: Any, prefix: str, enums: dict[str, Any]) -> None:
    enums[f"{prefix}.{enum.name}"] = {
        "values": {str(v.number): v.name for v in enum.value},
        "reserved_numbers": [[r.start, r.end] for r in enum.reserved_range],
        "reserved_names": sorted(enum.reserved_name),
    }


def summarise(descriptor_set: bytes, proto: Path = SCORING_PROTO) -> dict[str, Any]:
    files = descriptor_pb2.FileDescriptorSet.FromString(descriptor_set)
    [file] = [f for f in files.file if f.name == proto.as_posix()]
    prefix = f".{file.package}"
    messages: dict[str, Any] = {}
    enums: dict[str, Any] = {}
    for message in file.message_type:
        _message(message, prefix, messages, enums)
    for enum in file.enum_type:
        _enum(enum, prefix, enums)
    services = {
        f"{prefix}.{service.name}": {
            method.name: {
                "input": method.input_type,
                "output": method.output_type,
                "client_streaming": method.client_streaming,
                "server_streaming": method.server_streaming,
            }
            for method in service.method
        }
        for service in file.service
    }
    return {
        "package": file.package,
        "messages": messages,
        "enums": enums,
        "services": services,
    }


def _in_ranges(number: int, ranges: list[list[int]]) -> bool:
    return any(start <= number <= end for start, end in ranges)


def breaking_changes(old: dict[str, Any], new: dict[str, Any]) -> list[str]:
    found: list[str] = []
    if old["package"] != new["package"]:
        found.append(f"package changed from {old['package']} to {new['package']}")
    for name, message in old["messages"].items():
        current = new["messages"].get(name)
        if current is None:
            found.append(f"{name}: message removed")
        else:
            found.extend(_field_changes(name, message, current))
    for name, enum in old["enums"].items():
        current = new["enums"].get(name)
        if current is None:
            found.append(f"{name}: enum removed")
        else:
            found.extend(_enum_changes(name, enum, current))
    for name, methods in old["services"].items():
        current = new["services"].get(name)
        if current is None:
            found.append(f"{name}: service removed")
            continue
        for method, signature in methods.items():
            if method not in current:
                found.append(f"{name}.{method}: method removed")
            elif current[method] != signature:
                found.append(f"{name}.{method}: request, response or streaming mode changed")
    return found


def _reservation_changes(name: str, old: dict[str, Any], new: dict[str, Any]) -> list[str]:
    found = [
        f"{name}: reserved numbers {start}-{end} no longer reserved"
        for start, end in old["reserved_numbers"]
        if not all(_in_ranges(n, new["reserved_numbers"]) for n in range(start, end + 1))
    ]
    found.extend(
        f"{name}: reserved name {reserved} no longer reserved"
        for reserved in old["reserved_names"]
        if reserved not in new["reserved_names"]
    )
    return found


def _field_changes(name: str, old: dict[str, Any], new: dict[str, Any]) -> list[str]:
    found: list[str] = _reservation_changes(name, old, new)
    for number, field in old["fields"].items():
        now = new["fields"].get(number)
        where = f"{name} field {number} ({field['name']})"
        if now is None:
            if not _in_ranges(int(number), new["reserved_numbers"]):
                found.append(f"{where}: removed without reserving the number")
            if field["name"] not in new["reserved_names"]:
                found.append(f"{where}: removed without reserving the name")
            continue
        found.extend(
            f"{where}: {attribute} changed to {now[attribute]!r}"
            for attribute in ("name", "type", "type_name", "label", "oneof", "proto3_optional")
            if field[attribute] != now[attribute]
        )
    old_names = {f["name"]: n for n, f in old["fields"].items()}
    for number, field in new["fields"].items():
        if number in old["fields"]:
            continue
        if _in_ranges(int(number), old["reserved_numbers"]):
            found.append(f"{name} field {number}: reuses a reserved number")
        if field["name"] in old["reserved_names"]:
            found.append(f"{name} field {field['name']}: reuses a reserved name")
        if field["name"] in old_names:
            found.append(
                f"{name} field {field['name']}: renumbered from {old_names[field['name']]} "
                f"to {number}"
            )
    return found


def _enum_changes(name: str, old: dict[str, Any], new: dict[str, Any]) -> list[str]:
    found: list[str] = _reservation_changes(name, old, new)
    old_names = set(old["values"].values())
    for number, value in new["values"].items():
        if number in old["values"]:
            continue
        if _in_ranges(int(number), old["reserved_numbers"]) or value in old["reserved_names"]:
            found.append(f"{name} value {number} ({value}): reuses a reserved number or name")
        if value in old_names:
            found.append(f"{name} value {value}: renumbered to {number}")
    for number, value in old["values"].items():
        now = new["values"].get(number)
        if now is None:
            reserved = _in_ranges(int(number), new["reserved_numbers"])
            if not (reserved and value in new["reserved_names"]):
                found.append(f"{name} value {number} ({value}): removed without reserving")
        elif now != value:
            found.append(f"{name} value {number}: renamed from {value} to {now}")
    return found
