from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from types import ModuleType

import grpc_tools
import pytest
from grpc_tools import protoc

from fraudshield_contracts import CONTRACTS_ROOT
from fraudshield_contracts.openapi import load

PROTO_ROOT = CONTRACTS_ROOT / "proto"
SCORING_PROTO = PROTO_ROOT / "fraudshield/scoring/v1/scoring.proto"


@pytest.fixture(scope="module")
def scoring_pb2(tmp_path_factory: pytest.TempPathFactory) -> ModuleType:
    out = tmp_path_factory.mktemp("generated")
    well_known = Path(grpc_tools.__file__).parent / "_proto"
    status = protoc.main(
        [
            "grpc_tools.protoc",
            f"-I{PROTO_ROOT}",
            f"-I{well_known}",
            f"--python_out={out}",
            f"--grpc_python_out={out}",
            str(SCORING_PROTO),
        ]
    )
    assert status == 0, "protoc failed to compile scoring.proto"
    sys.path.insert(0, str(out))
    spec = importlib.util.spec_from_file_location(
        "scoring_pb2", out / "fraudshield/scoring/v1/scoring_pb2.py"
    )
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.req("FR-02-01", "FR-02-10")
def test_scoring_result_carries_the_fr_02_01_fields(scoring_pb2: ModuleType) -> None:
    fields = set(scoring_pb2.ScoringResult.DESCRIPTOR.fields_by_name)
    assert {
        "ensemble_score",
        "xgboost_score",
        "lightgbm_score",
        "anomaly_score",
        "shap_top5",
        "feature_vector",
        "model_version",
        "scoring_duration_ms",
        "model_risk_tier",
    } <= fields


@pytest.mark.req("D-04")
def test_device_and_agent_context_are_optional(scoring_pb2: ModuleType) -> None:
    transaction = scoring_pb2.Transaction.DESCRIPTOR.fields_by_name
    assert transaction["device_token"].has_presence
    assert transaction["agent_token"].has_presence
    ussd = scoring_pb2.Transaction(channel=scoring_pb2.CHANNEL_USSD)
    assert not ussd.HasField("device_token")


def test_channel_enum_matches_openapi(scoring_pb2: ModuleType) -> None:
    proto_channels = {
        name.removeprefix("CHANNEL_")
        for name in scoring_pb2.Channel.DESCRIPTOR.values_by_name
        if name != "CHANNEL_UNSPECIFIED"
    }
    assert proto_channels == set(load()["components"]["schemas"]["Channel"]["enum"])


def test_money_is_a_decimal_string_not_a_float(scoring_pb2: ModuleType) -> None:
    fields = scoring_pb2.Money.DESCRIPTOR.fields_by_name
    string_type = 9  # FieldDescriptor.TYPE_STRING
    assert fields["amount"].type == string_type
    assert fields["currency"].type == string_type


@pytest.mark.req("D-04")
def test_feature_value_can_represent_structural_missingness(scoring_pb2: ModuleType) -> None:
    missing = scoring_pb2.FeatureValue(missing=scoring_pb2.FeatureValue.Missing())
    assert missing.WhichOneof("value") == "missing"
    result = scoring_pb2.ScoringResult(model_version="fs-ensemble-2026.09.1")
    result.feature_vector["device_age_days"].missing.SetInParent()
    assert result.feature_vector["device_age_days"].WhichOneof("value") == "missing"


def test_service_exposes_score_and_model_status(scoring_pb2: ModuleType) -> None:
    methods = set(scoring_pb2.DESCRIPTOR.services_by_name["ScoringService"].methods_by_name)
    assert methods == {"Score", "GetModelStatus"}
