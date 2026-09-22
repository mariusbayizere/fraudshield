# 0070 — Staff identity, authorisation enforcement and audit anchoring: implementation decisions

- **Status:** Proposed (M7, awaiting owner review)
- **Date:** 2026-09-22
- **Requirements affected:** FR-06-01, FR-06-02, FR-06-06, FR-06-07, FR-07-01 … FR-07-09, NFR-SEC rows
  for authentication, E.8
- **Defects referenced:** D-19, D-20, D-23, D-24, D-26, D-27, D-32, D-51
- **Builds on:** ADR 0011 (contract conventions), ADR 0014 (staff authorisation model), ADR 0017
  (data model and database security)

## Context

ADR 0014 fixed *who* may do *what*. M7 had to decide *how* it is enforced and a number of details
that the SRS, Part B and the frozen contract leave open. The contract (`contracts/`) is frozen for
this milestone, and the schema was designed in M1 before the flows existed. Every decision below
follows build prompt A.3 rule 1: safest for money and personal data, then most faithful to the
acceptance criterion, then simplest to verify.

## Decisions

### 1. The URL policy is read from the contract at runtime; controllers repeat it

`ContractPolicy` loads `fraudshield-api.yaml` and `authorisation-matrix.yaml`, packaged into the
auth jar by the Maven build (never copied by hand). `ContractAuthorizationManager` authorises every
request against it, **deny by default**: a request that matches no contract operation is refused.
Operations of later milestones are therefore protected before their controllers exist.

Controllers also carry `@PreAuthorize` and `@ContractOperation`. `AuthorisationMatrixTest` checks both
layers against the matrix: every handler's method, path and role rule must equal the contract, and
every contract operation is called over HTTP by anonymous, the four roles and API keys of each
scope. A role change stays a two-file edit (contract and matrix), as ADR 0014 intended.

*Rejected:* a hand-written Java rule table (a third copy that can drift), and method security only
(unimplemented operations would answer 404 instead of 403).

### 2. Sessions: one cache for token version and signed-in state

`SessionStateCache` answers one question per request: does the token's `token_version` equal the
account's, and does its `sid` name a refresh family with a live token? It caches in process (1 s)
and in Redis (2 s). Every change is written through to Redis and announced on the pub/sub channel
`fs:auth:sessions` after commit. If the announcement and the Redis write are both lost, the two TTLs
still bound staleness to about 3 s, inside the 5 s of D-27 and FR-06-02. When Redis is down, the
database answers.

Sign-out therefore ends the access token too, not only the refresh token. A failed-sign-in lock
does **not** end sessions, because anyone who knows an email can cause one (D-26). An
administrator's lock, deactivation, a role change, a password change and "sign out everywhere"
increment the token version and revoke every refresh token.

A refresh family keeps its first token's expiry, so a sign-in lasts at most 7 days however often it
is refreshed (FR-07-04 "7-day expiry"). Reuse of a rotated token revokes the whole family.

### 3. Unlock and reset tokens are stateless and bound to account state

The schema has no unlock-token table. The token for the reset step after the OTP is not stored
either. `SignedToken` is `base64url(version | purpose | user | expiry | binding | HMAC-SHA256)`,
99 characters, which fits the contract's `SingleUseTokenRequest` pattern. The binding is a digest
of the state the token acts on. For unlock it is the lock's end; for reset it is the token version,
the password hash and the verified code. Using a token changes that state, so a token verifies
once. The HMAC key (`fraudshield.auth.token-key-hex`) is used for nothing else except keyed OTP
hashes.

*Rejected:* a new token table. It would add a second migration, and it gives no stronger guarantee
than state binding.

### 4. Reset codes are HMAC'd, not hashed

`password_reset_otps.code_hash` holds `HMAC(token key, code id | code)`. A plain SHA-256 of a
6-digit code is reversed by trying all 10^6 codes in milliseconds if the table leaks.

### 5. Enumeration: the same answers for accounts that do not exist or cannot sign in

Unknown emails and PENDING_APPROVAL or DEACTIVATED accounts with a wrong password get 401, then 423
after five tries. A per-email counter in the rate limiter drives this. Every path spends one
bcrypt. Registration, availability and reset requests are padded to a minimum duration
(`minimum-public-response`, default 250 ms). Registration from a domain that may not self-register
creates nothing and sends nothing. The availability endpoint remains the accepted residual channel
of ADR 0014.

### 6. Self-registration and Google sign-in resolve the institution from the email domain

Neither the registration request nor a Google identity names an institution. `fraudshield.auth.
self-service-domains` maps an email domain to an institution ID; a domain not listed cannot create
an account (D-23). It is configuration rather than a table because the contract has no endpoint to
manage it; a table and endpoint are a later contract change.

A Google-created account gets role ANALYST, status PENDING_APPROVAL, department OTHER and a
placeholder employee ID `G` + 19 hex characters of SHA-256 of the Google subject. The column is
NOT NULL and the contract gives administrators no way to set it (see the updates file for the
contract gap). Google names must pass the person-name rule (ADR 0013); if they do not, sign-in
answers 422 and asks the user to register with the form.

A failed-sign-in lock does not stop a verified Google identity. An administrator's lock does.

### 7. Google access tokens are held encrypted in Redis only

FR-07-09 asks for Google tokens to be revoked at sign-out, and the schema has nowhere to keep them.
The access token is sealed with AES-256-GCM (`SecretBox`, associated data bound to the session ID).
It is stored in Redis under the session ID with the token's own TTL, capped at one hour, and
revoked at sign-out. A token of a sign-in that did not produce a session is revoked at once.
Without Redis the token simply expires unrevoked. Offline access is never requested, so no Google
refresh token exists.

### 8. Staff email is sent after commit, never through the outbox

Unlock links, reset codes and temporary passwords are credentials. Writing them to `outbox_events`
would put them at rest in plain text. They are sent from memory on a virtual-thread executor
after the transaction commits, so they are never sent for a rolled-back or retried transaction.
A crash between commit and send loses the email. The user can repeat the request, wait for the
30-minute auto-unlock, or ask an administrator.

### 9. Temporary passwords do not force a change

FR-06-01 asks for a welcome email with a temporary password. The contract has no field to tell the
console a password change is required, so the account works with it until changed. This is a
residual risk recorded in the threat model; a `password_change_required` flag is a contract change.

### 10. Audit anchors cover sequence ranges, and the job reads hashes through owner functions

A calendar-day anchor can miss a row whose transaction started before midnight and committed after
the job ran. Each daily anchor therefore covers the rows appended since the partition's previous
anchor. The Merkle tree is RFC 6962's, streamed in O(log n) memory, and the statement is signed
with Ed25519 from the JDK.

A chain spans every institution that wrote to its partition. The anchoring job (`fs_app`) and the
verifier (`fs_compliance_ro`) read positions and hashes through two SECURITY DEFINER functions
(`audit_chain_head`, `audit_chain_hashes`, V12). They never read content.

The job refuses to sign a chain whose `prev_hash` links or sequence are broken. The verifier checks
every signature up to `--to`, recomputes the anchors inside `[--from, --to]`, and re-hashes the
chain from the last anchor before `--from`.

PB-11 (a dedicated anchoring role) is not solved here. A forged anchor inserted by `fs_app` fails
signature verification and is reported, but it can still block that day's legitimate anchor.

### 11. `users.version` for optimistic locking, returned as an ETag

`StaffUserUpdate.version` has no column in M1's schema, and `StaffUser` does not return a version.
V12 adds `users.version`, advanced by a trigger only when an administrator-editable field changes
(so sign-ins never make an edit stale). The admin API returns it in the `ETag` header of
single-account responses. A stale PATCH answers 409 `conflict` with the current account.

### 12. The last-active-admin rule is a race guard

With one role per account (ADR 0014), an acting administrator is always an ACTIVE ADMIN, so the
last active administrator can only be changed by themselves, which `self-modification` refuses
first. The rule therefore matters when two administrators demote each other at the same moment.
An admin-affecting edit locks every ACTIVE ADMIN row of the institution first, in ID order, which
serialises such edits without deadlock. Administrator writes also re-check the acting account's
role in the database, closing the session-cache window for a just-demoted administrator.

### 13. Unknown JSON fields are refused API-wide

`AuthAutoConfiguration` enables `FAIL_ON_UNKNOWN_PROPERTIES` on the application's JSON mapper.
Every request schema is closed (ADR 0011). A dropped unknown field is how a `role` smuggled into a
registration would go unnoticed. The ingest and decision modules share the mapper and get the same
400 `unknown_field` behaviour, which E.1 requires anyway.

### 14. Enum fields are validated as strings

Jackson aborts a body at the first bad enum value, so the other errors would be lost. Enum fields
are bound as strings and checked by `@EnumValue`, so every error is reported together with the
status ADR 0011 prescribes.

## Consequences

- Required configuration (the context fails without it): JWT signing keys, token key, secret-box
  key and ID, API-key peppers with a current version, console base URL, mail sender, and
  `spring.mail.*`. Redis command and connect timeouts should be a few hundred milliseconds, so
  that a Redis outage degrades quickly.
- New dependencies, all from the Spring Boot BOM: Nimbus JOSE (Apache-2.0), Lettuce (MIT),
  Jakarta Mail / Angus (EPL-2.0 or GPL with CPE; runtime, ADR 0020), Hibernate Validator
  (Apache-2.0) and SnakeYAML (Apache-2.0). The licence tool may need `EXCEPTIONS` entries (see the
  updates file).
- Deviations: FR-07-07 and FR-07-08 say 400 for a weak password and an expired OTP. The contract
  (ADR 0011) answers 422 `password_policy` and 422 `invalid_format`. FR-07-03's "< 3 s" is measured
  against a local fake of Google only.
- Verified by the tests named in `docs/parallel/M7_updates.md`.
