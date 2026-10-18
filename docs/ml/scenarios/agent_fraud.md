# Agent fraud

**Mechanism.** A cash agent processes cash-outs a customer did not request (fake cash-outs), or
manipulates the float between customer accounts and the agent's own. The loss appears as cash-out
transactions on customers' accounts at that agent.

**Signals in the data.**
- `AGENT_BANKING` cash-outs (MCC 6011) at a small set of compromised agents
  (`fraud.compromised_agent_fraction`), often not the customer's usual agent.
- Round sums, one to three withdrawals per incident (`fraud.rows_per_incident`), more than the
  usual cash-out amount (`fraud.amount_multiplier`).
- Across customers, the same agent shows an unusual concentration of such withdrawals, a signal
  visible only when aggregating per agent.

**Legitimate look-alikes.**
- Cash-outs peak twice a day at every agent (`behaviour.agent_peak_hours`) and are often round sums
  (`behaviour.round_sum_probability`).
- Customers sometimes use an agent away from home.

**Assumed parameters.** All the parameters above and `fraud.scenario_share`.
