# 0014 — Staff authorisation model

- **Status:** Accepted (author decision after the M1 contracts review, 2026-09-17; the threshold
  separation-of-duties choice is flagged for the repository owner in the M1 status report)
- **Date:** 2026-09-17
- **Requirements affected:** FR-01-05, FR-02-06, FR-03-07, FR-05-01, FR-05-03, FR-05-04, FR-05-07,
  FR-06-01 … FR-06-07, FR-07-01, FR-07-07, FR-07-09, E.1, E.8
- **Defects referenced:** D-12, D-19, D-23, D-24, D-26, D-27, D-44
- **Review findings addressed:** CR-03, CR-04, CR-05, CR-06, CR-09, CR-10, CR-24, CR-30
  (`docs/reviews/M1/contracts-review.md`); NF-03, NF-05, NF-06, NF-07, NF-10 of the re-review

## Context

The first OpenAPI contract declared a role list per operation, but nothing checked that the lists
were right: the M1 review added ADMIN to the alert-decision endpoint and every test still passed.
Endpoint roles alone also cannot express rules that depend on the object (who made a decision, who
an alert was escalated to). E.8 asks for an ADR on whether ADMIN may also hold a decision role. The
SRS itself is split on thresholds: FR-02-06 names the path `PATCH /api/v1/admin/thresholds` and
FR-05-07 says "configurable in admin UI", while thresholds sit in the Risk Officer section and E.8
limits ADMIN to "users/models/system".

## Options considered

1. **Hierarchy-derived permissions** (ADMIN ⊇ RISK_OFFICER ⊇ …) — E.8 forbids inferring permissions
   from the hierarchy, and it gives one compromised ADMIN account the whole decision surface.
2. **Role sets per user** (ADMIN plus a decision role) — represents "also granted a decision role",
   but makes every check depend on combinations and weakens the separation this ADR exists to keep.
3. **One role per account, explicit per-operation roles, a reviewed golden matrix, and object-level
   rules declared in the contract.**

## Decision

Option 3.

1. **One role per account.** The JWT `role` claim holds exactly one of ANALYST, SENIOR_ANALYST,
   RISK_OFFICER, ADMIN. ADMIN is never also given a decision role, so ADMIN does not decide, undo,
   escalate or override alerts and does not read the alert feed. A person who needs both duties
   holds two separately approved accounts; each action is audited under the account that took it.
2. **Golden matrix.** `contracts/openapi/authorisation-matrix.yaml` lists every operation with its
   roles, API-key scopes, `public` or `refresh-cookie`, and the FR, E-section or ADR that justifies
   it. `contracts/tests/test_authorisation_matrix.py` fails when the OpenAPI document and the matrix
   differ in either direction, and asserts invariants: no ADMIN on `/alerts` operations; only
   RISK_OFFICER on overrides and threshold changes; only ADMIN on user, API-key and model lifecycle;
   only ANALYST and SENIOR_ANALYST escalate; API keys reach exactly the four machine operations. The
   matrix also lists each operation's object-level rules (problem type and status, or `filter`), so a
   rule cannot be dropped or changed silently. The M7 role × endpoint test reads the same
   declarations against the running API.
3. **Thresholds (separation of duties).** Only RISK_OFFICER changes thresholds and previews their
   impact; ADMIN and RISK_OFFICER can read them. The SRS path `/admin/thresholds` is kept for
   traceability. Thresholds decide which fraud is declined; an ADMIN, who already controls users,
   API keys and models, must not also control them alone. FR-05-07's "admin UI" is read as the
   console's settings area, which a risk officer uses. *Owner check:* if the owner reads FR-02-06 as
   requiring ADMIN write access, the alternative is dual control (an ADMIN proposes, a different
   RISK_OFFICER approves, both named in the THRESHOLD_CHANGE audit event).
4. **Least-privilege reads (CR-30).** Campaigns are Risk Officer only (FR-05-04). Circuit-breaker
   state is ADMIN only (FR-03-07 "admin panel"). ADMIN keeps read access to model performance because
   promotion and rollback decisions depend on it (FR-06-03).
5. **Object-level rules** are declared per operation in `x-authorisation-rules` (rule, problem type,
   status) and become M7 test obligations:
   - an escalated alert entry is decided only by a caller whose role ranks at or above its target
     role (`escalation-level-required`, 403); `queue=escalated` lists only those entries and entries
     the caller escalated;
   - an escalation target ranks strictly above the caller (422 `validation` with code
     `escalation_target_not_higher`);
   - only the author undoes a decision, and only while it is `PENDING_COMMIT` before `undo_until`
     (`not-decision-author` 403, `undo-window-expired` 409);
   - only a `COMMITTED` decision can be overridden (`decision-not-committed`, 409), and never by the
     risk officer who made it or the override being replaced (`own-decision-override`, 403);
   - an administrator cannot change their own role or status (`self-modification`, 403, FR-07-01),
     and the last ACTIVE ADMIN of an institution cannot be demoted, locked or deactivated
     (`last-active-admin`, 409).
6. **Component health is not public (CR-10).** `/health/ml` and `/health/kafka` require ADMIN,
   because showing that the rule-based fallback is active tells attackers when to strike.
   Orchestration probes use `/actuator/health` on the management port, which returns only `UP` or
   `DOWN` for the API process; the fallback keeps the API able to decide, so ML degradation does not
   change it. **Deviation from SRS 8.1 (staging gate) and 8.2 (uptime probe):** the unauthenticated
   blackbox probe and the smoke test cannot present a staff token, and a monitoring robot with an
   ADMIN account would break the one-person-one-role model. They read `/actuator/health/ml` (and
   `/actuator/health` for Kafka spooling through the aggregate) on the management port, which is
   reachable only from the cluster network and never routed through the ingress. The contract marks
   both with `x-network: management`; OPS-CI-07 and OPS-OBS-05 carry the deviation.
7. **Passwords (CR-09).** 8–72 code points and at most 72 UTF-8 bytes, because bcrypt (cost 12,
   FR-07-07) reads only the first 72 bytes and current Spring Security encoders reject longer input.
   Permitted characters: anything except Unicode general category C (controls such as NUL, tab and
   U+001C; format characters such as U+200B and U+FEFF; surrogates; private use; unassigned) and
   category Z other than U+0020 SPACE (so no U+00A0 or U+2028). Classes are defined by code point,
   not by an engine's `\s`, because Python, JavaScript and Java disagree on what whitespace is.
   Required: an ASCII upper-case letter, an ASCII lower-case letter, an ASCII digit, and a special
   character, meaning any other permitted character except the space (punctuation, symbols, emoji,
   non-ASCII letters and digits). Checks run in the order LENGTH, BYTES, CHARACTERS, UPPER, LOWER,
   DIGIT, SPECIAL. Sign-in and the current-password field accept up to 1,024 characters as a
   transport cap only: input over 72 bytes is answered 401 as invalid credentials without calling
   bcrypt, never as a validation error that would reveal the policy. Zod and Bean Validation implement the same rule in M7 and are tested against
   `contracts/validation/password-vectors.json`; the contract tests carry a reference implementation
   that the vectors are checked against.
8. **Sessions (CR-24).** Access tokens carry a `sid` claim (the session and refresh-token family ID;
   an addition to the E.8 claim list). Logout revokes the family stored under `sid`, because the
   refresh cookie is scoped to the refresh path and never reaches the logout endpoint. Sign-in with
   correct credentials for a `PENDING_APPROVAL` or `DEACTIVATED` account returns 403
   `account-not-active`; a wrong password returns 401 regardless of state. Registration returns the
   same 202 for new and already-registered details and emails the existing owner instead. This does
   not remove enumeration altogether: E.8 requires an availability check for email and employee ID
   during registration, and `/auth/availability` still answers it. That channel is an **accepted
   residual risk**, limited by rate limiting and constant-time responses (E.8).

## Consequences

- A role change is a two-file edit (contract and matrix) that a reviewer sees explicitly.
- Staff who genuinely hold both administrative and decision duties need two accounts, which is more
  friction but keeps each audit trail unambiguous.
- **Residual risk:** two colluding ADMINs, or an ADMIN who creates and approves a sock-puppet
  RISK_OFFICER account, can still bypass separation of duties. Dual control on role grants is
  recorded for the M7 threat model (`docs/security/threat_model.md`) rather than built now.
- If the owner prefers ADMIN write access to thresholds, decision 3 changes to dual control and the
  matrix and tests change with it.
