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

## Every MinIO reference in the build prompt, and what supersedes it

Verified with `grep -n -i minio docs/prompts/FraudShield_Master_Build_Prompt.md` (three
occurrences) and a search for related object-storage duties (`S3`, `artifact store`, `upload`).
Amended during the M0 review (owner finding 4).

| Prompt location | Text | Superseded by |
|---|---|---|
| C.1 component table, MLflow row (line 258) | "S3-compatible artifact store (MinIO locally)" | SeaweedFS S3 gateway `object-store:8333`, bucket `mlflow-artifacts`; MLflow runs with `--artifacts-destination s3://mlflow-artifacts --serve-artifacts`. Verified by `make smoke` (MLflow run with an artifact written to and read back from the bucket) |
| D.3 M0 scope (line 347) | compose `core` profile "(… Redis, MinIO, MLflow …)" | `object-store` and `object-store-init` services in the `core` profile |
| G.5 step 4 (line 633) | "Large artifacts go to MinIO/MLflow locally" | Same SeaweedFS store, through MLflow (models, evaluation reports) or directly by S3 API for large generated files |
| E.8 admin, FR-06-04 (no MinIO wording) | "dataset upload (CSV schema validation, size limit, PII pattern scan)" | Uploaded training datasets are stored as S3 objects in a separate bucket (`training-datasets`, created by the migration or init step that introduces the feature in M8), never in PostgreSQL or git; the bucket name is configuration |
| E.3 / M2 dataset generation (no MinIO wording) | generated CSV/Parquet outputs | Local generator output stays on disk under the git-ignored `dataset/output/`; publication artifacts go to HuggingFace/Zenodo (author) — no object store needed at M2 |

No other part of the prompt depends on MinIO-specific APIs (admin console, `mc` client, bucket
notifications); only the S3 API is required.

## Consequences

- `docker-compose.yml` service `object-store` runs SeaweedFS with an S3 endpoint on 8333.
- Apache-2.0 licence satisfies F.3.
- If SeaweedFS S3 behaviour diverges from AWS S3 for MLflow's usage, the M4/M5 integration
  tests will expose it; the fallback is option 3.
