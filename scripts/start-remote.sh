#!/usr/bin/env bash
# Startet das GraphMesh-Backend lokal gegen einen entfernten k3s-Cluster.
#
# Voraussetzung:
#   - graphmesh-infra Helm-Release laeuft im Cluster
#   - Traefik TCP-EntryPoints fuer Cassandra (9042) und Kafka (9092) konfiguriert
#   - IngressRouteTCP-Ressourcen deployed (values-dev.yaml)
#   - Ollama laeuft lokal (oder OLLAMA_BASE_URL anpassen)
#
# Aufruf:
#   ./scripts/start-remote.sh                         # mit Default-IP
#   K8S_NODE_IP=10.0.0.5 ./scripts/start-remote.sh   # eigene Node-IP

set -euo pipefail

# ---------- Konfiguration ----------
# IP des k3s-Nodes. Anpassen, wenn sich die Node-IP aendert.
# Muss mit kafka.externalAccess.controller.service.domain in values-dev.yaml uebereinstimmen.
K8S_NODE_IP="${K8S_NODE_IP:-192.168.178.175}"

# Ports — entsprechen den Traefik-EntryPoints / NodePorts aus values-dev.yaml
KAFKA_PORT="${KAFKA_PORT:-9092}"
SCHEMA_REGISTRY_PORT="${SCHEMA_REGISTRY_PORT:-30181}"
CASSANDRA_PORT="${CASSANDRA_PORT:-9042}"
MINIO_PORT="${MINIO_PORT:-30900}"
QDRANT_GRPC_PORT="${QDRANT_GRPC_PORT:-30634}"

# Ollama laeuft in der Regel lokal
OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-http://localhost:11434}"

# ---------- Env-Variablen fuer application.yml ----------
export KAFKA_BOOTSTRAP_SERVERS="${K8S_NODE_IP}:${KAFKA_PORT}"
export SCHEMA_REGISTRY_URL="http://${K8S_NODE_IP}:${SCHEMA_REGISTRY_PORT}"
export CASSANDRA_CONTACT_POINTS="${K8S_NODE_IP}"
export CASSANDRA_PORT="${CASSANDRA_PORT}"
export MINIO_ENDPOINT="http://${K8S_NODE_IP}:${MINIO_PORT}"
export QDRANT_HOST="${K8S_NODE_IP}"
export QDRANT_GRPC_PORT="${QDRANT_GRPC_PORT}"
export OLLAMA_BASE_URL="${OLLAMA_BASE_URL}"

# ---------- Ausgabe ----------
echo "=== GraphMesh Remote Start ==="
echo ""
echo "  Node:             ${K8S_NODE_IP}"
echo "  Kafka:            ${KAFKA_BOOTSTRAP_SERVERS}"
echo "  Schema Registry:  ${SCHEMA_REGISTRY_URL}"
echo "  Cassandra:        ${CASSANDRA_CONTACT_POINTS}:${CASSANDRA_PORT}"
echo "  MinIO:            ${MINIO_ENDPOINT}"
echo "  Qdrant gRPC:      ${QDRANT_HOST}:${QDRANT_GRPC_PORT}"
echo "  Ollama:           ${OLLAMA_BASE_URL}"
echo ""

# ---------- Start ----------
exec ./gradlew bootRun
