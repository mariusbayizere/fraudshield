# Runbooks

One runbook per page-worthy alert (build prompt E.10). An alert is page-worthy when its rule has
`severity: page`; `infrastructure/checks/rule_conventions.py` fails if such an alert has no runbook
here, or if a runbook has no alert. Rules: `infrastructure/prometheus/rules/alerts.yml` and
`infrastructure/loki/rules/`. Routing: `infrastructure/alertmanager/alertmanager.yml`.

| Alert | Team | Source |
|---|---|---|
| [FraudShieldApiHealthProbeFailing](FraudShieldApiHealthProbeFailing.md) | sre (P1) | SRS 8.2 uptime |
| [FraudShieldMlHealthProbeFailing](FraudShieldMlHealthProbeFailing.md) | sre (P1) | SRS 8.2 uptime |
| [FraudShieldDecisionLatencyP99High](FraudShieldDecisionLatencyP99High.md) | sre | SRS 8.2 p99 > 80 ms |
| [FraudShieldTransactionRateDrop](FraudShieldTransactionRateDrop.md) | sre | SRS 8.2 TPS drop > 20% |
| [FraudShieldKafkaConsumerLagHigh](FraudShieldKafkaConsumerLagHigh.md) | sre | SRS 8.2 lag > 5,000 |
| [FraudShieldErrorLogged](FraudShieldErrorLogged.md) | sre | SRS 8.2 any ERROR log |
| [FraudShieldMlFallbackActive](FraudShieldMlFallbackActive.md) | sre | C.4 fallback |
| [FraudShieldSpoolBacklog](FraudShieldSpoolBacklog.md) | sre | D-15 spool |
| [FraudShieldAutoBlockRateAnomaly](FraudShieldAutoBlockRateAnomaly.md) | risk | SRS 8.2 auto-block > 3x baseline |
| [FraudShieldFraudRateAnomaly](FraudShieldFraudRateAnomaly.md) | risk | SRS 8.2 fraud rate > 3 sigma |
| [FraudShieldTimeoutReleaseRateHigh](FraudShieldTimeoutReleaseRateHigh.md) | risk | D-10 timeout release |

## Conventions

- Commands assume the production namespace `fraudshield` (staging: `fraudshield-staging`) and
  read-only access unless a step says otherwise. Health detail is on the management port, which is
  reachable only inside the cluster (ADR 0014 section 6):
  `kubectl -n fraudshield port-forward svc/fraudshield-api-management 8081`.
- Risk configuration (thresholds, circuit breakers, MEDIUM timeout policy) changes only through
  `/api/v1/config-changes`, never by editing the database: loosening needs a second risk officer
  (ADR 0014), and every change is audited (D-32).
- Never paste transaction payloads, account tokens or customer contact details into incident
  channels or tickets. Reference `transaction_id` values only.
- Every page ends with a short incident note: timeline, customer impact (count of affected
  transactions by decision), cause, and the follow-up issue.

Each runbook has the same sections: **Meaning**, **Impact**, **Diagnose** (first five minutes),
**Mitigate**, **Escalate**.
