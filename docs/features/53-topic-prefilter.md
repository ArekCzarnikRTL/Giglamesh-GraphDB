# Feature 53: Topic/Tag Pre-Filter fuer Retrieval

## Problem

GraphRAG und DocumentRAG filtern aktuell nur auf **Collection-Ebene** vor:
Die Vektorsuche durchsucht den gesamten Qdrant-Namespace der Collection
und der Graph-Retriever laeuft ueber alle Triples der Collection. Fuer
grosse Collections (>10k Dokumente) fuehrt das zu:

1. **Lautes Retrieval.** Thematisch irrelevante Chunks landen im Kontext,
   die nur zufaellig semantisch aehnlich aussehen.
2. **Langsame Antworten.** Der Graph-Retriever geht unnoetig viele Kanten
   durch, bis er die relevanten findet.
3. **Schlechtere Antwortqualitaet.** Mehr Rauschen im LLM-Kontext → LLM
   verwechselt Themen oder waehlt das falsche Dokument als Quelle.

MemPalace's "Spatial Pre-Filter"-Idee (Wings → Rooms → Halls) zeigt:
**Vor** der semantischen Suche erst strukturell vorfiltern gewinnt Qualitaet
*und* Latenz. GraphMesh hat die Topics bereits aus Feature 38
(TopicExtractor) und Tags aus dem Collection-Modell — sie werden nur noch
nicht zum Vorfiltern genutzt.

## Ziel

Ein optionaler **Topic-Filter-Parameter** an jedem Retrieval-Entry-Point,
der die Suche auf thematisch passende Chunks/Subgraphen einschraenkt —
**bevor** Embedding-Suche oder Graph-Traversierung laeuft.

1. **Topic-Index** — Schnelle Lookup-Struktur, die Chunks/Entities nach
   Topic-Tag gruppiert (Cassandra-Sekundaerindex ODER in-memory Cache).
2. **Retrieval-Filter** — GraphRAG und DocumentRAG akzeptieren
   `topicFilter: [String]` (Whitelist) und filtern *vor* Qdrant/Graph-Traversal.
3. **Auto-Suggestion** — NLP-Query erkennt in der Frage erwaehnte Topics und
   setzt den Filter automatisch.
4. **Frontend** — Topic-Facetten in der Suche (klickbare Chips, die den
   Filter einschraenken).

## Voraussetzungen

| Abhaengigkeit                  | Status       | Blocker? |
|--------------------------------|--------------|----------|
| Feature 04 (Qdrant)            | Implementiert | Ja       |
| Feature 07 (RDF Graph Model)   | Implementiert | Ja       |
| Feature 15 (Graph RAG)         | Implementiert | Ja       |
| Feature 16 (Document RAG)      | Implementiert | Ja       |
| Feature 38 (Topic Extractor)   | Implementiert | Ja       |
| Feature 18 (NLP Query Service) | Implementiert | Nein (Auto-Suggest wertvoll aber optional) |

## Architektur

### Topic-Indexierung

Der `TopicExtractor` schreibt schon heute Triples mit Praedikat
`http://graphmesh.io/ontology/hasTopic`. Neu: ein **Sekundaerindex** in
Cassandra, der pro Collection (topic → chunkIds) mapped.

```cql
CREATE TABLE chunks_by_topic (
  collection_id text,
  topic         text,
  chunk_id      text,
  confidence    double,
  PRIMARY KEY ((collection_id, topic), confidence, chunk_id)
) WITH CLUSTERING ORDER BY (confidence DESC);
```

`TopicExtractor` schreibt neben den Triples zusaetzlich in diese Tabelle.
Kein Breaking-Change fuer bestehende Daten — alte Chunks bekommen ihren
Eintrag erst bei naechster Extraktion oder einem optionalen Reindex-Job.

### Qdrant Payload-Filter

Embeddings in Qdrant bekommen ein neues Payload-Feld `topics: [string]`.
Bei Vektorsuche kann Qdrant bereits nativ auf Payload-Feldern filtern —
**vor** der Distanzberechnung, deshalb keine Latenz-Strafe:

```kotlin
val filter = if (topicFilter.isNotEmpty()) {
    Filter.newBuilder()
        .addMust(Condition.newBuilder()
            .setField(FieldCondition.newBuilder()
                .setKey("topics")
                .setMatch(Match.newBuilder().addAnyList(topicFilter))))
        .build()
} else null

qdrantClient.search(
    SearchPoints.newBuilder()
        .setCollectionName(collectionName)
        .setVector(questionEmbedding)
        .setLimit(topK.toLong())
        .also { if (filter != null) it.setFilter(filter) }
        .build()
)
```

### Retrieval-Services

```kotlin
data class GraphRagQuery(
    val question: String,
    val collectionId: String,
    val maxEdges: Int = 150,
    val topicFilter: List<String> = emptyList()   // NEU
)
```

`GraphRagService.retrieveSubgraph(...)`:
1. Wenn `topicFilter.isNotEmpty()`: **erst** die relevanten Chunk-IDs via
   `chunksByTopicRepo.findChunks(collectionId, topicFilter)` holen.
2. Vektorsuche NUR innerhalb dieser Chunk-IDs (Qdrant-Payload-Filter).
3. Anker-Entities ausschliesslich aus diesen Chunks.
4. Graph-Traversal vom gefilterten Anker-Set aus wie bisher.

`DocumentRagService.query(...)` analog — nur Schritt 1–2.

### Auto-Suggest im NLP-Service

```kotlin
// com.agentwork.graphmesh.nlp
@Component
class TopicDetector(
    private val topicRepo: ChunksByTopicRepository,
    private val embeddingProvider: LLMEmbeddingProvider
) {
    /**
     * Sucht in der Frage nach Wortteilen, die zu bekannten Topics der
     * Collection matchen (exakt + semantische Nearest-Neighbor auf den
     * Topic-Labels).
     */
    fun detect(collectionId: String, question: String): List<String> {
        val knownTopics = topicRepo.listDistinctTopics(collectionId)
        // 1. Exact substring match
        val exact = knownTopics.filter { question.contains(it, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact

        // 2. Embedding-Aehnlichkeit (Top-2, Threshold 0.7)
        return semanticMatch(knownTopics, question, threshold = 0.7, limit = 2)
    }
}
```

`NlpQueryService` setzt den detektierten Topic-Filter automatisch,
**wenn** der User nichts explizit angegeben hat. Bei expliziter Angabe
gilt User-Input.

### GraphQL

```graphql
input GraphRagInput {
  question: String!
  collectionId: ID!
  maxEdges: Int
  topicFilter: [String!]    # NEU
}

input DocumentRagInput {
  question: String!
  collectionId: ID!
  topK: Int
  topicFilter: [String!]    # NEU
}

extend type Query {
  # Fuer Frontend-Facetten
  topicFacets(collectionId: ID!, limit: Int = 20): [TopicFacet!]!
}

type TopicFacet {
  topic: String!
  chunkCount: Int!
}
```

### Frontend

- Neue `TopicFacetSidebar`-Komponente auf der Query-Seite. Zeigt Top-20
  Topics der Collection; Click toggled Chip in den Filter.
- Aktive Chips werden im `useSearchParams` gehalten, sodass Share-Links
  den Filter mitnehmen.

## Betroffene Dateien

### Backend

| Datei                                                           | Aenderung                     |
|-----------------------------------------------------------------|-------------------------------|
| `extraction/topic/TopicExtractorService.kt`                     | Zusaetzlicher Write in Index  |
| `storage/ChunksByTopicRepository.kt`                            | NEU                           |
| `extraction/embedding/EmbeddingConsumer.kt`                     | Topics in Qdrant-Payload      |
| `query/graphrag/GraphRagQuery.kt` / `GraphRagService.kt`        | topicFilter-Parameter + Logik |
| `query/docrag/DocumentRagQuery.kt` / `DocumentRagService.kt`    | dito                          |
| `nlp/NlpQueryService.kt`                                        | Auto-Suggest einbinden        |
| `nlp/TopicDetector.kt`                                          | NEU                           |
| `api/graphql/QueryResolver.kt`                                  | `topicFacets`-Query           |
| `src/main/resources/graphql/schema.graphqls`                    | Schema-Erweiterung            |
| `src/main/resources/db/migration/V??__chunks_by_topic.cql`      | NEU                           |

### Frontend

| Datei                                                       | Aenderung              |
|-------------------------------------------------------------|------------------------|
| `frontend/src/components/query/TopicFacetSidebar.tsx`       | NEU                    |
| `frontend/src/app/query/page.tsx`                           | Sidebar einbinden      |
| `frontend/src/graphql/query.graphql`                        | topicFacets + Filter   |

### Tests

| Datei                                              | Aenderung                          |
|----------------------------------------------------|------------------------------------|
| `query/graphrag/GraphRagServiceTest.kt`            | topicFilter reduziert Anker-Set    |
| `query/docrag/DocumentRagServiceIntegrationTest.kt`| Qdrant-Payload-Filter wirkt        |
| `nlp/TopicDetectorTest.kt`                         | NEU                                |

## Akzeptanzkriterien

- [ ] Ohne `topicFilter` verhalten sich GraphRAG + DocumentRAG **identisch** zu heute.
- [ ] Mit gesetztem `topicFilter` werden Chunks/Kanten ausserhalb der Topics **nicht** im LLM-Kontext angezeigt.
- [ ] `topicFacets`-Query liefert top-N Topics der Collection sortiert nach Chunkanzahl.
- [ ] `NlpQueryService` detektiert Topics in der Frage und setzt den Filter, wenn der Nutzer nichts explizit angibt.
- [ ] Re-Index-Skript fuer bestehende Chunks vorhanden (optional triggern via Admin-UI oder CLI).
- [ ] Integrationstest: Collection mit 3 klar abgegrenzten Themen → Filter auf Thema A liefert *keine* Chunks aus Thema B.
