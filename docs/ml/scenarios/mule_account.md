# Mule accounts

**Mechanism.** Accounts that receive the proceeds of other frauds and quickly pass them on, which
makes the money harder to trace. They may belong to recruited customers or be set up for the
purpose.

**Signals in the data.**
- **Fan-in:** a small set of accounts (`fraud.mule_account_fraction`) is the counterparty of drains
  from many unrelated victims (SIM swap, account takeover, synthetic-identity bust-outs).
- **Fan-out:** the mule's own outgoing transfers are labelled fraud. They come as bursts of 4–12
  transfers (`fraud.rows_per_incident`) to other mules or new accounts, often round sums
  (`fraud.scenario_channel_share.mule_round_sum`), by wallet or bank transfer
  (`fraud.scenario_channel_share.mule_bank_transfer`).
- Cross-border forwarding is more frequent than for ordinary customers
  (`fraud.mule_cross_border_share`).
- These are graph signals: they need counterparty history across accounts.

**Legitimate look-alikes.**
- Popular recipients of family remittances.
- Customers who collect contributions for a group and pay them out.
- Cross-border remittances along EAC corridors (`behaviour.cross_border_share`,
  `behaviour.remittance_corridors`).

**Assumed parameters.** All the parameters above and `fraud.scenario_share`.
