# `decision.final` webhook

Tells the institution that owns a transaction about every decision state after the ingest response:
the outcome of a MEDIUM `HOLD`, a customer verification, or a senior override. Core banking never keeps
an HTTP request open for 30 seconds (D-14). The body is one decision state, the same object that
`GET /api/v1/decisions/{transaction_id}` returns and that is published internally on
`fs.decisions.final`.

## Request

```
POST <webhook_url registered with the API key>        (HTTPS only; SSRF rules in the OpenAPI ApiKeyCreate schema)
Content-Type: application/json
X-FraudShield-Event: decision.final
X-FraudShield-Delivery: <uuid, new for every attempt; for logs only, not for deduplication>
X-FraudShield-Signature: t=<unix seconds of this attempt>,v1=<hex HMAC-SHA256>[,v1=<...>]
```

Body: `../kafka/schemas/decision-final.schema.json`, identical to the OpenAPI `FinalDecision` schema,
serialised as compact JSON (no insignificant whitespace). Receivers verify the signature over the
exact bytes received, not a re-serialised object.

## Signature

```
signed_payload = "<t>" + "." + <raw request body>
v1 = lowercase_hex( HMAC-SHA256( webhook_signing_secret, signed_payload ) )
```

The signing secret (`whsec_<env>_<32–64 alphanumerics>`) is shown once when the API key is created or
rotated (`ApiKeyCreated.webhook_signing_secret`). During a key's 24-hour rotation overlap, deliveries
carry one `v1=` entry per active secret; a receiver accepts if any entry verifies with any secret it
holds.

**Every attempt is signed when it is sent**, with `t` set to that attempt's time. A retry therefore
has a new `t` and the same body, so the replay window below never rejects a legitimate retry however
late it is.

Receivers must:

1. Parse the header: exactly one `t` of ASCII digits and at least one `v1` of 64 lower-case hex
   digits; anything else is **malformed**.
2. Reject if `|now − t| > 300` seconds (**replay window**; exactly 300 is accepted).
3. Compare signatures in constant time.
4. Apply the state only if `decision_sequence` is greater than the last sequence applied for that
   `transaction_id`, and otherwise ignore it. Answer 2xx in both cases so the delivery is not
   retried.

Deduplicate and order by `decision_sequence`, never by decision value: a transaction can go
HOLD → DECLINE → APPROVE (customer verification) → DECLINE (senior override), so the same value can
legitimately recur. A delayed retry of an older state can also arrive after a newer one. `event_id`
identifies a state and stays the same across retries of that state.

## Shared vectors

- `signature-test-vectors.json`: valid; tampered body; wrong secret; exactly 300 and 301 seconds old;
  300 and 301 seconds in the future; key rotation with either secret and with neither; missing `t`;
  missing `v1`; duplicate `t`; non-ASCII digits in `t`; a `v1` that is not lower-case hex.
- `delivery-ordering-vectors.json`: deliveries in order with a repeated decision value, a delayed
  retry of an older APPROVE after a newer DECLINE, and a duplicate delivery, each with the expected
  APPLY or IGNORE.

The Python reference implementation (`fraudshield_contracts.webhooks`) is tested against both. The
Java dispatcher (M6) and integrators' receivers use the same files.

## Delivery

- Success is any 2xx within 10 seconds.
- Retries use exponential backoff with full jitter, starting at 30 seconds, for up to 24 hours.
- States for one transaction are sent in sequence order; a state is not sent while an earlier state of
  the same transaction is still being retried. Receivers must still apply the rule above, because a
  state can be retried after the receiver has already processed it.
- After the final failure the delivery moves to the dead-letter list visible at
  `GET /api/v1/admin/webhook-deliveries`; integrators can also poll
  `GET /api/v1/decisions/{transaction_id}`.
