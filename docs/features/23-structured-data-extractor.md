# Feature 23: Structured Data Extractor

## Problem

Unstrukturierte Textchunks enthalten haeufig tabellarische Informationen (z.B. Preislisten, Mitarbeiterlisten,
technische Spezifikationen), die als strukturierte Zeilen in einem Row Store gespeichert werden sollten. Ohne
automatische Erkennung und Extraktion gehen diese tabellarischen Daten verloren oder muessen manuell erfasst werden.
Zusaetzlich koennen Tabellen ueber mehrere Chunks verteilt sein und muessen zusammengefuehrt werden.

## Ziel

Implementierung eines Kafka-basierten Structured Data Extractors, der tabellarische Daten in Textchunks erkennt, das
Schema inferiert und die extrahierten Zeilen im Structured Data Storage (Feature 22) speichert.

1. **Kafka-Consumer** -- Empfaengt `chunk.created`-Events vom Chunker (Feature 11)
2. **Tabellen-Erkennung** -- LLM erkennt, ob ein Chunk tabellarische Daten enthaelt
3. **Schema-Inferenz** -- LLM leitet Spaltennamen und -typen aus dem Inhalt ab
4. **Zeilen-Extraktion** -- LLM extrahiert strukturierte Zeilen aus unstrukturiertem Text
5. **Multi-Chunk-Merge** -- Tabellen, die ueber mehrere Chunks verteilt sind, werden zusammengefuehrt
6. **Persistenz** -- Extrahierte Zeilen werden ueber StructuredDataStore (Feature 22) gespeichert

## Voraussetzungen

| Abhaengigkeit                                                                      | Status     | Blocker? |
|------------------------------------------------------------------------------------|------------|----------|
| Feature 05: LLM Provider Abstraction (LlmProvider, ChatCompletionService)          | Geplant    | Ja       |
| Feature 11: Document Chunker (liefert chunk.created Events)                        | Geplant    | Ja       |
| Feature 22: Structured Data Storage (StructuredDataStore, SchemaService, RowStore) | Geplant    | Ja       |
| Spring Boot 3.x                                                                    | Verfuegbar | Nein     |

## Architektur

### TableDetector

```kotlin
package com.graphmesh.extraction.structured

import com.graphmesh.llm.ChatCompletionService
import com.graphmesh.llm.ChatMessage
import com.graphmesh.llm.ChatRole

/**
 * Erkennt, ob ein Textchunk tabellarische Daten enthaelt.
 *
 * Verwendet einen leichtgewichtigen LLM-Prompt, der mit einem
 * einfachen JSON-Objekt antwortet: {"has_table": true/false, "confidence": 0.0-1.0}
 */
class TableDetector(
    private val chatService: ChatCompletionService
) {

    data class DetectionResult(
        val hasTable: Boolean,
        val confidence: Double,
        val tableDescription: String? = null
    )

    suspend fun detect(chunkText: String): DetectionResult {
        val messages = listOf(
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = """
                    Analysiere den folgenden Text und bestimme, ob er tabellarische Daten
                    oder strukturierte Listen enthaelt, die als Tabelle dargestellt werden koennten.

                    Antworte NUR mit einem JSON-Objekt:
                    {"has_table": true/false, "confidence": 0.0-1.0, "description": "Kurzbeschreibung der Tabelle"}

                    Beispiele fuer tabellarische Daten:
                    - Preislisten, Produktkataloge
                    - Mitarbeiterlisten, Kontaktdaten
                    - Technische Spezifikationen
                    - Vergleichstabellen
                    - Aufzaehlungen mit wiederkehrender Struktur
                """.trimIndent()
            ),
            ChatMessage(role = ChatRole.USER, content = chunkText)
        )

        return try {
            val response = chatService.complete(messages)
            parseDetectionResult(response.content)
        } catch (e: Exception) {
            DetectionResult(hasTable = false, confidence = 0.0)
        }
    }

    internal fun parseDetectionResult(response: String): DetectionResult {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            response.trim().removePrefix("```json").removeSuffix("```").trim()
        ).jsonObject
        return DetectionResult(
            hasTable = json["has_table"]?.jsonPrimitive?.boolean ?: false,
            confidence = json["confidence"]?.jsonPrimitive?.double ?: 0.0,
            tableDescription = json["description"]?.jsonPrimitive?.content
        )
    }
}
```

### SchemaInferenceService

```kotlin
package com.graphmesh.extraction.structured

import com.graphmesh.llm.ChatCompletionService
import com.graphmesh.llm.ChatMessage
import com.graphmesh.llm.ChatRole
import com.graphmesh.structured.ColumnDescriptor
import com.graphmesh.structured.ColumnType
import com.graphmesh.structured.TableSchema

/**
 * Inferiert das Schema einer Tabelle aus dem Textinhalt mittels LLM.
 *
 * Das LLM analysiert den Text und schlaegt Spaltennamen, Datentypen
 * und Primary-Key-Spalten vor.
 */
class SchemaInferenceService(
    private val chatService: ChatCompletionService
) {

    /**
     * Inferiert ein TableSchema aus dem gegebenen Text.
     * Prueft vorher, ob bereits ein kompatibles Schema existiert.
     */
    suspend fun inferSchema(
        chunkText: String,
        existingSchemas: List<TableSchema> = emptyList(),
        tableDescription: String? = null
    ): InferredSchema {
        val existingContext = if (existingSchemas.isNotEmpty()) {
            "Existierende Schemata:\n" + existingSchemas.joinToString("\n") { schema ->
                "- ${schema.name}: ${schema.columns.joinToString(", ") { "${it.name}:${it.type}" }}"
            }
        } else ""

        val messages = listOf(
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = """
                    Analysiere den Text und erstelle ein Tabellen-Schema.
                    ${if (existingContext.isNotEmpty()) "\n$existingContext\n" else ""}
                    ${tableDescription?.let { "Tabellenbeschreibung: $it\n" } ?: ""}

                    Antworte mit einem JSON-Objekt:
                    {
                        "schema_name": "eindeutiger_name",
                        "description": "Beschreibung",
                        "columns": [
                            {"name": "spalte1", "type": "STRING|INTEGER|FLOAT|BOOLEAN|TIMESTAMP", "primary_key": false, "indexed": false, "description": "..."},
                            ...
                        ],
                        "matches_existing": "name_des_existierenden_schemas" oder null
                    }

                    Falls die Daten zu einem existierenden Schema passen, setze matches_existing.
                    Spaltennamen in kebab-case.
                """.trimIndent()
            ),
            ChatMessage(role = ChatRole.USER, content = chunkText)
        )

        val response = chatService.complete(messages)
        return parseInferredSchema(response.content)
    }

    internal fun parseInferredSchema(response: String): InferredSchema {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            response.trim().removePrefix("```json").removeSuffix("```").trim()
        ).jsonObject

        val columns = json["columns"]!!.jsonArray.map { col ->
            val obj = col.jsonObject
            ColumnDescriptor(
                name = obj["name"]!!.jsonPrimitive.content,
                type = ColumnType.fromString(obj["type"]!!.jsonPrimitive.content),
                primaryKey = obj["primary_key"]?.jsonPrimitive?.boolean ?: false,
                indexed = obj["indexed"]?.jsonPrimitive?.boolean ?: false,
                description = obj["description"]?.jsonPrimitive?.content
            )
        }

        return InferredSchema(
            schema = TableSchema(
                name = json["schema_name"]!!.jsonPrimitive.content,
                description = json["description"]?.jsonPrimitive?.content,
                columns = columns
            ),
            matchesExisting = json["matches_existing"]?.jsonPrimitive?.content
        )
    }
}

data class InferredSchema(
    val schema: TableSchema,
    val matchesExisting: String?
)
```

### StructuredDataExtractorService

```kotlin
package com.graphmesh.extraction.structured

import com.graphmesh.llm.ChatCompletionService
import com.graphmesh.llm.ChatMessage
import com.graphmesh.llm.ChatRole
import com.graphmesh.structured.*
import com.graphmesh.librarian.LibrarianService
import java.util.UUID

/**
 * Extrahiert tabellarische Daten aus Textchunks und speichert sie
 * im Structured Data Storage.
 *
 * Pipeline:
 * 1. TableDetector prueft, ob der Chunk Tabellen enthaelt
 * 2. SchemaInferenceService leitet das Schema ab
 * 3. LLM extrahiert die Zeilen im JSONL-Format
 * 4. Zeilen werden im StructuredDataStore gespeichert
 */
class StructuredDataExtractorService(
    private val chatService: ChatCompletionService,
    private val tableDetector: TableDetector,
    private val schemaInference: SchemaInferenceService,
    private val structuredDataStore: StructuredDataStore,
    private val librarianService: LibrarianService
) {

    /**
     * Extrahiert strukturierte Daten aus einem Chunk.
     */
    suspend fun extract(
        chunkId: String,
        collectionId: UUID
    ): StructuredExtractionResult {
        val content = librarianService.getContent(chunkId)
        val chunkText = content.toString(Charsets.UTF_8)

        // Schritt 1: Tabellen-Erkennung
        val detection = tableDetector.detect(chunkText)
        if (!detection.hasTable || detection.confidence < 0.5) {
            return StructuredExtractionResult(
                chunkId = chunkId,
                tableDetected = false,
                rowsExtracted = 0
            )
        }

        // Schritt 2: Schema-Inferenz
        val existingSchemas = structuredDataStore.schemaService.listSchemas()
            .mapNotNull { structuredDataStore.schemaService.getSchema(it) }
        val inferred = schemaInference.inferSchema(
            chunkText = chunkText,
            existingSchemas = existingSchemas,
            tableDescription = detection.tableDescription
        )

        // Schema bestimmen: existierendes oder neues
        val schemaName = inferred.matchesExisting ?: inferred.schema.name
        val schema = if (inferred.matchesExisting != null) {
            structuredDataStore.schemaService.getSchema(inferred.matchesExisting)
                ?: inferred.schema.also { structuredDataStore.schemaService.saveSchema(it) }
        } else {
            inferred.schema.also { structuredDataStore.schemaService.saveSchema(it) }
        }

        // Schritt 3: Zeilen-Extraktion via LLM
        val rows = extractRows(chunkText, schema, collectionId.toString())

        // Schritt 4: Speichern
        if (rows.isNotEmpty()) {
            structuredDataStore.storeBatch(rows)
        }

        return StructuredExtractionResult(
            chunkId = chunkId,
            tableDetected = true,
            schemaName = schemaName,
            rowsExtracted = rows.size
        )
    }

    /**
     * Extrahiert Zeilen aus dem Text gemaess dem Schema.
     * Das LLM gibt JSONL-Zeilen aus, eine pro Datenzeile.
     */
    private suspend fun extractRows(
        chunkText: String,
        schema: TableSchema,
        collection: String
    ): List<DataRow> {
        val columnSpec = schema.columns.joinToString(", ") { "${it.name} (${it.type})" }

        val messages = listOf(
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = """
                    Extrahiere die tabellarischen Daten aus dem Text als JSONL.

                    Schema: ${schema.name}
                    Spalten: $columnSpec

                    Gib pro Zeile ein JSON-Objekt aus mit den Spaltennamen als Keys:
                    {"${schema.columns.first().name}": "...", ...}

                    Regeln:
                    - Nur Daten extrahieren, die tatsaechlich im Text stehen
                    - Fehlende Werte als leeren String ""
                    - Zahlen als Strings ("42", "3.14")
                    - Jede Zeile auf einer eigenen Linie (JSONL-Format)
                """.trimIndent()
            ),
            ChatMessage(role = ChatRole.USER, content = chunkText)
        )

        val response = chatService.complete(messages)
        return parseRows(response.content, schema.name, collection)
    }

    internal fun parseRows(
        response: String,
        schemaName: String,
        collection: String
    ): List<DataRow> {
        return response.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val json = kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
                    val values = json.entries.associate { (key, value) ->
                        key to value.jsonPrimitive.content
                    }
                    DataRow(
                        collection = collection,
                        schemaName = schemaName,
                        values = values
                    )
                } catch (e: Exception) {
                    null
                }
            }
    }
}
```

### StructuredDataExtractorConsumer

```kotlin
package com.graphmesh.extraction.structured

import com.graphmesh.extraction.chunker.ChunkCreatedEvent
import com.graphmesh.messaging.MessageConsumer

/**
 * Kafka-Consumer fuer Structured-Data-Extraktion.
 * Lauscht auf chunk.created Events und delegiert an den
 * StructuredDataExtractorService.
 */
class StructuredDataExtractorConsumer(
    private val consumer: MessageConsumer<ChunkCreatedEvent>,
    private val extractorService: StructuredDataExtractorService
) {
    fun start() {
        consumer.subscribe { message ->
            val event = message.payload
            extractorService.extract(
                chunkId = event.chunkId,
                collectionId = event.collectionId
            )
        }
    }
}
```

### Datenmodelle

```kotlin
package com.graphmesh.extraction.structured

/**
 * Ergebnis einer Structured-Data-Extraktion.
 */
data class StructuredExtractionResult(
    val chunkId: String,
    val tableDetected: Boolean,
    val schemaName: String? = null,
    val rowsExtracted: Int,
    val mergedFromChunks: List<String> = emptyList()
)
```

### Multi-Chunk-Merge

```kotlin
package com.graphmesh.extraction.structured

/**
 * Erkennt und merged Tabellen, die ueber mehrere Chunks verteilt sind.
 *
 * Strategie:
 * - Chunks desselben Dokuments mit demselben Schema werden als
 *   zusammengehoerend betrachtet
 * - Der erste Chunk definiert das Schema
 * - Folge-Chunks fuegen Zeilen hinzu (kein Schema-Duplikat)
 */
class MultiChunkTableMerger {

    data class PendingTable(
        val documentId: String,
        val schemaName: String,
        val chunkIds: MutableList<String> = mutableListOf()
    )

    private val pendingTables = mutableMapOf<String, PendingTable>()

    /**
     * Prueft, ob ein Chunk zu einer bereits erkannten Tabelle gehoert.
     */
    fun belongsToExistingTable(documentId: String, schemaName: String): Boolean {
        val key = "$documentId:$schemaName"
        return key in pendingTables
    }

    /**
     * Registriert einen Chunk als Teil einer Tabelle.
     */
    fun registerChunk(documentId: String, schemaName: String, chunkId: String) {
        val key = "$documentId:$schemaName"
        pendingTables.getOrPut(key) {
            PendingTable(documentId, schemaName)
        }.chunkIds.add(chunkId)
    }
}
```

### Kafka-Topics

| Topic                     | Richtung  | Schema              |
|---------------------------|-----------|---------------------|
| `graphmesh.chunk.created` | Eingehend | `ChunkCreatedEvent` |

## Betroffene Dateien

### Backend

| Datei                                                                                               | Aenderung                                      |
|-----------------------------------------------------------------------------------------------------|------------------------------------------------|
| `extraction/src/main/kotlin/com/graphmesh/extraction/structured/StructuredDataExtractorService.kt`  | NEU - Haupt-Extraktions-Pipeline               |
| `extraction/src/main/kotlin/com/graphmesh/extraction/structured/StructuredDataExtractorConsumer.kt` | NEU - Kafka-Consumer fuer chunk.created Events |
| `extraction/src/main/kotlin/com/graphmesh/extraction/structured/TableDetector.kt`                   | NEU - Tabellen-Erkennung via LLM               |
| `extraction/src/main/kotlin/com/graphmesh/extraction/structured/SchemaInferenceService.kt`          | NEU - Schema-Inferenz via LLM                  |
| `extraction/src/main/kotlin/com/graphmesh/extraction/structured/MultiChunkTableMerger.kt`           | NEU - Multi-Chunk-Tabellen-Zusammenfuehrung    |
| `extraction/src/main/kotlin/com/graphmesh/extraction/structured/StructuredExtractionResult.kt`      | NEU - Ergebnis-Datenklasse                     |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                                  | Aenderung                                                  |
|--------------------------------------------------------------------------------------------------------|------------------------------------------------------------|
| `extraction/src/test/kotlin/com/graphmesh/extraction/structured/TableDetectorTest.kt`                  | NEU - Tabellen-Erkennung (positiv/negativ, Edge Cases)     |
| `extraction/src/test/kotlin/com/graphmesh/extraction/structured/SchemaInferenceServiceTest.kt`         | NEU - Schema-Inferenz mit verschiedenen Textformaten       |
| `extraction/src/test/kotlin/com/graphmesh/extraction/structured/StructuredDataExtractorServiceTest.kt` | NEU - End-to-End-Extraktion                                |
| `extraction/src/test/kotlin/com/graphmesh/extraction/structured/RowParsingTest.kt`                     | NEU - JSONL-Zeilen-Parsing (Edge Cases, ungueltige Zeilen) |
| `extraction/src/test/kotlin/com/graphmesh/extraction/structured/MultiChunkTableMergerTest.kt`          | NEU - Chunk-uebergreifende Tabellen                        |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                           |
|-------------------|-------------|-------------------------------------------------|
| Spring Boot (JVM) | Ja          | LLM-Client, Kafka-Consumer, StructuredDataStore |
| KMP Library       | Nein        | Abhaengigkeit zu JVM-spezifischen Clients       |
| Ktor/Wasm         | Nein        | LLM- und Kafka-Clients sind JVM-spezifisch      |

## Akzeptanzkriterien

- [ ] Extractor empfaengt `chunk.created`-Events und verarbeitet den zugehoerigen Chunk-Text
- [ ] TableDetector erkennt tabellarische Daten im Text mit Konfidenz-Schwellwert (>= 0.5)
- [ ] SchemaInferenceService leitet Spaltennamen, Typen und Primary Keys aus dem Text ab
- [ ] Bei existierendem kompatiblem Schema wird kein neues Schema erzeugt (`matchesExisting`)
- [ ] Zeilen werden im JSONL-Format extrahiert (truncation-resilient)
- [ ] Extrahierte Zeilen werden ueber StructuredDataStore.storeBatch() gespeichert
- [ ] Fehlende Werte werden als leere Strings gespeichert
- [ ] Chunks ohne tabellarische Daten werden uebersprungen (tableDetected = false)
- [ ] Multi-Chunk-Tabellen werden erkannt und zusammengefuehrt
- [ ] Ungueltige JSONL-Zeilen werden uebersprungen
- [ ] `StructuredExtractionResult` enthaelt Schema-Name, Zeilen-Zaehler und ggf. Merge-Info
