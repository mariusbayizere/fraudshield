# 0072 — Employee IDs are unique per institution, not across the deployment

- **Status:** Proposed (M7, awaiting owner review)
- **Date:** 2026-10-17
- **Requirements affected:** FR-06-01 (deviation recorded); FR-07-02 (registration availability)
- **Defects referenced:** D-31
- **Builds on:** ADR 0017 (data model and database security), ADR 0070 (staff identity)

## Context

FR-06-01's acceptance criterion says `UNIQUE(employee_id)`, and SRS section 4's `users` row repeats
it. The SRS was written for one bank. FraudShield serves several institutions from one database
(ADR 0017), and two banks number their staff independently: `EMP0001` exists at both.

The defect register already settled this. D-31's resolution says "`UNIQUE(employee_id)` becomes
`UNIQUE(institution_id, employee_id)`", and V2 has carried that constraint since M1. M7's deletion
audit (2026-10-17) found that FR-06-01 was marked DONE with no deviation recorded, so a reader of
the requirement would believe employee IDs are unique across the deployment. This ADR records the
deviation where the requirement points.

## Decision

1. **Uniqueness is per institution.** `users` keeps `UNIQUE (institution_id, employee_id)` (V2). An
   administrator creating an account gets 409 `conflict` when the employee ID exists in their own
   institution (`UserAdministrationService`, `existsByEmployeeId` under row-level security, with the
   constraint as the backstop). The same ID at another institution is accepted.
2. **Self-registration errs towards "taken".** Registration (E.8) asks about an employee ID before
   the institution is known, so `auth_employee_id_registered` (V70) answers across every
   institution. A newcomer whose ID exists at another bank is told it is taken and contacts their
   administrator. This leaks one bit (that some institution uses the ID), which is bounded by the
   public-endpoint rate limit (D-26) and is the price of not revealing which institutions exist.
3. **FR-06-01 can close only as DONE_WITH_DEVIATION**, citing D-31 and this ADR, never as DONE.
   Its other clauses (every field stored, the Google set-up link in the welcome email) are not yet
   all proven, so it stays IN_PROGRESS until they are.

## Consequences

- Employee IDs identify a person only together with the institution. Anything that joins on
  `employee_id` (reports, M10's audits) must join on the pair.
- `email` stays unique across the deployment (the unique index `users_email_unique`, V2), because sign-in finds the
  account by email before the institution is known.
- `UserAdministrationTest.adminCreatesAccountThatSignsInWithTheEmailedTemporaryPassword` proves
  both halves: the duplicate in one institution is refused with 409, and the same ID is accepted
  with 201 at another. Deleting the per-institution check and the constraint fails the first;
  making the constraint deployment-wide fails the second.
