#!/usr/bin/env bash
# Startet Backend (Spring Boot Fat-JAR) und Frontend (Next.js) parallel.
# Strg+C beendet beide Prozesse sauber.
#
# Umgebungsvariablen:
#   SKIP_BUILD=1              Ueberspringt ./gradlew build (nutzt existierendes JAR)
#   SERVER_PORT=8098          Backend-Port (Default: 8098)
#   OTEL_ENABLED=1            Aktiviert OpenTelemetry (braucht opentelemetry-javaagent.jar)
#   OTEL_AGENT_JAR=path.jar   Pfad zum OTel-Agent (Default: ./opentelemetry-javaagent.jar)
#   OTEL_ENDPOINT=https://... OTLP Collector Endpoint
#   OTEL_SERVICE_NAME=graphmesh
#   JAVA_EXTRA_OPTS="..."     Zusaetzliche JVM-Optionen

set -euo pipefail

cd "$(dirname "$0")"

LOG_FILE="logs/graphmesh.log"
JAR_PATH="build/libs/GraphMesh-0.0.1-SNAPSHOT.jar"

# Defaults
SERVER_PORT="${SERVER_PORT:-8083}"
OTEL_ENABLED="${OTEL_ENABLED:-0}"
OTEL_AGENT_JAR="${OTEL_AGENT_JAR:-./opentelemetry-javaagent.jar}"
OTEL_ENDPOINT="${OTEL_ENDPOINT:-https://signoz-otelcollector.operations-dev-internal.k8s.netrtl.com}"
OTEL_SERVICE_NAME="${OTEL_SERVICE_NAME:-graphmesh}"
JAVA_EXTRA_OPTS="${JAVA_EXTRA_OPTS:-}"

truncate_log() {
  mkdir -p "$(dirname "$LOG_FILE")"
  : > "$LOG_FILE"
}

BACKEND_PID=""
FRONTEND_PID=""

cleanup() {
  echo
  echo "Stoppe Prozesse..."
  [[ -n "$FRONTEND_PID" ]] && kill "$FRONTEND_PID" 2>/dev/null || true
  [[ -n "$BACKEND_PID"  ]] && kill "$BACKEND_PID"  2>/dev/null || true
  wait 2>/dev/null || true
  truncate_log
  echo "Log geleert: $LOG_FILE"
  echo "Beendet."
}
trap cleanup INT TERM EXIT

truncate_log
echo "Log geleert: $LOG_FILE"

# ---------- Build ----------
if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  echo "Baue Backend (./gradlew build -x test)..."
  ./gradlew build -x test
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "FEHLER: JAR nicht gefunden: $JAR_PATH" >&2
  exit 1
fi

# ---------- JVM-Args zusammenbauen ----------
JAVA_ARGS=()

if [[ "$OTEL_ENABLED" == "1" ]]; then
  if [[ ! -f "$OTEL_AGENT_JAR" ]]; then
    echo "WARNUNG: OTEL_ENABLED=1 aber Agent-JAR fehlt: $OTEL_AGENT_JAR" >&2
    echo "  Download: https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases" >&2
    echo "  Starte ohne OpenTelemetry..."
  else
    JAVA_ARGS+=(
      "-javaagent:$OTEL_AGENT_JAR"
      "-Dotel.exporter.otlp.endpoint=$OTEL_ENDPOINT"
      "-Dotel.service.name=$OTEL_SERVICE_NAME"
      "-Dotel.traces.exporter=otlp"
      "-Dotel.metrics.exporter=otlp"
      "-Dotel.logs.exporter=otlp"
      "-Dotel.instrumentation.kafka.producer-propagation.enabled=true"
      "-Dotel.instrumentation.kafka.experimental-span-attributes=true"
      "-Dotel.instrumentation.kafka.metric-reporter.enabled=true"
    )
    echo "OpenTelemetry aktiviert (Endpoint: $OTEL_ENDPOINT)"
  fi
fi

JAVA_ARGS+=("-Dserver.port=$SERVER_PORT")

if [[ -n "$JAVA_EXTRA_OPTS" ]]; then
  # shellcheck disable=SC2206
  JAVA_ARGS+=($JAVA_EXTRA_OPTS)
fi

# ---------- Start ----------
echo "Starte Backend (java -jar) auf :$SERVER_PORT..."
java "${JAVA_ARGS[@]}" -jar "$JAR_PATH" &
BACKEND_PID=$!

echo "Starte Frontend (Next.js) auf :3002..."
pnpm -C frontend dev &
FRONTEND_PID=$!

echo
echo "Backend  PID: $BACKEND_PID  -> http://localhost:$SERVER_PORT"
echo "Frontend PID: $FRONTEND_PID  -> http://localhost:3002"
echo "Strg+C zum Beenden."
echo

wait -n "$BACKEND_PID" "$FRONTEND_PID"
