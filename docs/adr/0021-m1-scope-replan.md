# 0021 — Re-plan of the M1 rows that later milestones complete

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** D-20, NFR-SEC-03, D-32, D-49 (milestone assignment only)
- **Source:** M1 milestone review, finding MAJOR-1 (`docs/reviews/M1/milestone-review.md`)

## Context

The traceability seed (`tools/src/fraudshield_tools/traceability_seed.py`) assigned D-20, NFR-SEC-03,
D-32 and D-49 to M1. The M1 gate and scope list in build prompt Part D.3 name none of the parts
still open:

- **D-20:** PII vault schema, its own role, AES-256-GCM envelope encryption, KMS, encrypted volumes,
  Redis TLS.
- **NFR-SEC-03:** the tokenisation service and the inspector test on analyst endpoints.
- **D-32:** the daily signed Merkle-root job and the `fraudshield audit verify` CLI.
- **D-49:** self-hosting on Kubernetes.

These parts need components that do not exist in M1: the ingestion service, staff APIs, the audit
service and the deployment manifests. Closing M1 with the rows silently re-dated would be an
unrecorded gap (D.3). Leaving them on M1 would fail `traceability-check` once M1 is marked
completed.

## Decision

The rows move to the milestone that builds the component completing them. The M1 part delivered
so far is recorded in each row's notes and evidence.

| Row | Delivered in M1 | Remaining | New milestone |
|---|---|---|---|
| D-20 | Separate `pii-vault` PostgreSQL instance in Compose (M0); no customer PII column in the main schema | Vault schema with a dedicated role, no grant for `fs_app`, `fs_app_readonly` or `fs_compliance_ro`; AES-256-GCM envelope encryption behind a key-provider interface, with tests. Then KMS-managed keys, encrypted volumes, Redis TLS and network policy (M9) | **M6** (first consumers: tokenisation at ingestion, customer notifications); deployment controls M9 |
| NFR-SEC-03 | Contracts accept tokens only and reject raw PII fields (contract tests); every customer identifier column is `CHECK (is_token(...))` (`SchemaPoliciesTest`) | PII store and tokenisation at ingestion (M6); inspector test showing masked tokens on every analyst endpoint (M7) | **M7** |
| D-32 | 12 event types; database-assigned hash chain per writer partition; `verify_audit_chain`; `audit_anchors`; compliance role; compression 30 days, retention 7 years by `recorded_at` (tested) | Daily signed Merkle-root job; `fraudshield audit verify --from --to` CLI | **M7** (audit service) |
| D-49 | ADR 0018 (Timescale License analysis and features used); image pinned; hypertables, compression, retention and continuous aggregates exercised by tests | Self-hosted PostgreSQL + TimescaleDB on Kubernetes (for example CloudNativePG) | **M9** |

The seed's milestone table cites this ADR, so `fs-traceability-seed --check` keeps the assignment
reproducible. The threat model lists the same milestones.

## Consequences

- M1 closes with every remaining M1 Must row in a final status. The four moved rows are checked
  again at the gates of M6, M7 and M9.
- Until M6, no data flow may carry raw customer PII. The token CHECK constraints and the contract
  schemas enforce that at the database and API boundaries.
