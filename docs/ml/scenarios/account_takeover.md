# Account takeover

**Mechanism.** Someone obtains a customer's credentials (for example by phishing) and signs in
from their own phone. They spend online and transfer money out while the session lasts.

**Signals in the data.**
- A `DEVICE_CHANGE` row in `account_events` shortly before the activity
  (`fraud.takeover_lead_minutes`).
- The transactions carry a device fingerprint never used by the account before.
- A burst of 2–8 transactions (`fraud.rows_per_incident`): online purchases at merchants the customer
  does not normally use, and wallet or bank transfers to mule or new accounts.
- Amounts above the usual median (`fraud.amount_multiplier`).
- Only customers with smartphones (the takeover needs an app session).

**Legitimate look-alikes.**
- Customers change or reinstall phones (`population.device_change_monthly_probability`), and the new
  device keeps being used for ordinary activity.
- Occasional travel (`behaviour.travel_probability`).
- Online purchases at new merchants.

**Assumed parameters.** All the parameters above and `fraud.scenario_share`.
