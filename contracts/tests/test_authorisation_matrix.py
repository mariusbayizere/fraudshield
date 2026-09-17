from __future__ import annotations

import copy
from pathlib import Path

import pytest

from fraudshield_contracts.authorisation import declared_access, load_matrix, matrix_differences
from fraudshield_contracts.openapi import load, operations

DOC = load()
MATRIX = load_matrix()
OPS = {op.operation_id: op for op in operations(DOC)}
PROBLEM_TYPES = set(DOC["components"]["schemas"]["ProblemType"]["enum"])

MACHINE_OPERATIONS = {"ingestTransaction", "ingestTransactionBatch", "getJob", "getFinalDecision"}
ALERT_DECISION_OPERATIONS = {
    "decideAlert",
    "undoAlertDecision",
    "escalateAlert",
    "overrideAlertDecision",
}
ADMIN_ONLY_OPERATIONS = {
    "listUsers",
    "createUser",
    "getUser",
    "updateUser",
    "listPendingApprovals",
    "decideApproval",
    "listApiKeys",
    "createApiKey",
    "rotateApiKey",
    "revokeApiKey",
    "listModelVersions",
    "promoteModel",
    "rollbackModel",
    "setShadowModel",
    "startRetraining",
    "uploadTrainingDataset",
}


@pytest.mark.req("FR-07-01", "FR-01-05", "FR-07-05")
def test_document_matches_the_reviewed_matrix_exactly() -> None:
    assert matrix_differences(DOC, MATRIX) == []


@pytest.mark.req("FR-07-01")
@pytest.mark.parametrize(
    ("operation_id", "roles"),
    [
        ("decideAlert", ["ANALYST", "SENIOR_ANALYST", "RISK_OFFICER", "ADMIN"]),
        ("overrideAlertDecision", ["RISK_OFFICER", "ANALYST"]),
        ("createApiKey", ["ANALYST", "ADMIN"]),
        ("updateThresholds", ["RISK_OFFICER", "ADMIN"]),
    ],
)
def test_matrix_check_detects_a_widened_role_list(operation_id: str, roles: list[str]) -> None:
    mutated = copy.deepcopy(DOC)
    for path_item in mutated["paths"].values():
        for spec in path_item.values():
            if isinstance(spec, dict) and spec.get("operationId") == operation_id:
                spec["x-required-roles"] = roles
    differences = matrix_differences(mutated, MATRIX)
    assert len(differences) == 1
    assert differences[0].startswith(operation_id)


def test_matrix_check_detects_missing_and_extra_rows() -> None:
    matrix = copy.deepcopy(MATRIX)
    matrix.pop("getJob")
    matrix["retiredOperation"] = {"public": True, "ref": "none"}
    assert sorted(matrix_differences(DOC, matrix)) == [
        "getJob: not in the matrix",
        "retiredOperation: in the matrix but not in the document",
    ]


@pytest.mark.req("FR-07-01")
def test_admin_never_decides_alerts() -> None:
    for operation_id in ALERT_DECISION_OPERATIONS:
        kind, roles = declared_access(OPS[operation_id].spec)
        assert kind == "roles"
        assert "ADMIN" not in roles, operation_id
    for op in OPS.values():
        if op.path.startswith("/alerts"):
            assert "ADMIN" not in declared_access(op.spec)[1], op.operation_id


@pytest.mark.req("FR-05-01", "FR-02-06", "FR-05-07")
def test_only_risk_officers_override_and_change_thresholds() -> None:
    for operation_id in ("overrideAlertDecision", "updateThresholds", "previewThresholdImpact"):
        assert declared_access(OPS[operation_id].spec) == ("roles", frozenset({"RISK_OFFICER"}))


@pytest.mark.req("FR-06-01", "FR-06-03", "FR-06-07")
def test_user_key_and_model_lifecycle_is_admin_only() -> None:
    for operation_id in ADMIN_ONLY_OPERATIONS:
        assert declared_access(OPS[operation_id].spec) == ("roles", frozenset({"ADMIN"})), (
            operation_id
        )


@pytest.mark.req("FR-01-05", "FR-07-05")
def test_api_keys_reach_only_the_four_machine_operations() -> None:
    for op in OPS.values():
        uses_api_key = any("apiKeyAuth" in entry for entry in op.spec.get("security") or [])
        machine = op.operation_id in MACHINE_OPERATIONS
        assert uses_api_key is machine, op.operation_id
        assert ("x-required-scopes" in op.spec) is machine, op.operation_id


@pytest.mark.req("FR-07-01")
@pytest.mark.parametrize("operation_id", sorted(ALERT_DECISION_OPERATIONS | {"updateUser"}))
def test_object_level_rules_are_declared_with_documented_problems(operation_id: str) -> None:
    spec = OPS[operation_id].spec
    rules = spec.get("x-authorisation-rules")
    assert rules, f"{operation_id} has no x-authorisation-rules"
    responses = DOC["components"]["responses"]
    for rule in rules:
        assert rule["problem"] in PROBLEM_TYPES, rule
        status = str(rule["status"])
        assert status in spec["responses"], f"{operation_id}: {status} not documented"
        ref = spec["responses"][status]["$ref"].rsplit("/", 1)[1]
        assert rule["problem"] in responses[ref]["x-problem-types"], (operation_id, rule)


def test_health_detail_is_not_public() -> None:
    for operation_id in ("getMlHealth", "getKafkaHealth"):
        assert declared_access(OPS[operation_id].spec) == ("roles", frozenset({"ADMIN"}))
    probe = DOC["components"]["schemas"]["ProbeStatus"]
    assert probe["properties"].keys() == {"status"}
    assert probe["properties"]["status"]["enum"] == ["UP", "DOWN"]


def test_malformed_rows_and_undeclared_operations_are_reported(tmp_path: Path) -> None:
    matrix = copy.deepcopy(MATRIX)
    matrix["getJob"] = {"public": True, "scopes": ["jobs:read"], "ref": ""}
    mutated = copy.deepcopy(DOC)
    liveness = mutated["paths"]["/actuator/health"]["get"]
    del liveness["x-public"]
    assert sorted(matrix_differences(mutated, matrix)) == [
        "getJob: document declares scopes ['jobs:read'], matrix says invalid []",
        "getJob: matrix row has no ref",
        "getLiveness: document declares undeclared [], matrix says public []",
    ]
    empty = tmp_path / "matrix.yaml"
    empty.write_text("rows: []\n", encoding="utf-8")
    with pytest.raises(ValueError, match="no operations mapping"):
        load_matrix(empty)
