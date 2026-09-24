# Velocity fraud

**Mechanism.** Many small payments in quick succession, for example testing whether stolen card
details work, or draining a balance in amounts small enough to avoid attention.

**Signals in the data.**
- 6–15 transactions within minutes (`fraud.rows_per_incident`, `fraud.burst_minutes`).
- Small amounts (a fraction of the channel median, `fraud.amount_multiplier`).
- Card payments at merchants, or wallet payments to accounts never paid before
  (`fraud.scenario_channel_share.velocity_card`).
- The signal is the count per short time window, not any single payment.

**Legitimate look-alikes.**
- Busy shoppers and traders on market days (`behaviour.market_day_weight`).
- Payday spending weeks (`behaviour.payday_spend_boost`).
- Several small airtime and utility payments together.

**Assumed parameters.** All the parameters above and `fraud.scenario_share`.
