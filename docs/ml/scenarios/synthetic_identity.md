# Synthetic identity

**Mechanism.** An account opened with a fabricated or blended identity behaves like an ordinary new
customer for a few months to build limits and trust, then "busts out": it moves out as much money
as it can and is abandoned.

**Signals in the data.**
- A recently joined account (`fraud.synthetic_identity_fraction`, bust-out after
  `fraud.synthetic_identity_bust_out_months`).
- Its earlier transactions are ordinary and not labelled fraud.
- The bust-out is a burst of 3–8 large transactions (`fraud.rows_per_incident`,
  `fraud.amount_multiplier`): cash-outs at the customer's agent and transfers to mule accounts
  (`fraud.scenario_channel_share.synthetic_cash_out`).
- The account's tenure and the sudden break from its own short history are the signals.

**Legitimate look-alikes.**
- New customers whose activity grows quickly.
- Customers making a large one-off payment (school fees, a purchase) or withdrawing savings.

**Assumed parameters.** All the parameters above and `fraud.scenario_share`.
- **Calibration consequence:** `fraud.synthetic_identity_fraction` is 12% of new joiners because the
  assumed scenario share has to be met with one bust-out per identity. That is higher than a
  plausible real share of onboarding, and the rationale in the parameter file says so.
