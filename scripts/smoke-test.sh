#!/usr/bin/env bash
# Smoke-Test fuer das GraphMesh-Backend.
# Voraussetzung: Backend laeuft auf :8080, Infrastruktur (Cassandra, Qdrant, Kafka, MinIO)
# ist hochgefahren. `jq` und `curl` muessen installiert sein.
#
# Aufruf:  ./smoke-test.sh
# Optional: BACKEND_URL=http://localhost:8083 ./smoke-test.sh

set -uo pipefail

BACKEND_URL="${BACKEND_URL:-http://localhost:8083}"
GRAPHQL_URL="$BACKEND_URL/graphql"
TEST_PDF="${TEST_PDF:-test-pdf.pdf}"
EXTRACTION_TIMEOUT="${EXTRACTION_TIMEOUT:-300}"  # max Sekunden, die wir auf Extraction warten

PASS=0
FAIL=0
COLLECTION_ID=""
DOCUMENT_ID=""

# ---------- Helpers ----------
GREEN=$'\033[0;32m'
RED=$'\033[0;31m'
YELLOW=$'\033[0;33m'
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

# Sendet eine GraphQL-Operation und gibt das `data`-Feld zurueck.
# Args: $1 = query, $2 = variables (JSON), $3 = jq-Pfad in `data`
gql() {
  local query="$1"
  local vars="$2"
  local body
  body=$(jq -cn --arg q "$query" --argjson v "$vars" '{query: $q, variables: $v}')
  curl -sS -X POST "$GRAPHQL_URL" \
    -H "Content-Type: application/json" \
    -d "$body"
}

# ---------- Vorbereitung ----------
require curl
require jq
require base64

step "Health-Check (GraphQL __typename)"
HEALTH_BODY=$(curl -sS -X POST "$GRAPHQL_URL" \
  -H "Content-Type: application/json" \
  -d '{"query":"{ __typename }"}' 2>/dev/null || echo "")
if echo "$HEALTH_BODY" | jq -e '.data.__typename' >/dev/null 2>&1; then
  ok "GraphQL-Endpoint antwortet"
else
  fail "GraphQL-Endpoint nicht erreichbar — laeuft ./start.sh?"
  echo "  Antwort: $HEALTH_BODY"
  echo
  echo "${RED}Abbruch.${RESET}"
  exit 1
fi

# ---------- 1. Collection erstellen ----------
step "Mutation: createCollection"
COLL_NAME="smoke-test-$(date +%s)"
RESP=$(gql 'mutation($input: CreateCollectionInput!) {
  createCollection(input: $input) { id name description }
}' "$(jq -cn --arg n "$COLL_NAME" '{input: {name: $n, description: "smoke test", tags: ["smoke"]}}')")
COLLECTION_ID=$(echo "$RESP" | jq -r '.data.createCollection.id // empty')
if [[ -n "$COLLECTION_ID" ]]; then
  ok "Collection erstellt: $COLLECTION_ID"
else
  fail "createCollection fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
fi

# ---------- 2. Collections listen ----------
step "Query: collections"
RESP=$(gql 'query { collections { id name } }' '{}')
COUNT=$(echo "$RESP" | jq '.data.collections | length // 0')
if [[ "$COUNT" -gt 0 ]]; then
  ok "$COUNT Collections gelistet"
else
  fail "Keine Collections: $(echo "$RESP" | jq -c '.errors // .')"
fi

# ---------- 3. Dokument hochladen ----------
if [[ -n "$COLLECTION_ID" ]]; then
  step "Mutation: uploadDocument ($TEST_PDF)"
  if [[ ! -f "$TEST_PDF" ]]; then
    fail "Test-PDF nicht gefunden: $TEST_PDF (TEST_PDF env-var setzen)"
  else
    PDF_SIZE=$(wc -c < "$TEST_PDF" | tr -d ' ')
    # base64 in temp file (zu gross fuer argv)
    B64_FILE=$(mktemp)
    base64 < "$TEST_PDF" | tr -d '\n' > "$B64_FILE"
    BODY_FILE=$(mktemp)
    jq -n --arg cid "$COLLECTION_ID" --arg t "$TEST_PDF" \
          --rawfile c "$B64_FILE" \
          '{query: "mutation($input: UploadDocumentInput!) { uploadDocument(input: $input) { id title state } }",
            variables: {input: {collectionId: $cid, title: $t, mimeType: "application/pdf", content: $c}}}' \
      > "$BODY_FILE"
    RESP=$(curl -sS -X POST "$GRAPHQL_URL" \
      -H "Content-Type: application/json" \
      --data-binary "@$BODY_FILE")
    rm -f "$B64_FILE" "$BODY_FILE"
    DOCUMENT_ID=$(echo "$RESP" | jq -r '.data.uploadDocument.id // empty')
    if [[ -n "$DOCUMENT_ID" ]]; then
      ok "PDF hochgeladen: $DOCUMENT_ID ($PDF_SIZE Bytes)"
    else
      fail "uploadDocument fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
    fi
  fi
fi

# ---------- 4. Dokumente listen ----------
if [[ -n "$COLLECTION_ID" ]]; then
  step "Query: documents (Liste fuer Collection)"
  RESP=$(gql 'query($cid: ID!) {
    documents(collectionId: $cid, pageSize: 50) { items { id title state } totalCount }
  }' "$(jq -cn --arg cid "$COLLECTION_ID" '{cid: $cid}')")
  TOTAL=$(echo "$RESP" | jq '.data.documents.totalCount // 0')
  if [[ "$TOTAL" -gt 0 ]]; then
    ok "$TOTAL Dokumente in Collection"
  else
    fail "Keine Dokumente: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 5. Dokument-Detail ----------
if [[ -n "$DOCUMENT_ID" ]]; then
  step "Query: document(id)"
  RESP=$(gql 'query($id: ID!) {
    document(id: $id) { id title state mimeType metadata { key value } }
  }' "$(jq -cn --arg id "$DOCUMENT_ID" '{id: $id}')")
  STATE=$(echo "$RESP" | jq -r '.data.document.state // empty')
  if [[ -n "$STATE" ]]; then
    ok "Dokument-State: $STATE"
  else
    fail "document() fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 6. Auf Extraktion warten ----------
# Achtung: state=EXTRACTED wird leider bereits nach PDF-Decode/Chunking gesetzt,
# nicht erst wenn Relationship-/Definition-/Embedding-Pipelines fertig sind.
# Deshalb pollen wir zusaetzlich auf tatsaechliche Triples UND Vector-Treffer.
if [[ -n "$DOCUMENT_ID" && -n "$COLLECTION_ID" ]]; then
  step "Warte auf Extraktion (max ${EXTRACTION_TIMEOUT}s, pollt Triples+Vektoren)"
  ITERS=$((EXTRACTION_TIMEOUT / 5))
  TRIPLE_COUNT=0
  VECTOR_COUNT=0
  STATE=""
  for i in $(seq 1 $ITERS); do
    sleep 5
    RESP=$(gql 'query($id: ID!) { document(id: $id) { state } }' \
      "$(jq -cn --arg id "$DOCUMENT_ID" '{id: $id}')")
    STATE=$(echo "$RESP" | jq -r '.data.document.state // empty')

    TRESP=$(gql 'query($cid: ID!) { triples(collectionId: $cid) { subject } }' \
      "$(jq -cn --arg cid "$COLLECTION_ID" '{cid: $cid}')")
    TRIPLE_COUNT=$(echo "$TRESP" | jq '.data.triples | length // 0')

    VRESP=$(gql 'query($cid: ID!, $q: String!) {
      vectorSearch(collectionId: $cid, query: $q, limit: 5) { id }
    }' "$(jq -cn --arg cid "$COLLECTION_ID" --arg q "Wissensgraph" '{cid: $cid, q: $q}')")
    VECTOR_COUNT=$(echo "$VRESP" | jq '.data.vectorSearch | length // 0')

    echo "  ($((i*5))s) state=$STATE triples=$TRIPLE_COUNT vectors=$VECTOR_COUNT"

    if [[ "$STATE" == "FAILED" ]]; then
      break
    fi
    if [[ "$TRIPLE_COUNT" -gt 0 && "$VECTOR_COUNT" -gt 0 ]]; then
      break
    fi
  done
  if [[ "$STATE" == "FAILED" ]]; then
    fail "Extraktion fehlgeschlagen"
  elif [[ "$TRIPLE_COUNT" -gt 0 && "$VECTOR_COUNT" -gt 0 ]]; then
    ok "Extraktion wirklich fertig (state=$STATE, triples=$TRIPLE_COUNT, vectors=$VECTOR_COUNT)"
  else
    fail "Extraktion nicht innerhalb ${EXTRACTION_TIMEOUT}s vollstaendig (state=$STATE, triples=$TRIPLE_COUNT, vectors=$VECTOR_COUNT)"
  fi
fi

# ---------- 7. Triples abfragen ----------
if [[ -n "$COLLECTION_ID" ]]; then
  step "Query: triples"
  RESP=$(gql 'query($cid: ID!) {
    triples(collectionId: $cid) { subject predicate object }
  }' "$(jq -cn --arg cid "$COLLECTION_ID" '{cid: $cid}')")
  TC=$(echo "$RESP" | jq '.data.triples | length // 0')
  if [[ "$TC" -gt 0 ]]; then
    ok "$TC Triples extrahiert"
  else
    fail "Keine Triples: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 8. Vector Search ----------
if [[ -n "$COLLECTION_ID" ]]; then
  step "Query: vectorSearch"
  RESP=$(gql 'query($cid: ID!, $q: String!) {
    vectorSearch(collectionId: $cid, query: $q, limit: 5) { id score }
  }' "$(jq -cn --arg cid "$COLLECTION_ID" --arg q "Wissensgraph" '{cid: $cid, q: $q}')")
  HITS=$(echo "$RESP" | jq '.data.vectorSearch | length // 0')
  if [[ "$HITS" -gt 0 ]]; then
    ok "$HITS Vector-Treffer"
  else
    fail "vectorSearch lieferte 0 Treffer: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 9. Graph RAG ----------
if [[ -n "$COLLECTION_ID" ]]; then
  step "Query: graphRag"
  RESP=$(gql 'query($input: GraphRagInput!) {
    graphRag(input: $input) { sessionId answer retrievedEdgeCount durationMs }
  }' "$(jq -cn --arg cid "$COLLECTION_ID" '{input: {question: "Was ist GraphMesh?", collectionId: $cid}}')")
  ANS=$(echo "$RESP" | jq -r '.data.graphRag.answer // empty')
  EDGES=$(echo "$RESP" | jq '.data.graphRag.retrievedEdgeCount // 0')
  if [[ -n "$ANS" && "$EDGES" -gt 0 && "$ANS" != *"No relevant knowledge"* ]]; then
    ok "graphRag-Antwort ($EDGES edges): ${ANS:0:80}..."
  else
    fail "graphRag liefert leere Antwort oder 0 edges (edges=$EDGES, errors=$(echo "$RESP" | jq -c '.errors // empty'))"
  fi
fi

# ---------- 10. Document RAG ----------
if [[ -n "$COLLECTION_ID" ]]; then
  step "Query: documentRag"
  RESP=$(gql 'query($input: DocumentRagInput!) {
    documentRag(input: $input) { sessionId answer retrievedChunkCount durationMs }
  }' "$(jq -cn --arg cid "$COLLECTION_ID" '{input: {question: "Was extrahiert GraphMesh?", collectionId: $cid}}')")
  ANS=$(echo "$RESP" | jq -r '.data.documentRag.answer // empty')
  if [[ -n "$ANS" ]]; then
    ok "documentRag-Antwort: ${ANS:0:80}..."
  else
    fail "documentRag fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 11. NLP Query ----------
if [[ -n "$COLLECTION_ID" ]]; then
  step "Query: nlpQuery"
  RESP=$(gql 'query($input: NlpQueryInput!) {
    nlpQuery(input: $input) {
      answer detectedIntent { intent confidence } durationMs
    }
  }' "$(jq -cn --arg cid "$COLLECTION_ID" '{input: {question: "Wer  baut eine Integrationsplattform?", collectionId: $cid}}')")
  ANS=$(echo "$RESP" | jq -r '.data.nlpQuery.answer // empty')
  if [[ -n "$ANS" ]]; then
    INTENT=$(echo "$RESP" | jq -r '.data.nlpQuery.detectedIntent.intent // "?"')
    ok "nlpQuery (intent=$INTENT): ${ANS:0:80}..."
  else
    fail "nlpQuery fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 12. Cleanup ----------
if [[ -n "$DOCUMENT_ID" ]]; then
  step "Mutation: deleteDocument"
  RESP=$(gql 'mutation($id: ID!) { deleteDocument(id: $id) }' \
    "$(jq -cn --arg id "$DOCUMENT_ID" '{id: $id}')")
  if [[ "$(echo "$RESP" | jq -r '.data.deleteDocument // false')" == "true" ]]; then
    ok "Dokument geloescht"
  else
    fail "deleteDocument fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

if [[ -n "$COLLECTION_ID" ]]; then
  step "Mutation: deleteCollection"
  RESP=$(gql 'mutation($id: ID!) { deleteCollection(id: $id) }' \
    "$(jq -cn --arg id "$COLLECTION_ID" '{id: $id}')")
  if [[ "$(echo "$RESP" | jq -r '.data.deleteCollection // false')" == "true" ]]; then
    ok "Collection geloescht"
  else
    fail "deleteCollection fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- Zusammenfassung ----------
echo
echo "================================================"
echo " Smoke-Test Ergebnis"
echo "================================================"
echo "  ${GREEN}PASS: $PASS${RESET}"
echo "  ${RED}FAIL: $FAIL${RESET}"
echo "================================================"

if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
