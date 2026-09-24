# Kafka schema baseline

Copies of the payload and envelope schemas at `schema_version` 1 (ADR 0012). Contract tests check that
the schemas in `../schemas` still accept every message these baselines accept
(`fraudshield_contracts.compatibility`). Editing a baseline does not help a breaking change: the
governance job (`fs-contract-baselines`) checks against the baselines as published on `main`. A breaking
change needs a new topic version with its own schema file (ADR 0012). Update a baseline only to record a
change the checks have accepted.
