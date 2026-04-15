#!/usr/bin/env bash
set -euo pipefail

CONTAINER="${KAFKA_CONTAINER:-graphmesh-kafka-1}"
BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
PARTITIONS="${PARTITIONS:-5}"

TOPICS=(
  graphmesh.document.ingested
  graphmesh.query.explained
  graphmesh.page.extracted
  graphmesh.chunk.created
  graphmesh.collection.lifecycle
  graphmesh.config.changed
)

echo "Repartitioning ${#TOPICS[@]} topics to ${PARTITIONS} partitions (container=${CONTAINER}, bootstrap=${BOOTSTRAP})"

for t in "${TOPICS[@]}"; do
  describe=$(docker exec "$CONTAINER" kafka-topics --bootstrap-server "$BOOTSTRAP" \
    --describe --topic "$t" 2>&1 || true)

  if echo "$describe" | grep -q -i "does not exist\|UnknownTopicOrPartition"; then
    echo "  [skip] $t (topic does not exist)"
    continue
  fi

  current=$(echo "$describe" | grep -oE 'PartitionCount:[[:space:]]*[0-9]+' | head -n1 | grep -oE '[0-9]+' || true)

  if [[ -z "$current" ]]; then
    echo "  [skip] $t (could not parse partition count)"
    echo "$describe" | sed 's/^/      /'
    continue
  fi

  if (( current >= PARTITIONS )); then
    echo "  [skip] $t (already has $current partitions)"
    continue
  fi

  echo "  [alter] $t: $current -> $PARTITIONS"
  docker exec "$CONTAINER" kafka-topics --bootstrap-server "$BOOTSTRAP" \
    --alter --topic "$t" --partitions "$PARTITIONS"
done

echo "Done."
