"""Protobuf lint and evolution checks with the pinned buf (ADR 0016).

Each breaking case edits a copy of the proto and requires `buf breaking` against the unedited copy
to report the expected finding, so a weakened buf configuration fails here. A reserved number
cannot be reused within one file (protoc rejects it), so reuse is caught where the reservation is
dropped.
"""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import pytest

from fraudshield_contracts import CONTRACTS_ROOT

BUF = CONTRACTS_ROOT.parent / "tools" / "bin" / "buf"
PROTO_ROOT = CONTRACTS_ROOT / "proto"
SCORING = Path("fraudshield/scoring/v1/scoring.proto")


def _buf(*args: str | Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(  # noqa: S603 - pinned launcher, fixed arguments
        [str(BUF), *map(str, args)], capture_output=True, text=True, check=False
    )


def _copy(tmp_path: Path, name: str, edits: list[tuple[str, str]]) -> Path:
    root = tmp_path / name
    shutil.copytree(PROTO_ROOT, root)
    target = root / SCORING
    text = target.read_text(encoding="utf-8")
    for old, new in edits:
        assert text.count(old) == 1, old
        text = text.replace(old, new)
    target.write_text(text, encoding="utf-8")
    return root


RESERVE_FIELD = (
    "  double shap_base_value = 12;\n",
    '  reserved 12;\n  reserved "shap_base_value";\n',
)
RESERVE_ENUM = (
    "  CHANNEL_BANK_TRANSFER = 6;\n",
    '  reserved 6;\n  reserved "CHANNEL_BANK_TRANSFER";\n',
)


def test_proto_passes_buf_lint() -> None:
    result = _buf("lint", PROTO_ROOT)
    assert result.returncode == 0, result.stdout + result.stderr


UNUSED_MESSAGE = ("message ScoreRequest {", "message Unused {}\n\nmessage ScoreRequest {")
UNUSED_ENUM = (
    "enum Channel {",
    "enum UnusedKind {\n  UNUSED_KIND_UNSPECIFIED = 0;\n}\n\nenum Channel {",
)
UNUSED_SERVICE = (
    "service ScoringService {",
    "service UnusedService {}\n\nservice ScoringService {",
)

BREAKING = [
    (
        "enum value deleted, only its number reserved",
        [],
        [("  CHANNEL_BANK_TRANSFER = 6;\n", "  reserved 6;\n")],
        "without reserving the name",
    ),
    (
        "enum value deleted, only its name reserved",
        [],
        [("  CHANNEL_BANK_TRANSFER = 6;\n", '  reserved "CHANNEL_BANK_TRANSFER";\n')],
        "without reserving the number",
    ),
    (
        "enum value renumbered",
        [],
        [("CHANNEL_USSD = 4;", "CHANNEL_USSD = 7;")],
        "was deleted without reserving the number",
    ),
    (
        "field moved into a oneof",
        [],
        [
            (
                "  double shap_base_value = 12;\n",
                "  oneof base {\n    double shap_base_value = 12;\n  }\n",
            )
        ],
        "oneof",
    ),
    ("message deleted", [UNUSED_MESSAGE], [], 'Previously present message "Unused"'),
    ("enum deleted", [UNUSED_ENUM], [], 'Previously present enum "UnusedKind"'),
    ("service deleted", [UNUSED_SERVICE], [], 'Previously present service "UnusedService"'),
    (
        "renumbered field",
        [],
        [("string model_version = 14;", "string model_version = 20;")],
        "was deleted without reserving the number",
    ),
    (
        "field deleted without reserving",
        [],
        [("  double shap_base_value = 12;\n", "")],
        "reserving the number",
    ),
    (
        "field deleted, only its number reserved",
        [],
        [("  double shap_base_value = 12;\n", "  reserved 12;\n")],
        "reserving the name",
    ),
    ("field type changed", [], [("  Money amount = 5;", "  string amount = 5;")], "amount"),
    (
        "field renamed",
        [],
        [("string traceparent = 4;", "string trace_parent = 4;")],
        "trace_parent",
    ),
    (
        "field label changed",
        [],
        [("optional string device_token = 10;", "repeated string device_token = 10;")],
        "cardinality",
    ),
    (
        "oneof member type changed",
        [],
        [("    Missing missing = 3;", "    bool missing = 3;")],
        "changed type",
    ),
    (
        "enum value renamed",
        [],
        [("CHANNEL_USSD = 4;", "CHANNEL_FEATURE_PHONE = 4;")],
        "changed name",
    ),
    (
        "enum value deleted without reserving",
        [],
        [("  CHANNEL_BANK_TRANSFER = 6;\n", "")],
        "without reserving",
    ),
    (
        "method removed",
        [],
        [("  rpc GetModelStatus(GetModelStatusRequest) returns (GetModelStatusResponse);\n", "")],
        "was deleted",
    ),
    (
        "response made streaming",
        [],
        [("returns (ScoreResponse);", "returns (stream ScoreResponse);")],
        "server streaming",
    ),
    (
        "request made streaming",
        [],
        [("rpc Score(ScoreRequest)", "rpc Score(stream ScoreRequest)")],
        "client streaming",
    ),
    (
        "package changed",
        [],
        [("package fraudshield.scoring.v1;", "package fraudshield.scoring.v2;")],
        "package changed",
    ),
    (
        "field reservation dropped",
        [RESERVE_FIELD],
        [("  double shap_base_value = 12;\n", "")],
        'reserved name "shap_base_value"',
    ),
    (
        "reserved field number reused",
        [RESERVE_FIELD],
        [("  double shap_base_value = 12;\n", "  double shap_base = 12;\n")],
        'reserved range "[12]" on message "ScoringResult"',
    ),
    (
        "reserved enum number reused",
        [RESERVE_ENUM],
        [("  CHANNEL_BANK_TRANSFER = 6;\n", "  CHANNEL_WALLET = 6;\n")],
        'reserved range "[6]" on enum "Channel"',
    ),
]


@pytest.mark.parametrize(
    ("label", "base_edits", "edits", "expected"), BREAKING, ids=[b[0] for b in BREAKING]
)
def test_buf_reports_breaking_changes(
    tmp_path: Path,
    label: str,
    base_edits: list[tuple[str, str]],
    edits: list[tuple[str, str]],
    expected: str,
) -> None:
    base = _copy(tmp_path, "base", base_edits)
    changed = _copy(tmp_path, "changed", edits)
    result = _buf("breaking", changed, "--against", base)
    assert result.returncode != 0, label
    assert expected in result.stdout, result.stdout


@pytest.mark.parametrize(
    ("label", "edits"),
    [
        (
            "field added",
            [
                (
                    "  bool feature_store_degraded = 19;\n",
                    "  bool feature_store_degraded = 19;\n  string trace_id = 20;\n",
                )
            ],
        ),
        ("field removed with number and name reserved", [RESERVE_FIELD]),
        ("enum value removed with number and name reserved", [RESERVE_ENUM]),
    ],
)
def test_buf_accepts_compatible_changes(
    tmp_path: Path, label: str, edits: list[tuple[str, str]]
) -> None:
    changed = _copy(tmp_path, "changed", edits)
    result = _buf("breaking", changed, "--against", PROTO_ROOT)
    assert result.returncode == 0, (label, result.stdout, result.stderr)
