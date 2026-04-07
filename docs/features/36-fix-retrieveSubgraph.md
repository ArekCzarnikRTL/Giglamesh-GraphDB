# Feature 36: Fix `GraphRagService.retrieveSubgraph` (Chunk → Knowledge-Edges)

## Problem

`GraphRagService` liefert seit Bestehen des Features `Retrieved 0 edges` zurueck — egal wie viele Triples in der Collection liegen. Damit faellt jede `graphRag`-Antwort auf die Default-Antwort `"No relevant knowledge found for this question."` zurueck, und der Graph-Anteil von `nlpQuery` (Intent `HYBRID` / `GRAPH_QUERY`) bleibt ebenfalls leer. Beobachtet z.B. im Smoke-Test mit 48 extrahierten Triples und 3 erfolgreichen Vektor-Treffern: graphRag findet trotzdem nichts.

Ursache ist ein Identifier-Mismatch zwischen Vector-Payload, Provenance-Schema und Knowledge-Triple-Subjekten. `GraphRagService.retrieveSubgraph` (`src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt:96-122`) nimmt den `chunk_id` aus dem Vector-Payload (Format `doc-<uuid>/p1/c1`) und uebergibt ihn 1:1 an `quadStore.findByEntities`, das nach `subject == id OR object == id` sucht. Diese ID-Form existiert aber in keinem gespeicherten Quad — Knowledge-Triples haben Subjekte wie `http://graphmesh.io/entity/<hash>`, und Provenance-Quads referenzieren Chunks mit dem Prefix `urn:chunk:doc-<uuid>/p1/c1`. Es gibt also nie einen Treffer.

## Ziel

Den Pfad **Vector-Hit → Chunk → Provenance-Subgraph → Knowledge-Edges** korrekt implementieren, sodass `graphRag` und der Graph-Anteil von `nlpQuery` echte Antworten auf Basis des extrahierten Wissens liefern.

1. **Chunk-URN-Lookup im QuadStore** — neue Methode, die Provenance-Subgraphen findet, deren `prov:wasDerivedFrom` auf `urn:chunk:<chunkId>` zeigt.
2. **Quoted-Triple-Entpacker** — aus den `tg:contains <<s p o>>`-Quads in der `SOURCE`-named-graph die ursprünglichen Knowledge-Triples (Subject-Entity-URIs) ableiten.
3. **`retrieveSubgraph`-Refactoring** — neuer Pfad: chunkIds → chunkURNs → Subgraphen → Quoted Triples → Entity-URIs → `findByEntities` mit den **Entity-URIs** statt mit den ChunkIds.
4. **Smoke-Test-Validierung** — `smoke-test.sh` muss am Ende eine inhaltlich sinnvolle `graphRag`-Antwort fuer "Was ist GraphMesh?" liefern (nicht "No relevant knowledge found").

## Voraussetzungen

| Abhaengigkeit                                  | Status         | Blocker? |
|------------------------------------------------|----------------|----------|
| Feature 07: RDF Graph Model (QuadStore, Quoted Triples) | Implementiert  | Nein     |
| Feature 13: Document Embeddings (Vector-Payload mit `chunk_id`) | Implementiert  | Nein     |
| Feature 15: Graph RAG (vorhandenes Service-Skelett)             | Implementiert (broken) | Nein     |
| Feature 29: Extraction-Time Provenance (Subgraph + wasDerivedFrom) | Implementiert  | Nein     |

## Architektur

### Aktuelle (kaputte) Datenflusskette

```
vectorSearch(question)
   └─▶ payload.chunk_id = "doc-<uuid>/p1/c1"
        └─▶ findByEntities(collectionId, [chunk_id])
             └─▶ query: subject==id OR object==id
                  └─▶ 0 Treffer  ❌
```

### Korrekte Datenflusskette

```
vectorSearch(question)
   └─▶ payload.chunk_id = "doc-<uuid>/p1/c1"
        └─▶ chunkURN = "urn:chunk:" + chunk_id
             └─▶ findSubgraphsForChunks(collectionId, [chunkURN])
                  ├─▶ Provenance-Subgraphen (urn:graphmesh:subgraph:<uuid>)
                  └─▶ Quoted Triples (s p o) aus SOURCE graph
                       └─▶ Entity-URIs extrahiert
                            └─▶ findByEntities(collectionId, entityUris)
                                 └─▶ N Knowledge-Edges  ✅
```

### Datenmodell-Recap (Belege im Code)

| Speicherort                  | Beispiel                                                            | Code-Referenz                                                            |
|------------------------------|---------------------------------------------------------------------|--------------------------------------------------------------------------|
| Vector-Payload (Qdrant)      | `chunk_id = "doc-<uuid>/p1/c1"`                                     | `EmbeddingService.kt:42-50`                                              |
| Knowledge-Quad (DEFAULT)     | `<entity/063fe4bf...> --label--> "GraphMesh"`                       | `RelationshipExtractorService.kt:64-70`                                  |
| Provenance-Link (SOURCE)     | `<subgraph/xyz> --prov:wasDerivedFrom--> <urn:chunk:doc-.../p1/c1>` | `ProvenanceService.kt:30-35`                                             |
| Quoted Triple (SOURCE)       | `<subgraph/xyz> --tg:contains--> <<s p o>>`                         | `ProvenanceService.kt:20-27`                                             |

### Subsection 1: Neue QuadStore-Methode

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt
interface QuadStore {
    // ... bestehende Methoden ...

    /**
     * Findet alle Provenance-Subgraphen, deren `prov:wasDerivedFrom`
     * auf einen der angegebenen Chunk-URNs zeigt.
     *
     * @param chunkUrns Liste vollqualifizierter URNs in der Form
     *                  `urn:chunk:doc-<uuid>/p<n>/c<m>`.
     * @return Liste der gefundenen `urn:graphmesh:subgraph:<uuid>`-Identifier.
     */
    fun findSubgraphsForChunks(collection: String, chunkUrns: List<String>): List<String>

    /**
     * Liefert alle Quoted Triples (`tg:contains <<s p o>>`),
     * die zu den uebergebenen Subgraph-URIs gehoeren.
     *
     * @return Flach entpackte Liste der ursprünglichen Knowledge-Triples
     *         (mit Entity-URIs als Subjekte/Objekte).
     */
    fun findQuotedTriplesForSubgraphs(collection: String, subgraphUris: List<String>): List<StoredQuad>
}
```

### Subsection 2: Neue `retrieveSubgraph`-Implementierung

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt
private fun retrieveSubgraph(query: GraphRagQuery): List<StoredQuad> {
    val embeddingModel = resolveLlmModel(embeddingConfig.model)

    val embedding = runBlocking {
        embeddingProvider.embed(query.question, embeddingModel)
    }
    val queryVector = FloatArray(embedding.size) { embedding[it].toFloat() }

    val searchResults = vectorStore.search(
        collection = query.collectionId,
        queryVector = queryVector,
        limit = 50
    )

    // Phase 1a: Vector-Hits → vollqualifizierte Chunk-URNs
    val chunkUrns = searchResults
        .mapNotNull { it.payload["chunk_id"]?.toString() }
        .map { "urn:chunk:$it" }
        .distinct()

    if (chunkUrns.isEmpty()) return emptyList()

    // Phase 1b: Chunks → Provenance-Subgraphen
    val subgraphUris = quadStore.findSubgraphsForChunks(query.collectionId, chunkUrns)
    if (subgraphUris.isEmpty()) return emptyList()

    // Phase 1c: Subgraphen → Quoted Triples (= Knowledge-Quads)
    val quotedTriples = quadStore.findQuotedTriplesForSubgraphs(query.collectionId, subgraphUris)

    // Phase 1d: Aus den entpackten Triples die beteiligten Entity-URIs sammeln
    //           und damit alle weiteren Edges holen (1-Hop Expansion).
    val entityUris = quotedTriples
        .flatMap { listOf(it.subject, it.objectValue) }
        .filter { it.startsWith("http://graphmesh.io/entity/") }
        .distinct()

    val expandedEdges = if (entityUris.isNotEmpty()) {
        quadStore.findByEntities(query.collectionId, entityUris)
    } else {
        emptyList()
    }

    return (quotedTriples + expandedEdges).distinct().take(query.maxEdges)
}
```

### Subsection 3: Smoke-Test als Akzeptanz-Check

`smoke-test.sh` validiert nicht nur, dass `graphRag` einen String zurueckgibt, sondern dass die Antwort substantiell ist:

```bash
# zusaetzliche Pruefung im graphRag-Step
ANS=$(echo "$RESP" | jq -r '.data.graphRag.answer // empty')
EDGES=$(echo "$RESP" | jq '.data.graphRag.retrievedEdgeCount // 0')
if [[ -n "$ANS" && "$EDGES" -gt 0 && "$ANS" != *"No relevant knowledge"* ]]; then
  ok "graphRag-Antwort ($EDGES edges): ${ANS:0:80}..."
else
  fail "graphRag liefert leere Antwort oder 0 edges (edges=$EDGES)"
fi
```

## Betroffene Dateien

### Backend

| Datei                                                                                          | Aenderung                                                                          |
|------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt`                                 | Zwei neue Methoden: `findSubgraphsForChunks`, `findQuotedTriplesForSubgraphs`      |
| `src/main/kotlin/com/agentwork/graphmesh/storage/cassandra/CassandraQuadStore.kt`              | Konkrete Cassandra-Implementierung der neuen Methoden (predicate+object-Index)     |
| `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`                    | `retrieveSubgraph` komplett neu (siehe Subsection 2)                               |

### Tests

| Datei                                                                                       | Aenderung                                                                           |
|---------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagServiceTest.kt`             | NEU — Unit-Test mit gemocktem QuadStore + VectorStore, prueft Pfad mit echten Daten |
| `src/test/kotlin/com/agentwork/graphmesh/storage/cassandra/CassandraQuadStoreTest.kt`       | Erweitern um Tests fuer die zwei neuen Lookup-Methoden                              |
| `smoke-test.sh`                                                                             | graphRag-Assertion verschaerfen (siehe Subsection 3)                                |

## Akzeptanzkriterien

- [ ] `GraphRagService.retrieveSubgraph` liefert fuer eine Collection mit ≥1 extrahierten Triple und passenden Vector-Hits **mindestens eine** Edge zurueck.
- [ ] `smoke-test.sh` produziert eine `graphRag`-Antwort mit `retrievedEdgeCount > 0` und Antworttext != "No relevant knowledge found for this question."
- [ ] `nlpQuery` mit Intent `GRAPH_QUERY` oder `HYBRID` liefert im Graph-Anteil ebenfalls Edges.
- [ ] Unit-Tests fuer `findSubgraphsForChunks` und `findQuotedTriplesForSubgraphs` decken den Happy-Path und Edge-Cases (leere Liste, unbekannter Chunk) ab.
- [ ] Vorhandene Tests (`./gradlew test`) bleiben gruen.
- [ ] Die Provenance- und Quoted-Triple-Schemas (`Feature 29`) bleiben unveraendert — der Fix ist rein lesend.
- [ ] Bestehende Funktionalitaet bleibt unberuehrt (`documentRag`, `triples`, `vectorSearch`).
