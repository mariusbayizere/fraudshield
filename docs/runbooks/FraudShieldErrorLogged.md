# FraudShieldErrorLogged

**Meaning.** A FraudShield service logged at least one ERROR line in the last minute (SRS 8.2:
any ERROR log pages). The alert is grouped per service, so a burst is one page. The rule is in
`infrastructure/loki/rules/fraudshield/errors.yml`.

**Impact.** Unknown until diagnosed: an ERROR marks something a developer judged should never
happen. Treat repeated ERRORs as a defect, not noise.

## Diagnose

1. Grafana Explore (Loki): `{namespace="fraudshield", app="<app from the alert>"} | json |
   level=~"(?i)error"` for the last 15 minutes.
2. Group by message: one message repeated, or many different ones?
3. Follow the `trace_id` of one line into the trace view to see the request path.
4. Check whether another alert explains it (scorer down, Kafka lag, database).

Logs pass through PII-redacting processors (E.10). If a line still contains a phone number,
name or account number, that is a privacy incident: report it to the engineering lead at once and
do not copy the line anywhere.

## Mitigate

Fix the cause. If the ERROR is not actionable, change its level in code with a review; never
filter it out in the alert rule.

## Escalate

Engineering lead for any ERROR that affects decisions, audit writes or customer notifications.
