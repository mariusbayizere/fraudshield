# 0030 — M4's evidence is stamped by fs-evidence; MLflow tracking arrives with M5's registry

- **Status:** Accepted, pending owner confirmation
- **Date:** 2026-09-22
- **Decided by:** author, resolving M4 review finding M4-9; the owner may overrule
- **Requirements affected:** E.4 (tracking), I.2 ("numbers reproduced from the MLflow run ID")

## Context

E.4 asks `train.py` to log params, metrics, data hashes, the git SHA, the environment lock and the
hardware to MLflow, and I.2 asks a reviewer to reproduce numbers from an MLflow run ID. M4 has no
MLflow logging (M4-9).

What M4 does have covers most of E.4's list by other means:

| E.4 asks for | Where M4 records it |
|---|---|
| git SHA | `fs-evidence` stamps the commit **and** the working-tree state, taken from git rather than typed (PB-52, PB-53) |
| data hash | the dataset fingerprint over its rows (`d8083dbc…`), in the realism report and every artefact name |
| params and metrics | `metrics.json`, committed beside the evidence, and the evidence text itself |
| environment lock | `uv.lock` at the stamped commit; `metrics.json` repeats the versions that decide the numbers |
| hardware | `metrics.json`'s `environment` block: platform, machine, CPU count |

## Decision

1. **M4's gate evidence is the `fs-evidence` artefact plus the committed `metrics.json`,** not an
   MLflow run. A reviewer reproduces a number by checking out the stamped commit and re-running the
   stamped command, which is what the M4 review did for the battery.
2. **MLflow arrives in M5, with the model registry.** M5's hot-swap polls an MLflow model alias
   (≤ 10 s), so M5 needs the MLflow client and a server regardless. Tracking `train` and `gate`
   runs belongs with it, and the run ID is then added beside the `fs-evidence` stamp rather than
   replacing it.

## Why not now

- The MLflow client is a large new dependency tree, and every package in it goes through
  ADR 0009's licence audit. Taking it on for tracking alone, one milestone before the registry
  forces it, would do that work twice.
- A tracking store has to live somewhere. The local compose stack's MLflow service is shared
  infrastructure other sessions use, and a file store under `mlruns/` is gitignored (G.5), so a run
  ID from it would be reproducible on one machine only — weaker than the committed evidence.

## Consequences

- Until M5, "reproduce from the MLflow run ID" reads "reproduce from the stamped commit and
  command". The substitution is visible in every artefact header.
- M5's plan gains an item: log `train` and `gate` to MLflow and record the run ID beside the
  evidence stamp.
