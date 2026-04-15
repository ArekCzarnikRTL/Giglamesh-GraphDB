#!/usr/bin/env bash
# Smoke-Test fuer Context Cores (Feature 37).
# Erstellt eine Collection mit RDF-Daten + Ontologie, baut einen Context Core,
# prueft List/Get/Tag/Import/Delete.
#
# Voraussetzung: Backend laeuft auf :8083, `jq` und `curl` installiert.
# Aufruf:  ./context-core-smoke-test.sh
# Optional: BACKEND_URL=http://localhost:8080 ./context-core-smoke-test.sh
#           ONTOLOGY_FILE=my-onto.ttl ./context-core-smoke-test.sh
#           RDF_DATA_FILE=my-data.ttl ./context-core-smoke-test.sh

set -uo pipefail

BACKEND_URL="${BACKEND_URL:-http://localhost:8083}"
GRAPHQL_URL="$BACKEND_URL/graphql"
ONTOLOGY_FILE="${ONTOLOGY_FILE:-sample-ontology.ttl}"
RDF_DATA_FILE="${RDF_DATA_FILE:-sample-data.ttl}"

TS=$(date +%s)
ONTOLOGY_KEY="core-smoke-ontology-$TS"
CORE_ID="smoke-core-$TS"
CORE_VERSION="1.0.0"
SOURCE_COLLECTION_ID=""
TARGET_COLLECTION_ID=""

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
  echo "${RED}Abbruch.${RESET}"
  exit 1
fi

# ==========================================================================
# TEIL 1: Testdaten vorbereiten (Collection + Ontologie + RDF-Daten)
# ==========================================================================

# ---------- 1. Quell-Collection erstellen ----------
step "Quell-Collection erstellen"
RESP=$(gql 'mutation($input: CreateCollectionInput!) {
  createCollection(input: $input) { id name }
}' "$(jq -cn --arg n "core-smoke-source-$TS" '{input: {name: $n, description: "Context Core smoke test source", tags: ["smoke"]}}')")
SOURCE_COLLECTION_ID=$(echo "$RESP" | jq -r '.data.createCollection.id // empty')
if [[ -n "$SOURCE_COLLECTION_ID" ]]; then
  ok "Quell-Collection erstellt: $SOURCE_COLLECTION_ID"
else
  fail "createCollection fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  echo "${RED}Abbruch.${RESET}"
  exit 1
fi

# ---------- 2. Ontologie importieren ----------
step "Ontologie importieren: $ONTOLOGY_FILE (key=$ONTOLOGY_KEY)"
if [[ ! -f "$ONTOLOGY_FILE" ]]; then
  fail "Ontologie-Datei nicht gefunden: $ONTOLOGY_FILE"
  echo "${RED}Abbruch.${RESET}"
  exit 1
fi
FORMAT="TURTLE"
if [[ "$ONTOLOGY_FILE" == *.rdf ]]; then
  FORMAT="RDFXML"
fi
B64_ONTO=$(base64 < "$ONTOLOGY_FILE" | tr -d '\n')
RESP=$(gql 'mutation($input: ImportOntologyInput!) {
  importOntology(input: $input) { key name classCount objectPropertyCount datatypePropertyCount }
}' "$(jq -cn \
  --arg key "$ONTOLOGY_KEY" \
  --arg content "$B64_ONTO" \
  --arg format "$FORMAT" \
  --arg name "$(basename "$ONTOLOGY_FILE")" \
  --arg ns "http://graphmesh.org/ontology/" \
  '{input: {key: $key, content: $content, format: $format, name: $name, namespace: $ns}}')")
IMPORTED_KEY=$(echo "$RESP" | jq -r '.data.importOntology.key // empty')
if [[ -n "$IMPORTED_KEY" ]]; then
  CLASS_COUNT=$(echo "$RESP" | jq '.data.importOntology.classCount // 0')
  ok "Ontologie importiert: $CLASS_COUNT Klassen"
else
  fail "importOntology fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  ONTOLOGY_KEY=""
fi

# ---------- 3. RDF-Daten importieren ----------
step "RDF-Daten importieren: $RDF_DATA_FILE"
if [[ ! -f "$RDF_DATA_FILE" ]]; then
  fail "RDF-Datendatei nicht gefunden: $RDF_DATA_FILE"
  echo "${RED}Abbruch.${RESET}"
  exit 1
fi
RDF_FORMAT="TURTLE"
if [[ "$RDF_DATA_FILE" == *.rdf ]]; then
  RDF_FORMAT="RDFXML"
elif [[ "$RDF_DATA_FILE" == *.nt ]]; then
  RDF_FORMAT="NTRIPLES"
fi
B64_RDF=$(base64 < "$RDF_DATA_FILE" | tr -d '\n')
RESP=$(gql 'mutation($input: ImportRdfInput!) {
  importRdf(input: $input) { tripleCount skippedCount durationMs }
}' "$(jq -cn \
  --arg cid "$SOURCE_COLLECTION_ID" \
  --arg content "$B64_RDF" \
  --arg format "$RDF_FORMAT" \
  '{input: {collectionId: $cid, content: $content, format: $format, generateEmbeddings: false}}')")
TRIPLE_COUNT=$(echo "$RESP" | jq '.data.importRdf.tripleCount // 0')
if [[ "$TRIPLE_COUNT" -gt 0 ]]; then
  ok "RDF-Import: $TRIPLE_COUNT Triples"
else
  fail "importRdf fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
fi

# ==========================================================================
# TEIL 2: Context Core Build
# ==========================================================================

# ---------- 4. Context Core bauen ----------
step "Mutation: buildContextCore (coreId=$CORE_ID, version=$CORE_VERSION)"
BUILD_VARS=$(jq -cn \
  --arg coreId "$CORE_ID" \
  --arg version "$CORE_VERSION" \
  --arg source "$SOURCE_COLLECTION_ID" \
  --arg desc "Smoke-Test Context Core" \
  --arg ontKey "$ONTOLOGY_KEY" \
  '{coreId: $coreId, version: $version, sourceCollection: $source, description: $desc, tags: ["smoke","test"], ontologyKey: $ontKey}')
# Wenn keine Ontologie importiert wurde, ontologyKey weglassen
if [[ -z "$ONTOLOGY_KEY" ]]; then
  BUILD_VARS=$(echo "$BUILD_VARS" | jq 'del(.ontologyKey)')
fi
RESP=$(gql 'mutation($coreId: String!, $version: String!, $sourceCollection: String!, $description: String, $tags: [String!], $ontologyKey: String) {
  buildContextCore(coreId: $coreId, version: $version, sourceCollection: $sourceCollection, description: $description, tags: $tags, ontologyKey: $ontologyKey) {
    coreId version checksum stats { quadCount entityCount chunkEmbeddingCount ontologyAxiomCount }
  }
}' "$BUILD_VARS")
BUILD_CHECKSUM=$(echo "$RESP" | jq -r '.data.buildContextCore.checksum // empty')
BUILD_QUADS=$(echo "$RESP" | jq '.data.buildContextCore.stats.quadCount // 0')
BUILD_ENTITIES=$(echo "$RESP" | jq '.data.buildContextCore.stats.entityCount // 0')
if [[ -n "$BUILD_CHECKSUM" ]]; then
  ok "Context Core gebaut: $BUILD_QUADS Quads, $BUILD_ENTITIES Entities, checksum=$BUILD_CHECKSUM"
else
  fail "buildContextCore fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
fi

# ==========================================================================
# TEIL 3: Context Core Queries
# ==========================================================================

# ---------- 5. Alle Cores auflisten ----------
step "Query: contextCores"
RESP=$(gql 'query { contextCores { coreId version sourceCollection tags stats { quadCount entityCount } checksum } }' '{}')
CORE_COUNT=$(echo "$RESP" | jq '.data.contextCores | length // 0')
if [[ "$CORE_COUNT" -gt 0 ]]; then
  ok "$CORE_COUNT Context Core(s) gelistet"
  echo "$RESP" | jq -r '.data.contextCores[] | "  - \(.coreId)@\(.version): \(.stats.quadCount) Quads, tags=\(.tags | join(","))"'
else
  fail "Keine Context Cores gefunden: $(echo "$RESP" | jq -c '.errors // .')"
fi

# ---------- 6. Einzelnen Core abfragen ----------
step "Query: contextCore(coreId=$CORE_ID, version=$CORE_VERSION)"
RESP=$(gql 'query($coreId: String!, $version: String!) {
  contextCore(coreId: $coreId, version: $version) {
    coreId version sourceCollection description tags checksum
    stats { quadCount entityCount chunkEmbeddingCount ontologyAxiomCount }
  }
}' "$(jq -cn --arg coreId "$CORE_ID" --arg version "$CORE_VERSION" '{coreId: $coreId, version: $version}')")
GOT_ID=$(echo "$RESP" | jq -r '.data.contextCore.coreId // empty')
if [[ "$GOT_ID" == "$CORE_ID" ]]; then
  GOT_DESC=$(echo "$RESP" | jq -r '.data.contextCore.description // "–"')
  GOT_CHECKSUM=$(echo "$RESP" | jq -r '.data.contextCore.checksum')
  ok "Core gefunden: description=$GOT_DESC, checksum=$GOT_CHECKSUM"
else
  fail "contextCore fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
fi

# ---------- 7. Nicht-existenten Core abfragen ----------
step "Query: contextCore(coreId=does-not-exist) — erwartet null"
RESP=$(gql 'query($coreId: String!, $version: String!) {
  contextCore(coreId: $coreId, version: $version) { coreId }
}' '{"coreId":"does-not-exist","version":"0.0.0"}')
GOT=$(echo "$RESP" | jq -r '.data.contextCore // "null"')
if [[ "$GOT" == "null" ]]; then
  ok "Nicht-existenter Core gibt null zurueck"
else
  fail "Erwartete null, bekam: $GOT"
fi

# ==========================================================================
# TEIL 4: Tagging
# ==========================================================================

# ---------- 8. Tag setzen ----------
step "Mutation: tagContextCore (tag=latest)"
RESP=$(gql 'mutation($coreId: String!, $version: String!, $tag: String!) {
  tagContextCore(coreId: $coreId, version: $version, tag: $tag) { coreId version tags }
}' "$(jq -cn --arg coreId "$CORE_ID" --arg version "$CORE_VERSION" --arg tag "latest" '{coreId: $coreId, version: $version, tag: $tag}')")
TAGGED_TAGS=$(echo "$RESP" | jq -r '.data.tagContextCore.tags // [] | join(",")')
if echo "$TAGGED_TAGS" | grep -q "latest"; then
  ok "Tag 'latest' gesetzt: tags=[$TAGGED_TAGS]"
else
  fail "tagContextCore fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
fi

# ---------- 9. Core per Tag abfragen ----------
step "Query: contextCoreByTag(coreId=$CORE_ID, tag=latest)"
RESP=$(gql 'query($coreId: String!, $tag: String!) {
  contextCoreByTag(coreId: $coreId, tag: $tag) { coreId version tags }
}' "$(jq -cn --arg coreId "$CORE_ID" --arg tag "latest" '{coreId: $coreId, tag: $tag}')")
GOT_VERSION=$(echo "$RESP" | jq -r '.data.contextCoreByTag.version // empty')
if [[ "$GOT_VERSION" == "$CORE_VERSION" ]]; then
  ok "Core per Tag gefunden: version=$GOT_VERSION"
else
  fail "contextCoreByTag fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
fi

# ==========================================================================
# TEIL 5: Import in andere Collection
# ==========================================================================

# ---------- 10. Ziel-Collection erstellen ----------
step "Ziel-Collection erstellen"
RESP=$(gql 'mutation($input: CreateCollectionInput!) {
  createCollection(input: $input) { id name }
}' "$(jq -cn --arg n "core-smoke-target-$TS" '{input: {name: $n, description: "Context Core smoke test target", tags: ["smoke"]}}')")
TARGET_COLLECTION_ID=$(echo "$RESP" | jq -r '.data.createCollection.id // empty')
if [[ -n "$TARGET_COLLECTION_ID" ]]; then
  ok "Ziel-Collection erstellt: $TARGET_COLLECTION_ID"
else
  fail "createCollection fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
fi

# ---------- 11. Context Core importieren (FAIL-Strategie) ----------
if [[ -n "$TARGET_COLLECTION_ID" ]]; then
  step "Mutation: importContextCore (strategy=FAIL)"
  RESP=$(gql 'mutation($coreId: String!, $version: String!, $target: String!, $strategy: ConflictStrategy!) {
    importContextCore(coreId: $coreId, version: $version, targetCollection: $target, strategy: $strategy) {
      coreId version quadsImported embeddingsImported
    }
  }' "$(jq -cn \
    --arg coreId "$CORE_ID" \
    --arg version "$CORE_VERSION" \
    --arg target "$TARGET_COLLECTION_ID" \
    --arg strategy "FAIL" \
    '{coreId: $coreId, version: $version, target: $target, strategy: $strategy}')")
  IMPORTED_QUADS=$(echo "$RESP" | jq '.data.importContextCore.quadsImported // -1')
  IMPORTED_EMBEDS=$(echo "$RESP" | jq '.data.importContextCore.embeddingsImported // -1')
  if [[ "$IMPORTED_QUADS" -ge 0 ]]; then
    ok "Import erfolgreich: $IMPORTED_QUADS Quads, $IMPORTED_EMBEDS Embeddings"
  else
    fail "importContextCore fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 12. Importierte Triples in Ziel-Collection pruefen ----------
if [[ -n "$TARGET_COLLECTION_ID" ]]; then
  step "Verify: Triples in Ziel-Collection"
  RESP=$(gql 'query($cid: ID!) {
    triples(collectionId: $cid) { subject predicate object }
  }' "$(jq -cn --arg cid "$TARGET_COLLECTION_ID" '{cid: $cid}')")
  TC=$(echo "$RESP" | jq '.data.triples | length // 0')
  if [[ "$TC" -gt 0 ]]; then
    ok "$TC Triples in Ziel-Collection vorhanden"
    echo "$RESP" | jq -r '.data.triples[:5][] | "  \(.subject | split("/") | last) -- \(.predicate | split("/") | last) --> \(.object | split("/") | last)"'
    if [[ "$TC" -gt 5 ]]; then
      echo "  ... und $((TC - 5)) weitere"
    fi
  else
    fail "Keine Triples in Ziel-Collection: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- 13. Doppel-Import mit FAIL-Strategie — erwartet Fehler ----------
if [[ -n "$TARGET_COLLECTION_ID" ]]; then
  step "Mutation: importContextCore nochmal mit FAIL — erwartet Fehler"
  RESP=$(gql 'mutation($coreId: String!, $version: String!, $target: String!, $strategy: ConflictStrategy!) {
    importContextCore(coreId: $coreId, version: $version, targetCollection: $target, strategy: $strategy) {
      coreId version quadsImported
    }
  }' "$(jq -cn \
    --arg coreId "$CORE_ID" \
    --arg version "$CORE_VERSION" \
    --arg target "$TARGET_COLLECTION_ID" \
    --arg strategy "FAIL" \
    '{coreId: $coreId, version: $version, target: $target, strategy: $strategy}')")
  HAS_ERROR=$(echo "$RESP" | jq 'has("errors")')
  if [[ "$HAS_ERROR" == "true" ]]; then
    ok "FAIL-Strategie blockiert Doppel-Import korrekt"
  else
    fail "Doppel-Import haette fehlschlagen muessen, ging aber durch"
  fi
fi

# ---------- 14. Import mit MERGE-Strategie ----------
if [[ -n "$TARGET_COLLECTION_ID" ]]; then
  step "Mutation: importContextCore mit MERGE-Strategie"
  RESP=$(gql 'mutation($coreId: String!, $version: String!, $target: String!, $strategy: ConflictStrategy!) {
    importContextCore(coreId: $coreId, version: $version, targetCollection: $target, strategy: $strategy) {
      coreId version quadsImported embeddingsImported
    }
  }' "$(jq -cn \
    --arg coreId "$CORE_ID" \
    --arg version "$CORE_VERSION" \
    --arg target "$TARGET_COLLECTION_ID" \
    --arg strategy "MERGE" \
    '{coreId: $coreId, version: $version, target: $target, strategy: $strategy}')")
  MERGED_QUADS=$(echo "$RESP" | jq '.data.importContextCore.quadsImported // -1')
  if [[ "$MERGED_QUADS" -ge 0 ]]; then
    ok "MERGE-Import erfolgreich: $MERGED_QUADS Quads"
  else
    fail "MERGE-Import fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ==========================================================================
# TEIL 6: Cleanup
# ==========================================================================

# ---------- 15. Context Core loeschen ----------
step "Mutation: deleteContextCore"
RESP=$(gql 'mutation($coreId: String!, $version: String!) {
  deleteContextCore(coreId: $coreId, version: $version)
}' "$(jq -cn --arg coreId "$CORE_ID" --arg version "$CORE_VERSION" '{coreId: $coreId, version: $version}')")
DELETED=$(echo "$RESP" | jq -r '.data.deleteContextCore // false')
if [[ "$DELETED" == "true" ]]; then
  ok "Context Core geloescht"
else
  fail "deleteContextCore fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
fi

# ---------- 16. Verify: Core nach Delete nicht mehr vorhanden ----------
step "Verify: contextCore nach Delete — erwartet null"
RESP=$(gql 'query($coreId: String!, $version: String!) {
  contextCore(coreId: $coreId, version: $version) { coreId }
}' "$(jq -cn --arg coreId "$CORE_ID" --arg version "$CORE_VERSION" '{coreId: $coreId, version: $version}')")
GOT=$(echo "$RESP" | jq -r '.data.contextCore // "null"')
if [[ "$GOT" == "null" ]]; then
  ok "Context Core nach Delete nicht mehr vorhanden"
else
  fail "Core noch vorhanden nach Delete: $GOT"
fi

# ---------- 17. Ontologie aufraemen ----------
if [[ -n "$ONTOLOGY_KEY" ]]; then
  step "Cleanup: Ontologie loeschen (key=$ONTOLOGY_KEY)"
  RESP=$(gql 'mutation($key: String!) { deleteOntology(key: $key) }' \
    "$(jq -cn --arg key "$ONTOLOGY_KEY" '{key: $key}')")
  DELETED=$(echo "$RESP" | jq -r '.data.deleteOntology // false')
  if [[ "$DELETED" == "true" ]]; then
    ok "Ontologie geloescht"
  else
    fail "deleteOntology fehlgeschlagen: $(echo "$RESP" | jq -c '.errors // .')"
  fi
fi

# ---------- Zusammenfassung ----------
echo
echo "================================================"
echo " Context Core Smoke-Test Ergebnis"
echo "================================================"
echo "  ${GREEN}PASS: $PASS${RESET}"
echo "  ${RED}FAIL: $FAIL${RESET}"
echo "  Core: $CORE_ID@$CORE_VERSION"
echo "  Source: $SOURCE_COLLECTION_ID"
echo "  Target: $TARGET_COLLECTION_ID"
echo "================================================"

if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
