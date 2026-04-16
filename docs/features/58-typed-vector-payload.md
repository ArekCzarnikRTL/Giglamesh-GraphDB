# Feature 58: Typed Vector Payload

## Problem

`VectorPoint.payload` ist `Map<String, Any>` — ein untypisierter Bag. Writer (`EmbeddingService`) schreiben String-Keys wie `"chunk_id"`, `"document_id"`, `"collection"`, `"entity_uri"`. Reader (`GraphRagService`, `DocumentRagService`) lesen mit Casts: `payload["chunk_id"] as String`. Key-Renames brechen lautlos zur Laufzeit. Kein Compile-Time-Check. Dieser implizite Kontrakt spannt sich ueber 4 Module: `extraction/embedding`, `query/graphrag`, `query/docrag`, `storage/vector`.

Es gibt genau 2 Payload-Shapes:

- **Chunk-Payload**: `chunk_id`, `document_id`, `collection` (geschrieben von `EmbeddingService`)
- **Entity-Payload**: `entity_uri`, `source`, `collection` (geschrieben von `RdfImportService`)

Beide Shapes teilen sich den gleichen untypisierten `Map<String, Any>`-Container — weder der Compiler noch die IDE koennen pruefen, ob ein Key existiert oder den richtigen Typ hat.

## Ziel

Einfuehrung einer typisierten `VectorPayload` data class, die beide Payload-Shapes in einem flachen Typ zusammenfuehrt und durchgehend in `VectorPoint` und `SearchResult` eingebettet wird.

1. **`VectorPayload` data class** — flacher Typ mit allen bekannten Feldern (nullable ausser `collection`), plus `extra: Map<String, Any>` fuer Ad-hoc-Felder und Backward-Kompatibilitaet.
2. **Einbettung in `VectorPoint` und `SearchResult`** — `payload` wird von `Map<String, Any>` auf `VectorPayload` umgestellt, erzwungene Typsicherheit im gesamten Lese- und Schreib-Pfad.
3. **`toMap()` / `fromMap()` Bridge** — Qdrant-Adapter arbeitet weiterhin mit `Map<String, Any>` als Wire-Format; Konvertierung geschieht zentral im Adapter.
4. **`extra`-Map fuer Erweiterbarkeit** — unbekannte Keys aus bestehenden Qdrant-Dokumenten gehen nicht verloren, Forward-Kompatibilitaet bleibt gewahrt.
5. **Property-Access statt Casts** — Reader-Code wird `result.payload.chunkId` statt `result.payload["chunk_id"] as String`.

## Voraussetzungen

| Abhaengigkeit                          | Status        | Blocker? |
|----------------------------------------|---------------|----------|
| Feature 04 (Qdrant Vector Store)       | Implementiert | Nein     |
| Feature 13 (Embeddings)                | Implementiert | Nein     |
| Feature 15 (GraphRAG)                  | Implementiert | Nein     |
| Feature 16 (DocRAG)                    | Implementiert | Nein     |

Keine Infra-Aenderung in Qdrant / docker-compose.

## Architektur

### Ist-Zustand

```kotlin
// VectorStore.kt
data class VectorPoint(
    val id: String,
    val vector: List<Float>,
    val payload: Map<String, Any> = emptyMap()
)

data class SearchResult(
    val id: String,
    val score: Float,
    val payload: Map<String, Any> = emptyMap()
)

// EmbeddingService.kt — Writer
val point = VectorPoint(
    id = chunkId,
    vector = embedding,
    payload = mapOf(
        "chunk_id" to chunkId,
        "document_id" to documentId,
        "collection" to collection
    )
)

// GraphRagService.kt — Reader
val entityUri = result.payload["entity_uri"] as String   // lautloser Laufzeitfehler bei Rename
val collection = result.payload["collection"] as String
```

### Soll-Zustand

```kotlin
// VectorStore.kt
data class VectorPayload(
    val collection: String,
    val chunkId: String? = null,
    val documentId: String? = null,
    val entityUri: String? = null,
    val source: String? = null,
    val extra: Map<String, Any> = emptyMap()
) {
    fun toMap(): Map<String, Any> = buildMap {
        put("collection", collection)
        chunkId?.let { put("chunk_id", it) }
        documentId?.let { put("document_id", it) }
        entityUri?.let { put("entity_uri", it) }
        source?.let { put("source", it) }
        putAll(extra)
    }

    companion object {
        fun fromMap(map: Map<String, Any>): VectorPayload {
            val known = setOf("collection", "chunk_id", "document_id", "entity_uri", "source")
            return VectorPayload(
                collection = map["collection"]?.toString() ?: "",
                chunkId = map["chunk_id"]?.toString(),
                documentId = map["document_id"]?.toString(),
                entityUri = map["entity_uri"]?.toString(),
                source = map["source"]?.toString(),
                extra = map.filterKeys { it !in known }
            )
        }
    }
}

data class VectorPoint(
    val id: String,
    val vector: List<Float>,
    val payload: VectorPayload
)

data class SearchResult(
    val id: String,
    val score: Float,
    val payload: VectorPayload
)

// EmbeddingService.kt — Writer (typsicher)
val point = VectorPoint(
    id = chunkId,
    vector = embedding,
    payload = VectorPayload(
        collection = collection,
        chunkId = chunkId,
        documentId = documentId
    )
)

// GraphRagService.kt — Reader (typsicher, Compile-Time-Check)
val entityUri = result.payload.entityUri ?: error("entity_uri missing")
val collection = result.payload.collection
```

### Subsection 1: Wire-Format-Bridge im Qdrant-Adapter

```kotlin
// QdrantVectorStore.kt — upsert
private fun buildPayload(payload: VectorPayload): Map<String, Value> {
    return payload.toMap().mapValues { (_, v) ->
        Value.newBuilder().setStringValue(v.toString()).build()
    }
}

// QdrantVectorStore.kt — search/scroll
private fun toSearchResult(scored: ScoredPoint): SearchResult {
    val rawMap = scored.payloadMap.mapValues { (_, v) -> v.stringValue as Any }
    return SearchResult(
        id = scored.id.uuid,
        score = scored.score,
        payload = VectorPayload.fromMap(rawMap)
    )
}
```

`Map<String, Any>` bleibt exklusiv Wire-Format im Qdrant-Adapter. Alle anderen Schichten arbeiten nur noch mit `VectorPayload`.

### Subsection 2: BundleWriter/BundleReader-Anpassung

```kotlin
// BundleWriter.kt
val payloadMap = vectorPoint.payload.toMap()   // Serialisierung fuer Bundle-Format

// BundleReader.kt
val payload = VectorPayload.fromMap(rawPayloadMap)   // Deserialisierung aus Bundle-Format
```

### Subsection 3: Reader-Migration (GraphRAG, DocRAG)

```kotlin
// vorher
val chunkId = result.payload["chunk_id"] as? String ?: return
val documentId = result.payload["document_id"] as? String ?: return

// nachher
val chunkId = result.payload.chunkId ?: return
val documentId = result.payload.documentId ?: return
```

Alle `as String`-Casts und String-Key-Zugriffe werden durch Property-Zugriffe ersetzt. Der Compiler prueft, dass die Properties existieren und den richtigen Typ haben.

## Betroffene Dateien

### Backend

| Datei                                                                                                   | Aenderung                                                                                              |
|---------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStore.kt`                                | `VectorPayload` data class hinzufuegen; `VectorPoint.payload` und `SearchResult.payload` auf `VectorPayload` umstellen |
| `src/main/kotlin/com/agentwork/graphmesh/storage/vector/QdrantVectorStore.kt`                          | `buildPayload`, Search-Result-Mapping und Scroll-Ergebnisse auf `VectorPayload.toMap()` / `fromMap()` umstellen |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/embedding/EmbeddingService.kt`                     | Raw-Map-Payload durch `VectorPayload`-Konstruktor ersetzen                                              |
| `src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt`                                | Raw-Map-Payload durch `VectorPayload`-Konstruktor ersetzen                                              |
| `src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt`                           | `payload["key"] as String`-Casts durch Property-Zugriffe ersetzen                                       |
| `src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`                            | `payload["key"] as String`-Casts durch Property-Zugriffe ersetzen                                       |
| `src/main/kotlin/com/agentwork/graphmesh/contextcore/BundleWriter.kt`                                  | `.payload.toMap()` fuer Serialisierung verwenden                                                        |
| `src/main/kotlin/com/agentwork/graphmesh/contextcore/BundleReader.kt`                                  | `VectorPayload.fromMap()` fuer Deserialisierung verwenden                                               |

### Frontend

Keine Aenderungen.

### Tests

| Datei                                                                                                   | Aenderung                                                                                              |
|---------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/storage/vector/VectorPayloadTest.kt`                          | NEU — Unit-Tests fuer `toMap()`, `fromMap()`, Round-Trip, `extra`-Handling, fehlende Keys               |
| Tests die `SearchResult` oder `VectorPoint` mit Raw-Maps konstruieren                                   | Auf `VectorPayload`-Konstruktor umstellen (betrifft ca. 3 Testdateien)                                 |

## Akzeptanzkriterien

- [ ] `VectorPayload` data class existiert in `storage/vector/VectorStore.kt` mit allen 5 bekannten Feldern plus `extra`.
- [ ] `VectorPoint.payload` und `SearchResult.payload` sind vom Typ `VectorPayload` (nicht `Map<String, Any>`).
- [ ] `toMap()` erzeugt ein `Map<String, Any>` das alle gesetzten Felder und `extra`-Eintraege enthaelt.
- [ ] `fromMap()` rekonstruiert ein `VectorPayload` korrekt, unbekannte Keys landen in `extra`.
- [ ] Kein `payload["..."] as String`-Cast mehr in `GraphRagService`, `DocumentRagService` oder anderen Readern.
- [ ] Kein Raw-Map-Payload-Konstruktor mehr in `EmbeddingService` oder `RdfImportService`.
- [ ] `QdrantVectorStore` konvertiert intern via `toMap()` / `fromMap()` — externe API arbeitet nur mit `VectorPayload`.
- [ ] Round-Trip-Test: `fromMap(payload.toMap()) == payload` fuer beide Shapes (Chunk + Entity).
- [ ] Bestehende Tests von Feature 04 / 13 / 15 / 16 bleiben gruen.
- [ ] Bestehende Funktionalitaet bleibt unberuehrt.
