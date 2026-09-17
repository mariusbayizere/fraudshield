# Kafka schema baseline

Copies of the published payload and envelope schemas at `schema_version` 1 (ADR 0012). Contract tests
check that every schema in `../schemas` still accepts every message these baselines accept
(`fraudshield_contracts.compatibility`). Do not edit a baseline to make a breaking change pass: add a
new schema version instead, keep the old one until producers have migrated, and baseline the new file
when it is published. Update a baseline only to record a change the check has accepted.
