# 0016 — buf for protobuf lint and breaking-change detection

- **Status:** Accepted (repository owner decision, 2026-09-17)
- **Date:** 2026-09-17
- **Requirements affected:** FR-02-01, FR-02-10
- **Defects referenced:** D-16
- **Supersedes:** the custom descriptor checker in ADR 0012 §6 (`fraudshield_contracts.proto_compat`)

## Context

ADR 0012 first enforced protobuf evolution with a custom descriptor-summary checker. The events
re-review (NF-E02) showed it had missed streaming changes and dropped reservations. `buf breaking` is
the maintained tool for exactly this job, and the owner directed a switch to it once it covers the same
mutation cases.

## Decision

1. **Tool.** `tools/bin/buf` launches buf **1.72.0** (Apache-2.0). The launcher is pinned, and it
   verifies the release archive against the SHA-256 in the release's `sha256.txt` and re-verifies the
   extracted binary on every run, like `tools/bin/gitleaks`. 1.73.0 was published six days before
   adoption; 1.72.0 (2026-07-17) is used so the version has aged past a release cooldown, as for
   frontend dependencies.
2. **Configuration** (`contracts/proto/buf.yaml`):
   - Lint uses `STANDARD`. The only change it required was renaming the model-status RPC's request and
     response to `GetModelStatusRequest` and `GetModelStatusResponse`.
   - Breaking-change detection uses `FILE`, the strictest category, with `FIELD_NO_DELETE` and
     `ENUM_VALUE_NO_DELETE` replaced by their `…_UNLESS_NUMBER_RESERVED` and `…_UNLESS_NAME_RESERVED`
     forms. A field or enum value can then be removed only with both its number and name reserved,
     which is ADR 0012's rule.
3. **Against main.** `fs-contract-baselines` runs `buf breaking contracts/proto --against
   '.git#ref=<merge base with origin/main>,subdir=contracts/proto'` in the governance CI job (full
   history) and in `make governance`. Until main contains the proto, it reports "not yet published".
   The committed descriptor baseline is deleted.
4. **Verification.** `contracts/tests/test_proto_breaking.py` runs `buf lint`. It also runs
   `buf breaking` on edited copies and requires the expected finding for each of these cases:
   - fields: renumbered; deleted without reserving, or with only its number reserved; type change;
     rename; label change; oneof member type change; moved into a oneof;
   - enum values: renamed; renumbered; deleted without reserving, or with only the number or only the
     name reserved;
   - declarations: message, enum, service or method deleted; request or response made streaming;
     package changed;
   - reservations: field or enum reservation dropped; reserved field or enum number reused.

   The events final review (F-04) found that removing some configured rules went unnoticed. The
   author then removed each deletion rule, and excluded `FIELD_SAME_ONEOF`, `MESSAGE_NO_DELETE`,
   `ENUM_NO_DELETE` and `SERVICE_NO_DELETE`, one at a time, and confirmed that a test fails each
   time.

   Adding a field, and removing a field or enum value with both reservations, must pass. A reserved
   number cannot be reused within one file, because protoc rejects that, so reuse is caught where the
   reservation is dropped.
5. **Removed:** `fraudshield_contracts.proto_compat`, its tests, `contracts/proto/baseline/`, and the
   direct `protobuf` dependency (grpcio-tools still brings it for proto compilation tests).

## Consequences

- The CI python job and the governance job download buf once per runner (cached per user, checksum
  verified).
- buf's messages, not project code, define the proto check. A weakened configuration is caught by the
  expected-finding assertions.
- The Kafka JSON Schema checker stays custom. By owner decision it is feature-complete, and further
  changes are made only for BLOCKER findings.
