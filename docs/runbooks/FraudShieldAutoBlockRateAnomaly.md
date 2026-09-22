# FraudShieldAutoBlockRateAnomaly (risk officers)

**Meaning.** HIGH-tier declines (auto-blocks) over the last 15 minutes are more than 3 times their
7-day average rate, and at least one per minute (SRS 8.2).

**Impact.** Either a fraud campaign is under way, or customers are being blocked wrongly after a
model, threshold or rule change. Each auto-block also sends a customer SMS and can freeze an
account after the third HIGH in 60 minutes (E.6).

## Diagnose (risk officer)

1. **FraudShield / Risk overview** dashboard: which channel, and did it start at a known change?
2. `GET /api/v1/config-changes`: any threshold, rule or circuit-breaker change approved recently?
   `GET /api/v1/admin/models`: did the `@production` model alias move?
3. `/campaigns`: has campaign detection grouped the blocks (same device, counterparty, H3 cell or
   MCC)? A campaign points to real fraud.
4. Sample ten recent blocks in the alert feed and read their top SHAP reasons: do they make sense?

## Mitigate

- Real campaign: keep blocking; consider the MCC circuit breaker for an affected merchant category.
- Change-induced: propose reverting the change through `/api/v1/config-changes` (a second risk
  officer approves loosening), or ask an ADMIN to roll back the model. Record the reason.

## Escalate

Head of fraud operations. If legitimate customers were blocked, prepare the customer contact and
unblock plan; the customer verification page (FR-03-04) lets eligible customers self-unblock.
