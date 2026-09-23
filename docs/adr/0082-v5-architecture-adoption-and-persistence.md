# 0082 — SRS v5.0's architecture requirement, adopted and reconciled with what is built

- **Status:** Accepted (owner decision, 2026-09-23)
- **Date:** 2026-09-23
- **Requirements affected:** FR-07-01 (method-level authorisation), FR-06-02, the v5 §02/§03
  architecture statement, and every module's layering
- **Defects referenced:** D-27, D-52, D-54; ADR 0003 (versions), ADR 0017 (data model), ADR 0070,
  ADR 0071 (hybrid persistence, on `m7/staff-auth`), ADR 0080 (front-end architecture)

## Context

SRS v5.0 §02 states a four-layer MVC architecture — "No business logic appears in controllers. No
database queries appear in services. Raw JPA entities never cross a layer boundary" — with the
package tree `com.fraudshield.{controller,service,repository,entity,dto,security,exception,kafka}`,
and §03 gives a Spring Security configuration with `csrf.disable()`, a hand-written
`JwtAuthenticationFilter` and a `UserDetailsService` lookup per request. §23.1 adds "Hibernate +
Spring Data JPA … no raw SQL".

The built backend is hexagonal (ports and adapters), which the build prompt requires (H.1, H.2) and
ArchUnit enforces per module. The owner adopted v5's architecture **as the written requirement**
while directing that where the built code is stricter, that satisfies it, and that the hybrid
persistence rule stays.

## Decision

### 1. The architecture requirement, in the project's own words

Adopted, and satisfied by what exists:

| v5 requirement | How it is met | Stricter than v5? |
|---|---|---|
| No business logic in controllers | Controllers translate HTTP only; the rule is in their own javadoc, e.g. `IngestController` ("Controllers only translate HTTP: every decision is the application services'") | Same |
| Business rules in a service layer | `application/` services depend on `application/port/` interfaces, never on adapters; `domain/` is pure | **Yes** — the dependency direction is enforced, not merely described |
| Repositories hold no business logic | Repositories are adapters behind ports (`adapter/jdbc`, `adapter/redis`, `adapter/kafka`) | Same |
| DTOs at every boundary; entities never cross one | Request and response Java records with `@Valid`; entities stop at the adapter | **Yes** — entities never leave the adapter, let alone the layer |
| Method-level `@PreAuthorize` | 35 occurrences on the staff endpoints, with an enumerating deny-by-default matrix test | Same, plus the test |
| Layering is checked | ArchUnit per module (`domainIsPure`, `applicationUsesPortsNotAdapters`) | **Yes** — v5 states the rule; here a test fails when it breaks |

**The package tree is not renamed.** `io.github.mariusbayizere.fraudshield.<module>.{domain,
application,adapter,web}` expresses the same separation as `com.fraudshield.{controller,service,
repository,entity}` with the dependency rule made explicit. Renaming 204 files would change no
behaviour and invalidate every path cited in reviews, ADRs and the register.

**No Lombok**, contrary to v5's `@RequiredArgsConstructor` examples: the build prompt forbids it
without an ADR (H.2), and constructor injection is written out.

### 2. Spring Security

Adopted in substance: JWT RS256 with a 15-minute access token, BCrypt cost 12, four roles, deny by
default. Not adopted, with reasons in D-54: `csrf.disable()` (the refresh token is an httpOnly
cookie by D-27, so the double-submit token on `/auth/refresh` and `/auth/logout` is load-bearing)
and a `UserDetailsService` lookup per request (`token_version` as a claim, checked against a
2-second Redis cache with pub/sub invalidation, already meets FR-06-02's 5-second budget without a
database round trip on the hot path).

### 3. Why "JPA everywhere" is not adopted

ADR 0071 (on `m7/staff-auth`) keeps a hybrid: Spring Data JPA for CRUD domains, explicit SQL for
four things an ORM cannot express safely here. v5's "no raw SQL" would unwind all four:

1. **The audit hash chain.** Each row's `row_hash` covers the previous row's hash, so inserts must
   reach the database in the order the application decided. Hibernate's flush is free to reorder
   and batch; a reordered insert silently breaks the chain, and `verify_audit_chain` then reports a
   tampered log that was never tampered with.
2. **Append-only tables.** `auto_block_events`, `alert_decisions` and `audit_events` carry triggers
   that refuse UPDATE and DELETE for every role. Hibernate's dirty checking issues UPDATEs as a
   matter of course; the ORM and the trigger are in direct conflict.
3. **Hypertables and security-barrier views.** TimescaleDB hypertables, continuous aggregates and
   the tenant-isolating views have no JPA mapping. Reading them through the ORM means either
   native queries — raw SQL by another name — or losing the isolation the view provides.
4. **Set-based revocation.** Refresh-token family revocation is one statement whose ordering the
   ADR 0070 race fix depends on. Expressed as entity loads and saves it becomes several
   statements with a window between them.

A fifth follows from ADR 0017: the **PII vault** is a separate database with its own role, which
the application role cannot read. It is reached deliberately and narrowly, not through the ORM's
session.

The rule therefore stays as ADR 0071 states it, and this ADR records why v5's wording does not
change it. ADR 0071 belongs to `m7/staff-auth`; the amendment pointing at this decision is proposed
to the M7 agent in `docs/parallel/M8_updates.md` rather than made here.

### 4. Versions

v5 §23.1's version column (Spring Boot 3.2, React 18, MUI v5, TypeScript 5, Vite 5, Python 3.11) is
superseded by ADR 0003, which already says that every document naming the older majors refers to the
successor it records, and that the SRS text itself is not edited. Installed: Spring Boot 4.1.1,
Spring Security 7.1.1, Hibernate 7.4, React 19.3, MUI 9.4, TypeScript 6.0.3, Vite 8.3, Python 3.12;
Java 21 and Tailwind 4.3 match v5 as written.

## Consequences

- The architecture requirement is now written down in the project's own vocabulary, so a reviewer
  can check it without holding two package trees in their head.
- Anyone reading v5 §02 and the code will see different package names. This ADR and
  `docs/srs/v5_decisions.md` are the map between them.
- The hybrid persistence rule survives contact with a document that contradicts it, and the reason
  is recorded where the next reader will look.
- M6 remains bound by ADR 0071 Decision 5 (JPA for configuration tables, explicit SQL for the hot
  path and hypertables, every audit record through `AuditLog`). That is a merge item between M6 and
  M7, not a v5 item.
