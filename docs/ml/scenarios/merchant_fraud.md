# Merchant fraud

**Mechanism.** A merchant (or someone controlling a merchant account) processes card charges that
customers did not make, for example using compromised card data.

**Signals in the data.**
- `CARD` charges at a small set of colluding merchants (`fraud.colluding_merchant_fraction`), from
  customers who do not usually shop there.
- One to three charges per incident (`fraud.rows_per_incident`), moderately above typical amounts
  (`fraud.amount_multiplier`).
- Aggregated per merchant, an unusual share of first-time customers. The signal is at the merchant
  level, not in a single row.

**Legitimate look-alikes.**
- New shops gaining customers.
- Customers trying a new merchant.
- Seasonal spikes (school-fee months, `behaviour.school_fee_months`).

**Assumed parameters.** All the parameters above and `fraud.scenario_share`.
