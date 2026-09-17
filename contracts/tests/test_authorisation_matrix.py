from __future__ import annotations

import copy
import re
from pathlib import Path
from typing import Any

import pytest

from fraudshield_contracts import CONTRACTS_ROOT
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
DUAL_CONTROL = (
    "proposeThresholdChange",
    "proposeCircuitBreakerChange",
    "listConfigChanges",
    "getConfigChange",
    "approveConfigChange",
    "rejectConfigChange",
    "withdrawConfigChange",
)
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
        ("proposeThresholdChange", ["RISK_OFFICER", "ADMIN"]),
        ("approveConfigChange", ["RISK_OFFICER", "ADMIN"]),
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
    for operation_id in ("overrideAlertDecision", "previewThresholdImpact", *DUAL_CONTROL):
        assert declared_access(OPS[operation_id].spec) == ("roles", frozenset({"RISK_OFFICER"}))


@pytest.mark.req("FR-05-07", "FR-03-07")
def test_dual_control_endpoints_forbid_admin_and_self_review() -> None:
    """Owner decision: ADMIN never proposes or approves; nobody reviews their own change."""
    for operation_id in DUAL_CONTROL:
        assert "ADMIN" not in declared_access(OPS[operation_id].spec)[1], operation_id
    for operation_id in ("approveConfigChange", "rejectConfigChange"):
        problems = {r["problem"] for r in OPS[operation_id].spec["x-authorisation-rules"]}
        assert "urn:fraudshield:problem:self-review" in problems
        assert (
            OPS[operation_id]
            .spec["responses"]["403"]["$ref"]
            .endswith("/AuthorisationRuleViolation")
        )
    for operation_id in ("proposeThresholdChange", "proposeCircuitBreakerChange"):
        assert "Tightening" in OPS[operation_id].spec["description"]
        assert "24 hours" in OPS[operation_id].spec["description"]
    assert OPS["proposeThresholdChange"].path == "/admin/thresholds"  # SRS path kept (FR-02-06)


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
@pytest.mark.parametrize(
    "operation_id",
    sorted(ALERT_DECISION_OPERATIONS | {"updateUser", *DUAL_CONTROL[:2], *DUAL_CONTROL[4:]}),
)
def test_object_level_rules_are_declared_with_documented_problems(operation_id: str) -> None:
    spec = OPS[operation_id].spec
    rules = spec.get("x-authorisation-rules")
    assert rules, f"{operation_id} has no x-authorisation-rules"
    responses = DOC["components"]["responses"]
    codes = set(DOC["components"]["schemas"]["ValidationErrorCode"]["enum"])
    for rule in rules:
        assert rule["problem"] in PROBLEM_TYPES, rule
        status = str(rule["status"])
        assert status in spec["responses"], f"{operation_id}: {status} not documented"
        ref = spec["responses"][status]["$ref"].rsplit("/", 1)[1]
        assert rule["problem"] in responses[ref]["x-problem-types"], (operation_id, rule)
        assert ("code" in rule) is rule["problem"].endswith(":validation"), rule
        assert rule.get("code", "required") in codes, rule


def test_health_detail_is_not_public() -> None:
    """Owner decision: one public status-only endpoint; component detail on the management port."""
    public = OPS["getPublicHealth"]
    assert public.path == "/health"
    assert declared_access(public.spec) == ("public", frozenset())
    assert "x-network" not in public.spec
    for status in ("200", "503"):
        ref = public.spec["responses"][status]["content"]["application/json"]["schema"]["$ref"]
        assert ref.endswith("/ProbeStatus")
    probe = DOC["components"]["schemas"]["ProbeStatus"]
    assert probe["properties"].keys() == {"status"}
    assert probe["properties"]["status"]["enum"] == ["UP", "DOWN"]
    assert probe["additionalProperties"] is False
    health_paths = {
        op.path for op in OPS.values() if "health" in op.path and op.path != "/admin/health"
    }
    assert health_paths == {
        "/health",
        "/actuator/health",
        "/actuator/health/ml",
        "/actuator/health/kafka",
        "/actuator/health/db",
        "/actuator/health/redis",
    }


@pytest.mark.req("FR-07-01")
@pytest.mark.parametrize(
    ("operation_id", "change"),
    [
        ("updateUser", lambda rules: rules.pop(0)),
        ("overrideAlertDecision", lambda rules: rules.pop(1)),
        ("escalateAlert", lambda rules: rules[0].update(status=403)),
        ("listAlerts", lambda rules: rules.clear()),
    ],
)
def test_matrix_check_detects_a_removed_or_changed_object_rule(
    operation_id: str, change: Any
) -> None:
    mutated = copy.deepcopy(DOC)
    for path_item in mutated["paths"].values():
        for spec in path_item.values():
            if isinstance(spec, dict) and spec.get("operationId") == operation_id:
                change(spec["x-authorisation-rules"])
    differences = matrix_differences(mutated, MATRIX)
    assert len(differences) == 1
    assert differences[0].startswith(f"{operation_id}: document rules")


@pytest.mark.req("FR-07-01", "FR-05-01")
def test_self_elevation_and_self_override_rules_cannot_be_dropped() -> None:
    def problems(operation_id: str) -> set[str]:
        return {rule.get("problem", "") for rule in OPS[operation_id].spec["x-authorisation-rules"]}

    assert "urn:fraudshield:problem:self-modification" in problems("updateUser")
    assert "urn:fraudshield:problem:last-active-admin" in problems("updateUser")
    assert "urn:fraudshield:problem:own-decision-override" in problems("overrideAlertDecision")
    assert "urn:fraudshield:problem:not-decision-author" in problems("undoAlertDecision")
    [queue] = OPS["listAlerts"].spec["x-authorisation-rules"]
    assert "queue=escalated" in queue["rule"]
    assert "target role is at or below the caller" in queue["rule"]


@pytest.mark.req("FR-04-10")
def test_only_roles_below_risk_officer_can_escalate() -> None:
    kind, roles = declared_access(OPS["escalateAlert"].spec)
    assert kind == "roles"
    assert roles <= {"ANALYST", "SENIOR_ANALYST"}


def test_sign_in_and_registration_do_not_disclose_account_state() -> None:
    register = OPS["register"].spec["responses"]
    assert "409" not in register
    assert [code for code in register if code.startswith("2")] == ["202"]
    login = OPS["login"].spec["responses"]
    assert login["403"]["$ref"].endswith("/AccountNotActive")


def test_management_endpoints_are_public_only_on_the_management_network() -> None:
    for op in OPS.values():
        if op.path.startswith("/actuator"):
            assert op.spec.get("x-public") is True
            assert op.spec.get("x-network") == "management", op.operation_id
            [server] = DOC["paths"][op.path]["servers"]
            assert "{managementPort}" in server["url"]
        else:
            assert "x-network" not in op.spec, op.operation_id


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


DOMAIN_REFUSALS = (
    CONTRACTS_ROOT.parent
    / "backend/common/src/main/java/io/github/mariusbayizere/fraudshield/common/config"
    / "DualControlException.java"
)
DOMAIN_OPERATIONS = {
    "ROLE_NOT_PERMITTED": ["proposeThresholdChange", "approveConfigChange", "withdrawConfigChange"],
    "SELF_REVIEW": ["approveConfigChange", "rejectConfigChange"],
    "NOT_PROPOSER": ["withdrawConfigChange"],
    "CHANGE_NOT_FOUND": ["approveConfigChange", "rejectConfigChange", "withdrawConfigChange"],
    "CHANGE_NOT_OPEN": ["approveConfigChange", "rejectConfigChange", "withdrawConfigChange"],
    "OPEN_CHANGE_EXISTS": ["proposeThresholdChange", "proposeCircuitBreakerChange"],
    "STALE_BASE_VERSION": ["proposeThresholdChange", "proposeCircuitBreakerChange"],
    "NO_CHANGE": ["proposeThresholdChange", "proposeCircuitBreakerChange"],
}


@pytest.mark.req("FR-05-07")
def test_domain_refusals_match_the_contract() -> None:
    """Review F-02: every Java refusal maps to a documented status and catalogued problem type."""
    source = DOMAIN_REFUSALS.read_text(encoding="utf-8")
    pattern = r'^\s+([A-Z_]+)\((\d{3}), "([^"]+)"\)'
    refusals = {
        name: (int(status), problem) for name, status, problem in re.findall(pattern, source, re.M)
    }
    assert set(refusals) == set(DOMAIN_OPERATIONS)
    responses = DOC["components"]["responses"]
    for name, (status, problem) in refusals.items():
        assert problem in PROBLEM_TYPES, name
        for operation_id in DOMAIN_OPERATIONS[name]:
            documented = OPS[operation_id].spec["responses"]
            assert str(status) in documented, (name, operation_id)
            ref = documented[str(status)]["$ref"].rsplit("/", 1)[1]
            assert problem in responses[ref]["x-problem-types"], (name, operation_id, ref)
