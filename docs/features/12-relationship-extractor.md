# Feature 12: Relationship Extractor

## Problem

Textchunks enthalten implizite Wissensbeziehungen (z.B. "Alice arbeitet bei Acme Corp"), die als strukturierte
Subject-Predicate-Object-Triples extrahiert und im Knowledge Graph gespeichert werden muessen. Ohne eine LLM-basierte
Extraktionspipeline bleiben diese Beziehungen unstrukturiert im Rohtext verborgen und koennen weder fuer Graph-Abfragen
noch fuer RAG-basierte Antworten genutzt werden.

## Ziel

Implementierung eines Kafka-basierten Relationship Extractors, der Textchunks mit Hilfe eines LLM in RDF-Triples
umwandelt und ueber den QuadStore in Cassandra persistiert.

1. **Kafka-Consumer** -- Empfaengt `chunk.created`-Events vom Chunker (Feature 11)
2. **LLM-basierte Extraktion** -- Verwendet `LlmProvider` (Feature 05) mit einem Prompt-Template zur Triple-Extraktion
3. **RDF-Triple-Generierung** -- Erzeugt typsichere `Quad`-Objekte mit `RdfTerm` (Feature 07)
4. **Cassandra-Persistenz** -- Speichert extrahierte Quads ueber `QuadStore` (Feature 02)
5. **Deterministische Entity-IDs** -- Verwendet `EntityIdGenerator` (Feature 07) fuer konsistente Entitaetsreferenzen

## Voraussetzungen

| Abhaengigkeit                                                             | Status     | Blocker? |
|---------------------------------------------------------------------------|------------|----------|
| Feature 01: Kafka Messaging Infrastructure                                | Geplant    | Ja       |
| Feature 02: Cassandra Storage Layer (QuadStore)                           | Geplant    | Ja       |
| Feature 05: LLM Provider Abstraction (LlmProvider, ChatCompletionService) | Geplant    | Ja       |
| Feature 07: RDF Graph Model (Quad, RdfTerm, EntityIdGenerator)            | Geplant    | Ja       |
| Feature 11: Document Chunker (liefert chunk.created Events)               | Geplant    | Ja       |
| Spring Boot 4.x                                                           | Verfuegbar | Nein     |

## Architektur

### ExtractionPromptTemplate

```kotlin
package com.graphmesh.extraction.relationship

/**
 * Prompt-Template fuer die LLM-basierte Triple-Extraktion.
 *
 * Das Template instruiert das LLM, strukturierte Beziehungen
 * im Format Subject|Predicate|Object aus dem Text zu extrahieren.
 */
object ExtractionPromptTemplate {

    /**
     * Generiert den System-Prompt fuer die Extraktion.
     */
    fun systemPrompt(): String = """
        Du bist ein Wissensextraktions-Assistent. Deine Aufgabe ist es,
        strukturierte Beziehungen aus dem gegebenen Text zu extrahieren.

        Extrahiere Subject-Predicate-Object Triples im folgenden Format:
        SUBJECT|PREDICATE|OBJECT

        Regeln:
        - Subjects und Objects sind Entitaeten (Personen, Organisationen, Orte, Konzepte)
        - Predicates beschreiben die Beziehung zwischen Subject und Object
        - Verwende klare, kanonische Bezeichnungen fuer Entitaeten
        - Extrahiere nur explizit im Text genannte Beziehungen
        - Jedes Triple auf einer eigenen Zeile
        - Keine Nummerierung, keine Aufzaehlungszeichen

        Beispiel:
        Text: "Alice arbeitet seit 2020 bei Acme Corp in Berlin."
        Alice|arbeitetBei|Acme Corp
        Alice|arbeitetIn|Berlin
        Acme Corp|hatStandort|Berlin
    """.trimIndent()

    /**
     * Generiert den User-Prompt mit dem zu analysierenden Text.
     */
    fun userPrompt(chunkText: String): String = """
        Extrahiere alle Subject-Predicate-Object Beziehungen aus folgendem Text:

        ---
        $chunkText
        ---

        Antworte NUR mit den Triples im Format SUBJECT|PREDICATE|OBJECT, eines pro Zeile.
    """.trimIndent()
}
```

### RelationshipExtractorService

```kotlin
package com.graphmesh.extraction.relationship

import com.graphmesh.llm.ChatCompletionService
import com.graphmesh.llm.ChatMessage
import com.graphmesh.llm.ChatRole
import com.graphmesh.rdf.*
import com.graphmesh.storage.cassandra.QuadStore
import com.graphmesh.librarian.LibrarianService
import java.util.UUID

/**
 * Extrahiert Beziehungen aus Textchunks mittels LLM und speichert
 * sie als RDF-Quads im Knowledge Graph.
 */
class RelationshipExtractorService(
    private val chatService: ChatCompletionService,
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService
) {

    /**
     * Extrahiert Triples aus einem Chunk und speichert sie als Quads.
     *
     * 1. Chunk-Text vom Librarian laden
     * 2. LLM-Prompt zusammenbauen und senden
     * 3. Antwort parsen (SUBJECT|PREDICATE|OBJECT pro Zeile)
     * 4. Deterministische Entity-IDs generieren
     * 5. Quads im Default-Graph speichern
     * 6. Provenance-Quads im Source-Graph speichern
     */
    suspend fun extractRelationships(
        chunkId: String,
        collectionId: UUID
    ): ExtractionResult {
        val content = librarianService.getContent(chunkId)
        val chunkText = content.toString(Charsets.UTF_8)

        // LLM-Extraktion
        val messages = listOf(
            ChatMessage(role = ChatRole.SYSTEM, content = ExtractionPromptTemplate.systemPrompt()),
            ChatMessage(role = ChatRole.USER, content = ExtractionPromptTemplate.userPrompt(chunkText))
        )
        val response = chatService.complete(messages)

        // Antwort parsen
        val rawTriples = parseTriples(response.content)

        // Quads mit deterministischen Entity-IDs erzeugen
        val quads = rawTriples.map { (subject, predicate, objectValue) ->
            Quad(
                subject = EntityIdGenerator.generate(subject),
                predicate = RdfTerm.Uri("http://graphmesh.io/ontology/${normalizePredicateName(predicate)}"),
                objectTerm = EntityIdGenerator.generate(objectValue),
                graph = NamedGraph.DEFAULT
            )
        }

        // Quads im Knowledge Graph speichern
        quadStore.saveAll(collectionId.toString(), quads)

        // Provenance-Quads: Verknuepfung mit Quell-Chunk
        val provenanceQuads = quads.map { quad ->
            Quad(
                subject = RdfTerm.QuotedTriple(quad.triple),
                predicate = RdfTerm.Uri("http://graphmesh.io/ontology/extractedFrom"),
                objectTerm = RdfTerm.Uri("urn:chunk:$chunkId"),
                graph = NamedGraph.SOURCE
            )
        }
        quadStore.saveAll(collectionId.toString(), provenanceQuads)

        // Label-Triples fuer Entity-Resolution
        val labelQuads = rawTriples.flatMap { (subject, _, objectValue) ->
            listOf(
                Quad(
                    subject = EntityIdGenerator.generate(subject),
                    predicate = RdfTerm.Uri("http://www.w3.org/2000/01/rdf-schema#label"),
                    objectTerm = RdfTerm.Literal(subject),
                    graph = NamedGraph.DEFAULT
                ),
                Quad(
                    subject = EntityIdGenerator.generate(objectValue),
                    predicate = RdfTerm.Uri("http://www.w3.org/2000/01/rdf-schema#label"),
                    objectTerm = RdfTerm.Literal(objectValue),
                    graph = NamedGraph.DEFAULT
                )
            )
        }.distinctBy { it.subject.toNTriples() + it.objectTerm.toNTriples() }

        quadStore.saveAll(collectionId.toString(), labelQuads)

        return ExtractionResult(
            chunkId = chunkId,
            triplesExtracted = quads.size,
            entitiesFound = rawTriples.flatMap { listOf(it.first, it.third) }.distinct().size
        )
    }

    /**
     * Parst die LLM-Antwort in (Subject, Predicate, Object)-Tupel.
     */
    internal fun parseTriples(llmResponse: String): List<kotlin.Triple<String, String, String>> {
        return llmResponse.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("|") }
            .mapNotNull { line ->
                val parts = line.split("|").map { it.trim() }
                if (parts.size == 3 && parts.all { it.isNotBlank() }) {
                    kotlin.Triple(parts[0], parts[1], parts[2])
                } else {
                    null
                }
            }
    }

    /**
     * Normalisiert einen Predikat-Namen zu einem URI-kompatiblen camelCase.
     */
    internal fun normalizePredicateName(predicate: String): String {
        return predicate.trim()
            .split(Regex("\\s+"))
            .mapIndexed { index, word ->
                if (index == 0) word.lowercase()
                else word.replaceFirstChar { it.uppercase() }
            }
            .joinToString("")
    }
}
```

### RelationshipExtractorConsumer

```kotlin
package com.graphmesh.extraction.relationship

import com.graphmesh.extraction.chunker.ChunkCreatedEvent
import com.graphmesh.messaging.MessageConsumer

/**
 * Kafka-Consumer fuer Relationship-Extraktion.
 * Lauscht auf chunk.created Events.
 */
class RelationshipExtractorConsumer(
    private val consumer: MessageConsumer<ChunkCreatedEvent>,
    private val extractorService: RelationshipExtractorService
) {
    fun start() {
        consumer.subscribe { message ->
            val event = message.payload
            extractorService.extractRelationships(
                chunkId = event.chunkId,
                collectionId = event.collectionId
            )
        }
    }
}
```

### Datenmodelle

```kotlin
package com.graphmesh.extraction.relationship

/**
 * Ergebnis einer Relationship-Extraktion.
 */
data class ExtractionResult(
    val chunkId: String,
    val triplesExtracted: Int,
    val entitiesFound: Int
)
```

### Kafka-Topics

| Topic                     | Richtung  | Schema              |
|---------------------------|-----------|---------------------|
| `graphmesh.chunk.created` | Eingehend | `ChunkCreatedEvent` |

## Betroffene Dateien

### Backend

| Datei                                                                                               | Aenderung                            |
|-----------------------------------------------------------------------------------------------------|--------------------------------------|
| `extraction/src/main/kotlin/com/graphmesh/extraction/relationship/RelationshipExtractorService.kt`  | NEU - LLM-basierte Triple-Extraktion |
| `extraction/src/main/kotlin/com/graphmesh/extraction/relationship/RelationshipExtractorConsumer.kt` | NEU - Kafka-Consumer                 |
| `extraction/src/main/kotlin/com/graphmesh/extraction/relationship/ExtractionPromptTemplate.kt`      | NEU - Prompt-Template fuer LLM       |
| `extraction/src/main/kotlin/com/graphmesh/extraction/relationship/ExtractionResult.kt`              | NEU - Ergebnis-Datenklasse           |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                                  | Aenderung                                    |
|--------------------------------------------------------------------------------------------------------|----------------------------------------------|
| `extraction/src/test/kotlin/com/graphmesh/extraction/relationship/RelationshipExtractorServiceTest.kt` | NEU - Unit-Tests fuer Extraktion und Parsing |
| `extraction/src/test/kotlin/com/graphmesh/extraction/relationship/ExtractionPromptTemplateTest.kt`     | NEU - Prompt-Template-Validierung            |
| `extraction/src/test/kotlin/com/graphmesh/extraction/relationship/TripleParsingTest.kt`                | NEU - Parsen der LLM-Antworten (Edge Cases)  |
| `extraction/src/test/kotlin/com/graphmesh/extraction/relationship/ProvenanceTest.kt`                   | NEU - Provenance-Quads im Source-Graph       |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                        |
|-------------------|-------------|----------------------------------------------|
| Spring Boot (JVM) | Ja          | LLM-Client, Kafka-Consumer, Cassandra-Client |
| KMP Library       | Nein        | Abhaengigkeit zu JVM-spezifischen Clients    |
| Ktor/Wasm         | Nein        | LLM- und Kafka-Clients sind JVM-spezifisch   |

## Akzeptanzkriterien

- [ ] Extractor empfaengt `chunk.created`-Events und verarbeitet den zugehoerigen Chunk-Text
- [ ] LLM-Prompt extrahiert Subject-Predicate-Object-Triples im definierten Format
- [ ] Extrahierte Triples werden als `Quad`-Objekte mit korrekten `RdfTerm`-Typen erzeugt
- [ ] Subjects und Objects erhalten deterministische Entity-IDs via `EntityIdGenerator` (Feature 07)
- [ ] Knowledge-Quads werden im Default-Graph (`""`) gespeichert
- [ ] Provenance-Quads mit `QuotedTriple` (RDF-Star) werden im Source-Graph (`urn:graph:source`) gespeichert
- [ ] Label-Triples (`rdfs:label`) werden fuer jede extrahierte Entitaet erzeugt (dedupliziert)
- [ ] Ungueltige LLM-Antwortzeilen (kein Pipe-Format, leere Felder) werden uebersprungen
- [ ] Predicate-Namen werden zu URI-kompatiblem camelCase normalisiert
- [ ] `ExtractionResult` enthaelt korrekte Zaehler fuer extrahierte Triples und gefundene Entitaeten
