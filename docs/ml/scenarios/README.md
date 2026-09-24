# Fraud scenario design notes

One page per scenario generated in FraudShield-EAC-Transactions (Part E.3, ML-DATA-04, ADR 0022).
Each note describes the real-world mechanism in plain terms, the signals it leaves in the data, the
legitimate behaviour that looks similar, and which of its parameters are assumed.

The notes are written at the level of **detection signals**. They explain what a fraud-detection
system can observe, not how to commit fraud.

Every parameter named here lives in `dataset/generator/params/` with its provenance, and
`dataset/params_provenance.md` lists them all. All behavioural parameters of the scenarios are
**ASSUMED**: none is taken from a published East African fraud statistic. The prevalence targets
they are calibrated to (0.87% overall, 0.91% in the temporal hold-out test period) come from SRS
section 7.1.

Shared by every scenario:
- Rows go through the same code path, formats and null rules as legitimate rows.
- Incidents follow the victim's daily activity profile, with a share at night
  (`fraud.night_probability`).
- From simulation month 12 some amounts adapt to just below a published rule threshold
  (`fraud.adaptation_*`, `fraud.rule_amount_threshold_rwf`).

| Scenario | Note | Main signals |
|---|---|---|
| SIM-swap takeover | [sim_swap.md](sim_swap.md) | SIM swap shortly before transfers to new or mule counterparties |
| Account takeover | [account_takeover.md](account_takeover.md) | new device, then online spend and transfers |
| Agent fraud | [agent_fraud.md](agent_fraud.md) | cash-outs concentrated at a few agents, round sums |
| Velocity fraud | [velocity.md](velocity.md) | bursts of small payments in minutes |
| Card-not-present | [card_not_present.md](card_not_present.md) | online or card spend from an unseen device |
| Mule accounts | [mule_account.md](mule_account.md) | many inbound drains, fast forwarding outward |
| Merchant fraud | [merchant_fraud.md](merchant_fraud.md) | card charges clustering at a few merchants |
| Synthetic identity | [synthetic_identity.md](synthetic_identity.md) | quiet new account, then a bust-out |
