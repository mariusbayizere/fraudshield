# notify

**Purpose.** Telling customers and integrators about decisions: the auto-block SMS intent and its
D-25 self-service eligibility, zone-aware local time (D-43), the GSM-7 message catalogue, single-use
verification links and the verification flow (E.7, FR-03-04/05), and signed `decision.final`
webhooks with retries, supersession and dead-lettering (D-14). ADRs 0065, 0066.

**Boundaries.** The policies (`*Policy`, `LocalTimes`, `Gsm7`, `WebhookSignatures`) are pure. The
PII-vault `ContactDirectory` and the institution `WebhookEndpoints` are ports implemented elsewhere;
without them their channels do not start.

**Test.** `../mvnw -pl notify verify`. `WebhookSignaturesTest` runs the shared vectors in
`contracts/webhooks`.
