# PB-67 — the 240K learning-curve point, run in a GitHub Codespace

The laptop cannot hold the 240K cache (lab notebook, 2026-09-22). This runs the pre-registered
design (`d071147`) as written: a 520,000-row corpus, 240,000 training rows, the same 20,000
validation, 20,000 calibration and 101,909 test rows as the declared gate run, five seeds, 1,000
bootstrap resamples. Accuracy results are admissible from any machine (ADR 0010 restricts only
latency), and every artefact names its machine in `# evidence-machine:`.

## Machine

A Codespace on this repository with **4 cores and 16 GB** (the devcontainer's own
`hostRequirements`). Check out **the commit named in the owner's instructions** (the latest commit
that changed this runbook). Its code differs from `c59da11`, where the laptop's three points ran,
only in how the access log names a learning-curve run.

## Expected runtime

About 1–1.5 hours after the container is up. The steps are:
- **generate the 1M draw:** about 24 minutes on the laptop;
- **the cache build:** a 520,000-row corpus plus about 382,000 featurised rows at 270–450 rows
  a second, 20–35 minutes;
- **the gate:** five fits at 240,000 rows, plus the bootstrap, 10–25 minutes.

Peak memory should stay under 3 GB.

## Commands

Run from the repository root. Each step stops on its first failure.

```bash
set -euo pipefail
git fetch origin && git checkout --detach <the commit named in the owner's instructions>
uv sync --all-packages --locked

# 1. Regenerate the draw and prove it is d8083dbc. Not an evidence step: the draw already has one
#    (docs/benchmarks/m4_generate_pb61.txt); this only has to reproduce it.
uv run fs-dataset generate --output dataset/output/bench1m --seed 20260917 --rows 1000000
uv run python -c "
from pathlib import Path
from fraudshield_dataset.fingerprint import dataset_fingerprint
got = dataset_fingerprint(Path('dataset/output/bench1m'))
want = 'd8083dbc742c20437bf3d060614f88849059eb8cf12bd0d3bbb092b518e6be32'
assert got == want, f'fingerprint {got}, expected {want}: stop, this is not the benchmark'
print('fingerprint matches d8083dbc')"
uv run fs-dataset split --output dataset/output/split.json dataset/output/bench1m
uv run fs-dataset packs --output dataset/output/packs.json
uv run python -c "
import json
b = json.load(open('dataset/output/split.json'))['boundaries_micros']
assert b['test_start'] == 1761688920337485 and b['validation_start'] == 1756099490732196, b
print('split boundaries match')"

# 2. The cache: no model fitted, no test row scored.
uv run fs-evidence --output docs/benchmarks/m4_learning_curve_cache_240k_d8083dbc.txt -- \
  .venv/bin/python -u -m fraudshield_ml.cli evaluate dataset/output/bench1m \
  --packs dataset/output/packs.json --split dataset/output/split.json \
  --corpus-rows 520000 --train-rows 240000 --validation-rows 20000 \
  --calibration-rows 20000 --test-rows 101909 \
  --cache dataset/output/features_lc240k.parquet --cache-only
git add docs/benchmarks/m4_learning_curve_cache_240k_d8083dbc.txt
git commit -m "data(ml): the PB-67 240K learning-curve cache, built in a Codespace"

# 3. The gate at 240K. Logged in the test-set access log.
uv run fs-evidence --output docs/benchmarks/m4_learning_curve_240k_d8083dbc.txt -- \
  .venv/bin/python -u -m fraudshield_ml.cli gate dataset/output/features_lc240k.parquet \
  --out docs/benchmarks/m4_learning_curve_240k_d8083dbc --train-rows 240000 \
  --seeds 1 2 3 4 5 --resamples 1000 --metrics-only
git add docs/benchmarks/m4_learning_curve_240k_d8083dbc.txt \
  docs/benchmarks/m4_learning_curve_240k_d8083dbc docs/benchmarks/test_set_access.jsonl
git commit -m "data(ml): the PB-67 learning curve at 240K training rows, in a Codespace"

# 4. Send it back on its own branch; the author folds it into m4/generalisation.
git push origin HEAD:refs/heads/pb67-240k
```

`fs-evidence` refuses on a dirty tree, so step 2's artefact is committed before step 3 starts, as
above. `dataset/output/` is gitignored and stays in the Codespace.

## What to check in the result

- Both artefact headers say `# evidence-tree-state: clean`. The `# evidence-machine:` line names
  the Codespace's CPU and about 16 GiB.
- The cache build ends with `cache written; no model fitted and no test row scored`.
- The gate's `rows/fraud` line reads `train 240,000/…` and `test 101,909/985`. That is the same
  evaluation set as the laptop's points and the declared gate run.
