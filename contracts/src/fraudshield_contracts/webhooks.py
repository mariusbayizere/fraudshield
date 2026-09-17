"""Reference verifier for `decision.final` webhook signatures.

Specification: contracts/webhooks/decision-final.md.

The Java dispatcher and integrators' verifiers are tested against the same vectors.
"""

from __future__ import annotations

import hashlib
import hmac
from enum import StrEnum

REPLAY_WINDOW_SECONDS = 300


class Verdict(StrEnum):
    VALID = "VALID"
    INVALID_SIGNATURE = "INVALID_SIGNATURE"
    EXPIRED = "EXPIRED"
    MALFORMED = "MALFORMED"


def sign(secret: str, timestamp: int, body: bytes) -> str:
    signed_payload = str(timestamp).encode() + b"." + body
    return hmac.new(secret.encode(), signed_payload, hashlib.sha256).hexdigest()


def verify(header: str, body: bytes, secrets: list[str], now: int) -> Verdict:
    """Verify an `X-FraudShield-Signature` header against one or more active secrets."""
    timestamp: int | None = None
    signatures: list[str] = []
    for part in header.split(","):
        key, _, value = part.strip().partition("=")
        if key == "t" and value.isdigit():
            timestamp = int(value)
        elif key == "v1" and value:
            signatures.append(value)
    if timestamp is None or not signatures:
        return Verdict.MALFORMED
    if abs(now - timestamp) > REPLAY_WINDOW_SECONDS:
        return Verdict.EXPIRED
    expected = [sign(secret, timestamp, body) for secret in secrets]
    if any(hmac.compare_digest(sig, exp) for sig in signatures for exp in expected):
        return Verdict.VALID
    return Verdict.INVALID_SIGNATURE
