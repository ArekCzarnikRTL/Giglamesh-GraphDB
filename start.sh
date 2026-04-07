#!/usr/bin/env bash
# Startet Backend (Spring Boot) und Frontend (Next.js) parallel.
# Strg+C beendet beide Prozesse sauber.

set -euo pipefail

cd "$(dirname "$0")"

LOG_FILE="logs/graphmesh.log"

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

echo "Starte Backend (Spring Boot) auf :8080..."
./gradlew bootRun &
BACKEND_PID=$!

echo "Starte Frontend (Next.js) auf :3000..."
pnpm -C frontend dev &
FRONTEND_PID=$!

echo
echo "Backend  PID: $BACKEND_PID  -> http://localhost:8080"
echo "Frontend PID: $FRONTEND_PID  -> http://localhost:3000"
echo "Strg+C zum Beenden."
echo

wait -n "$BACKEND_PID" "$FRONTEND_PID"
