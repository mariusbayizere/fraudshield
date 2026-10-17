# 0066 — Webhook delivery, and the ports other milestones implement

- **Status:** Accepted
- **Date:** 2026-09-22
- **Requirements affected:** FR-01-05, FR-03-02
- **Defects referenced:** D-14, D-19

## Context

`decision.final` webhooks need delivery state that survives restarts and an administrator view of
failures, which M1 did not model. M6 also depends on components built in parallel: API keys (M7)
and the scorer (M5).

## Decision

1. **V63 `webhook_deliveries`**: at most one pending delivery per transaction; a newer state
   supersedes a pending older one and an older state arriving later is recorded superseded and
   never sent. Every attempt is signed at send time with each active secret; retries back off with
   full jitter from 30 s (each wait capped at 1 h) for 24 h, then the delivery is dead-lettered.
   Destinations must be public HTTPS (no loopback, private, link-local or unique-local address, no
   user-info), checked before every attempt.
2. **Ports, not stand-ins.** M6 defines and consumes, and ships no implementation of:
   `ApiKeyAuthenticator` (ingest; M7 — the application refuses to start without one),
   `WebhookEndpoints` (notify; M7, from `api_keys.webhook_url` and the encrypted secret) and
   `ContactDirectory` (notify; the PII vault). Without the last two their channels do not start and
   the log says so. The scorer is reached only through the frozen proto.

## Consequences

`WebhookSignaturesTest` (all 18 shared vectors), `WebhookDeliveryTest`, `EnvelopeConsumerTest`,
`IngestApiTest` (a hold's release delivered and verified end to end). The DNS check is not pinned
to the connection, so a rebinding attack between check and connect remains possible; pinning is a
follow-up.
