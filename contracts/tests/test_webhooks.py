from __future__ import annotations

import json

import pytest

from fraudshield_contracts import CONTRACTS_ROOT
from fraudshield_contracts.events import topics, validate_event
from fraudshield_contracts.webhooks import Verdict, sign, verify

VECTORS = json.loads((CONTRACTS_ROOT / "webhooks" / "signature-test-vectors.json").read_text())


@pytest.mark.req("D-14")
@pytest.mark.parametrize("vector", VECTORS["vectors"], ids=lambda v: v["name"])
def test_signature_vectors(vector: dict[str, object]) -> None:
    verdict = verify(
        str(vector["header"]),
        str(vector["body"]).encode(),
        [str(vector["secret"])],
        int(str(vector["verify_at"])),
    )
    assert verdict == Verdict(str(vector["expected"]))


@pytest.mark.req("D-14")
def test_rotation_overlap_accepts_either_active_secret() -> None:
    body = b'{"transaction_id":"7a2c9e41-0d3b-4c8a-b5f6-1e2d3c4b5a60"}'
    timestamp = 1_789_624_961
    old, new = "whsec_test_old_value_00000000000000", "whsec_test_new_value_00000000000000"
    header = f"t={timestamp},v1={sign(old, timestamp, body)},v1={sign(new, timestamp, body)}"
    assert verify(header, body, [new], timestamp) is Verdict.VALID
    assert verify(header, body, [old], timestamp) is Verdict.VALID
    assert (
        verify(header, body, ["whsec_test_other_0000000000000000"], timestamp)
        is Verdict.INVALID_SIGNATURE
    )


def test_future_timestamps_outside_the_window_are_rejected() -> None:
    body = b"{}"
    header = f"t=2000,v1={sign('s', 2000, body)}"
    assert verify(header, body, ["s"], 1000) is Verdict.EXPIRED


@pytest.mark.req("D-14")
def test_vector_body_is_a_valid_decision_final_payload() -> None:
    topic = next(t for t in topics() if t.name == "fs.decisions.final")
    body = json.loads(VECTORS["vectors"][0]["body"])
    event = {
        "event_id": "0d1e2f3a-4b5c-4d6e-8f70-81a2b3c4d5e6",
        "event_type": "decision.final",
        "schema_version": 1,
        "occurred_at": "2026-09-17T06:02:41Z",
        "institution_id": "5b1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a5b",
        "producer": "fraudshield-api",
        "payload": body,
    }
    assert validate_event(topic, event) == []
