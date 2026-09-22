# FraudShieldFraudRateAnomaly (risk officers)

**Meaning.** The HIGH-tier share of one channel's decisions over the last hour is more than 3
standard deviations from its 30-day hourly mean, in either direction (SRS 8.2). Confirmed-fraud
labels arrive hours to weeks later, so the live "fraud rate" is the model-flagged rate.

**Impact.** A rise can be a campaign on that channel; a fall can mean the model, features or
thresholds stopped flagging fraud (for example a broken feature for USSD). Both need a human look
within minutes.

## Diagnose (risk officer)

1. **FraudShield / Risk overview**: the channel's flagged share against its 30-day band.
2. A rise: check `/campaigns` and the top counterparties and districts on `/portfolio`.
3. A fall: ask the ML on-call to check feature drift (`fs_feature_psi`) and whether the model or a
   threshold changed (`GET /api/v1/config-changes`, `GET /api/v1/admin/models`).
4. Volume: a large drop in the channel's total traffic makes the share noisy; see the
   transaction rate panel.

## Mitigate

Same as `FraudShieldAutoBlockRateAnomaly`: act on a campaign, revert an unintended change through
dual control, and record the decision.

## Escalate

Head of fraud operations for a rise; ML on-call and engineering lead for a fall.
