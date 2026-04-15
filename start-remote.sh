#!/usr/bin/env bash
# Startet Backend + Frontend gegen die Remote-k3s-Infrastruktur.
#
# Annahmen:
#   - deploy/helm/graphmesh-infra ist mit values-dev.yaml installiert
#   - Auf diesem Rechner zeigen die <service>.graphmesh.local-Eintraege
#     im /etc/hosts auf die k3s-Node-IP (siehe deploy/helm/graphmesh-infra/README.md)
#   - kubectl/Helm nicht noetig zum Starten; nur Netzwerk-Erreichbarkeit
#
# Umgebungsvariablen (alle optional):
#   K8S_NODE_IP=192.168.178.175     IP des k3s-Nodes (fuer TCP-NodePorts)
#   HOST_SUFFIX=graphmesh.local     Domain-Suffix der Ingress-Hosts
#   SERVER_PORT=8083                Backend-Port
#   SKIP_PREFLIGHT=1                Ueberspringt Konnektivitaetscheck
#   Alle Variablen aus start.sh (SKIP_BUILD, OTEL_ENABLED, ...)
#
# Exit-Codes:
#   2 - Preflight fehlgeschlagen (Service nicht erreichbar)

set -euo pipefail

cd "$(dirname "$0")"

K8S_NODE_IP="${K8S_NODE_IP:-192.168.178.175}"
HOST_SUFFIX="${HOST_SUFFIX:-graphmesh.local}"

# ---------- Ports (passend zu deploy/helm/graphmesh-infra/values-dev.yaml) ----------
KAFKA_NODEPORT="${KAFKA_NODEPORT:-30092}"
CASSANDRA_NODEPORT="${CASSANDRA_NODEPORT:-30942}"
QDRANT_GRPC_NODEPORT="${QDRANT_GRPC_NODEPORT:-30634}"

# ---------- Host/Endpoint-Mapping ----------
# HTTP-Services laufen via Traefik-Ingress auf Port 80 (Host-Header entscheidet).
# TCP-Services (Kafka/Cassandra/Qdrant-gRPC) gehen direkt auf Node-IP + NodePort.
export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-${K8S_NODE_IP}:${KAFKA_NODEPORT}}"
export SCHEMA_REGISTRY_URL="${SCHEMA_REGISTRY_URL:-http://schema-registry.${HOST_SUFFIX}}"
export CASSANDRA_CONTACT_POINTS="${CASSANDRA_CONTACT_POINTS:-${K8S_NODE_IP}}"
export CASSANDRA_PORT="${CASSANDRA_PORT:-${CASSANDRA_NODEPORT}}"
export MINIO_ENDPOINT="${MINIO_ENDPOINT:-http://minio.${HOST_SUFFIX}}"
export MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-minioadmin}"
export MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-minioadmin}"
export QDRANT_HOST="${QDRANT_HOST:-${K8S_NODE_IP}}"
export QDRANT_GRPC_PORT="${QDRANT_GRPC_PORT:-${QDRANT_GRPC_NODEPORT}}"

# ---------- Preflight ----------
tcp_check() {
  local host=$1 port=$2 name=$3
  if command -v nc >/dev/null 2>&1; then
    nc -z -w 3 "$host" "$port" 2>/dev/null
  else
    (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null
  fi
  # shellcheck disable=SC2181
  if [[ $? -ne 0 ]]; then
    echo "  FEHLER: $name nicht erreichbar (${host}:${port})" >&2
    return 1
  fi
  echo "  OK:     $name (${host}:${port})"
}

http_check() {
  local url=$1 name=$2
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$url" || echo "000")
  if [[ "$code" == "000" ]]; then
    echo "  FEHLER: $name nicht erreichbar ($url)" >&2
    return 1
  fi
  echo "  OK:     $name ($url -> HTTP $code)"
}

if [[ "${SKIP_PREFLIGHT:-0}" != "1" ]]; then
  echo "Preflight gegen k3s (${K8S_NODE_IP}, *.${HOST_SUFFIX}):"
  fail=0
  tcp_check  "$K8S_NODE_IP"                       "$KAFKA_NODEPORT"       "Kafka"            || fail=1
  tcp_check  "$K8S_NODE_IP"                       "$CASSANDRA_NODEPORT"   "Cassandra"        || fail=1
  tcp_check  "$K8S_NODE_IP"                       "$QDRANT_GRPC_NODEPORT" "Qdrant gRPC"      || fail=1
  http_check "$SCHEMA_REGISTRY_URL/subjects"                              "Schema Registry"  || fail=1
  http_check "$MINIO_ENDPOINT/minio/health/live"                          "MinIO"            || fail=1
  http_check "http://qdrant.${HOST_SUFFIX}/healthz"                       "Qdrant HTTP"      || fail=1

  if [[ "$fail" -ne 0 ]]; then
    echo
    echo "Preflight fehlgeschlagen. Checks:" >&2
    echo "  - Stehen die /etc/hosts-Eintraege? (schema-registry/minio/qdrant.${HOST_SUFFIX} -> ${K8S_NODE_IP})" >&2
    echo "  - Laufen alle Pods? kubectl get pods" >&2
    echo "  - Richtige Node-IP? Setze K8S_NODE_IP=<ip>" >&2
    echo "  - Check abschalten: SKIP_PREFLIGHT=1 $0" >&2
    exit 2
  fi
  echo
fi

# ---------- Hinweis Kafka ----------
# Die Bitnami-Kafka-Chart-Default-Config veroeffentlicht im advertised.listener
# den cluster-internen DNS-Namen. Ein Remote-Client kriegt damit beim Metadata-
# Call einen Host, den er nicht aufloesen kann. Wenn Kafka-Consumer/Producer
# im Backend beim Connect haengen, muss in values-dev.yaml
# externalAccess.enabled=true mit NodePort + advertised=node-IP gesetzt werden
# (siehe deploy/helm/graphmesh-infra/README.md).
if [[ "${KAFKA_WARN:-1}" == "1" ]]; then
  echo "Hinweis: Kafka via NodePort funktioniert nur, wenn der Broker seinen"
  echo "externen Listener als ${K8S_NODE_IP}:${KAFKA_NODEPORT} advertised."
  echo "Falls Producer/Consumer haengen: externalAccess in values-dev.yaml aktivieren."
  echo
fi

echo "Backend-Config gegen k3s:"
echo "  KAFKA_BOOTSTRAP_SERVERS  = $KAFKA_BOOTSTRAP_SERVERS"
echo "  SCHEMA_REGISTRY_URL      = $SCHEMA_REGISTRY_URL"
echo "  CASSANDRA_CONTACT_POINTS = $CASSANDRA_CONTACT_POINTS:$CASSANDRA_PORT"
echo "  MINIO_ENDPOINT           = $MINIO_ENDPOINT"
echo "  QDRANT                   = $QDRANT_HOST:$QDRANT_GRPC_PORT (gRPC)"
echo

# ---------- Delegation an start.sh ----------
exec ./start.sh "$@"
