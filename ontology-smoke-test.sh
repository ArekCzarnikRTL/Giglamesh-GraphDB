#!/usr/bin/env bash
# Smoke-Test fuer Ontology-Import (Feature 44) und RDF-Daten-Import (Feature 43).
# Laedt examples/sample-ontology.ttl und examples/sample-data.ttl hoch,
# prueft list/get/delete und RDF-Import mit Triples-Verifikation.
#
# Voraussetzung: Backend laeuft auf :8083, `jq` und `curl` installiert.
# Aufruf:  ./ontology-smoke-test.sh
# Optional: BACKEND_URL=http://localhost:8080 ./ontology-smoke-test.sh
#           ONTOLOGY_FILE=my-onto.ttl ./ontology-smoke-test.sh
#           RDF_DATA_FILE=my-data.ttl ./ontology-smoke-test.sh

set -uo pipefail

BACKEND_URL="${BACKEND_URL:-http://localhost:8083}"
GRAPHQL_URL="$BACKEND_URL/graphql"
ONTOLOGY_FILE="${ONTOLOGY_FILE:-examples/sample-ontology.ttl}"
ONTOLOGY_KEY="${ONTOLOGY_KEY:-smoke-ontology-$(date +%s)}"
RDF_DATA_FILE="${RDF_DATA_FILE:-examples/sample-data.ttl}"
RDF_COLLECTION_ID=""

PASS=0
FAIL=0

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
  fail "GraphQL-Endpoint nicht erreichbar — laeuft das Backend?"
  echo "  Antwort: $HEALTH_BODY"
  echo
  echo "${RED}Abbruch.${RESET}"
  exit 1
fi

# ---------- 1. Ontologie-Datei pruefen ----------
step "Ontologie-Datei pruefen: $ONTOLOGY_FILE"
if [[ ! -f "$ONTOLOGY_FILE" ]]; then
  fail "Datei nicht gefunden: $ONTOLOGY_FILE"
  echo "${RED}Abbruch.${RESET}"
  exit 1
fi
FILE_SIZE=$(wc -c < "$ONTOLOGY_FILE" | tr -d ' ')
ok "Datei vorhanden ($FILE_SIZE Bytes)"

# Format erkennen: .rdf -> RDFXML, sonst TURTLE
FORMAT="TURTLE"
if [[ "$ONTOLOGY_FILE" == *.rdf ]]; then
  FORMAT="RDFXML"
fi
echo "  Format: $FORMAT"

# ---------- 2. Ontologie importieren ----------
step "Mutation: importOntology (key=$ONTOLOGY_KEY)"
B64_CONTENT=$(base64 < "$ONTOLOGY_FILE" | tr -d '\n')
RESP=$(gql 'mutation($input: ImportOntologyInput!) {
  importOntology(input: $input) {
    key name namespace version classCount objectPropertyCount datatypePropertyCount
  }
}' "$(jq -cn \
  --arg key "$ONTOLOGY_KEY" \
  --arg content "$B64_CONTENT" \
  --arg format "$FORMAT" \
  --arg name "$(basename "$ONTOLOGY_FILE")" \
  --arg ns "http://graphmesh.org/ontology/" \
  '{input: {key: $key, content: $content, format: $format, name: $name, namespace: $ns}}')")

IMPORTED_KEY=$(echo "$RESP" | jq -r '.data.importOntology.key // empty')
if [[ -n "$IMPORTED_KEY" ]]; then
  CLASS_COUNT=$(echo "$RESP" | jq '.data.importOntology.classCount // 0')
  OBJ_PROP_COUNT=$(echo "$RESP" | jq '.data.importOntology.objectPropertyCount // 0')
  DT_PROP_COUNT=$(echo "$RESP" | jq '.data.importOntology.datatypePropertyCount // 0')
  ok "Import erfolgreich: $CLASS_COUNT Klassen, $OBJ_PROP_COUNT Object Properties, $DT_PROP_COUNT Datatype Properties"
else
  fail "importOntology fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
fi

# ---------- 3. Ontologien auflisten ----------
step "Query: listOntologies"
RESP=$(gql 'query { listOntologies { key name namespace version classCount objectPropertyCount datatypePropertyCount } }' '{}')
ONT_COUNT=$(echo "$RESP" | jq '.data.listOntologies | length // 0')
if [[ "$ONT_COUNT" -gt 0 ]]; then
  ok "$ONT_COUNT Ontologie(n) gelistet"
  echo "$RESP" | jq -r '.data.listOntologies[] | "  - \(.key): \(.classCount) Klassen, \(.objectPropertyCount) ObjProps, \(.datatypePropertyCount) DtProps"'
else
  fail "Keine Ontologien: $(echo "$RESP" | jq -c '.errors // .')"
fi

# ---------- 4. Einzelne Ontologie abfragen ----------
step "Query: ontology(key=$ONTOLOGY_KEY)"
RESP=$(gql 'query($key: String!) {
  ontology(key: $key) { key name namespace version classCount objectPropertyCount datatypePropertyCount }
}' "$(jq -cn --arg key "$ONTOLOGY_KEY" '{key: $key}')")
GOT_KEY=$(echo "$RESP" | jq -r '.data.ontology.key // empty')
if [[ "$GOT_KEY" == "$ONTOLOGY_KEY" ]]; then
  NAME=$(echo "$RESP" | jq -r '.data.ontology.name')
  VERSION=$(echo "$RESP" | jq -r '.data.ontology.version')
  ok "Ontologie gefunden: name=$NAME, version=$VERSION"
else
  fail "ontology() fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
fi

# ---------- 5. Nicht-existente Ontologie ----------
step "Query: ontology(key=does-not-exist) — erwartet null"
RESP=$(gql 'query($key: String!) { ontology(key: $key) { key } }' '{"key":"does-not-exist"}')
GOT=$(echo "$RESP" | jq -r '.data.ontology // "null"')
if [[ "$GOT" == "null" ]]; then
  ok "Nicht-existente Ontologie gibt null zurueck"
else
  fail "Erwartete null, bekam: $GOT"
fi

# ---------- 6. Ontologie loeschen ----------
step "Mutation: deleteOntology(key=$ONTOLOGY_KEY)"
RESP=$(gql 'mutation($key: String!) { deleteOntology(key: $key) }' \
  "$(jq -cn --arg key "$ONTOLOGY_KEY" '{key: $key}')")
DELETED=$(echo "$RESP" | jq -r '.data.deleteOntology // false')
if [[ "$DELETED" == "true" ]]; then
  ok "Ontologie geloescht"
else
  fail "deleteOntology fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
fi

# ---------- 7. Verifizieren, dass geloescht ----------
step "Verify: ontology(key=$ONTOLOGY_KEY) nach Delete — erwartet null"
RESP=$(gql 'query($key: String!) { ontology(key: $key) { key } }' \
  "$(jq -cn --arg key "$ONTOLOGY_KEY" '{key: $key}')")
GOT=$(echo "$RESP" | jq -r '.data.ontology // "null"')
if [[ "$GOT" == "null" ]]; then
  ok "Ontologie nach Delete nicht mehr vorhanden"
else
  fail "Ontologie noch vorhanden nach Delete: $GOT"
fi

# ==========================================================================
# TEIL 2: RDF-Daten-Import (Feature 43)
# ==========================================================================

# ---------- 8. Collection fuer RDF-Import erstellen ----------
step "Mutation: createCollection fuer RDF-Daten-Import"
RDF_COLL_NAME="rdf-smoke-$(date +%s)"
RESP=$(gql 'mutation($input: CreateCollectionInput!) {
  createCollection(input: $input) { id name }
}' "$(jq -cn --arg n "$RDF_COLL_NAME" '{input: {name: $n, description: "RDF import smoke test", tags: ["smoke"]}}')")
RDF_COLLECTION_ID=$(echo "$RESP" | jq -r '.data.createCollection.id // empty')
if [[ -n "$RDF_COLLECTION_ID" ]]; then
  ok "Collection erstellt: $RDF_COLLECTION_ID"
else
  fail "createCollection fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
fi

# ---------- 9. RDF-Datendatei importieren ----------
if [[ -n "$RDF_COLLECTION_ID" ]]; then
  step "Mutation: importRdf ($RDF_DATA_FILE)"
  if [[ ! -f "$RDF_DATA_FILE" ]]; then
    fail "RDF-Datendatei nicht gefunden: $RDF_DATA_FILE"
  else
    RDF_SIZE=$(wc -c < "$RDF_DATA_FILE" | tr -d ' ')
    B64_RDF=$(base64 < "$RDF_DATA_FILE" | tr -d '\n')
    RDF_FORMAT="TURTLE"
    if [[ "$RDF_DATA_FILE" == *.rdf ]]; then
      RDF_FORMAT="RDFXML"
    elif [[ "$RDF_DATA_FILE" == *.nt ]]; then
      RDF_FORMAT="NTRIPLES"
    fi
    RESP=$(gql 'mutation($input: ImportRdfInput!) {
      importRdf(input: $input) { tripleCount skippedCount durationMs embeddingsGenerated }
    }' "$(jq -cn \
      --arg cid "$RDF_COLLECTION_ID" \
      --arg content "$B64_RDF" \
      --arg format "$RDF_FORMAT" \
      '{input: {collectionId: $cid, content: $content, format: $format, generateEmbeddings: true}}')")
    TRIPLE_COUNT=$(echo "$RESP" | jq '.data.importRdf.tripleCount // 0')
    SKIPPED=$(echo "$RESP" | jq '.data.importRdf.skippedCount // 0')
    DURATION=$(echo "$RESP" | jq '.data.importRdf.durationMs // 0')
    EMBEDDINGS=$(echo "$RESP" | jq '.data.importRdf.embeddingsGenerated // 0')
    if [[ "$TRIPLE_COUNT" -gt 0 ]]; then
      ok "RDF-Import: $TRIPLE_COUNT Triples, $SKIPPED uebersprungen, $EMBEDDINGS Embeddings, ${DURATION}ms ($RDF_SIZE Bytes)"
    else
      fail "importRdf fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
    fi
  fi
fi

# ---------- 10. Importierte Triples abfragen ----------
if [[ -n "$RDF_COLLECTION_ID" ]]; then
  step "Query: triples in importierter Collection"
  RESP=$(gql 'query($cid: ID!) {
    triples(collectionId: $cid) { subject predicate object }
  }' "$(jq -cn --arg cid "$RDF_COLLECTION_ID" '{cid: $cid}')")
  TC=$(echo "$RESP" | jq '.data.triples | length // 0')
  if [[ "$TC" -gt 0 ]]; then
    ok "$TC Triples in Collection abrufbar"
    echo "$RESP" | jq -r '.data.triples[:5][] | "  \(.subject) -- \(.predicate) --> \(.object)"'
    if [[ "$TC" -gt 5 ]]; then
      echo "  ... und $((TC - 5)) weitere"
    fi
  else
    fail "Keine Triples nach Import: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 11. Triples nach Subject filtern ----------
if [[ -n "$RDF_COLLECTION_ID" ]]; then
  step "Query: triples(subject=http://example.org/Alice)"
  RESP=$(gql 'query($cid: ID!, $s: String) {
    triples(collectionId: $cid, subject: $s) { subject predicate object }
  }' "$(jq -cn --arg cid "$RDF_COLLECTION_ID" --arg s "http://example.org/Alice" '{cid: $cid, s: $s}')")
  TC=$(echo "$RESP" | jq '.data.triples | length // 0')
  if [[ "$TC" -gt 0 ]]; then
    ok "$TC Triples fuer Alice gefunden"
    echo "$RESP" | jq -r '.data.triples[] | "  \(.predicate | split("/") | last) -> \(.object)"'
  else
    fail "Keine Triples fuer Alice: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 12. Triples nach Predicate filtern ----------
if [[ -n "$RDF_COLLECTION_ID" ]]; then
  step "Query: triples(predicate=http://example.org/arbeitetBei)"
  RESP=$(gql 'query($cid: ID!, $p: String) {
    triples(collectionId: $cid, predicate: $p) { subject predicate object }
  }' "$(jq -cn --arg cid "$RDF_COLLECTION_ID" --arg p "http://example.org/arbeitetBei" '{cid: $cid, p: $p}')")
  TC=$(echo "$RESP" | jq '.data.triples | length // 0')
  if [[ "$TC" -gt 0 ]]; then
    ok "$TC 'arbeitetBei'-Relationen gefunden"
    echo "$RESP" | jq -r '.data.triples[] | "  \(.subject | split("/") | last) arbeitetBei \(.object | split("/") | last)"'
  else
    fail "Keine arbeitetBei-Triples: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 13. Entity Search ----------
if [[ -n "$RDF_COLLECTION_ID" ]]; then
  step "Query: entitySearch(prefix=Alice)"
  RESP=$(gql 'query($cid: ID!, $prefix: String!) {
    entitySearch(collectionId: $cid, prefix: $prefix, limit: 10)
  }' "$(jq -cn --arg cid "$RDF_COLLECTION_ID" --arg prefix "Alice" '{cid: $cid, prefix: $prefix}')")
  HITS=$(echo "$RESP" | jq '.data.entitySearch | length // 0')
  if [[ "$HITS" -gt 0 ]]; then
    ok "$HITS Entities mit 'Alice' gefunden"
    echo "$RESP" | jq -r '.data.entitySearch[] | "  \(.)"'
  else
    fail "entitySearch(Alice) lieferte 0 Treffer: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 14. Graph Metadata ----------
if [[ -n "$RDF_COLLECTION_ID" ]]; then
  step "Query: graphMetadata"
  RESP=$(gql 'query($cid: ID!) {
    graphMetadata(collectionId: $cid) { datasets predicates entityTypes }
  }' "$(jq -cn --arg cid "$RDF_COLLECTION_ID" '{cid: $cid}')")
  PREDS=$(echo "$RESP" | jq '.data.graphMetadata.predicates | length // 0')
  TYPES=$(echo "$RESP" | jq '.data.graphMetadata.entityTypes | length // 0')
  if [[ "$PREDS" -gt 0 ]]; then
    ok "$PREDS Prädikate, $TYPES Entity-Typen im Graph"
    echo "  Prädikate:"
    echo "$RESP" | jq -r '.data.graphMetadata.predicates[:10][] | "    \(. | split("/") | last)"'
    if [[ "$TYPES" -gt 0 ]]; then
      echo "  Entity-Typen:"
      echo "$RESP" | jq -r '.data.graphMetadata.entityTypes[] | "    \(. | split("/") | last)"'
    fi
  else
    fail "graphMetadata lieferte keine Prädikate: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ==========================================================================
# TEIL 3: Embedding-basierte Abfragen (setzt generateEmbeddings: true voraus)
# ==========================================================================

# ---------- 15. NLP-Query: Wer arbeitet bei Acme? ----------
if [[ -n "$RDF_COLLECTION_ID" ]]; then
  step "NLP-Query: Wer arbeitet bei Acme?"
  RESP=$(gql 'query($input: NlpQueryInput!) {
    nlpQuery(input: $input) { answer detectedIntent { intent confidence } durationMs sources }
  }' "$(jq -cn --arg cid "$RDF_COLLECTION_ID" '{input: {question: "Wer arbeitet bei Acme?", collectionId: $cid}}')")
  ANSWER=$(echo "$RESP" | jq -r '.data.nlpQuery.answer // empty')
  INTENT=$(echo "$RESP" | jq -r '.data.nlpQuery.detectedIntent.intent // empty')
  DURATION=$(echo "$RESP" | jq '.data.nlpQuery.durationMs // 0')
  if [[ -n "$ANSWER" ]]; then
    ok "NLP-Antwort erhalten (intent=$INTENT, ${DURATION}ms)"
    echo "  Antwort: ${ANSWER:0:200}"
  else
    fail "nlpQuery fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 16. NLP-Query: Wer leitet Projekt X? ----------
if [[ -n "$RDF_COLLECTION_ID" ]]; then
  step "NLP-Query: Wer leitet Projekt X?"
  RESP=$(gql 'query($input: NlpQueryInput!) {
    nlpQuery(input: $input) { answer detectedIntent { intent confidence } durationMs sources }
  }' "$(jq -cn --arg cid "$RDF_COLLECTION_ID" '{input: {question: "Wer leitet Projekt X?", collectionId: $cid}}')")
  ANSWER=$(echo "$RESP" | jq -r '.data.nlpQuery.answer // empty')
  if [[ -n "$ANSWER" ]]; then
    ok "NLP-Antwort: ${ANSWER:0:200}"
  else
    fail "nlpQuery fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 17. NLP-Query: Welche Organisationen gibt es? ----------
if [[ -n "$RDF_COLLECTION_ID" ]]; then
  step "NLP-Query: Welche Organisationen gibt es?"
  RESP=$(gql 'query($input: NlpQueryInput!) {
    nlpQuery(input: $input) { answer detectedIntent { intent confidence } durationMs sources }
  }' "$(jq -cn --arg cid "$RDF_COLLECTION_ID" '{input: {question: "Welche Organisationen gibt es und wann wurden sie gegründet?", collectionId: $cid}}')")
  ANSWER=$(echo "$RESP" | jq -r '.data.nlpQuery.answer // empty')
  if [[ -n "$ANSWER" ]]; then
    ok "NLP-Antwort: ${ANSWER:0:200}"
  else
    fail "nlpQuery fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 18. NLP-Query: Wen kennt Alice? ----------
if [[ -n "$RDF_COLLECTION_ID" ]]; then
  step "NLP-Query: Wen kennt Alice?"
  RESP=$(gql 'query($input: NlpQueryInput!) {
    nlpQuery(input: $input) { answer detectedIntent { intent confidence } durationMs sources }
  }' "$(jq -cn --arg cid "$RDF_COLLECTION_ID" '{input: {question: "Wen kennt Alice und welche Beziehungen hat sie?", collectionId: $cid}}')")
  ANSWER=$(echo "$RESP" | jq -r '.data.nlpQuery.answer // empty')
  if [[ -n "$ANSWER" ]]; then
    ok "NLP-Antwort: ${ANSWER:0:200}"
  else
    fail "nlpQuery fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 19. GraphRAG: Beziehungen rund um Acme ----------
if [[ -n "$RDF_COLLECTION_ID" ]]; then
  step "GraphRAG: Welche Personen und Projekte gehören zu Acme?"
  RESP=$(gql 'query($input: GraphRagInput!) {
    graphRag(input: $input) { answer selectedEdges { subject predicate objectValue relevanceScore } retrievedEdgeCount durationMs }
  }' "$(jq -cn --arg cid "$RDF_COLLECTION_ID" '{input: {question: "Welche Personen und Projekte gehören zu Acme?", collectionId: $cid, maxDepth: 2}}')")
  ANSWER=$(echo "$RESP" | jq -r '.data.graphRag.answer // empty')
  EDGES=$(echo "$RESP" | jq '.data.graphRag.retrievedEdgeCount // 0')
  SELECTED=$(echo "$RESP" | jq '.data.graphRag.selectedEdges | length // 0')
  DURATION=$(echo "$RESP" | jq '.data.graphRag.durationMs // 0')
  if [[ -n "$ANSWER" ]]; then
    ok "GraphRAG-Antwort: $EDGES Kanten abgerufen, $SELECTED selektiert, ${DURATION}ms"
    echo "  Antwort: ${ANSWER:0:200}"
    echo "$RESP" | jq -r '.data.graphRag.selectedEdges[:5][] | "  \(.subject | split("/") | last) --\(.predicate | split("/") | last)--> \(.objectValue | split("/") | last) (score=\(.relevanceScore))"'
  else
    fail "graphRag fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 20. GraphRAG: Verbindung zwischen Alice und Carol ----------
if [[ -n "$RDF_COLLECTION_ID" ]]; then
  step "GraphRAG: Gibt es eine Verbindung zwischen Alice und Carol?"
  RESP=$(gql 'query($input: GraphRagInput!) {
    graphRag(input: $input) { answer selectedEdges { subject predicate objectValue relevanceScore } retrievedEdgeCount durationMs }
  }' "$(jq -cn --arg cid "$RDF_COLLECTION_ID" '{input: {question: "Gibt es eine Verbindung zwischen Alice und Carol? Über welche Personen sind sie verbunden?", collectionId: $cid, maxDepth: 3}}')")
  ANSWER=$(echo "$RESP" | jq -r '.data.graphRag.answer // empty')
  EDGES=$(echo "$RESP" | jq '.data.graphRag.retrievedEdgeCount // 0')
  DURATION=$(echo "$RESP" | jq '.data.graphRag.durationMs // 0')
  if [[ -n "$ANSWER" ]]; then
    ok "GraphRAG-Antwort ($EDGES Kanten, ${DURATION}ms): ${ANSWER:0:200}"
  else
    fail "graphRag fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 21. Cleanup: RDF-Collection loeschen ----------
# if [[ -n "$RDF_COLLECTION_ID" ]]; then
#   step "Cleanup: deleteCollection (RDF-Import)"
#   RESP=$(gql 'mutation($id: ID!) { deleteCollection(id: $id) }' \
#     "$(jq -cn --arg id "$RDF_COLLECTION_ID" '{id: $id}')")
#   if [[ "$(echo "$RESP" | jq -r '.data.deleteCollection // false')" == "true" ]]; then
#     ok "RDF-Collection geloescht"
#   else
#     fail "deleteCollection fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
#   fi
# fi

# ---------- Zusammenfassung ----------
echo
echo "================================================"
echo " Ontology & RDF Import Smoke-Test Ergebnis"
echo "================================================"
echo "  ${GREEN}PASS: $PASS${RESET}"
echo "  ${RED}FAIL: $FAIL${RESET}"
echo "================================================"

if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
