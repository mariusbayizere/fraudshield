# `decision.final` webhook

Delivers the final decision for a transaction that was not final at ingest time (MEDIUM `HOLD`,
customer verification, senior override), so core banking never keeps an HTTP request open for 30
seconds (D-14). The same payload is published on `fs.decisions.final`.

## Request

```
POST <webhook_url registered with the API key>        (HTTPS only)
Content-Type: application/json
X-FraudShield-Event: decision.final
X-FraudShield-Delivery: <uuid, unique per delivery attempt series>
X-FraudShield-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256>
```

Body: `../kafka/schemas/decision-final.schema.json`, serialised as compact JSON (no insignificant
whitespace). Receivers must verify the signature over the exact bytes received, not a re-serialised
object.

## Signature

```
signed_payload = "<t>" + "." + <raw request body>
v1 = lowercase_hex( HMAC-SHA256( webhook_signing_secret, signed_payload ) )
```

The signing secret is shown once when the API key is created or rotated
(`ApiKeyCreated.webhook_signing_secret`). During a key's 24-hour rotation overlap, deliveries carry one
`v1=` entry per active secret, comma-separated; a receiver accepts if any entry verifies.

Receivers must:

1. Parse `t` and every `v1`; reject a header without both as **malformed**.
2. Reject if `|now − t| > 300` seconds (**replay window**).
3. Compare signatures in constant time.
4. Deduplicate on `transaction_id` + `decision`: deliveries are at-least-once.

`signature-test-vectors.json` holds vectors (valid, tampered body, expired, wrong secret, malformed)
that both the Java dispatcher and integrators' verifiers are tested against.

## Delivery

- Success is any 2xx within 10 seconds.
- Retries use exponential backoff with full jitter, starting at 30 seconds, for up to 24 hours.
- After the final failure the delivery moves to the dead-letter list visible at
  `GET /api/v1/admin/webhook-deliveries`; integrators can also poll
  `GET /api/v1/decisions/{transaction_id}`.
