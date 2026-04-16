# Feature 58: Typed Vector Payload — Done

Abschlussdatum: 2026-04-16
Spec: [58-typed-vector-payload.md](58-typed-vector-payload.md)

## Zusammenfassung

`VectorPoint.payload` und `SearchResult.payload` wurden von `Map<String, Any>` auf eine typisierte `VectorPayload` data class umgestellt. Alle Writer (EmbeddingService, RdfImportService) und Reader (DocumentRagService, GraphRagService, BundleWriter/BundleReader) nutzen jetzt Property-Zugriffe statt String-Key-Casts. Der Qdrant-Adapter konvertiert via `toMap()`/`fromMap()` an der Wire-Grenze.

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStore.kt`** — `VectorPayload` data class mit 5 typisierten Feldern (`collection`, `chunkId`, `documentId`, `entityUri`, `source`) plus `extra: Map<String, Any>` fuer Forward-Kompatibilitaet. `toMap()` serialisiert in Wire-Format, `fromMap()` (Companion) deserialisiert zurueck. Unbekannte Keys landen in `extra`. `VectorPoint.payload` und `SearchResult.payload` sind jetzt `VectorPayload` statt `Map<String, Any>`.
- **`src/main/kotlin/com/agentwork/graphmesh/storage/vector/QdrantVectorStore.kt`** — Upsert nutzt `payload.toMap()` fuer die Konvertierung; Search/Scroll-Ergebnisse werden via `VectorPayload.fromMap()` zurueckkonvertiert. `Map<String, Any>` bleibt exklusiv Wire-Format im Adapter.
- **`src/main/kotlin/com/agentwork/graphmesh/extraction/embedding/EmbeddingService.kt`** — Raw-Map-Payload durch `VectorPayload(collection=..., chunkId=..., documentId=...)` Konstruktor ersetzt.
- **`src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt`** — Raw-Map-Payload durch `VectorPayload(collection=..., entityUri=..., source=...)` Konstruktor ersetzt.
- **`src/main/kotlin/com/agentwork/graphmesh/query/docrag/DocumentRagService.kt`** — `payload["key"] as String`-Casts durch Property-Zugriffe ersetzt (`result.payload.chunkId`).
- **`src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`** — analog, Property-Zugriffe statt Casts.
- **`src/main/kotlin/com/agentwork/graphmesh/contextcore/BundleWriter.kt`** — `.payload.toMap()` fuer Serialisierung.
- **`src/main/kotlin/com/agentwork/graphmesh/contextcore/BundleReader.kt`** — `VectorPayload.fromMap()` fuer Deserialisierung.
- **`src/main/kotlin/com/agentwork/graphmesh/api/SearchController.kt`** — `VectorPayload.toMap()` fuer Payload-Iteration im API-Response.

### Tests

- **`src/test/kotlin/com/agentwork/graphmesh/storage/vector/VectorPayloadTest.kt`** — NEU, 14 Tests: `toMap()` mit allen Feldern, `toMap()` mit null-Feldern, `toMap()` mit Extra-Eintraegen, `fromMap()` fuer Chunk-Payload, `fromMap()` fuer Entity-Payload, Round-Trip-Serialisierung, leere Map, fehlende Keys landen in `extra`.
- **`src/test/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagServiceTest.kt`** — an `VectorPayload`-Konstruktor angepasst.
- **`src/test/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportServiceTest.kt`** — an `VectorPayload`-Konstruktor angepasst.
- **`src/test/kotlin/com/agentwork/graphmesh/storage/vector/QdrantVectorStoreIntegrationTest.kt`** — an `VectorPayload`-Konstruktor angepasst.
- **`src/test/kotlin/com/agentwork/graphmesh/contextcore/BundleWriterReaderTest.kt`** — an `VectorPayload`-Konstruktor angepasst.

## Akzeptanzkriterien

- [x] `VectorPayload` data class existiert in `storage/vector/VectorStore.kt` mit 5 bekannten Feldern plus `extra`.
- [x] `VectorPoint.payload` und `SearchResult.payload` sind vom Typ `VectorPayload`.
- [x] `toMap()` erzeugt ein `Map<String, Any>` mit allen gesetzten Feldern und `extra`-Eintraegen.
- [x] `fromMap()` rekonstruiert `VectorPayload` korrekt, unbekannte Keys landen in `extra`.
- [x] Kein `payload["..."] as String`-Cast mehr in `GraphRagService`, `DocumentRagService` oder anderen Readern.
- [x] Kein Raw-Map-Payload-Konstruktor mehr in `EmbeddingService` oder `RdfImportService`.
- [x] `QdrantVectorStore` konvertiert intern via `toMap()` / `fromMap()` — externe API arbeitet nur mit `VectorPayload`.
- [x] Round-Trip-Test: `fromMap(payload.toMap()) == payload` fuer beide Shapes (Chunk + Entity).
- [x] Bestehende Tests bleiben gruen.

## Abweichungen vom Feature-Dokument

- **Keine separaten Dateien**: `VectorPayload` lebt direkt in `VectorStore.kt` statt in einer eigenen `VectorPayload.kt`, da sie eng mit `VectorPoint`/`SearchResult` zusammengehoert.
- **SearchController**: zusaetzlich angepasst (war nicht in der Spec erwaehnt), da er `payload`-Werte iteriert.

## Commits

```
8a10a34 refactor(storage): replace untyped Map payload with VectorPayload data class (feature 58)
fa6e699 fix: resolve build errors from feature 58/60 integration
```
