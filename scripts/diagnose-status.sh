#!/usr/bin/env bash
# Diagnose: Prueft ob der Document-Status nach Upload von UPLOADED weiter wandert.
# Voraussetzung: Backend auf :8083, Infra (Cassandra, Qdrant, Kafka, MinIO) laeuft.
#
# Aufruf:  ./scripts/diagnose-status.sh

set -uo pipefail

BACKEND_URL="${BACKEND_URL:-http://localhost:8083}"
GRAPHQL_URL="$BACKEND_URL/graphql"
POLL_INTERVAL=3
POLL_MAX=20   # max 60s

GREEN=$'\033[0;32m'
RED=$'\033[0;31m'
YELLOW=$'\033[0;33m'
CYAN=$'\033[0;36m'
RESET=$'\033[0m'

gql() {
  local query="$1" vars="$2"
  local body
  body=$(jq -cn --arg q "$query" --argjson v "$vars" '{query: $q, variables: $v}')
  curl -sS -X POST "$GRAPHQL_URL" \
    -H "Content-Type: application/json" \
    -d "$body"
}

cleanup() {
  if [[ -n "${DOCUMENT_ID:-}" ]]; then
    echo ""
#    echo "${YELLOW}Cleanup: loesche Dokument $DOCUMENT_ID${RESET}"
#    gql 'mutation($id: ID!) { deleteDocument(id: $id) }' \
#      "$(jq -cn --arg id "$DOCUMENT_ID" '{id: $id}')" >/dev/null 2>&1
  fi
  if [[ -n "${COLLECTION_ID:-}" ]]; then
    echo ""
#    echo "${YELLOW}Cleanup: loesche Collection $COLLECTION_ID${RESET}"
#    gql 'mutation($id: ID!) { deleteCollection(id: $id) }' \
#      "$(jq -cn --arg id "$COLLECTION_ID" '{id: $id}')" >/dev/null 2>&1
  fi
}
trap cleanup EXIT

# ---------- 0. Health-Check ----------
echo "${CYAN}=== Document-Status Diagnose ===${RESET}"
echo
HEALTH=$(curl -sS -X POST "$GRAPHQL_URL" \
  -H "Content-Type: application/json" \
  -d '{"query":"{ __typename }"}' 2>/dev/null || echo "")
if ! echo "$HEALTH" | jq -e '.data.__typename' >/dev/null 2>&1; then
  echo "${RED}Backend nicht erreichbar auf $BACKEND_URL${RESET}"
  exit 1
fi
echo "${GREEN}Backend laeuft auf $BACKEND_URL${RESET}"

# ---------- 1. Collection erstellen ----------
COLL_NAME="diag-status-$(date +%s)"
echo
echo "${YELLOW}1. Collection erstellen: $COLL_NAME${RESET}"
RESP=$(gql 'mutation($input: CreateCollectionInput!) {
  createCollection(input: $input) { id name }
}' "$(jq -cn --arg n "$COLL_NAME" '{input: {name: $n, description: "status diagnose", tags: ["diag"]}}')")
COLLECTION_ID=$(echo "$RESP" | jq -r '.data.createCollection.id // empty')
if [[ -z "$COLLECTION_ID" ]]; then
  echo "${RED}Collection konnte nicht erstellt werden: $(echo "$RESP" | jq -c '.errors // .')${RESET}"
  exit 1
fi
echo "   Collection ID: $COLLECTION_ID"

# ---------- 2. Markdown-Dokument hochladen ----------
echo
echo "${YELLOW}2. Markdown-Dokument hochladen (text/markdown)${RESET}"
TEST_CONTENT="# Diagnose-Dokument

Dies ist ein Testdokument fuer die Status-Diagnose.
GraphMesh extrahiert Wissen aus Dokumenten und baut einen Knowledge Graph auf.
Die Plattform nutzt LLMs fuer die Extraktion von RDF-Triples."
B64_CONTENT=$(echo "$TEST_CONTENT" | base64 | tr -d '\n')

RESP=$(gql 'mutation($input: UploadDocumentInput!) {
  uploadDocument(input: $input) { id title state mimeType }
}' "$(jq -cn --arg cid "$COLLECTION_ID" --arg c "$B64_CONTENT" \
  '{input: {collectionId: $cid, title: "diag-test.md", mimeType: "text/markdown", content: $c}}')")
DOCUMENT_ID=$(echo "$RESP" | jq -r '.data.uploadDocument.id // empty')
INIT_STATE=$(echo "$RESP" | jq -r '.data.uploadDocument.state // empty')

if [[ -z "$DOCUMENT_ID" ]]; then
  echo "${RED}Upload fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')${RESET}"
  exit 1
fi
echo "   Document ID: $DOCUMENT_ID"
echo "   Initial State: $INIT_STATE"
echo "   MimeType: $(echo "$RESP" | jq -r '.data.uploadDocument.mimeType // "?"')"

# ---------- 3. Status pollen ----------
echo
echo "${YELLOW}3. Pollt Document-State alle ${POLL_INTERVAL}s (max $((POLL_MAX * POLL_INTERVAL))s)${RESET}"
echo

PREV_STATE="$INIT_STATE"
TRANSITIONS=("$INIT_STATE")
FINAL_STATE=""

for i in $(seq 1 $POLL_MAX); do
  sleep "$POLL_INTERVAL"
  RESP=$(gql 'query($id: ID!) {
    document(id: $id) { state }
  }' "$(jq -cn --arg id "$DOCUMENT_ID" '{id: $id}')")
  STATE=$(echo "$RESP" | jq -r '.data.document.state // empty')

  if [[ -z "$STATE" ]]; then
    echo "   ${RED}[$((i * POLL_INTERVAL))s] Fehler beim Abfragen: $(echo "$RESP" | jq -c '.errors // .')${RESET}"
    continue
  fi

  if [[ "$STATE" != "$PREV_STATE" ]]; then
    echo "   ${GREEN}[$((i * POLL_INTERVAL))s] Status-Wechsel: $PREV_STATE -> $STATE${RESET}"
    TRANSITIONS+=("$STATE")
    PREV_STATE="$STATE"
  else
    echo "   [$((i * POLL_INTERVAL))s] state=$STATE (unveraendert)"
  fi

  if [[ "$STATE" == "EXTRACTED" || "$STATE" == "FAILED" ]]; then
    FINAL_STATE="$STATE"
    break
  fi
done

if [[ -z "$FINAL_STATE" ]]; then
  FINAL_STATE="$PREV_STATE"
fi

# ---------- 4. Error-Log pruefen ----------
echo
echo "${YELLOW}4. Pruefe Error-Log${RESET}"
ERROR_LOG="logs/graphmesh-error.log"
if [[ -f "$ERROR_LOG" ]]; then
  RECENT_ERRORS=$(tail -50 "$ERROR_LOG" | grep -i -E "(decoder|document|ingested|kafka|consumer)" | tail -10)
  if [[ -n "$RECENT_ERRORS" ]]; then
    echo "   ${RED}Relevante Fehler im Error-Log:${RESET}"
    echo "$RECENT_ERRORS" | while IFS= read -r line; do
      echo "   $line"
    done
  else
    echo "   Keine relevanten Fehler im Error-Log"
  fi
else
  echo "   ${YELLOW}Kein Error-Log gefunden ($ERROR_LOG)${RESET}"
fi

# ---------- 5. Ergebnis ----------
echo
echo "${CYAN}=== Ergebnis ===${RESET}"
echo
echo "   Transitions: $(IFS=' -> '; echo "${TRANSITIONS[*]}")"
echo "   Final State:  $FINAL_STATE"
echo

if [[ "$FINAL_STATE" == "EXTRACTED" ]]; then
  echo "${GREEN}   OK: Status hat sich korrekt von UPLOADED zu EXTRACTED bewegt.${RESET}"
elif [[ "$FINAL_STATE" == "PROCESSING" ]]; then
  echo "${YELLOW}   HAENGT: Status blieb bei PROCESSING. Decoder laeuft, aber kommt nicht durch.${RESET}"
  echo "   -> Pruefe Chunker/Decoder-Logs auf Fehler."
elif [[ "$FINAL_STATE" == "UPLOADED" ]]; then
  echo "${RED}   BUG: Status blieb bei UPLOADED. Kafka-Consumer hat das Event nicht verarbeitet.${RESET}"
  echo
  echo "   Moegliche Ursachen:"
  echo "   1. Kafka laeuft nicht oder Consumer hat Verbindungsproblem"
  echo "   2. Schema-Registry nicht erreichbar (localhost:8181)"
  echo "   3. Avro-Deserialisierung schlaegt fehl"
  echo "   4. Consumer-Gruppe hat altes Offset und auto-offset-reset greift nicht"
  echo
  echo "   Naechste Schritte:"
  echo "   - Backend-Konsole auf Kafka-Consumer-Fehler pruefen"
  echo "   - Kafka-UI auf http://localhost:8080 -> Topics -> graphmesh.document.ingested pruefen"
  echo "   - Pruefe ob Consumer-Gruppen registriert sind:"
  echo "     Kafka-UI -> Consumer Groups -> graphmesh-pdf-decoder / graphmesh-text-decoder"
elif [[ "$FINAL_STATE" == "FAILED" ]]; then
  echo "${RED}   FAILED: Decoder ist gestartet, aber fehlgeschlagen.${RESET}"
  echo "   -> Pruefe Error-Log und Backend-Konsole auf Details."
fi
echo
