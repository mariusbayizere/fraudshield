# 0014 — Staff authorisation model

- **Status:** Accepted (author decision after the M1 contracts review, 2026-09-17; decisions 3 and 6
  amended by repository owner decisions on 2026-09-17)
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
3. **Risk configuration under asymmetric dual control** *(owner decision)*. It applies to per-channel
   thresholds, the MEDIUM timeout policy, and MCC circuit-breaker settings.
   - Only RISK_OFFICER proposes, approves, confirms or rejects; **ADMIN cannot** (403), and ADMIN
     keeps read access to thresholds and breaker settings. A change is never reviewed by its
     proposer: self-approval, self-confirmation and self-rejection all return 403 `self-review`.
   - **Tightening** takes effect immediately, within 60 s (FR-05-07), as
     `APPLIED_PENDING_CONFIRMATION`. Tightening means a lower threshold, a lower breaker rate or
     minimum volume, a longer clean reset, or `RELEASE_WITH_TIMEOUT_LABEL` → `DECLINE_AND_VERIFY`. A
     different RISK_OFFICER must confirm it within 24 hours. Otherwise it is reverted automatically:
     the previous settings are restored as a new version, and a THRESHOLD_CHANGE audit event with a
     system actor records the revert. A rejection reverts it at once.
   - **Loosening** is `PENDING_APPROVAL` and changes nothing until a different RISK_OFFICER approves;
     it then takes effect within 60 s of approval. Loosening means the opposite changes, including
     `DECLINE_AND_VERIFY` → `RELEASE_WITH_TIMEOUT_LABEL`; any change mixing tightening and loosening
     elements; and any change of the breaker's rolling window.
   - A proposal must name the version in effect, and at most one change per kind is open. A
     tightening is never blocked, so emergency tightening is always possible (events final review
     F-01). A tightening supersedes an open loosening proposal (`SUPERSEDED`). It folds an unconfirmed
     tightening into itself: the new change keeps that change's baseline and a new 24-hour deadline,
     so one confirmation keeps both and one revert restores the baseline. A loosening is refused
     (409) while another change of its kind is open. The proposer may withdraw a loosening proposal
     (`WITHDRAWN`); a tightening cannot be withdrawn, because that would loosen without a second
     officer.
   - The SRS path `PATCH /admin/thresholds` (FR-02-06) is kept as the threshold proposal.
     `PATCH /admin/circuit-breaker-settings` proposes breaker settings. `GET /config-changes` lists
     pending changes, `POST /config-changes/{id}/approval` and `…/rejection` review them, and
     `…/withdrawal` withdraws a loosening proposal.
   - The OpenAPI contract and the authorisation matrix declare the rules, and
     `common.config.DualControlWorkflow` implements them with an injected clock. A contract test checks
     that every domain refusal maps to a documented status and a catalogued problem type.
     `DualControlWorkflowTest` covers:
     - tightening immediate, loosening pending until approved, and confirmation;
     - auto-revert at exactly 24 hours and not one nanosecond before, and revert on rejection;
     - self-approval 403, and 403 for ADMIN, ANALYST and SENIOR_ANALYST;
     - classification of each threshold and breaker element in both directions, and mixed changes;
     - superseding, folding in, withdrawal, not found, and stale versions.
   - **Deviation:** FR-02-06 and FR-05-07 say a threshold change takes effect within 60 seconds.
     Loosening now takes effect within 60 seconds of *approval*, not of the request.
   - Demo data will seed two RISK_OFFICER accounts so the flow can be demonstrated. They are not
     implemented yet; they come with the M1 database seed, with a test.
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
6. **Health** *(owner decision)*. `GET /api/v1/health` is the only public health endpoint, and it
   returns exactly `{"status": "UP"}` or `{"status": "DOWN"}`, with no component names, versions,
   hostnames or fallback state. Detailed health is exposed only on the management port, which is
   reachable only from the internal cluster network and never routed through the ingress (marked
   `x-network: management` in the contract):
   - `/actuator/health/ml`, `/kafka`, `/db` and `/redis`;
   - `/actuator/health`, which is process liveness only.

   Showing that the ML scorer has fallen back to rules would tell attackers when to strike. **Deviation
   from E.1 and SRS 8.1 and 8.2:** `/api/v1/health/ml` and `/api/v1/health/kafka` do not exist. The
   staging smoke test and the uptime probe read the management endpoints instead, and the admin health
   panel (FR-06-05) gets its detail from the backend. OPS-CI-07 and OPS-OBS-05 carry the deviation.
7. **Passwords (CR-09).** 8–72 code points and at most 72 UTF-8 bytes, because bcrypt (cost 12,
   FR-07-07) reads only the first 72 bytes and current Spring Security encoders reject longer input.
   Permitted characters: anything except Unicode general categories Cc, Cf, Cs and Co (controls such
   as NUL, tab and U+001C; format characters such as U+200B and U+FEFF; surrogates; private use) and
   category Z other than U+0020 SPACE (so no U+00A0 or U+2028). Unassigned code points (Cn) are
   allowed and count as special: which code points are unassigned depends on the Unicode version of
   each runtime (Python 3.12 has 15.0), so rejecting them would make Python, Java and JavaScript
   disagree on new characters. Classes are defined by code point,
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
- Risk officers must be staffed so that a second officer can review within 24 hours; otherwise
  tightening changes revert and loosening changes wait.
