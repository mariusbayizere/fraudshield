# FraudShieldTimeoutReleaseRateHigh (risk officers)

**Meaning.** On one channel, the share of MEDIUM holds that ended by the review deadline rather
than by an analyst decision has been above the configured level
(`fraudshield:config:timeout_release_ratio_max`, in
`infrastructure/prometheus/rules/thresholds.yml`) for 10 minutes, with at least 20 holds per
15 minutes (D-10).

**The level is ASSUMED.** The default 0.10 is a placeholder chosen without a capacity model.
It is replaced by the level derived in `docs/ml/capacity_model.md` (alert budget against analysts
on shift) once that document exists. Until then, treat this alert as a signal to check analyst
capacity, not as a calibrated threshold, and report how often it fires so the capacity model can
use it.

**Impact.** Under the `RELEASE_WITH_TIMEOUT_LABEL` policy each timeout **approves a transaction
the model rated risky without anyone looking at it**. This is the silent failure D-10 exists to
catch.

## Diagnose (risk officer)

1. **FraudShield / Analyst operations**: MEDIUM queue depth (`fs_alert_queue_depth{tier}`) and
   review duration (`fs_review_duration_seconds`). A deep queue means too few analysts for the
   alert volume; a shallow queue with timeouts means alerts are not reaching analysts.
2. Alerts not reaching analysts: check `FraudShieldKafkaConsumerLagHigh` for the `alerting` and
   `websocket-fanout` groups.
3. Alert volume jumped: a threshold change or a campaign (see the other risk runbooks).

## Mitigate

- Bring more analysts onto the shift, or
- switch the channel's MEDIUM timeout policy to `DECLINE_AND_VERIFY` through
  `/api/v1/config-changes`. This is a tightening, so it takes effect within 60 s as
  `APPLIED_PENDING_CONFIRMATION`; a different risk officer must confirm it within 24 hours or it
  reverts automatically (ADR 0014; audited as `THRESHOLD_CHANGE`). It trades customer friction
  for not approving risky payments unseen.

## Escalate

Head of fraud operations. Record the analyst capacity gap against `docs/ml/capacity_model.md`.
