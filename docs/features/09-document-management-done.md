# Feature 09: Document Management (Librarian) — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/librarian/Document.kt`** — `DocumentState` (UPLOADED, PROCESSING, EXTRACTED, FAILED), `DocumentType` (SOURCE, PAGE, CHUNK), `Document` mit `id: String`, `collectionId: String` (kein UUID-Typ), `parentId`, `title`, `mimeType`, `contentUri`, `metadata`, Zeitstempel.
- **`src/main/kotlin/com/agentwork/graphmesh/librarian/DocumentStore.kt`** — Interface (synchron): `save`, `findById`, `findByCollection(collectionId, type?)`, `findChildren`, `updateState`, `delete`, `deleteWithChildren`.
- **`src/main/kotlin/com/agentwork/graphmesh/librarian/CassandraDocumentStore.kt`** — PreparedStatements gegen drei Tabellen: `documents` (by id), `documents_by_collection` (PK `(collection_id, type), id`), `documents_by_parent`. `findByCollection` liefert per Default `type = SOURCE`; ein optionales `type`-Argument filtert auf PAGE/CHUNK. `deleteWithChildren` ist rekursiv.
- **`src/main/kotlin/com/agentwork/graphmesh/librarian/DocumentSchemaInitializer.kt`** — `@PostConstruct`, legt die drei Tabellen an.
- **`src/main/kotlin/com/agentwork/graphmesh/librarian/LibrarianService.kt`** — `@Service`. `uploadDocument(collectionId, title, mimeType, content, metadata)` prueft Collection, legt Blob unter `doc/{UUID}` in S3, erzeugt Document-ID `doc-{UUID}` und publiziert `document.ingested`-Kafka-Event via `DocumentIngestedProducer`. `createChildDocument(parentId, type, title, content, mimeType)` zaehlt bestehende Kinder desselben Typs und bildet `{parentId}/p{n}` bzw. `{parentId}/c{n}`. `getContent` laedt Blob aus S3. `findByCollectionPaginated` mit Filter (type/state/search) und Paging. `deleteDocument` ist rekursiv (Kinder + Blobs + Metadaten).
- **`src/main/kotlin/com/agentwork/graphmesh/librarian/DocumentNotFoundException.kt`** — RuntimeException.

### Tests

- **`LibrarianServiceTest`** — Upload, Parent-Child-Hierarchie, Cascade-Delete, Paging/Filter, State-Transitions (mit Fake-Stores).
- **`CassandraDocumentStoreIntegrationTest`** — Integrationstest gegen Cassandra (docker-compose).

## Abweichungen vom Feature-Dokument

- **Package/Modul**: `com.agentwork.graphmesh.librarian`, kein separates Gradle-Modul.
- **`collectionId` ist String, nicht UUID**: Konsistent mit `Collection.id` (siehe Feature 08).
- **`findByCollection`-Signatur**: Spec: `findByCollection(id, includeChildren: Boolean = false)`. Implementiert: `findByCollection(id, type: DocumentType? = null)` — filtert ueber `documents_by_collection`-Partition-Key `(collection_id, type)`. Default = `SOURCE` liefert nur Quelldokumente, ansonsten gezielt Pages/Chunks.
- **Kafka-Event `document.ingested`**: Nicht im Spec fuer dieses Feature — `LibrarianService.uploadDocument` triggert `DocumentIngestedProducer.send(...)`, das den PDF-/Text-Decoder anstoesst. Topic-Name: `graphmesh.document.ingested`.
- **Synchrone API**: Keine `suspend`-Funktionen; direkter Cassandra-/S3-Client.
- **`blobStore.put`-Signatur**: Nimmt `(bucket, key, content, mimeType)` — Bucket aus `graphmesh.storage.blob.default-bucket`.
- **`findByCollectionPaginated` + `DocumentFilterCriteria`/`DocumentPageResult`**: Zusaetzlich zu Spec, fuer GraphQL-Paging.
- **Kein Testcontainers**: Integrationstests gegen docker-compose.
- **`deleteDocument` ist rekursiv im Service**: Spec hatte eine 1-Ebene-plus-Grandchildren-Loop; die Implementierung ruft `deleteDocument` rekursiv auf (Cleaner).

## Akzeptanzkriterien

- [x] Getrennte Speicherung Metadaten/Blob — Cassandra + S3.
- [x] Upload verlangt existierende Collection — `collectionService.requireExists(collectionId)`.
- [x] Kind-Dokumente mit Parent-ID — `createChildDocument` setzt `parentId`.
- [x] ID-Schema `doc-.../p../c..` — `{parentId}/p{n}` bzw. `/c{n}`.
- [x] Zustandsuebergaenge — `updateState` plus Setzer in `PdfDecoderService` (UPLOADED -> PROCESSING -> EXTRACTED/FAILED).
- [x] Cascade Delete — `deleteDocument` rekursiv inkl. Blobs; `CassandraDocumentStore.deleteWithChildren` als Store-Pendant.
- [x] `findByCollection` standardmaessig nur SOURCE — Default-Type `SOURCE` in `CassandraDocumentStore.findByCollection`.
- [x] `findChildren` — `documents_by_parent`-Tabelle.
- [x] MIME-Type-gestuetzter Upload — `mimeType`-Feld, Decoder-Dispatch via Consumer-Filter.
- [x] `getContent` liest aus S3 — `blobStore.get(bucket, contentUri).data`.

## Offene Punkte

- Keine.
