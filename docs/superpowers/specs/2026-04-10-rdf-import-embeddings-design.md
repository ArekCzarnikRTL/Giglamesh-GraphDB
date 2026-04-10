# RDF Import Embeddings + Graph RAG Dual-Retrieval

## Problem

RDF-importierte Triples (Feature 43) landen nur im QuadStore (Cassandra). Ohne Embeddings in Qdrant funktionieren vectorSearch, Graph RAG, NLP Query und Agent nicht mit diesen Daten. Der bestehende Graph RAG-Pfad erwartet `chunk_id` im Embedding-Payload und navigiert uber Provenance-Subgraphen — beides existiert nicht fuer RDF-importierte Daten.

## Loesung

Zwei Aenderungen:

1. **Entity-basierte Embeddings im RdfImportService**: Wenn `generateEmbeddings=true`, werden nach dem QuadStore-Insert Embeddings pro Subject-URI erzeugt und in Qdrant gespeichert.
2. **Dual-Retrieval in GraphRagService**: Neben dem bestehenden Chunk-Pfad wird ein zweiter Pfad fuer `entity_uri`-basierte Treffer eingefuehrt.

## Design

### 1. Entity-Embedding-Generierung (RdfImportService)

Nach `quadStore.insertBatch()`, wenn `generateEmbeddings=true`:

- Gruppiere importierte Triples nach Subject-URI
- Baue pro Subject lesbaren Text aus allen Triples:
  ```
  "Alice arbeitetBei Acme. Alice name Alice Mueller. Alice email alice@example.org."
  ```
  URI-Suffixe werden extrahiert (letztes Segment nach `#` oder `/`), Literale direkt eingesetzt.
- Erzeuge Embedding via `LLMEmbeddingProvider.embed(text, model)` mit dem konfigurierten Embedding-Modell (`EmbeddingConfig.model`)
- Speichere als `VectorPoint` in Qdrant:
  ```kotlin
  VectorPoint(
      id = subjectUri,
      vector = embeddingVector,
      payload = mapOf(
          "entity_uri" to subjectUri,
          "source" to "rdf-import",
          "collection" to collectionName
      )
  )
  ```
- Batch-upsert alle Entity-Punkte auf einmal

### 2. Dual-Retrieval in GraphRagService.retrieveSubgraph()

Bestehender Pfad (unveraendert):
```
vectorSearch → chunk_id payloads → urn:chunk:... → findSubgraphsForChunks → findQuotedTriplesForSubgraphs
```

Neuer zusaetzlicher Pfad:
```
vectorSearch → entity_uri payloads → findByEntities(entityUris)
```

Konkret: Nach `vectorStore.search()` werden die Ergebnisse in zwei Gruppen aufgeteilt:
- Treffer mit `chunk_id` im Payload → bestehender Chunk-Pfad
- Treffer mit `entity_uri` im Payload → `quadStore.findByEntities()`

Beide Ergebnismengen werden vereinigt (`distinct`) und ans Edge-Selection weitergegeben.

### 3. Betroffene Dateien

| Datei | Aenderung |
|---|---|
| `rdfimport/RdfImportService.kt` | Embedding-Generierung nach Import, benoetigt `LLMEmbeddingProvider`, `VectorStore`, `EmbeddingConfig` als Dependencies |
| `rdfimport/RdfImportController.kt` | `generateEmbeddings` aus Input an Service durchreichen |
| `query/graphrag/GraphRagService.kt` | `retrieveSubgraph()` um Entity-URI-Pfad erweitern |
| `rdfimport/RdfImportServiceTest.kt` | Tests fuer Embedding-Generierung (mit Mock-EmbeddingProvider) |
| `query/graphrag/GraphRagServiceTest.kt` | Test fuer Entity-URI-Retrieval-Pfad |

### 4. Was sich nicht aendert

- `EmbeddingService` bleibt unveraendert (chunk-spezifisch)
- `Document RAG` bleibt unveraendert (braucht echte Dokument-Chunks)
- `NLP Query` profitiert automatisch (delegiert an Graph RAG)
- `Agent/Streaming` profitiert automatisch (nutzt dieselben Query-Tools)
- Bestehende chunk-basierte Embeddings und Retrieval bleiben vollstaendig funktional

### 5. Text-Generierung fuer Entities

Funktion `buildEntityText(subjectUri: String, triples: List<StoredQuad>): String`:
- Extrahiert den lokalen Namen aus der Subject-URI (z.B. `Alice` aus `http://example.org/Alice`)
- Fuer jedes Triple: `"{localSubject} {localPredicate} {objectValue}"`
- Bei URI-Objekten wird ebenfalls der lokale Name extrahiert
- Saetze werden mit `. ` verbunden
- Ergebnis ist ein natuerlichsprachlicher Text, der gut embeddbar ist

### 6. Fehlerbehandlung

- Wenn der Embedding-Provider fehlschlaegt, wird der Import trotzdem als erfolgreich gemeldet (Triples sind in Cassandra). Die Embedding-Fehler werden geloggt mit WARN.
- `embeddingsGenerated` wird als neues Feld im `ImportResult` zurueckgegeben, damit der Aufrufer weiss, wie viele Entities Embeddings bekommen haben.
