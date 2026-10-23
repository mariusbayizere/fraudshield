# Card-not-present fraud

**Mechanism.** Stolen card details are used for online purchases, or for card payments where the
physical card is not checked.

**Signals in the data.**
- `ONLINE` (mostly) or `CARD` payments at merchants
  (`fraud.scenario_channel_share.card_not_present_online`).
- Often from a device the account has never used (`fraud.new_device_probability`).
- One to five charges per incident (`fraud.rows_per_incident`), somewhat above the usual amount
  (`fraud.amount_multiplier`), at merchants outside the customer's regular set.
- Only customers with smartphones or cards.

**Legitimate look-alikes.**
- Ordinary online shopping at new merchants.
- New devices after a phone change.
- Card spending during travel.

**Assumed parameters.** All the parameters above and `fraud.scenario_share`.
