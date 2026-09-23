# 0069 — The PII vault: a separate instance, per-row keys, and who may read it

- **Status:** Accepted; revised 2026-09-23 (points 3, 9 and 10: the key service, the tokenisation
  map, and where keys in configuration are allowed)
- **Date:** 2026-09-22
- **Requirements affected:** FR-03-04, FR-03-05, NFR-SEC-03
- **Defects referenced:** D-20
- **Builds on:** ADR 0012 section 4 (tokenisation and the vault), ADR 0021 (D-20 moved to M6),
  ADR 0065 (customer SMS and verification), ADR 0068 (persistence rule)

## Context

FraudShield carries no customer PII: every identifier in the main database, on every topic and in
every contract is a token. But an SMS has to reach a phone, and the message has to name an account
the customer recognises. D-20 asks for the vault that holds those two values, and ADR 0021 put it
in M6 because the first consumers are here. Until now `ContactDirectory` had no implementation, so
no customer SMS could be sent and FR-03-04 and FR-03-05 were dark in production.

## Decision

1. **A separate PostgreSQL instance**, `pii-vault` in Compose since M0, with its own volume and
   credentials. Two roles exist in it: `fs_vault_migrator`, which owns the schema, and `fs_vault`,
   which the notification service uses and which may only `SELECT`, `INSERT` and `UPDATE`
   `vault.contacts`. `fs_app`, `fs_app_readonly` and `fs_compliance_ro` **do not exist there**: a
   grant cannot be forgotten if the role is absent. Nothing that can read a transaction can read a
   phone number, and nothing in the vault can read a transaction.
2. **Envelope encryption, AES-256-GCM.** Each row carries its own data key, itself wrapped under a
   master key held by a `KeyProvider`. The row's identity — institution and account token — is the
   additional authenticated data, so a ciphertext or a wrapped key copied into another row fails to
   decrypt instead of answering for the wrong customer. A dump of the vault without the key
   material yields row counts and timestamps.
3. **The key provider is an interface with two implementations.** `PassphraseKeyProvider` takes
   its master keys from configuration, keyed by id, with older ids kept so rows written under them
   still read; it is the local key-encryption key for development, demos and CI (point 10).
   `KmsKeyProvider` is the production one: the key-encryption key stays in a key management
   service, which generates each data key and unwraps it again, under a fixed context that binds
   the wrapping to the vault. It talks to the service through `KmsClient`, whose shape is the core
   the cloud services share (AWS KMS `GenerateDataKey`/`Decrypt`, Google Cloud KMS with additional
   data, Vault Transit `datakey`). **The vendor binding is not written in M6** (revised
   2026-09-23): which service it is follows M9's choice of cloud, and an adapter written blind
   against a service nobody here can reach would be untested code on the path of every customer's
   phone number. Instead every binding must pass `KmsClientContract` (notify's test jar), which
   the in-memory double passes today: 256-bit keys, a wrong context, key or wrapping refused, an
   unknown key refused. Every row records the id of the key that wrapped it; a row naming a key
   this deployment does not list is refused before the service is asked, never guessed.
4. **The value is one JSON document** holding the phone number in E.164, the preferred locale and
   the masked account number. It is resolved for one send, never logged, stored or put on a topic;
   `ContactDirectory.Contact` redacts itself in `toString`, and the plaintext buffer is cleared
   after it is read.
5. **Explicit SQL, not JPA** (ADR 0068 point 2): nothing here may be written by change tracking or
   held in a persistence context, and the upsert is `ON CONFLICT`.
6. **The channel starts only when the vault is configured.** Without `fraudshield.vault.url` there
   is no `ContactDirectory` bean, the SMS consumer does not start, and intents stay on
   `fs.notifications.customer`, durable and unconsumed — the same shape as the missing webhook
   registry (ADR 0066). The start-up log says which.
7. **Who fills it.** M6 has no enrolment endpoint: `VaultContacts.store` and
   `AccountTokens.tokenise` are what an institution's onboarding calls, which is M7's staff and
   batch API. Until then the vault is empty in a real deployment, so FR-03-04 remains
   **DONE_WITH_DEVIATION**: the path exists and is tested end to end with the real vault, and no
   customer receives an SMS until contacts are enrolled. Self-service being refused while the MNO
   SIM-swap signal is missing is **not** a deviation: the owner confirmed it on 2026-09-23 as
   D-25's intended behaviour (ADR 0065 point 1).
8. **The masked account gap closes here.** ADR 0065 point 4 recorded that
   `notification-customer.parameters.masked_account` must be a masked account number while the API
   had only tokens. The vault holds the number the customer knows, masked, so the SMS renders what
   the contract asks for. The contract itself is unchanged.

9. **The tokenisation map** (added 2026-09-23; the "tokenisation map" of the architecture table,
   and ADR 0021's "tokenisation at ingestion"). `vault.account_tokens` holds one permanent token
   per account number per institution, so the same account is the same token whether it sends or
   receives, which the counterparty features depend on. The number is found by a blind index
   (HMAC-SHA-256 of the institution and the normalised number under an index key the database never
   sees) and stored as AES-256-GCM ciphertext under a per-row data key, with institution and token
   as additional data. Concurrent first requests for one number agree on one token (`ON CONFLICT
   DO NOTHING` on the index, then read back). `fs_vault` may read and insert, not update or delete.
   The index key must never change, because a new key gives every number a new token; under a key
   service it is configured wrapped and unwrapped once at start-up. `AccountTokens` has no HTTP
   endpoint in M6 (the contracts are frozen); the institution onboarding that enrols accounts
   (M7) calls it, as it calls `VaultContacts.store`.
10. **Keys in configuration are refused in production** (added 2026-09-23). The key provider is
    `fraudshield.vault.key-provider`, **`kms` by default**. `kms` without a bound `KmsClient` stops
    the application at start-up rather than falling back; `kms` with master keys in configuration
    is refused as contradictory. `configured` starts only when a `dev`, `demo` or `test` profile is
    active, so a production deployment cannot select it by a missing setting. This is the
    stricter of the two readings of "a key from configuration", chosen because a mistake here is
    silent: the vault would work, with its keys in an environment file.

## Consequences

`AccountTokensTest` (one token per number however it is written; concurrent first requests agree;
the database holds neither the number nor an unkeyed hash of it; a wrong key or a moved row does not
decrypt; the application role cannot change or delete a token; the same under a key service),
`KmsKeyProviderTest` and `InMemoryKmsTest` (the `KmsClientContract`), `VaultConfigurationTest`
(point 10), `IngestApiTest.customerNumbersAndPhonesReachTheSmsProviderAndNothingElse` (a blocked
payment from a tokenised account, with the real vault in the API: the phone reaches the SMS
provider, and neither it nor the account number is in any log line, Kafka record on any topic, Redis
key or value, or spool file), and `VaultContactsTest` (a contact goes in and comes back; the stored
bytes contain neither the number nor the locale; a row copied to another account does not decrypt;
another master key does not decrypt; a missing key id is refused; the main database's roles do not
exist in the vault, and `fs_app` cannot connect even if someone creates it there, because `CONNECT`
is revoked from `PUBLIC`; `fs_vault` cannot drop or delete; master keys are checked when the
provider is built).

Operationally the vault is a second database to back up, restore and rotate keys for, and its
master keys must reach the API process without touching the repository. Key rotation re-wraps rows
lazily: a row keeps its key id until it is written again, which is why old ids stay configured.

**Bookkeeping**: this vault's code was committed in `452d1d9`, whose message describes only the
JPA configuration change. The two were staged together by mistake. The history is not rewritten
because the branch is pushed; `docs/parallel/M6_updates.md` records it so a reviewer looking for
the vault's commit finds it.
