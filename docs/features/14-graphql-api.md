# Feature 14: GraphQL API

## Problem

GraphMesh braucht eine einheitliche API-Schicht, ueber die Clients auf Collections, Dokumente, Knowledge-Graph-Triples
und Vektor-Suche zugreifen koennen. Ohne eine typsichere, flexible Query-Sprache muessen fuer jede Abfragekombination
eigene REST-Endpunkte erstellt werden, was zu einer unuebersichtlichen API-Oberflaeche fuehrt. GraphQL bietet
Schema-first-Typsicherheit, flexible Feldauswahl und ermoeglicht Subscriptions fuer Echtzeit-Updates zum
Extraktionsfortschritt.

## Ziel

Implementierung einer GraphQL-API auf Basis von Spring Boot GraphQL (Schema-first), die Queries, Mutations und
Subscriptions fuer alle zentralen Domaenen von GraphMesh bereitstellt.

1. **Schema-first Approach** -- GraphQL-Schema (.graphqls) als zentrale API-Definition
2. **Queries** -- Collections, Dokumente, Triples/Quads, Vektor-Suche
3. **Mutations** -- Collection-CRUD, Dokument-Upload
4. **Subscriptions** -- Echtzeit-Updates zum Extraktionsfortschritt
5. **Error Handling** -- Strukturierte GraphQL-Fehlermeldungen mit Fehlertypen

## Voraussetzungen

| Abhaengigkeit                                              | Status     | Blocker? |
|------------------------------------------------------------|------------|----------|
| Feature 02: Cassandra Storage Layer (QuadStore, QuadQuery) | Geplant    | Ja       |
| Feature 04: Qdrant Vector Store (VectorStore)              | Geplant    | Ja       |
| Feature 07: RDF Graph Model (Quad, Triple, RdfTerm)        | Geplant    | Ja       |
| Feature 08: Collection Management (CollectionService)      | Geplant    | Ja       |
| Feature 09: Document Management (LibrarianService)         | Geplant    | Ja       |
| Spring Boot Starter GraphQL                                | Verfuegbar | Nein     |

## Architektur

### GraphQL Schema

```graphql
# api/src/main/resources/graphql/schema.graphqls

type Query {
    # Collections
    collections(tags: [String]): [Collection!]!
    collection(id: ID!): Collection

    # Dokumente
    documents(collectionId: ID!, includeChildren: Boolean = false): [Document!]!
    document(id: ID!): Document

    # Knowledge Graph
    triples(
        collectionId: ID!,
        subject: String,
        predicate: String,
        object: String,
        graph: String,
        limit: Int = 100
    ): [Quad!]!

    # Vektor-Suche
    vectorSearch(
        collectionId: ID!,
        query: String!,
        limit: Int = 10
    ): [SearchResult!]!
}

type Mutation {
    # Collections
    createCollection(input: CreateCollectionInput!): Collection!
    updateCollection(id: ID!, input: UpdateCollectionInput!): Collection!
    deleteCollection(id: ID!): Boolean!

    # Dokumente
    uploadDocument(input: UploadDocumentInput!): Document!
    deleteDocument(id: ID!): Boolean!

    # Extraktion
    startExtraction(documentId: ID!): ExtractionStatus!
}

type Subscription {
    # Extraktionsfortschritt fuer ein Dokument
    extractionProgress(documentId: ID!): ExtractionProgress!
}

# --- Types ---

type Collection {
    id: ID!
    name: String!
    description: String
    tags: [String!]!
    metadata: [KeyValue!]!
    documentCount: Int!
    createdAt: String!
    updatedAt: String!
}

type Document {
    id: ID!
    collectionId: ID!
    parentId: String
    type: DocumentType!
    state: DocumentState!
    title: String!
    mimeType: String!
    children: [Document!]!
    metadata: [KeyValue!]!
    createdAt: String!
}

type Quad {
    subject: RdfTerm!
    predicate: String!
    object: RdfTerm!
    graph: String!
}

type RdfTerm {
    type: RdfTermType!
    value: String!
    datatype: String
    language: String
}

type SearchResult {
    chunkId: String!
    score: Float!
    documentId: String
    text: String
}

type ExtractionStatus {
    documentId: ID!
    state: DocumentState!
    startedAt: String
}

type ExtractionProgress {
    documentId: ID!
    stage: String!
    progress: Float!
    message: String
}

type KeyValue {
    key: String!
    value: String!
}

# --- Enums ---

enum DocumentType {
    SOURCE
    PAGE
    CHUNK
}

enum DocumentState {
    UPLOADED
    PROCESSING
    EXTRACTED
    FAILED
}

enum RdfTermType {
    URI
    LITERAL
    BLANK_NODE
    QUOTED_TRIPLE
}

# --- Inputs ---

input CreateCollectionInput {
    name: String!
    description: String
    tags: [String!]
    metadata: [KeyValueInput!]
}

input UpdateCollectionInput {
    name: String
    description: String
    tags: [String!]
    metadata: [KeyValueInput!]
}

input UploadDocumentInput {
    collectionId: ID!
    title: String!
    mimeType: String!
    content: String!  # Base64-encoded
    metadata: [KeyValueInput!]
}

input KeyValueInput {
    key: String!
    value: String!
}
```

### CollectionController

```kotlin
package com.graphmesh.api.graphql

import com.graphmesh.collection.CollectionService
import com.graphmesh.collection.Collection
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import java.util.UUID

@Controller
class CollectionController(
    private val collectionService: CollectionService
) {

    @QueryMapping
    suspend fun collections(@Argument tags: List<String>?): List<Collection> {
        return collectionService.listCollections(tags?.toSet() ?: emptySet())
    }

    @QueryMapping
    suspend fun collection(@Argument id: String): Collection? {
        return collectionService.getCollection(UUID.fromString(id))
    }

    @MutationMapping
    suspend fun createCollection(@Argument input: CreateCollectionInput): Collection {
        return collectionService.createCollection(
            name = input.name,
            description = input.description ?: "",
            tags = input.tags?.toSet() ?: emptySet(),
            metadata = input.metadata?.associate { it.key to it.value } ?: emptyMap()
        )
    }

    @MutationMapping
    suspend fun updateCollection(
        @Argument id: String,
        @Argument input: UpdateCollectionInput
    ): Collection {
        return collectionService.updateCollection(
            id = UUID.fromString(id),
            name = input.name,
            description = input.description,
            tags = input.tags?.toSet(),
            metadata = input.metadata?.associate { it.key to it.value }
        )
    }

    @MutationMapping
    suspend fun deleteCollection(@Argument id: String): Boolean {
        collectionService.deleteCollection(UUID.fromString(id))
        return true
    }
}

data class CreateCollectionInput(
    val name: String,
    val description: String?,
    val tags: List<String>?,
    val metadata: List<KeyValueInput>?
)

data class UpdateCollectionInput(
    val name: String?,
    val description: String?,
    val tags: List<String>?,
    val metadata: List<KeyValueInput>?
)

data class KeyValueInput(val key: String, val value: String)
```

### DocumentController

```kotlin
package com.graphmesh.api.graphql

import com.graphmesh.librarian.LibrarianService
import com.graphmesh.librarian.Document
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller
import java.util.Base64
import java.util.UUID

@Controller
class DocumentController(
    private val librarianService: LibrarianService
) {

    @QueryMapping
    suspend fun documents(
        @Argument collectionId: String,
        @Argument includeChildren: Boolean?
    ): List<Document> {
        return librarianService.listDocuments(
            collectionId = UUID.fromString(collectionId),
            includeChildren = includeChildren ?: false
        )
    }

    @QueryMapping
    suspend fun document(@Argument id: String): Document? {
        return librarianService.getDocument(id)
    }

    /**
     * Resolver fuer Document.children -- laedt Kind-Dokumente lazy.
     */
    @SchemaMapping(typeName = "Document", field = "children")
    suspend fun children(document: Document): List<Document> {
        return librarianService.getChildren(document.id)
    }

    @MutationMapping
    suspend fun uploadDocument(@Argument input: UploadDocumentInput): Document {
        val content = Base64.getDecoder().decode(input.content)
        return librarianService.uploadDocument(
            collectionId = UUID.fromString(input.collectionId),
            title = input.title,
            mimeType = input.mimeType,
            content = content,
            metadata = input.metadata?.associate { it.key to it.value } ?: emptyMap()
        )
    }

    @MutationMapping
    suspend fun deleteDocument(@Argument id: String): Boolean {
        librarianService.deleteDocument(id)
        return true
    }
}

data class UploadDocumentInput(
    val collectionId: String,
    val title: String,
    val mimeType: String,
    val content: String,
    val metadata: List<KeyValueInput>?
)
```

### GraphController

```kotlin
package com.graphmesh.api.graphql

import com.graphmesh.rdf.Quad
import com.graphmesh.rdf.RdfTerm
import com.graphmesh.storage.cassandra.QuadStore
import com.graphmesh.storage.cassandra.QuadQuery
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class GraphController(
    private val quadStore: QuadStore
) {

    @QueryMapping
    suspend fun triples(
        @Argument collectionId: String,
        @Argument subject: String?,
        @Argument predicate: String?,
        @Argument objectArg: String?,
        @Argument graph: String?,
        @Argument limit: Int?
    ): List<Quad> {
        val query = QuadQuery(
            collection = collectionId,
            subject = subject,
            predicate = predicate,
            objectValue = objectArg,
            graph = graph,
            limit = limit ?: 100
        )
        return quadStore.query(query)
    }
}
```

### SearchController

```kotlin
package com.graphmesh.api.graphql

import com.graphmesh.storage.qdrant.VectorStore
import com.graphmesh.llm.EmbeddingService
import com.graphmesh.librarian.LibrarianService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class SearchController(
    private val vectorStore: VectorStore,
    private val embeddingService: EmbeddingService,
    private val librarianService: LibrarianService
) {

    /**
     * Semantische Suche: Text -> Embedding -> Nearest Neighbors in Qdrant.
     */
    @QueryMapping
    suspend fun vectorSearch(
        @Argument collectionId: String,
        @Argument query: String,
        @Argument limit: Int?
    ): List<SearchResult> {
        // Query-Text vektorisieren
        val queryVector = embeddingService.embed(query)

        // Nearest Neighbors in Qdrant suchen
        val results = vectorStore.search(
            collection = "doc-embeddings-$collectionId",
            vector = queryVector,
            limit = limit ?: 10
        )

        // Chunk-Text aus dem Librarian laden fuer die Antwort
        return results.map { result ->
            val chunkId = result.payload["chunk_id"] ?: ""
            val text = try {
                val content = librarianService.getContent(chunkId)
                content.toString(Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }

            SearchResult(
                chunkId = chunkId,
                score = result.score,
                documentId = result.payload["document_id"],
                text = text
            )
        }
    }
}

data class SearchResult(
    val chunkId: String,
    val score: Float,
    val documentId: String?,
    val text: String?
)
```

### GraphQL Error Handling

```kotlin
package com.graphmesh.api.graphql

import com.graphmesh.collection.CollectionNotFoundException
import com.graphmesh.librarian.DocumentNotFoundException
import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.stereotype.Component

/**
 * Zentrale Fehlerbehandlung fuer GraphQL-Requests.
 * Konvertiert Domain-Exceptions in strukturierte GraphQL-Errors.
 */
@Component
class GraphMeshExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(
        ex: Throwable,
        env: DataFetchingEnvironment
    ): GraphQLError? {
        return when (ex) {
            is CollectionNotFoundException -> GraphQLError.newError()
                .message(ex.message)
                .extensions(mapOf("code" to "COLLECTION_NOT_FOUND"))
                .build()

            is DocumentNotFoundException -> GraphQLError.newError()
                .message(ex.message)
                .extensions(mapOf("code" to "DOCUMENT_NOT_FOUND"))
                .build()

            is IllegalArgumentException -> GraphQLError.newError()
                .message(ex.message)
                .extensions(mapOf("code" to "BAD_REQUEST"))
                .build()

            else -> GraphQLError.newError()
                .message("Interner Serverfehler")
                .extensions(mapOf("code" to "INTERNAL_ERROR"))
                .build()
        }
    }
}
```

### Spring Boot Konfiguration

```yaml
# api/src/main/resources/application.yml
spring:
  graphql:
    graphiql:
      enabled: true
      path: /graphiql
    schema:
      locations: classpath:graphql/
      printer:
        enabled: true
    websocket:
      path: /graphql
```

## Betroffene Dateien

### Backend

| Datei                                                                         | Aenderung                                                 |
|-------------------------------------------------------------------------------|-----------------------------------------------------------|
| `api/src/main/resources/graphql/schema.graphqls`                              | NEU - GraphQL-Schema-Definition                           |
| `api/src/main/kotlin/com/graphmesh/api/graphql/CollectionController.kt`       | NEU - Collection Queries und Mutations                    |
| `api/src/main/kotlin/com/graphmesh/api/graphql/DocumentController.kt`         | NEU - Document Queries und Mutations                      |
| `api/src/main/kotlin/com/graphmesh/api/graphql/GraphController.kt`            | NEU - Triple/Quad Queries                                 |
| `api/src/main/kotlin/com/graphmesh/api/graphql/SearchController.kt`           | NEU - Vektor-Suche                                        |
| `api/src/main/kotlin/com/graphmesh/api/graphql/GraphMeshExceptionResolver.kt` | NEU - Fehlerbehandlung                                    |
| `api/src/main/kotlin/com/graphmesh/api/graphql/InputTypes.kt`                 | NEU - GraphQL Input-Typen                                 |
| `api/build.gradle.kts`                                                        | NEU/AENDERUNG - spring-boot-starter-graphql Abhaengigkeit |

### Frontend

Nicht betroffen (GraphiQL als eingebaute Test-UI verfuegbar).

### Tests

| Datei                                                                       | Aenderung                                          |
|-----------------------------------------------------------------------------|----------------------------------------------------|
| `api/src/test/kotlin/com/graphmesh/api/graphql/CollectionControllerTest.kt` | NEU - Collection-Query- und Mutation-Tests         |
| `api/src/test/kotlin/com/graphmesh/api/graphql/DocumentControllerTest.kt`   | NEU - Document-Query- und Upload-Tests             |
| `api/src/test/kotlin/com/graphmesh/api/graphql/GraphControllerTest.kt`      | NEU - Triple-Query-Tests mit verschiedenen Filtern |
| `api/src/test/kotlin/com/graphmesh/api/graphql/SearchControllerTest.kt`     | NEU - Vektor-Suche End-to-End                      |
| `api/src/test/kotlin/com/graphmesh/api/graphql/ErrorHandlingTest.kt`        | NEU - GraphQL-Fehlertypen und Error-Extensions     |
| `api/src/test/kotlin/com/graphmesh/api/graphql/SchemaValidationTest.kt`     | NEU - Schema-Validierung und Introspection         |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                              |
|-------------------|-------------|----------------------------------------------------|
| Spring Boot (JVM) | Ja          | Spring Boot Starter GraphQL native Integration     |
| KMP Library       | Nein        | Spring-spezifische Annotations und GraphQL-Runtime |
| Ktor/Wasm         | Nein        | Spring Boot GraphQL ist JVM-spezifisch             |

## Akzeptanzkriterien

- [ ] GraphQL-Endpunkt unter `/graphql` erreichbar und verarbeitet Queries
- [ ] GraphiQL-IDE unter `/graphiql` im Browser nutzbar
- [ ] `collections`-Query listet alle Collections, optional gefiltert nach Tags
- [ ] `documents`-Query listet Dokumente einer Collection, `includeChildren` steuert Kind-Dokumente
- [ ] `triples`-Query filtert Quads nach Subject, Predicate, Object und Graph
- [ ] `vectorSearch`-Query fuehrt semantische Suche durch und liefert Chunk-Texte zurueck
- [ ] `createCollection`-Mutation erstellt Collection und synchronisiert Backends
- [ ] `uploadDocument`-Mutation nimmt Base64-Inhalt entgegen und erstellt Dokument im Librarian
- [ ] `deleteCollection`- und `deleteDocument`-Mutations loeschen kaskadiert
- [ ] Domain-Exceptions werden als strukturierte GraphQL-Errors mit `extensions.code` zurueckgegeben
- [ ] WebSocket-Endpunkt unter `/graphql` unterstuetzt GraphQL-Subscriptions
- [ ] Schema-Introspection funktioniert fuer Tooling-Kompatibilitaet (Apollo, Postman)
