# 0005 — SeaweedFS instead of MinIO as the local S3-compatible store

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** RES-03 (model weights via MLflow registry), OPS-CI-05
- **Defects referenced:** D-51

## Context

Build prompt C.1 and M0 name MinIO as the local S3-compatible artifact store behind MLflow.
On 2026-09-17 the `minio/minio` GitHub repository is archived (last push 2026-04-24) and
Docker Hub returns no tags for `minio/minio` or `bitnami/minio`. An archived storage
server receives no security fixes.

## Options considered

1. **MinIO from an old cached image** — unmaintained, AGPL-3.0, cannot be reproduced on a
   clean machine if the image is gone.
2. **SeaweedFS** (`chrislusf/seaweedfs:4.47`, released 2026-09-14, Apache-2.0), which
   provides an S3 gateway (`weed server -s3`).
3. **RustFS** (1.0.0 released 2026-09-16) — newly 1.0, smaller operational track record.
4. **Local filesystem artifact store in MLflow** — no S3 semantics, diverges from
   production object storage.

## Decision

Option 2. The S3 API is the contract; application code uses only the S3 API via
configuration (`MLFLOW_S3_ENDPOINT_URL`, bucket names), so the store is replaceable by any
S3-compatible production service.

## Consequences

- `docker-compose.yml` service `object-store` runs SeaweedFS with an S3 endpoint on 8333.
- Apache-2.0 licence satisfies F.3.
- If SeaweedFS S3 behaviour diverges from AWS S3 for MLflow's usage, the M4/M5 integration
  tests will expose it; the fallback is option 3.
