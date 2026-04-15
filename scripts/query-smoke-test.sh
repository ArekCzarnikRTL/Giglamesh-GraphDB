#!/usr/bin/env bash
# Query-Smoke-Test fuer GraphMesh.
# Feuert NLP- und GraphRAG-Queries gegen eine bestehende Collection.
#
# Voraussetzung: Backend laeuft, Collection existiert bereits mit Daten.
#
# Aufruf:
#   COLLECTION_ID=<uuid> ./query-smoke-test.sh
#   BACKEND_URL=http://localhost:8083 COLLECTION_ID=<uuid> ./query-smoke-test.sh
#
# Ohne COLLECTION_ID wird die erste verfuegbare Collection genutzt.

set -uo pipefail

BACKEND_URL="${BACKEND_URL:-http://localhost:8083}"
GRAPHQL_URL="$BACKEND_URL/graphql"
COLLECTION_ID="${COLLECTION_ID:-}"

PASS=0
FAIL=0

# ---------- Helpers ----------
GREEN=$'\033[0;32m'
RED=$'\033[0;31m'
YELLOW=$'\033[0;33m'
BLUE=$'\033[0;34m'
RESET=$'\033[0m'

step() {
  echo
  echo "${YELLOW}▶ $*${RESET}"
}

ok() {
  echo "${GREEN}  ✓ $*${RESET}"
  PASS=$((PASS + 1))
}

fail() {
  echo "${RED}  ✗ $*${RESET}"
  FAIL=$((FAIL + 1))
}

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "${RED}Fehlende Abhaengigkeit: $1${RESET}"
    exit 2
  }
}

gql() {
  local query="$1"
  local vars="$2"
  local body
  body=$(jq -cn --arg q "$query" --argjson v "$vars" '{query: $q, variables: $v}')
  curl -sS -X POST "$GRAPHQL_URL" \
    -H "Content-Type: application/json" \
    -d "$body"
}

# Fuehrt eine NLP-Query aus und zeigt Antwort + Intent + Dauer.
run_nlp() {
  local label="$1"
  local question="$2"
  step "NLP: $label"
  echo "  ${BLUE}Frage:${RESET} $question"
  local start_ns
  start_ns=$(date +%s)
  local resp
  resp=$(gql 'query($input: NlpQueryInput!) {
    nlpQuery(input: $input) {
      answer
      detectedIntent { intent confidence reasoning }
      durationMs
      sources
    }
  }' "$(jq -cn --arg cid "$COLLECTION_ID" --arg q "$question" \
    '{input: {question: $q, collectionId: $cid}}')")
  local end_ns
  end_ns=$(date +%s)
  local elapsed=$((end_ns - start_ns))

  local answer intent confidence duration
  answer=$(echo "$resp" | jq -r '.data.nlpQuery.answer // empty')
  intent=$(echo "$resp" | jq -r '.data.nlpQuery.detectedIntent.intent // empty')
  confidence=$(echo "$resp" | jq -r '.data.nlpQuery.detectedIntent.confidence // empty')
  duration=$(echo "$resp" | jq '.data.nlpQuery.durationMs // 0')

  if [[ -n "$answer" ]]; then
    ok "Antwort erhalten (intent=$intent conf=$confidence backend=${duration}ms wall=${elapsed}s)"
    echo "  ${BLUE}Antwort:${RESET} ${answer:0:400}"
    local src_count
    src_count=$(echo "$resp" | jq '.data.nlpQuery.sources | length // 0')
    if [[ "$src_count" -gt 0 ]]; then
      echo "  ${BLUE}Quellen ($src_count):${RESET}"
      echo "$resp" | jq -r '.data.nlpQuery.sources[:5][] | "    - \(.)"'
    fi
  else
    fail "nlpQuery fehlgeschlagen: $(echo "$resp" | jq -c '.errors // .')"
  fi
}

# Fuehrt eine GraphRAG-Query aus (direkt, ohne Intent-Detection).
run_graphrag() {
  local label="$1"
  local question="$2"
  local max_depth="${3:-2}"
  step "GraphRAG: $label"
  echo "  ${BLUE}Frage:${RESET} $question"
  local start_ns
  start_ns=$(date +%s)
  local resp
  resp=$(gql 'query($input: GraphRagInput!) {
    graphRag(input: $input) {
      answer
      selectedEdges { subject predicate objectValue relevanceScore reasoning }
      retrievedEdgeCount
      durationMs
    }
  }' "$(jq -cn --arg cid "$COLLECTION_ID" --arg q "$question" --argjson d "$max_depth" \
    '{input: {question: $q, collectionId: $cid, maxDepth: $d}}')")
  local end_ns
  end_ns=$(date +%s)
  local elapsed=$((end_ns - start_ns))

  local answer retrieved selected duration
  answer=$(echo "$resp" | jq -r '.data.graphRag.answer // empty')
  retrieved=$(echo "$resp" | jq '.data.graphRag.retrievedEdgeCount // 0')
  selected=$(echo "$resp" | jq '.data.graphRag.selectedEdges | length // 0')
  duration=$(echo "$resp" | jq '.data.graphRag.durationMs // 0')

  if [[ -n "$answer" ]]; then
    ok "GraphRAG OK ($retrieved Kanten -> $selected selektiert, backend=${duration}ms wall=${elapsed}s)"
    echo "  ${BLUE}Antwort:${RESET} ${answer:0:400}"
    if [[ "$selected" -gt 0 ]]; then
      echo "  ${BLUE}Top-Kanten:${RESET}"
      echo "$resp" | jq -r '.data.graphRag.selectedEdges[:5][] |
        "    \(.subject | split("/") | last) --\(.predicate | split("/") | last)--> \(.objectValue | split("/") | last)  [score=\(.relevanceScore)]"'
    fi
  else
    fail "graphRag fehlgeschlagen: $(echo "$resp" | jq -c '.errors // .')"
  fi
}

# ---------- Vorbereitung ----------
require curl
require jq

step "Health-Check (GraphQL __typename)"
HEALTH_BODY=$(curl -sS -X POST "$GRAPHQL_URL" \
  -H "Content-Type: application/json" \
  -d '{"query":"{ __typename }"}' 2>/dev/null || echo "")
if echo "$HEALTH_BODY" | jq -e '.data.__typename' >/dev/null 2>&1; then
  ok "GraphQL-Endpoint antwortet ($GRAPHQL_URL)"
else
  fail "GraphQL-Endpoint nicht erreichbar: $GRAPHQL_URL"
  echo "  Antwort: $HEALTH_BODY"
  exit 1
fi

# ---------- Collection bestimmen ----------
if [[ -z "$COLLECTION_ID" ]]; then
  step "Keine COLLECTION_ID gesetzt — suche erste verfuegbare Collection"
  COLLS_RESP=$(gql 'query { collections { id name } }' '{}')
  COLLECTION_ID=$(echo "$COLLS_RESP" | jq -r '.data.collections[0].id // empty')
  COLLECTION_NAME=$(echo "$COLLS_RESP" | jq -r '.data.collections[0].name // empty')
  if [[ -z "$COLLECTION_ID" ]]; then
    fail "Keine Collections gefunden. Setze COLLECTION_ID oder importiere zuerst Daten."
    exit 1
  fi
  ok "Nutze Collection: $COLLECTION_NAME ($COLLECTION_ID)"
else
  ok "Nutze Collection: $COLLECTION_ID"
fi

# ---------- Graph-Metadaten anzeigen ----------
step "Graph-Metadaten"
RESP=$(gql 'query($cid: ID!) {
  graphMetadata(collectionId: $cid) { datasets predicates entityTypes }
}' "$(jq -cn --arg cid "$COLLECTION_ID" '{cid: $cid}')")
PREDS=$(echo "$RESP" | jq '.data.graphMetadata.predicates | length // 0')
TYPES=$(echo "$RESP" | jq '.data.graphMetadata.entityTypes | length // 0')
if [[ "$PREDS" -gt 0 || "$TYPES" -gt 0 ]]; then
  ok "$PREDS Praedikate, $TYPES Entity-Typen"
else
  fail "Collection scheint leer zu sein"
fi

# ==========================================================================
# TEIL A: NLP-Queries (Auto-Modus mit Intent-Detection)
# ==========================================================================

run_nlp "Organisation-Zugehoerigkeit" \
  "Wer arbeitet bei Acme?"

run_nlp "Projekt-Leitung" \
  "Wer leitet Projekt X?"

run_nlp "Organisations-Uebersicht" \
  "Welche Organisationen gibt es und wann wurden sie gegruendet?"

run_nlp "Beziehungs-Abfrage" \
  "Wen kennt Alice und welche Beziehungen hat sie?"

# ==========================================================================
# TEIL B: GraphRAG direkt (skip Intent-Detection)
# ==========================================================================

run_graphrag "Acme-Umfeld" \
  "Welche Personen und Projekte gehoeren zu Acme?" \
  2

run_graphrag "Pfad Alice -> Carol" \
  "Gibt es eine Verbindung zwischen Alice und Carol? Ueber welche Personen sind sie verbunden?" \
  3

# ---------- Zusammenfassung ----------
echo
echo "================================================"
echo " Query Smoke-Test Ergebnis"
echo "================================================"
echo "  Collection:    $COLLECTION_ID"
echo "  Backend:       $BACKEND_URL"
echo "  ${GREEN}PASS: $PASS${RESET}"
echo "  ${RED}FAIL: $FAIL${RESET}"
echo "================================================"

if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
