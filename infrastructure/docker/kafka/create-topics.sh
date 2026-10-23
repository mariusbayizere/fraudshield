#!/bin/sh
# Creates every topic of contracts/kafka/topics.yaml and its dead-letter topic on the Compose broker,
# because producers never create topics (auto-create is off; ADR 0012). Idempotent: an existing
# topic is left as it is. Partition counts are the local stack's (3, and 1 for a DLQ), as in
# KafkaTestCluster, not the catalogue's production defaults; replication is 1 on a single broker.
#
# Runs in the apache/kafka image as the one-shot service kafka-topics-init (docker-compose.yml).
set -eu

catalogue=${TOPICS_CATALOGUE:-/fraudshield/topics.yaml}
bootstrap=${KAFKA_BOOTSTRAP:-kafka:29092}
topics=/opt/kafka/bin/kafka-topics.sh

names=$(sed -n 's/^  - name: \([^ ]*\)$/\1/p' "$catalogue")
[ -n "$names" ] || { echo "no topics found in $catalogue" >&2; exit 1; }

expected=0
for name in $names; do
  "$topics" --bootstrap-server "$bootstrap" --create --if-not-exists --topic "$name" \
    --partitions 3 --replication-factor 1 >/dev/null
  "$topics" --bootstrap-server "$bootstrap" --create --if-not-exists --topic "$name.dlq" \
    --partitions 1 --replication-factor 1 >/dev/null
  expected=$((expected + 2))
done

# Every topic must now exist; a creation that failed silently would surface only as a producer
# blocking on metadata inside the API.
existing=$("$topics" --bootstrap-server "$bootstrap" --list)
missing=0
for name in $names; do
  for topic in "$name" "$name.dlq"; do
    if ! printf '%s\n' "$existing" | grep -qx "$topic"; then
      echo "topic $topic is missing after creation" >&2
      missing=1
    fi
  done
done
[ "$missing" = 0 ] || exit 1
echo "kafka topics ready: $expected (from $(basename "$catalogue"))"
