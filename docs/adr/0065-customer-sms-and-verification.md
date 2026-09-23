# 0065 — Customer SMS, verification and local time

- **Status:** Accepted
- **Date:** 2026-09-22
- **Requirements affected:** FR-03-04, FR-03-05
- **Defects referenced:** D-25, D-42, D-43, D-51

## Context

E.7 and D-25 specify the auto-block SMS and the verification page. Three facts constrain them:
FraudShield only ever sees account tokens; no MNO SIM-swap adapter exists yet; and the SMS must be
one GSM-7 segment in four languages.

## Decision

1. **Self-service is refused** when the SIM swap is under 7 days **or the signal is unavailable**,
   the device changed in 24 hours, the score is 0.95 or more, the model's reasons indicate takeover,
   or the fallback decided. Until an MNO adapter exists every block is therefore told to call the
   institution; that is D-25's rule, not a defect. **The owner confirmed this on 2026-09-23**: with
   the SIM-swap signal unavailable, refusing customer self-service is correct per D-25 and is
   recorded as intended behaviour, not as a deviation.
   `SmsPolicyTest.withoutTheSimSwapSignalNoBlockOffersSelfService` holds it: no score, channel or
   device state offers the link while `days_since_sim_swap` is missing. The value arrives through
   the scorer's feature vector (ADR 0033), so when the MNO feed reaches the feature store the link
   becomes available without a change here.
2. **The link** is minted only at send time: 128 random bits, stored as SHA-256, bound to the
   block, one per block, expiring at exactly 10 minutes, answered once (V5 triggers). The page
   never says why a token is unusable beyond "expired or already used".
3. **Local time** follows the transaction currency's country (CAT, EAT; the DRC is split at 22.5°E
   between Kinshasa WAT and Lubumbashi CAT, an approximation of the provincial boundary); USD and
   EUR carry no country and are shown in UTC rather than a guessed zone.
4. **Contract gap, now closed by the vault**: `notification-customer.parameters.masked_account`
   must be a masked account, but the API has only tokens. The intent carries the token's last four
   characters; the sender uses the vault's masked account number when it renders the message. The
   vault exists since ADR 0069, so what the customer reads is the account number they know.
5. **Templates** are GSM-7, fit one segment at worst-case lengths with a verification link of at
   most 54 characters (`https://` + a 21-character domain + `/v/` + token), never ask for a PIN or
   password, and are all `machine_draft` (D-43): none has had native-speaker review.

6. **The answer is committed before the decision moves** (Principal Review finding 6). The response
   row and the `unblock_events` row are one transaction; the decision transition follows it and can
   fail on its own (a full spool, an unknown spool outcome, a refused state write). The answer
   stands, because the token is single-use and the customer cannot give it again: the page then
   says the payment "is being unblocked" instead of "is unblocked", and a leader-run sweep (V64,
   10-second grace) applies the transition. The sweep is idempotent: a decision that has already
   moved on is refused and skipped.

## Consequences

`SmsPolicyTest`, `VerificationFlowTest` (including an answer whose transition fails, applied by the
sweep), `ResilienceApiTest` (page over HTTP, block lifted and webhook delivered within 10 s). The PII-vault `ContactDirectory` is `VaultContacts` (ADR 0069). Without
`fraudshield.vault.url` the SMS channel still does not start and intents stay on Kafka; with it,
sending waits on contacts being enrolled (M7's onboarding) and on the MNO SIM-swap signal.
