# Spec: Fix `GraphRagService.retrieveSubgraph` (Feature 36)

**Datum:** 2026-04-07
**Feature:** [36-fix-retrieveSubgraph](../../features/36-fix-retrieveSubgraph.md)
**Typ:** Bugfix (rein lesend, keine Schema-Änderung)

## Problem

`GraphRagService.retrieveSubgraph` (`src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt:96-122`) übergibt rohe `chunk_id`-Strings (z. B. `doc-<uuid>/p1/c1`) direkt an `quadStore.findByEntities`. Diese IDs existieren in keinem gespeicherten Quad — Knowledge-Triples haben Subjekte wie `http://graphmesh.io/entity/<hash>`, Provenance-Quads referenzieren Chunks als `urn:chunk:doc-<uuid>/p1/c1`. Folge: `graphRag` und der Graph-Anteil von `nlpQuery` liefern immer 0 Edges und fallen auf "No relevant knowledge found for this question." zurück.

## Lösung — Korrekter Datenfluss

```
vectorSearch(question)
   └─▶ payload.chunk_id = "doc-<uuid>/p1/c1"
        └─▶ chunkURN = "urn:chunk:" + chunk_id
             └─▶ findSubgraphsForChunks(collection, [chunkURN])
                  ├─▶ Provenance-Subgraphen (urn:graphmesh:subgraph:<uuid>)
                  └─▶ findQuotedTriplesForSubgraphs(collection, subgraphURIs)
                       └─▶ Quoted-Triple-StoredQuads (otype = T)
                            └─▶ entpacken via QuadConverter → Entity-URIs
                                 └─▶ findByEntities(collection, entityURIs)
                                      └─▶ N Knowledge-Edges  ✅
```

## Designentscheidungen

### 1. Neue Lookups als Default-Methoden auf `QuadStore`

Beide neuen Methoden lassen sich vollständig über das bestehende `query(QuadQuery)`-Primitiv ausdrücken. Daher werden sie als **Default-Methoden auf dem Interface** implementiert. Vorteile:

- Keine neuen prepared CQL-Statements in `CassandraQuadStore` (`QuadStoreService.kt`)
- Keine Schema-Änderung
- Pure JVM-Unit-Tests möglich (kein Cassandra/Testcontainers)
- Folgt dem YAGNI-Prinzip

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
     * @return Liste der gefundenen `urn:graphmesh:subgraph:<uuid>`-Identifier (distinct).
     */
    fun findSubgraphsForChunks(collection: String, chunkUrns: List<String>): List<String> {
        if (chunkUrns.isEmpty()) return emptyList()
        return chunkUrns.flatMap { chunkUrn ->
            query(collection, QuadQuery(
                predicate = ProvenanceNamespaces.PROV_WAS_DERIVED_FROM,
                objectValue = chunkUrn,
                dataset = NamedGraph.SOURCE
            )).map { it.subject }
        }.distinct()
    }

    /**
     * Liefert alle Quoted-Triple-Quads (`tg:contains <<s p o>>`),
     * die zu den übergebenen Subgraph-URIs gehören.
     */
    fun findQuotedTriplesForSubgraphs(collection: String, subgraphUris: List<String>): List<StoredQuad> {
        if (subgraphUris.isEmpty()) return emptyList()
        return subgraphUris.flatMap { subgraphUri ->
            query(collection, QuadQuery(
                subject = subgraphUri,
                predicate = ProvenanceNamespaces.TG_CONTAINS,
                dataset = NamedGraph.SOURCE
            )).filter { it.objectType == ObjectType.QUOTED_TRIPLE }
        }
    }
}
```

### 2. Quoted Triples vor Rückgabe auspacken

`findQuotedTriplesForSubgraphs` liefert `StoredQuad`s mit `objectType=QUOTED_TRIPLE` und `objectValue` im Format `<<s|p|o>>`. Damit kann `selectEdges`/`synthesizeAnswer` nichts anfangen — diese Komponenten erwarten echte `subject --predicate--> object`-Edges.

`GraphRagService.retrieveSubgraph` entpackt daher die Quoted Triples zu synthetischen `StoredQuad`s:

```kotlin
private fun unwrapQuotedTriple(quad: StoredQuad): StoredQuad? {
    if (quad.objectType != ObjectType.QUOTED_TRIPLE) return null
    val rdfQuad = QuadConverter.fromStoredQuad(quad)
    val quoted = (rdfQuad.objectTerm as? RdfTerm.QuotedTriple) ?: return null
    val inner = quoted.triple
    return StoredQuad(
        subject = (inner.subject as? RdfTerm.Uri)?.value ?: return null,
        predicate = inner.predicate.value,
        objectValue = (inner.objectTerm as? RdfTerm.Uri)?.value
            ?: (inner.objectTerm as? RdfTerm.Literal)?.value
            ?: return null,
        dataset = NamedGraph.DEFAULT,
        objectType = if (inner.objectTerm is RdfTerm.Uri) ObjectType.URI else ObjectType.LITERAL
    )
}
```

### 3. Neue `retrieveSubgraph`-Implementierung

```kotlin
internal fun retrieveSubgraph(query: GraphRagQuery): List<StoredQuad> {
    val embeddingModel = resolveLlmModel(embeddingConfig.model)
    val embedding = runBlocking { embeddingProvider.embed(query.question, embeddingModel) }
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

    // Phase 1c: Subgraphen → Quoted-Triple-Rohquads
    val quotedQuads = quadStore.findQuotedTriplesForSubgraphs(query.collectionId, subgraphUris)

    // Phase 1d: Quoted Triples auspacken zu echten Knowledge-Edges
    val innerTriples = quotedQuads.mapNotNull { unwrapQuotedTriple(it) }

    // Phase 1e: 1-Hop-Expansion über Entity-URIs
    val entityUris = innerTriples
        .flatMap { listOf(it.subject, it.objectValue) }
        .filter { it.startsWith("http://graphmesh.io/entity/") }
        .distinct()

    val expandedEdges = if (entityUris.isNotEmpty()) {
        quadStore.findByEntities(query.collectionId, entityUris)
    } else {
        emptyList()
    }

    return (innerTriples + expandedEdges).distinct().take(query.maxEdges)
}
```

`retrieveSubgraph` wird auf `internal` umgestellt, damit der Test die Methode direkt aufrufen kann.

## Betroffene Dateien

### Backend

| Datei | Änderung |
|---|---|
| `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt` | Zwei neue Default-Methoden + Imports für `ProvenanceNamespaces`, `NamedGraph`, `ObjectType` |
| `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt` | `retrieveSubgraph` neu (siehe oben), neue Helper `unwrapQuotedTriple`, `internal`-Sichtbarkeit |

**Nicht angefasst:** `QuadStoreService.kt` (CassandraQuadStore), Provenance-Schema, Quoted-Triple-Serialisierung.

### Tests

| Datei | Änderung |
|---|---|
| `src/test/kotlin/com/agentwork/graphmesh/storage/QuadStoreDefaultMethodsTest.kt` (NEU) | Tests für `findSubgraphsForChunks` + `findQuotedTriplesForSubgraphs` mit kleinem in-memory `QuadStore` |
| `src/test/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagServiceTest.kt` | Erweitern um End-to-End-Test für `retrieveSubgraph` mit Fake `QuadStore` + Fake `VectorStore` + Fake `LLMEmbeddingProvider` |
| `smoke-test.sh` | graphRag-Assertion verschärfen: `retrievedEdgeCount > 0` UND Antwort ≠ "No relevant knowledge..." |

### Test-Doubles

- **In-memory `QuadStore`**: implementiert `query()` über eine `MutableList<StoredQuad>` mit Filter-Logik analog zu `CassandraQuadStore.matchesFilter`. Damit greifen die Default-Methoden aufs reale Interface zurück.
- **Fake `VectorStore`**: liefert vorkonfigurierte `SearchResult`s mit `chunk_id` im Payload.
- **Fake `LLMEmbeddingProvider`**: liefert konstanten Embedding-Vektor.
- **Fake `PromptExecutor`** + **Fake `ExplainabilityEventProducer`**: für `retrieveSubgraph` nicht aufgerufen, nur Konstruktor-Erfüllung. Da der Test nur `retrieveSubgraph` direkt aufruft, müssen die LLM-Pfade nichts tun.

## Akzeptanzkriterien

- [ ] `GraphRagService.retrieveSubgraph` liefert für eine Collection mit ≥1 extrahierten Triple und passenden Vector-Hits **mindestens eine** Edge zurück.
- [ ] Unit-Test deckt Happy-Path (1 Chunk → 1 Subgraph → 3 Quoted Triples → entpackte Edges + 1-Hop-Expansion).
- [ ] Edge-Case-Tests: leere `chunkUrns`-Liste, unbekannter Chunk (keine Subgraphen), Subgraph ohne `tg:contains`-Quads.
- [ ] `smoke-test.sh` produziert `retrievedEdgeCount > 0` und Antworttext ≠ "No relevant knowledge found for this question."
- [ ] `nlpQuery` mit Intent `GRAPH_QUERY` oder `HYBRID` profitiert automatisch (nutzt denselben `GraphRagService`).
- [ ] `./gradlew test` bleibt grün.
- [ ] Provenance- und Quoted-Triple-Schemas unverändert.
- [ ] `documentRag`, `triples`, `vectorSearch` unberührt.

## Risiken & Mitigation

| Risiko | Mitigation |
|---|---|
| `query()` mit `predicate+objectValue+dataset` deckt Pattern 9 oder 10 ab — funktioniert das in `CassandraQuadStore.resolvePattern`? | Pattern 9 (`?,P,O,D`) und 10 (`?,P,O,?`) sind in `prepareQueryStatements` abgedeckt → entity=O, role='O', AND p=?. ✅ |
| `query()` mit `subject+predicate+dataset` (kein object)? | Pattern 3 (`S,P,?,D`) abgedeckt → entity=S, role='S', AND p=?, dataset-Filter in-memory. ✅ |
| Quoted-Triple-Objekt ist Literal statt URI | `unwrapQuotedTriple` behandelt beide Fälle. Entity-URI-Filter (`startsWith http://graphmesh.io/entity/`) sortiert Literals automatisch aus dem Expansion-Schritt. |
| Bestehender Smoke-Test schlägt vor dem Fix fehl | Erst Backend-Fix, dann Smoke-Test schärfen. Reihenfolge im Plan beachten. |

## Out of Scope

- Performance-Optimierung der pro-Chunk-Loops (linear in `|chunkUrns|`, akzeptabel bei ≤50 Vector-Hits)
- Caching von Subgraph-Lookups
- Support für Quoted-Triples mit Blank-Node-Subjekten (aktuell nicht produziert)
- Änderungen am Provenance-Schema oder am Quoted-Triple-Serialisierungsformat
