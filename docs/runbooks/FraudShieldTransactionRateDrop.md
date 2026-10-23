# FraudShieldTransactionRateDrop

**Meaning.** Ingest requests per second over 5 minutes have been more than 20% below the same
5 minutes one week earlier, for 10 minutes, with a baseline of at least 1 request/s.

**Impact.** Either payments are flowing without fraud checks (core banking stopped calling or
cannot reach FraudShield), or real traffic fell (holiday, upstream outage). The first is serious.

## Diagnose

1. Is the API healthy? If `FraudShieldApiHealthProbeFailing` fires, follow that runbook first
   (this alert is inhibited while it fires).
2. `fs_ingest_requests_total` by `status`: a rise in 401/403 means API keys were revoked or
   rotated without the caller updating (`GET /api/v1/admin/api-keys`); 429 means rate limits;
   5xx means the API is failing requests after counting them.
3. Edge: `kubectl -n fraudshield logs deploy/fraudshield-edge --tail=200` for TLS or upstream
   errors, and certificate expiry.
4. By `channel`: a drop on one channel only usually points at one upstream integration.
5. Calendar: public holidays and month-end shift traffic; the weekly comparison does not know
   about them. Note it in the incident if that is the cause.

## Mitigate

Fix the cause found above. Do not relax rate limits or re-enable revoked keys without the key
owner's confirmation (API key changes are audited, `API_KEY_LIFECYCLE`).

## Escalate

The institution's integration contact if their calls stopped; engineering lead otherwise.
