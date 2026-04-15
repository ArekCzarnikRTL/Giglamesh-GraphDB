# Feature 14: GraphQL API — Done

## Implementierung

### Backend

- **`src/main/resources/graphql/schema.graphqls`** — Kern-Schema: Queries (`collections`, `collection`, `documents` mit Pagination + Filter, `document`, `documentChunks`, `triples`, `vectorSearch`, `entitySearch`, `graphMetadata`), Mutations (`createCollection`, `updateCollection`, `deleteCollection`, `uploadDocument`, `deleteDocument`), Typen `Collection`, `Document`, `Quad` (flaches Schema mit `subject`/`predicate`/`object`/`dataset`/`objectType`/`datatype`/`language`), `SearchResult`, `KeyValue`, `DocumentPage`, `GraphMetadata`, Enums `DocumentType`, `DocumentState`, Inputs `CreateCollectionInput`/`UpdateCollectionInput`/`UploadDocumentInput`/`KeyValueInput`/`DocumentFilter`. Keine `Subscription` im Haupt-Schema (dedizierte Subscriptions liegen in `streaming.graphqls`).
- **`src/main/kotlin/com/agentwork/graphmesh/api/CollectionController.kt`** — `@QueryMapping` `collections(tags)`/`collection(id)`, `@MutationMapping` `createCollection`/`updateCollection`/`deleteCollection`, `@SchemaMapping` fuer `Collection.metadata` (konvertiert `Map<String,String>` in `KeyValue`-Liste). Delegiert an `CollectionService`. `id` ist ein `String`, keine UUID-Konvertierung.
- **`src/main/kotlin/com/agentwork/graphmesh/api/DocumentController.kt`** — `documents(collectionId, filter, page, pageSize)` -> `DocumentPagePayload` via `librarianService.findByCollectionPaginated`; `documentChunks`, `document`; `@SchemaMapping` fuer `Document.children` (lazy via `findChildren`) und `Document.metadata`. `uploadDocument` dekodiert Base64-Content und ruft `librarianService.uploadDocument`. `deleteDocument` kaskadiert ueber den Librarian.
- **`src/main/kotlin/com/agentwork/graphmesh/api/GraphController.kt`** — `triples(collectionId, subject, predicate, object, dataset, limit)` mit Default 500 / Max 5000, `entitySearch(prefix, limit)` mit Max 200, `graphMetadata(collectionId)`. `@SchemaMapping Quad.object` (Kotlin-Property heisst `objectValue`) und `Quad.objectType` (Enum -> String).
- **`src/main/kotlin/com/agentwork/graphmesh/api/SearchController.kt`** — `vectorSearch(collectionId, query, limit)`: Query-Text wird via Koog `LLMEmbeddingProvider.embed` mit `resolveLlmModel(embeddingConfig.model)` vektorisiert, `VectorStore.search(collection = collectionId, ...)`, pro Treffer wird Chunk-Text aus `LibrarianService.getContent(result.id)` nachgeladen. Rueckgabe als `List<Map<String,Any?>>` mit `id`/`score`/`payload`/`text`.
- **`src/main/kotlin/com/agentwork/graphmesh/api/GraphMeshExceptionResolver.kt`** — `DataFetcherExceptionResolverAdapter`, mapped `CollectionNotFoundException -> COLLECTION_NOT_FOUND`, `DocumentNotFoundException -> DOCUMENT_NOT_FOUND`, `IllegalArgumentException -> BAD_REQUEST`, andere Exceptions werden an Spring GraphQL weitergereicht (`null`).
- **`src/main/kotlin/com/agentwork/graphmesh/api/InputTypes.kt`** — Input-Datenklassen fuer alle Mutationen + `DocumentFilterInput`, `DocumentPagePayload`.
- **`src/main/resources/application.yml`** — `spring.graphql.graphiql.enabled: true`, `spring.graphql.schema.locations: classpath:graphql/`, `spring.graphql.websocket.path: /graphql` (Subscriptions-Channel).

Zusaetzlich existieren im selben Paket weitere dedizierte Controller fuer spezifische Subdomaenen (nicht Teil dieses Features, aber Teil der gleichen API-Schicht): `AgentController`, `CollectionDataController`, `ConfigGraphQlController`, `ContextCoreController`, `DocumentHierarchyController`, `DocumentRagController`, `ExplainabilityController`, `GraphRagController`, `NlpQueryController`, `PurgeController`, `StreamingController` (Subscriptions).

### Tests

- **`CollectionDataControllerTest`** — Tests fuer Collection-nahe Daten-Queries (separate Controller).
- **`DocumentControllerTest`** — `documents`-Resolver mit Pagination-Defaults, Filter-Mapping auf `DocumentFilterCriteria`, Rueckgabe als `DocumentPagePayload`.
- **`DocumentHierarchyControllerTest`** — Hierarchie-Resolver.
- **`GraphControllerTest`** — `triples`-Limit, Filter-Kombinationen (Seeding via `InMemoryQuadStore`).
- **`ConfigGraphQlControllerTest`**, **`AgentControllerTest`**, **`StreamingControllerTest`**, **`PurgeServiceTest`** — weitere GraphQL-Layer-Tests.

## Abweichungen vom Feature-Dokument

- **Paket-Prefix**: Controller liegen unter `com.agentwork.graphmesh.api`, nicht `com.graphmesh.api.graphql`. Schema-Datei unter `src/main/resources/graphql/schema.graphqls` (kein `api/`-Submodul).
- **Schema-Unterschiede**: 
  - `Quad` ist **flach** (`subject: String`, `predicate: String`, `object: String`, `dataset: String`, `objectType: String`, plus optional `datatype`/`language`) statt mit nested `RdfTerm`-Objekten + `RdfTermType`-Enum. Der Typ `RdfTerm` und das Enum `RdfTermType` existieren im Schema nicht.
  - `triples`-Argument heisst `dataset`, nicht `graph`.
  - `SearchResult` hat `id`/`score`/`payload: [KeyValue!]!`/`text`, nicht `chunkId`/`documentId`.
  - `Document` hat keine `documentCount`, aber `documentChunks`-Query und `Document.children`.
  - `documents`-Query gibt `DocumentPage!` zurueck (Pagination), nicht `[Document!]!`; Parameter `includeChildren` existiert nicht, stattdessen Filter + Pagination.
  - **Keine `Subscription`** im `schema.graphqls`. `startExtraction`-Mutation und `extractionProgress`-Subscription fehlen hier; Subscriptions laufen ueber ein eigenes `streaming.graphqls`-Schema (`StreamingController`).
  - Zusaetzliche Queries `entitySearch` und `graphMetadata`, nicht im Spec.
- **`SearchController` gibt `List<Map<String,Any?>>` zurueck**, nicht eine typisierte `SearchResult`-Datenklasse (vermeidet explizite Mapping-Schicht).
- **`id` als `String`**, keine UUID-Konvertierung in den Controllern — Collections/Documents arbeiten durchgaengig mit String-IDs.
- **Keine `startExtraction`-Mutation** — Extraktion laeuft eventgetrieben ueber Kafka (Upload -> `document.ingested` -> Decoder -> Chunker -> ...), kein expliziter Trigger-Endpoint.
- **`ExceptionResolver` faellt durch**: Fuer nicht bekannte Exceptions gibt der Resolver `null` zurueck (Spring-Default-Behandlung) statt eines generischen `INTERNAL_ERROR`, wie im Spec.
- **Keine Suspend-Funktionen**: Alle Controller-Methoden sind synchron. Die im Spec vorgeschlagenen `suspend fun`-Signaturen wurden nicht uebernommen (`runBlocking` wird nur fuer Koog-Embedding-Calls im `SearchController` benutzt).
- **Keine dedizierten `CollectionControllerTest`/`SearchControllerTest`/`ErrorHandlingTest`/`SchemaValidationTest`**. Testabdeckung ist ueber Document-, Graph- und Streaming-Controller-Tests konzentriert; Search- und Error-Resolver haben keinen eigenen Test.

## Akzeptanzkriterien

- [x] GraphQL-Endpunkt unter `/graphql` erreichbar — Spring Boot Starter GraphQL via `spring-boot-starter-graphql`.
- [x] GraphiQL-IDE unter `/graphiql` — `spring.graphql.graphiql.enabled: true`.
- [x] `collections`-Query listet alle Collections, optional nach Tags — `CollectionController.collections`.
- [~] `documents`-Query listet Dokumente einer Collection — implementiert mit Pagination+Filter; `includeChildren` nicht, stattdessen dedizierter `Document.children`-Resolver und `documentChunks`-Query.
- [x] `triples`-Query filtert nach Subject/Predicate/Object/Graph — Argument heisst `dataset` statt `graph`, sonst 1:1.
- [x] `vectorSearch`-Query fuehrt semantische Suche durch und liefert Chunk-Texte — `SearchController.vectorSearch`.
- [x] `createCollection`-Mutation — `CollectionController.createCollection`.
- [x] `uploadDocument`-Mutation nimmt Base64-Inhalt entgegen — `DocumentController.uploadDocument`.
- [x] `deleteCollection`/`deleteDocument` kaskadieren — Delegation an `CollectionService.delete` / `LibrarianService.deleteDocument`.
- [x] Domain-Exceptions als strukturierte GraphQL-Errors mit `extensions.code` — `GraphMeshExceptionResolver`.
- [x] WebSocket-Endpunkt unter `/graphql` fuer Subscriptions — `spring.graphql.websocket.path: /graphql`, Subscriptions in `streaming.graphqls`.
- [x] Schema-Introspection funktioniert — Spring-GraphQL-Default (aktiv, da GraphiQL laeuft).
- [ ] `extractionProgress`-Subscription und `startExtraction`-Mutation im Kern-Schema — nicht im `schema.graphqls`; Extraktion lauft eventgetrieben, Streaming ueber `StreamingController`.

## Offene Punkte

- `startExtraction`-Mutation fehlt im Schema; bei Bedarf als expliziter Re-Trigger fuer fehlgeschlagene Dokumente nachziehen.
- Dedizierte Tests fuer `SearchController`, `GraphMeshExceptionResolver` und Schema-Introspection existieren noch nicht.
- `Quad`-Typ ist flach, nicht nested. Falls Clients RDF-Term-Semantik (URI/Literal/BNode/QuotedTriple) typisiert brauchen, waere eine Schema-Erweiterung noetig.
