# SIM-swap takeover

**Mechanism.** A customer's mobile number is moved to a new SIM that someone else controls. Wallet
access tied to the number follows it, and money is moved out before the customer notices the lost
service.

**Signals in the data.**
- A `SIM_SWAP` row in `account_events` minutes before the transfers (`fraud.takeover_lead_minutes`).
- A short burst of 2–6 outgoing person-to-person transfers (`fraud.rows_per_incident`,
  `fraud.burst_minutes`). Most go to known mule accounts, the rest to accounts the customer never
  paid before (`fraud.drain_to_mule_probability`).
- Amounts above the channel's usual median (`fraud.amount_multiplier`), often round sums.
- For smartphone customers, often a device never seen on the account (`fraud.new_device_probability`).
- **Novel sub-variant, test period only (D-08):**
  - the enabling event shows as a device change (re-provisioning), not a SIM swap;
  - the drain follows days later (`fraud.novelty_delay_days`), by bank transfer for smartphone
    customers (`fraud.novelty_share_of_sim_swap`).

  A model that learned "SIM swap then minutes later a drain" will not fire on it.

**Legitimate look-alikes.**
- Legitimate SIM replacements after a lost or damaged SIM
  (`population.legit_sim_swap_monthly_probability`), followed by ordinary activity.
- Round-sum family transfers.
- Occasional transfers to new counterparties.

**Assumed parameters.** All the parameters above, plus the scenario's share of fraud
(`fraud.scenario_share`).
