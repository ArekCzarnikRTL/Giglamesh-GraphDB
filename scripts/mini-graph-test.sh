#!/usr/bin/env bash
# Mini-Test: Lade einen kleinen Text (4-5 Saetze) in GraphMesh, warte bis
# alle Extraktoren fertig sind, und zeige was im Graph gelandet ist
# (Triples, Topics, Definitionen, Vektor-Treffer).
#
# Voraussetzung: Backend auf :8083, docker-compose-Infra laeuft.
# Aufruf:
#   ./scripts/mini-graph-test.sh
#   BACKEND_URL=http://localhost:8083 ./scripts/mini-graph-test.sh
#   EXTRACTION_TIMEOUT=600 ./scripts/mini-graph-test.sh
#   KEEP=1 ./scripts/mini-graph-test.sh              # Collection nicht loeschen

set -uo pipefail

BACKEND_URL="${BACKEND_URL:-http://localhost:8083}"
GRAPHQL_URL="$BACKEND_URL/graphql"
EXTRACTION_TIMEOUT="${EXTRACTION_TIMEOUT:-600}"
KEEP="${KEEP:-0}"

G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; B=$'\033[1m'; D=$'\033[2m'; C=$'\033[36m'; N=$'\033[0m'

section() {
  printf "\n%s%s%s\n" "$B$C" "================================================================" "$N"
  printf "%s%s %s%s\n"     "$B$C" ">>" "$*" "$N"
  printf "%s%s%s\n"        "$B$C" "================================================================" "$N"
}
say()  { printf "\n%s-- %s%s\n" "$Y" "$*" "$N"; }
ok()   { printf "   %s[OK]%s %s\n"   "$G" "$N" "$*"; }
warn() { printf "   %s[..]%s %s\n"   "$Y" "$N" "$*"; }
err()  { printf "   %s[!!]%s %s\n"   "$R" "$N" "$*"; }

command -v jq >/dev/null   || { echo "jq fehlt"; exit 2; }
command -v curl >/dev/null || { echo "curl fehlt"; exit 2; }

gql() {
  local q="$1" v="$2"
  local body
  body=$(jq -cn --arg q "$q" --argjson v "$v" '{query:$q, variables:$v}')
  curl -sS -X POST "$GRAPHQL_URL" -H "Content-Type: application/json" -d "$body"
}

# ---------- kleines Test-Dokument ----------
DOC_TEXT="# Arctic Research Glossary

Dr. Elena Vasquez leads the Arctic climate research team at the Oslo Institute.
Permafrost is ground that remains frozen for at least two consecutive years.
A methane release zone refers to an area where trapped methane escapes from thawing soil.
The team uses satellite imagery from NASA to monitor the Svalbard archipelago.
GraphMesh is a knowledge graph platform that turns field reports into a searchable graph."

# Erwartete Entitaeten / Begriffe (Subjects/Objects in den Triples)
EXPECTED_TOKENS=("Vasquez" "Oslo" "Svalbard" "NASA" "GraphMesh" "methane" "Permafrost")

# Erwartete Definitions-Subjects (DefinitionExtractor produziert rdfs:comment)
EXPECTED_DEFS=("Permafrost" "methane" "GraphMesh")

# ---------- 0. Test-Dokument anzeigen ----------
section "Test-Dokument"
echo "$DOC_TEXT" | sed "s/^/  $D|$N /"
printf "\n  Erwartete Entitaeten:   %s%s%s\n" "$C" "${EXPECTED_TOKENS[*]}" "$N"
printf "  Erwartete Definitionen: %s%s%s\n"   "$C" "${EXPECTED_DEFS[*]}"   "$N"

# ---------- 1. Health ----------
section "1. Health-Check"
if curl -sS -X POST "$GRAPHQL_URL" -H "Content-Type: application/json" \
     -d '{"query":"{ __typename }"}' | jq -e '.data.__typename' >/dev/null; then
  ok "GraphQL erreichbar ($GRAPHQL_URL)"
else
  err "GraphQL nicht erreichbar — laeuft das Backend?"
  exit 1
fi

# ---------- 2. Collection ----------
section "2. Collection erstellen"
COLL_NAME="mini-test-$(date +%s)"
RESP=$(gql 'mutation($input: CreateCollectionInput!) {
  createCollection(input: $input) { id name }
}' "$(jq -cn --arg n "$COLL_NAME" '{input:{name:$n, description:"mini graph test", tags:["mini"]}}')")
COLLECTION_ID=$(echo "$RESP" | jq -r '.data.createCollection.id // empty')
[ -n "$COLLECTION_ID" ] || { err "createCollection fehlgeschlagen: $(echo "$RESP" | jq -c .)"; exit 1; }
ok "Collection: $COLLECTION_ID ($COLL_NAME)"

# ---------- 3. Dokument hochladen ----------
section "3. Markdown-Dokument hochladen (${#DOC_TEXT} Zeichen)"
B64=$(printf '%s' "$DOC_TEXT" | base64 | tr -d '\n')
RESP=$(gql 'mutation($input: UploadDocumentInput!) {
  uploadDocument(input: $input) { id title state }
}' "$(jq -cn --arg cid "$COLLECTION_ID" --arg c "$B64" \
      '{input:{collectionId:$cid, title:"Arctic Research Note.md", mimeType:"text/markdown", content:$c}}')")
DOCUMENT_ID=$(echo "$RESP" | jq -r '.data.uploadDocument.id // empty')
[ -n "$DOCUMENT_ID" ] || { err "uploadDocument fehlgeschlagen: $(echo "$RESP" | jq -c .)"; exit 1; }
ok "Dokument: $DOCUMENT_ID"

# ---------- 4. Warten bis Extraktoren wirklich fertig sind ----------
section "4. Warte auf Extraktion (max ${EXTRACTION_TIMEOUT}s)"
STATE=""; TRIPLES=0; VECTORS=0
ITERS=$((EXTRACTION_TIMEOUT / 5))
for i in $(seq 1 $ITERS); do
  sleep 5

  STATE=$(gql 'query($id: ID!){ document(id:$id){ state } }' \
    "$(jq -cn --arg id "$DOCUMENT_ID" '{id:$id}')" | jq -r '.data.document.state // empty')

  TRIPLES=$(gql 'query($cid: ID!){ triples(collectionId:$cid){ subject } }' \
    "$(jq -cn --arg cid "$COLLECTION_ID" '{cid:$cid}')" | jq '.data.triples | length // 0')

  VECTORS=$(gql 'query($cid: ID!, $q: String!){ vectorSearch(collectionId:$cid, query:$q, limit:5){ id } }' \
    "$(jq -cn --arg cid "$COLLECTION_ID" --arg q "Arctic research Svalbard" '{cid:$cid, q:$q}')" \
    | jq '.data.vectorSearch | length // 0')

  printf "   %s[%3ss]%s state=%-10s triples=%-4s vectors=%-2s\n" \
    "$D" "$((i*5))" "$N" "$STATE" "$TRIPLES" "$VECTORS"

  if [ "$STATE" = "FAILED" ]; then break; fi
  # done = state=EXTRACTED/READY + Triples da + Vectors da
  if [ "$TRIPLES" -gt 0 ] && [ "$VECTORS" -gt 0 ]; then
    # Ein bisschen Puffer damit topic/def auch nachkommen
    sleep 8
    break
  fi
done

if [ "$STATE" = "FAILED" ]; then
  err "Extraktion FAILED"
elif [ "$TRIPLES" -gt 0 ] && [ "$VECTORS" -gt 0 ]; then
  ok "Extraktion fertig (state=$STATE, triples=$TRIPLES, vectors=$VECTORS)"
else
  warn "Timeout — weiter pruefen mit dem was da ist (state=$STATE)"
fi

# ---------- 5. Triples anzeigen ----------
section "5. Extrahierte Triples (S -[P]-> O)"
RESP=$(gql 'query($cid: ID!){
  triples(collectionId:$cid){ subject predicate object }
}' "$(jq -cn --arg cid "$COLLECTION_ID" '{cid:$cid}')")
echo "$RESP" | jq -r '.data.triples[]? | "   - \(.subject)  —[\(.predicate)]→  \(.object)"' | head -30
TRIPLE_COUNT=$(echo "$RESP" | jq '.data.triples | length // 0')
echo "   $D(${TRIPLE_COUNT} triples insgesamt)$N"

# ---------- 6. Topics + Definitionen ----------
section "6. Topics & Definitionen"

ALL_TRIPLES=$(gql 'query($cid: ID!){ triples(collectionId:$cid){ subject predicate object } }' \
  "$(jq -cn --arg cid "$COLLECTION_ID" '{cid:$cid}')")

TOPIC_COUNT=$(echo "$ALL_TRIPLES" \
  | jq '[.data.triples[]? | select(.predicate | test("topic"; "i"))] | length')
DEF_COUNT=$(echo "$ALL_TRIPLES" \
  | jq '[.data.triples[]? | select(.predicate | test("rdf-schema#comment"; "i"))] | length')

ok "Topic-Triples:      $TOPIC_COUNT"
ok "Definition-Triples: $DEF_COUNT (Praedikat rdfs:comment)"

if [ "$DEF_COUNT" -gt 0 ]; then
  say "Definitionen im Detail:"
  echo "$ALL_TRIPLES" \
    | jq -r '.data.triples[]? | select(.predicate | test("rdf-schema#comment"; "i"))
              | "   * \(.subject)\n       = \(.object)"'
fi

if [ "$TOPIC_COUNT" -gt 0 ]; then
  say "Topics im Detail:"
  echo "$ALL_TRIPLES" \
    | jq -r '.data.triples[]? | select(.predicate | test("topic"; "i"))
              | "   * \(.object)"' | sort -u | head -10
fi

# ---------- 7. Erwartete Begriffe + Definitions-Subjects ----------
section "7. Coverage-Check"

ALL_FLAT=$(echo "$ALL_TRIPLES" \
  | jq -r '.data.triples[]? | "\(.subject) \(.predicate) \(.object)"')

say "Allgemeine Entitaeten:"
FOUND=0
for tok in "${EXPECTED_TOKENS[@]}"; do
  if echo "$ALL_FLAT" | grep -qi -- "$tok"; then
    ok "$tok"
    FOUND=$((FOUND+1))
  else
    warn "$tok (fehlt)"
  fi
done

say "Definitions-Subjects (sollten als rdfs:comment auftauchen):"
DEF_FLAT=$(echo "$ALL_TRIPLES" \
  | jq -r '.data.triples[]? | select(.predicate | test("rdf-schema#comment"; "i"))
            | "\(.subject) \(.object)"')
DEFS_FOUND=0
for tok in "${EXPECTED_DEFS[@]}"; do
  if echo "$DEF_FLAT" | grep -qi -- "$tok"; then
    ok "$tok"
    DEFS_FOUND=$((DEFS_FOUND+1))
  else
    warn "$tok (keine Definition gefunden)"
  fi
done

# ---------- 8. Sanity: Graph-RAG beantwortet sinnvolle Frage ----------
section "8. Graph-RAG"
say "Frage: 'Who leads the Arctic climate research team?'"
RESP=$(gql 'query($input: GraphRagInput!){
  graphRag(input:$input){ answer retrievedEdgeCount }
}' "$(jq -cn --arg cid "$COLLECTION_ID" \
     '{input:{question:"Who leads the Arctic climate research team?", collectionId:$cid}}')")
ANS=$(echo "$RESP" | jq -r '.data.graphRag.answer // empty')
EDGES=$(echo "$RESP" | jq '.data.graphRag.retrievedEdgeCount // 0')
if [ -n "$ANS" ] && [ "$EDGES" -gt 0 ]; then
  ok "RAG: $EDGES Edges | Antwort: ${ANS:0:140}..."
else
  err "RAG lieferte keine belastbare Antwort (edges=$EDGES)"
fi

# ---------- 9. Vector-Search ----------
section "9. Vector-Search"
say "Query: 'satellite methane polar'"
RESP=$(gql 'query($cid: ID!, $q: String!){
  vectorSearch(collectionId:$cid, query:$q, limit:5){ id score }
}' "$(jq -cn --arg cid "$COLLECTION_ID" --arg q "satellite methane polar" '{cid:$cid, q:$q}')")
HITS=$(echo "$RESP" | jq '.data.vectorSearch | length // 0')
SCORES=$(echo "$RESP" | jq -r '[.data.vectorSearch[]?.score] | map(tostring) | join(", ")')
[ "$HITS" -gt 0 ] && ok "$HITS Treffer, scores=[$SCORES]" || err "0 Vector-Treffer"

# ---------- Cleanup ----------
# if [ "$KEEP" != "1" ]; then
#   say "Cleanup"
#   gql 'mutation($id: ID!){ deleteDocument(id:$id) }' \
#     "$(jq -cn --arg id "$DOCUMENT_ID" '{id:$id}')" >/dev/null
#   gql 'mutation($id: ID!){ deleteCollection(id:$id) }' \
#     "$(jq -cn --arg id "$COLLECTION_ID" '{id:$id}')" >/dev/null
#   ok "Collection & Dokument entfernt"
# else
#   warn "KEEP=1 — Collection $COLLECTION_ID bleibt stehen"
# fi

# ---------- Verdict ----------
section "Zusammenfassung"
printf "  %-26s %s%d%s\n" "Triples gesamt:"        "$C" "$TRIPLE_COUNT" "$N"
printf "  %-26s %s%d%s\n" "Topic-Triples:"         "$C" "$TOPIC_COUNT"  "$N"
printf "  %-26s %s%d%s\n" "Definition-Triples:"    "$C" "$DEF_COUNT"    "$N"
printf "  %-26s %s%d%s\n" "Vector-Treffer:"        "$C" "$HITS"         "$N"
printf "  %-26s %s%d/%d%s\n" "Entitaeten gefunden:"  "$C" "$FOUND"      "${#EXPECTED_TOKENS[@]}" "$N"
printf "  %-26s %s%d/%d%s\n" "Definitionen gefunden:" "$C" "$DEFS_FOUND" "${#EXPECTED_DEFS[@]}"  "$N"
printf "  %-26s %s%s%s\n" "Graph-RAG Edges:"       "$C" "${EDGES:-0}"   "$N"

echo
if [ "$TRIPLE_COUNT" -gt 5 ] && [ "$HITS" -gt 0 ] \
   && [ "$FOUND" -ge 4 ] && [ "$DEFS_FOUND" -ge 2 ]; then
  printf "  %sVerdict: OK — Graph wurde sinnvoll aufgebaut.%s\n" "$G$B" "$N"
  exit 0
else
  printf "  %sVerdict: unzureichend — Lags pruefen: ./scripts/extraction-monitor.sh%s\n" "$R$B" "$N"
  exit 1
fi
