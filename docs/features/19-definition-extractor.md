# Feature 19: Definition Extractor

## Problem

Textchunks enthalten haeufig erklaerende Passagen, die Entitaeten definieren ("Was ist X?", "X bezeichnet..."). Diese
Definitionen sind wertvolle Wissensbausteine, die als einzelne Entitaetsbeschreibungen im Knowledge Graph gespeichert
werden muessen. Im Gegensatz zur Beziehungsextraktion (Feature 12) fokussiert sich der Definition Extractor auf die
Beschreibung einzelner Entitaeten, nicht auf Relationen zwischen mehreren Entitaeten.

## Ziel

Implementierung eines Kafka-basierten Definition Extractors, der Textchunks mit Hilfe eines LLM nach
Entitaetsdefinitionen durchsucht und diese als `rdfs:comment`-Triples im Knowledge Graph speichert.

1. **Kafka-Consumer** -- Empfaengt `chunk.created`-Events vom Chunker (Feature 11)
2. **LLM-basierte Definitions-Extraktion** -- Verwendet `LlmProvider` (Feature 05) mit einem spezialisierten
   Prompt-Template
3. **RDF-Triple-Generierung** -- Erzeugt Triples im Format `(entity, rdfs:comment, definition_text)` (Feature 07)
4. **Cassandra-Persistenz** -- Speichert extrahierte Quads ueber `QuadStore` (Feature 02)
5. **JSONL-Output** -- Truncation-resistentes Ausgabeformat fuer LLM-Antworten

## Voraussetzungen

| Abhaengigkeit                                                             | Status     | Blocker? |
|---------------------------------------------------------------------------|------------|----------|
| Feature 01: Kafka Messaging Infrastructure (MessageConsumer)              | Geplant    | Ja       |
| Feature 05: LLM Provider Abstraction (LlmProvider, ChatCompletionService) | Geplant    | Ja       |
| Feature 07: RDF Graph Model (Quad, RdfTerm, EntityIdGenerator)            | Geplant    | Ja       |
| Feature 11: Document Chunker (liefert chunk.created Events)               | Geplant    | Ja       |
| Spring Boot 3.x                                                           | Verfuegbar | Nein     |

## Architektur

### DefinitionResult

```kotlin
package com.graphmesh.extraction.definition

/**
 * Ergebnis einer einzelnen Definition-Extraktion aus dem LLM.
 */
data class DefinitionResult(
    val entity: String,
    val definition: String
)

/**
 * Gesamtergebnis der Definitions-Extraktion fuer einen Chunk.
 */
data class DefinitionExtractionResult(
    val chunkId: String,
    val definitionsExtracted: Int,
    val entitiesFound: List<String>
)
```

### DefinitionPromptTemplate

```kotlin
package com.graphmesh.extraction.definition

/**
 * Prompt-Template fuer die LLM-basierte Definitions-Extraktion.
 *
 * Das Template instruiert das LLM, erklaerende Passagen im Text zu erkennen
 * und als Entity-Definition-Paare im JSONL-Format auszugeben.
 * Unterschied zur Relationship-Extraktion: Hier werden keine Beziehungen
 * zwischen Entitaeten extrahiert, sondern Beschreibungen einzelner Entitaeten.
 */
object DefinitionPromptTemplate {

    fun systemPrompt(): String = """
        Du bist ein Wissensextraktions-Assistent. Deine Aufgabe ist es,
        Definitionen und Beschreibungen von Entitaeten aus dem gegebenen Text
        zu extrahieren.

        Extrahiere fuer jede Entitaet, die im Text definiert oder beschrieben wird,
        ein JSON-Objekt pro Zeile im folgenden Format:
        {"entity": "<Name der Entitaet>", "definition": "<Definition oder Beschreibung>"}

        Regeln:
        - Extrahiere nur explizit im Text enthaltene Definitionen
        - Eine Definition beschreibt WAS eine Entitaet IST oder WAS sie TUT
        - Ignoriere reine Beziehungen zwischen Entitaeten (z.B. "A arbeitet bei B")
        - Verwende klare, kanonische Bezeichnungen fuer Entitaeten
        - Die Definition soll ein vollstaendiger, verstaendlicher Satz sein
        - Jedes JSON-Objekt auf einer eigenen Zeile (JSONL-Format)

        Beispiel:
        Text: "Photosynthese ist der Prozess, bei dem Pflanzen Sonnenlicht
        in chemische Energie umwandeln. Chlorophyll ist das gruene Pigment,
        das diesen Prozess ermoeglicht."

        {"entity": "Photosynthese", "definition": "Prozess, bei dem Pflanzen Sonnenlicht in chemische Energie umwandeln"}
        {"entity": "Chlorophyll", "definition": "Gruenes Pigment, das den Prozess der Photosynthese ermoeglicht"}
    """.trimIndent()

    fun userPrompt(chunkText: String): String = """
        Extrahiere alle Entitaets-Definitionen aus folgendem Text:

        ---
        $chunkText
        ---

        Antworte NUR mit JSON-Objekten im JSONL-Format, eines pro Zeile.
    """.trimIndent()
}
```

### DefinitionExtractorService

```kotlin
package com.graphmesh.extraction.definition

import com.graphmesh.llm.ChatCompletionService
import com.graphmesh.llm.ChatMessage
import com.graphmesh.llm.ChatRole
import com.graphmesh.rdf.*
import com.graphmesh.storage.cassandra.QuadStore
import com.graphmesh.librarian.LibrarianService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Extrahiert Definitionen aus Textchunks mittels LLM und speichert
 * sie als rdfs:comment-Triples im Knowledge Graph.
 *
 * Im Gegensatz zum RelationshipExtractorService (Feature 12) werden
 * hier keine Beziehungen zwischen Entitaeten extrahiert, sondern
 * beschreibende Texte fuer einzelne Entitaeten.
 */
class DefinitionExtractorService(
    private val chatService: ChatCompletionService,
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService
) {

    companion object {
        private const val RDFS_COMMENT = "http://www.w3.org/2000/01/rdf-schema#comment"
        private const val RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label"
        private const val EXTRACTED_FROM = "http://graphmesh.io/ontology/extractedFrom"
    }

    /**
     * Extrahiert Definitionen aus einem Chunk und speichert sie als Quads.
     *
     * 1. Chunk-Text vom Librarian laden
     * 2. LLM-Prompt zusammenbauen und senden
     * 3. JSONL-Antwort parsen (ein JSON-Objekt pro Zeile)
     * 4. Deterministische Entity-IDs generieren
     * 5. rdfs:comment-Quads im Default-Graph speichern
     * 6. rdfs:label-Quads fuer Entity-Resolution speichern
     * 7. Provenance-Quads im Source-Graph speichern
     */
    suspend fun extractDefinitions(
        chunkId: String,
        collectionId: UUID
    ): DefinitionExtractionResult {
        val content = librarianService.getContent(chunkId)
        val chunkText = content.toString(Charsets.UTF_8)

        // LLM-Extraktion
        val messages = listOf(
            ChatMessage(role = ChatRole.SYSTEM, content = DefinitionPromptTemplate.systemPrompt()),
            ChatMessage(role = ChatRole.USER, content = DefinitionPromptTemplate.userPrompt(chunkText))
        )
        val response = chatService.complete(messages)

        // JSONL-Antwort parsen
        val definitions = parseJsonlDefinitions(response.content)

        // Definition-Quads erzeugen
        val definitionQuads = definitions.map { result ->
            Quad(
                subject = EntityIdGenerator.generate(result.entity),
                predicate = RdfTerm.Uri(RDFS_COMMENT),
                objectTerm = RdfTerm.Literal(result.definition),
                graph = NamedGraph.DEFAULT
            )
        }

        // Label-Quads fuer Entity-Resolution
        val labelQuads = definitions.map { result ->
            Quad(
                subject = EntityIdGenerator.generate(result.entity),
                predicate = RdfTerm.Uri(RDFS_LABEL),
                objectTerm = RdfTerm.Literal(result.entity),
                graph = NamedGraph.DEFAULT
            )
        }.distinctBy { it.subject.toNTriples() }

        // Provenance-Quads: Verknuepfung mit Quell-Chunk
        val provenanceQuads = definitionQuads.map { quad ->
            Quad(
                subject = RdfTerm.QuotedTriple(quad.triple),
                predicate = RdfTerm.Uri(EXTRACTED_FROM),
                objectTerm = RdfTerm.Uri("urn:chunk:$chunkId"),
                graph = NamedGraph.SOURCE
            )
        }

        // Alle Quads speichern
        quadStore.saveAll(collectionId.toString(), definitionQuads + labelQuads + provenanceQuads)

        return DefinitionExtractionResult(
            chunkId = chunkId,
            definitionsExtracted = definitions.size,
            entitiesFound = definitions.map { it.entity }
        )
    }

    /**
     * Parst die JSONL-Antwort des LLM in DefinitionResult-Objekte.
     *
     * Truncation-resilient: Jede Zeile wird einzeln geparst.
     * Ungueltige Zeilen werden uebersprungen.
     */
    internal fun parseJsonlDefinitions(llmResponse: String): List<DefinitionResult> {
        return llmResponse.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val json = Json.parseToJsonElement(line).jsonObject
                    val entity = json["entity"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val definition = json["definition"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    if (entity.isNotBlank() && definition.isNotBlank()) {
                        DefinitionResult(entity = entity, definition = definition)
                    } else null
                } catch (e: Exception) {
                    null // Ungueltige Zeile ueberspringen
                }
            }
    }
}
```

### DefinitionExtractorConsumer

```kotlin
package com.graphmesh.extraction.definition

import com.graphmesh.extraction.chunker.ChunkCreatedEvent
import com.graphmesh.messaging.MessageConsumer

/**
 * Kafka-Consumer fuer Definition-Extraktion.
 * Lauscht auf chunk.created Events und delegiert an den DefinitionExtractorService.
 */
class DefinitionExtractorConsumer(
    private val consumer: MessageConsumer<ChunkCreatedEvent>,
    private val extractorService: DefinitionExtractorService
) {
    fun start() {
        consumer.subscribe { message ->
            val event = message.payload
            extractorService.extractDefinitions(
                chunkId = event.chunkId,
                collectionId = event.collectionId
            )
        }
    }
}
```

### Kafka-Topics

| Topic                     | Richtung  | Schema              |
|---------------------------|-----------|---------------------|
| `graphmesh.chunk.created` | Eingehend | `ChunkCreatedEvent` |

## Betroffene Dateien

### Backend

| Datei                                                                                           | Aenderung                                        |
|-------------------------------------------------------------------------------------------------|--------------------------------------------------|
| `extraction/src/main/kotlin/com/graphmesh/extraction/definition/DefinitionExtractorService.kt`  | NEU - LLM-basierte Definitions-Extraktion        |
| `extraction/src/main/kotlin/com/graphmesh/extraction/definition/DefinitionExtractorConsumer.kt` | NEU - Kafka-Consumer fuer chunk.created Events   |
| `extraction/src/main/kotlin/com/graphmesh/extraction/definition/DefinitionPromptTemplate.kt`    | NEU - Prompt-Template fuer Definition-Extraktion |
| `extraction/src/main/kotlin/com/graphmesh/extraction/definition/DefinitionResult.kt`            | NEU - Datenklassen fuer Extraktionsergebnisse    |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                              | Aenderung                                                 |
|----------------------------------------------------------------------------------------------------|-----------------------------------------------------------|
| `extraction/src/test/kotlin/com/graphmesh/extraction/definition/DefinitionExtractorServiceTest.kt` | NEU - Unit-Tests fuer Extraktion und JSONL-Parsing        |
| `extraction/src/test/kotlin/com/graphmesh/extraction/definition/DefinitionPromptTemplateTest.kt`   | NEU - Prompt-Template-Validierung                         |
| `extraction/src/test/kotlin/com/graphmesh/extraction/definition/JsonlParsingTest.kt`               | NEU - JSONL-Parsing Edge Cases (leere Zeilen, truncation) |
| `extraction/src/test/kotlin/com/graphmesh/extraction/definition/ProvenanceTest.kt`                 | NEU - Provenance-Quads im Source-Graph                    |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                        |
|-------------------|-------------|----------------------------------------------|
| Spring Boot (JVM) | Ja          | LLM-Client, Kafka-Consumer, Cassandra-Client |
| KMP Library       | Nein        | Abhaengigkeit zu JVM-spezifischen Clients    |
| Ktor/Wasm         | Nein        | LLM- und Kafka-Clients sind JVM-spezifisch   |

## Akzeptanzkriterien

- [ ] Extractor empfaengt `chunk.created`-Events und verarbeitet den zugehoerigen Chunk-Text
- [ ] LLM-Prompt extrahiert Entity-Definition-Paare im JSONL-Format
- [ ] Definitionen werden als `(entity, rdfs:comment, definition_text)` Triples gespeichert
- [ ] Subjects erhalten deterministische Entity-IDs via `EntityIdGenerator` (Feature 07)
- [ ] `rdfs:label`-Triples werden fuer jede extrahierte Entitaet erzeugt (dedupliziert)
- [ ] Knowledge-Quads werden im Default-Graph gespeichert
- [ ] Provenance-Quads mit `QuotedTriple` (RDF-Star) werden im Source-Graph gespeichert
- [ ] JSONL-Parsing ist truncation-resilient: unvollstaendige letzte Zeilen werden uebersprungen
- [ ] Ungueltige JSONL-Zeilen (kein JSON, fehlende Felder) werden uebersprungen
- [ ] Leere Definitionen oder Entity-Namen werden ignoriert
- [ ] `DefinitionExtractionResult` enthaelt korrekte Zaehler und Entity-Liste
