# Feature 04: Qdrant Vector Store — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStore.kt`** — Interface `VectorStore` mit `upsert(collection, points)`, `search(collection, queryVector, limit, filter?, scoreThreshold?)`, `delete(collection, dimension, ids)`, `deleteCollection(collection)`, `collectionExists(collection, dimension)`, `scroll(collection)`. Datenmodelle: `VectorPoint(id, vector: FloatArray, payload)` mit `dimension`-Property und Content-aware equals/hashCode, `SearchResult(id, score, payload)`, `sealed class VectorFilter { Equals | In | And | Or | Not }`.
- **`src/main/kotlin/com/agentwork/graphmesh/storage/vector/CollectionNaming.kt`** — `object` mit `physicalName(logicalName, dimension) = "${logical}_$dim"`, `prefixPattern(logicalName) = "${logical}_"`, `extractDimension(physicalName) = substringAfterLast('_').toIntOrNull()`.
- **`src/main/kotlin/com/agentwork/graphmesh/storage/vector/QdrantVectorStore.kt`** — Implementierung gegen `io.qdrant.client.QdrantClient` (gRPC). `upsert`: pruft einheitliche Dimension (`require(points.all { it.dimension == dimension })`), ruft `ensureCollection(physicalName, dimension)` (thread-safe via `@Synchronized` + `ConcurrentHashMap`-`knownCollections`), baut `PointStruct` mit deterministischer UUID (`UUID.nameUUIDFromBytes(id)`), speichert die Original-ID als `_original_id` im Payload. `search`: prueft `collectionExists` und gibt leere Liste zurueck, wenn Collection fehlt; baut `SearchPoints`-Request mit `withPayload`, optionalem `scoreThreshold` und `toQdrantFilter` fuer die `VectorFilter`-DSL (Equals/In/And/Or/Not -> Qdrant `Filter` mit `must`/`should`/`mustNot`). `delete` benoetigt explizit die Dimension (siehe Abweichungen). `deleteCollection` listet alle physischen Collections, filtert per `prefixPattern` und loescht alle Varianten. `scroll` blaettert via `ScrollPoints` mit Page-Size 1000.
- **`src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStoreProperties.kt`** — `@ConfigurationProperties(prefix = "graphmesh.storage.vector")` mit `host`, `grpcPort` (default 6334), `apiKey?`, `useTls`.
- **`src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStoreAutoConfiguration.kt`** — `@Configuration` + `@EnableConfigurationProperties`. Stellt `QdrantClient` (via `QdrantGrpcClient.newBuilder(host, grpcPort, useTls)`, optionaler API-Key) und `VectorStore` (`QdrantVectorStore`) als Beans bereit.
- **`src/main/resources/application.yml`** — `graphmesh.storage.vector` mit Env-Overrides `QDRANT_HOST`/`QDRANT_GRPC_PORT`, `use-tls: false`. Zusaetzlich wird `org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration` von Spring AI global deaktiviert (Konflikt mit eigenem Bean).

### Tests

- **`CollectionNamingTest`** — 6 Unit-Tests fuer `physicalName`, `prefixPattern`, `extractDimension` inkl. Edge Cases.
- **`QdrantVectorStoreIntegrationTest`** — 10 Integrationstests gegen laufendes Qdrant via docker-compose: upsert/search-Roundtrip, Equals-Filter, leere Collection liefert leere Liste, `delete` mit Dimension, `deleteCollection` ueber mehrere Dimensionsvarianten, Koexistenz verschiedener Dimensionen unter gleichem logischen Namen, `collectionExists`, Idempotenz, `scoreThreshold`, Payload-Erhalt.

## Abweichungen vom Feature-Dokument

- **Package**: Spec verwendet `com.graphmesh.storage.vector` in Submodul `storage-vector`. Real: `com.agentwork.graphmesh.storage.vector` im Monomodul. **Memory-Hinweis**: Feature-Specs nutzen oft alte Packages — das Projekt setzt bewusst auf Spring-Modulith-Packages statt Gradle-Submodule.
- **Synchron statt `suspend`**: Spec nennt `suspend fun upsert/search/...`. Real synchron, mit `.get()` auf den `ListenableFuture` des Qdrant-Clients (passt zu Projekt-Regel "keine Coroutines in I/O-Layern").
- **`delete(collection, dimension, ids)` statt `delete(collection, ids)`**: Qdrant speichert je Dimension eine eigene physische Collection; um die richtige zu treffen, ist die Dimension explizit Pflicht-Parameter. Spec-API wuerde nicht funktionieren.
- **Zusaetzliche `scroll(collection)`-Methode**: Fuer Export/Debug implementiert, nicht im Spec.
- **`_original_id` im Payload**: Weil Qdrant nur UUID-/Int-Point-IDs akzeptiert, wird die Original-String-ID im Payload abgelegt und beim `search` zurueckgemappt. Spec erwaehnt das Detail nicht.
- **`VectorStoreProperties` ohne `port`/`defaultDistance`**: Spec listet `port` zusaetzlich zu `grpcPort` sowie `defaultDistance`. Real: nur `grpcPort`, Distance ist fest `Cosine` (kein Config-Punkt).
- **`knownCollections`-Cache**: Thread-safer Set-Cache fuer bereits bekannte physische Collections (`ConcurrentHashMap.newKeySet()`). Reduziert gRPC-Roundtrips auf `collectionExistsAsync`.
- **Kein dediziertes `VectorFilterTest`-Unit-File**: Filter-DSL wird ausschliesslich ueber den `QdrantVectorStoreIntegrationTest` (Equals-Test) verifiziert.

## Akzeptanzkriterien

- [x] Lazy Collection Creation mit korrekter Dimension — `ensureCollection` in `upsert`.
- [x] Collection-Namen `<collection>_<dimension>` — `CollectionNaming.physicalName`.
- [x] `search()` auf fehlende Collection liefert leere Liste — `if (!collectionExists(...)) return emptyList()`, Integrationstest `search on non-existent collection returns empty list`.
- [x] Cosine Distance — `VectorParams.setDistance(Distance.Cosine)`.
- [x] Filter Equals/In/And/Or/Not — `toQdrantFilter` mappt alle fuenf Varianten.
- [x] `deleteCollection` loescht alle Dimensionsvarianten — `listCollectionsAsync().filter { startsWith(prefix) }`.
- [x] Dimensionen 384/768/1536 koexistieren — Integrationstest `different dimensions coexist under same logical name`.
- [x] Concurrent First Writes — `@Synchronized ensureCollection` + `ConcurrentHashMap`-Cache.
- [x] Spring-Boot-Auto-Config — `VectorStoreAutoConfiguration`.
- [x] Integrationstests mit Qdrant laufen — 10 Tests gegen docker-compose.
- [x] Bestehende Funktionalitaet unberuehrt.

## Offene Punkte

- Keine.
