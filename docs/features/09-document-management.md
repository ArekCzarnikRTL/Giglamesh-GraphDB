# Feature 09: Document Management (Librarian)

## Problem

GraphMesh braucht eine zentrale Dokumentenverwaltung, die Metadaten und Inhalte getrennt speichert und eine
hierarchische Beziehung zwischen Quelldokumenten, Seiten und Chunks abbildet. Ohne ein einheitliches
Document-Lifecycle-Management gibt es keine konsistente Verwaltung von Dokumentzustaenden (Upload, Verarbeitung,
Extraktion) und keinen Mechanismus fuer die Nachverfolgung der Dokumentherkunft in der Extraktionspipeline.

## Ziel

Implementierung eines Librarian-Moduls fuer die Verwaltung von Dokumenten mit Metadaten in Cassandra, Inhalten in S3 und
einer Parent-Child-Hierarchie (Source -> Pages -> Chunks).

1. **Getrennte Speicherung** -- Metadaten in Cassandra, Dokumentinhalte in S3 via BlobStore
2. **Parent-Child-Hierarchie** -- Quelldokument -> Seiten -> Chunks als verknuepfte Dokumentstruktur
3. **Document States** -- Zustandsmaschine: UPLOADED -> PROCESSING -> EXTRACTED -> FAILED
4. **Format-Unterstuetzung** -- PDF, Text, HTML und weitere Formate ueber MIME-Type-Erkennung
5. **CRUD-Operationen** -- Erstellen, Lesen, Aktualisieren und Loeschen von Dokumenten mit Cascade Delete

## Voraussetzungen

| Abhaengigkeit                       | Status     | Blocker? |
|-------------------------------------|------------|----------|
| Feature 02: Cassandra Storage Layer | Geplant    | Ja       |
| Feature 03: S3 Blob Storage         | Geplant    | Ja       |
| Feature 08: Collection Management   | Geplant    | Ja       |
| Spring Boot 4.x                     | Verfuegbar | Nein     |

## Architektur

### Document Datenmodell

```kotlin
package com.graphmesh.librarian

import java.time.Instant
import java.util.UUID

/**
 * Dokumentzustand in der Extraktionspipeline.
 */
enum class DocumentState {
    UPLOADED,     // Dokument hochgeladen, noch nicht verarbeitet
    PROCESSING,   // Extraktion laeuft
    EXTRACTED,    // Extraktion erfolgreich abgeschlossen
    FAILED        // Extraktion fehlgeschlagen
}

/**
 * Dokumenttyp in der Parent-Child-Hierarchie.
 */
enum class DocumentType {
    SOURCE,   // Vom Benutzer hochgeladenes Quelldokument
    PAGE,     // Extrahierte Seite aus einem PDF oder aehnlichem Format
    CHUNK     // Textabschnitt, aus einer Seite oder direkt aus dem Quelltext
}

/**
 * Zentrales Datenmodell fuer Dokumente im Librarian.
 *
 * Die ID-Hierarchie kodiert die Herkunft:
 * - Source: "doc-123"
 * - Page:  "doc-123/p5"
 * - Chunk: "doc-123/p5/c2"
 */
data class Document(
    val id: String,
    val collectionId: UUID,
    val parentId: String? = null,
    val type: DocumentType = DocumentType.SOURCE,
    val state: DocumentState = DocumentState.UPLOADED,
    val title: String = "",
    val mimeType: String = "application/octet-stream",
    val contentUri: String = "",          // S3 Object-Key: "doc/{objectId}"
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
```

### DocumentStore Interface

```kotlin
package com.graphmesh.librarian

import java.util.UUID

/**
 * Persistenz-Interface fuer Dokument-Metadaten in Cassandra.
 */
interface DocumentStore {

    suspend fun save(document: Document)

    suspend fun findById(id: String): Document?

    /**
     * Listet alle Quelldokumente einer Collection.
     * Child-Dokumente (Pages, Chunks) werden standardmaessig ausgeblendet.
     */
    suspend fun findByCollection(
        collectionId: UUID,
        includeChildren: Boolean = false
    ): List<Document>

    /**
     * Findet alle Kind-Dokumente eines Elterndokuments.
     */
    suspend fun findChildren(parentId: String): List<Document>

    suspend fun updateState(id: String, state: DocumentState)

    suspend fun delete(id: String)

    /**
     * Kaskadierte Loeschung: Elterndokument und alle Kinder.
     */
    suspend fun deleteWithChildren(id: String)
}
```

### LibrarianService

```kotlin
package com.graphmesh.librarian

import com.graphmesh.collection.CollectionService
import com.graphmesh.storage.s3.BlobStore
import java.util.UUID

/**
 * Zentraler Service fuer Dokument-Lifecycle-Management.
 * Koordiniert Metadaten (Cassandra) und Inhalte (S3).
 */
class LibrarianService(
    private val documentStore: DocumentStore,
    private val blobStore: BlobStore,
    private val collectionService: CollectionService
) {

    /**
     * Laedt ein neues Dokument hoch.
     *
     * 1. Prueft ob Collection existiert
     * 2. Speichert Inhalt in S3
     * 3. Erstellt Metadaten in Cassandra
     */
    suspend fun uploadDocument(
        collectionId: UUID,
        title: String,
        mimeType: String,
        content: ByteArray,
        metadata: Map<String, String> = emptyMap()
    ): Document {
        // Collection muss existieren (Feature 08)
        collectionService.requireExists(collectionId)

        val objectId = UUID.randomUUID()
        val contentUri = "doc/$objectId"

        // Inhalt in S3 speichern
        blobStore.put(contentUri, content, mimeType)

        val document = Document(
            id = "doc-${objectId}",
            collectionId = collectionId,
            title = title,
            mimeType = mimeType,
            contentUri = contentUri,
            metadata = metadata,
            state = DocumentState.UPLOADED
        )

        documentStore.save(document)
        return document
    }

    /**
     * Erstellt ein Kind-Dokument (Page oder Chunk).
     * Wird von der Extraktionspipeline aufgerufen.
     */
    suspend fun createChildDocument(
        parentId: String,
        type: DocumentType,
        title: String,
        content: ByteArray,
        mimeType: String = "text/plain"
    ): Document {
        val parent = documentStore.findById(parentId)
            ?: throw DocumentNotFoundException(parentId)

        val objectId = UUID.randomUUID()
        val contentUri = "doc/$objectId"
        blobStore.put(contentUri, content, mimeType)

        val childId = when (type) {
            DocumentType.PAGE -> {
                val pageCount = documentStore.findChildren(parentId)
                    .count { it.type == DocumentType.PAGE }
                "${parentId}/p${pageCount + 1}"
            }
            DocumentType.CHUNK -> {
                val chunkCount = documentStore.findChildren(parentId)
                    .count { it.type == DocumentType.CHUNK }
                "${parentId}/c${chunkCount + 1}"
            }
            DocumentType.SOURCE -> throw IllegalArgumentException(
                "Kind-Dokument kann nicht vom Typ SOURCE sein"
            )
        }

        val child = Document(
            id = childId,
            collectionId = parent.collectionId,
            parentId = parentId,
            type = type,
            title = title,
            mimeType = mimeType,
            contentUri = contentUri,
            state = DocumentState.EXTRACTED
        )

        documentStore.save(child)
        return child
    }

    /**
     * Liest den Inhalt eines Dokuments aus S3.
     */
    suspend fun getContent(documentId: String): ByteArray {
        val document = documentStore.findById(documentId)
            ?: throw DocumentNotFoundException(documentId)
        return blobStore.get(document.contentUri)
    }

    /**
     * Aktualisiert den Dokumentzustand.
     */
    suspend fun updateState(documentId: String, state: DocumentState) {
        documentStore.updateState(documentId, state)
    }

    /**
     * Loescht ein Dokument und alle Kinder (Cascade).
     * Entfernt sowohl Metadaten als auch S3-Inhalte.
     */
    suspend fun deleteDocument(documentId: String) {
        val document = documentStore.findById(documentId)
            ?: throw DocumentNotFoundException(documentId)

        // Kinder rekursiv loeschen
        val children = documentStore.findChildren(documentId)
        for (child in children) {
            blobStore.delete(child.contentUri)
            // Rekursiv fuer verschachtelte Kinder (Chunks unter Pages)
            val grandchildren = documentStore.findChildren(child.id)
            for (gc in grandchildren) {
                blobStore.delete(gc.contentUri)
            }
        }

        // S3-Inhalt des Elterndokuments loeschen
        blobStore.delete(document.contentUri)

        // Metadaten kaskadiert loeschen
        documentStore.deleteWithChildren(documentId)
    }
}
```

### Cassandra Schema

```sql
CREATE TABLE IF NOT EXISTS documents (
    id          text,
    collection_id uuid,
    parent_id   text,
    type        text,
    state       text,
    title       text,
    mime_type   text,
    content_uri text,
    metadata    map<text, text>,
    created_at  timestamp,
    updated_at  timestamp,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS documents_collection_idx ON documents (collection_id);
CREATE INDEX IF NOT EXISTS documents_parent_idx ON documents (parent_id);
CREATE INDEX IF NOT EXISTS documents_state_idx ON documents (state);
```

### Document-ID-Hierarchie

Die ID-Struktur kodiert die Herkunft eines Dokuments:

| Dokument-Typ   | ID-Beispiel     | Beschreibung                        |
|----------------|-----------------|-------------------------------------|
| Source         | `doc-123`       | Vom Benutzer hochgeladenes Dokument |
| Page           | `doc-123/p5`    | Seite 5 des Quelldokuments          |
| Chunk aus Page | `doc-123/p5/c2` | Chunk 2 der Seite 5                 |
| Chunk aus Text | `doc-123/c2`    | Chunk 2 eines Textdokuments         |

## Betroffene Dateien

### Backend

| Datei                                                                            | Aenderung                                              |
|----------------------------------------------------------------------------------|--------------------------------------------------------|
| `librarian/src/main/kotlin/com/graphmesh/librarian/Document.kt`                  | NEU - Document Data Class, DocumentState, DocumentType |
| `librarian/src/main/kotlin/com/graphmesh/librarian/DocumentStore.kt`             | NEU - Persistenz-Interface                             |
| `librarian/src/main/kotlin/com/graphmesh/librarian/LibrarianService.kt`          | NEU - Zentraler Document-Lifecycle-Service             |
| `librarian/src/main/kotlin/com/graphmesh/librarian/DocumentNotFoundException.kt` | NEU - Exception                                        |
| `librarian/src/main/kotlin/com/graphmesh/librarian/CassandraDocumentStore.kt`    | NEU - Cassandra-Implementierung                        |
| `librarian/build.gradle.kts`                                                     | NEU - Gradle-Modul                                     |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                             | Aenderung                                        |
|-----------------------------------------------------------------------------------|--------------------------------------------------|
| `librarian/src/test/kotlin/com/graphmesh/librarian/LibrarianServiceTest.kt`       | NEU - Unit-Tests fuer CRUD und State-Transitions |
| `librarian/src/test/kotlin/com/graphmesh/librarian/CassandraDocumentStoreTest.kt` | NEU - Integrationstests mit Testcontainers       |
| `librarian/src/test/kotlin/com/graphmesh/librarian/DocumentHierarchyTest.kt`      | NEU - Parent-Child-Hierarchie und Cascade Delete |
| `librarian/src/test/kotlin/com/graphmesh/librarian/DocumentStateTest.kt`          | NEU - Zustandsuebergaenge validieren             |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                           |
|-------------------|-------------|-------------------------------------------------|
| Spring Boot (JVM) | Ja          | Spring Data Cassandra, S3-Client Integration    |
| KMP Library       | Nein        | Abhaengigkeit zu Cassandra-Driver und S3-Client |
| Ktor/Wasm         | Nein        | JVM-spezifische Storage-APIs                    |

## Akzeptanzkriterien

- [ ] Dokument-Upload speichert Metadaten in Cassandra und Inhalt in S3 getrennt
- [ ] Upload wird abgelehnt, wenn die referenzierte Collection nicht existiert
- [ ] Kind-Dokumente (Pages, Chunks) werden korrekt mit Parent-ID verknuepft
- [ ] Document-IDs folgen der Hierarchie-Konvention (`doc-123/p5/c2`)
- [ ] Zustandsuebergaenge UPLOADED -> PROCESSING -> EXTRACTED/FAILED werden korrekt verwaltet
- [ ] Cascade Delete loescht Elterndokument, alle Kinder und zugehoerige S3-Objekte
- [ ] `findByCollection` listet standardmaessig nur Source-Dokumente (keine Kinder)
- [ ] `findChildren` liefert alle direkten Kinder eines Dokuments
- [ ] PDF, Text, HTML werden ueber den MIME-Type korrekt erkannt und gespeichert
- [ ] Inhalt kann ueber `getContent()` aus S3 abgerufen werden
