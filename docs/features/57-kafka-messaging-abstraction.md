# Feature 57: Kafka Messaging Abstraction (EventBus + TypedKafkaConsumer)

## Problem

15+ Kafka-Producer/Consumer-Klassen folgen dem identischen Muster. Jeder Producer (6 Klassen, ~210 LOC total) macht: Schema parsen, GenericRecord bauen, CloudEvent-Headers erzeugen, `KafkaTemplate.send()` aufrufen. Jeder Consumer (~13 Klassen) macht: GenericRecord-Felder extrahieren, Service aufrufen, `DocumentNotFoundException` + generische Exception fangen. Das `messaging/`-Modul hat eine 66:1 Shallow-Ratio — die flachste im gesamten Codebase. Kein DLQ, kein Retry.

Der duplizierte Boilerplate erschwert Wartung und Konsistenz: jede Aenderung am Error-Handling oder an der Header-Erzeugung muss in allen Klassen einzeln nachgezogen werden. Neue Topics erfordern das Kopieren einer bestehenden Producer/Consumer-Klasse mit minimaler Anpassung — ein klares Zeichen fuer fehlende Abstraktion.

## Ziel

Einfuehrung zweier zentraler Abstraktionen, die den repetitiven Kafka-Code konsolidieren, ohne die Spring-`@KafkaListener`-Mechanik zu aendern.

1. **`EventBus`** — ein einzelner Publisher-Service. Schema-Cache (`ConcurrentHashMap`), CloudEvent-Header-Assembly, async Error-Logging. Eliminiert alle 5 einfachen Producer-Klassen (`ExplainabilityEventProducer` bleibt wegen nested Avro).
2. **`TypedKafkaConsumer<T>`** — abstrakte Basisklasse mit `decode(): T?` und `process(event: T)`. Absorbiert GenericRecord-Parsing, `DocumentNotFoundException`-Handling, Error-Logging.
3. **`@KafkaListener` bleibt auf konkreten Consumer-Klassen** (Spring-Anforderung — Listener-Annotation muss auf konkreter Methode liegen).
4. **Consumer-Klassen schrumpfen** von ~35 LOC auf ~10–15 LOC.
5. **Kein DLQ/Retry im ersten Schritt** (kann spaeter als separate Erweiterung draufgesetzt werden).

## Voraussetzungen

| Abhaengigkeit                                           | Status           | Blocker? |
|---------------------------------------------------------|------------------|----------|
| Feature 01 (Kafka)                                      | Implementiert    | Nein     |
| `spring-kafka` (schon in Classpath)                     | Verfuegbar       | Nein     |
| `org.apache.avro` (schon in Classpath)                  | Verfuegbar       | Nein     |
| CloudEvent-Header-Konvention (bestehend)                | Verfuegbar       | Nein     |

Keine Infra-Aenderung in Kafka / docker-compose.

## Architektur

### Ist-Zustand

```kotlin
// DocumentIngestedProducer.kt (typischer Producer, ~40 LOC)
@Service
class DocumentIngestedProducer(private val kafka: KafkaTemplate<String, GenericRecord>) {
    private val schema = Schema.Parser().parse(
        this::class.java.getResourceAsStream("/avro/document-ingested.avsc")
    )

    fun send(documentId: String, collectionId: String) {
        val record = GenericData.Record(schema).apply {
            put("documentId", documentId)
            put("collectionId", collectionId)
        }
        val producerRecord = ProducerRecord<String, GenericRecord>(
            "document-ingested", documentId, record
        )
        producerRecord.headers().add("ce_type", "DocumentIngested".toByteArray())
        producerRecord.headers().add("ce_source", "graphmesh".toByteArray())
        producerRecord.headers().add("ce_id", UUID.randomUUID().toString().toByteArray())
        kafka.send(producerRecord)
    }
}
```

```kotlin
// ChunkEmbeddingConsumer.kt (typischer Consumer, ~35 LOC)
@Component
class ChunkEmbeddingConsumer(private val embeddingService: EmbeddingService) {
    @KafkaListener(topics = ["chunk-created"], groupId = "embedding-consumer")
    fun consume(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val chunkId = value.get("chunkId")?.toString()
        val collectionId = value.get("collectionId")?.toString()
        if (chunkId == null || collectionId == null) {
            logger.warn("Missing fields in chunk-created event")
            return
        }
        try {
            embeddingService.embedChunk(chunkId, collectionId)
        } catch (e: DocumentNotFoundException) {
            logger.warn("Document not found for chunk $chunkId: ${e.message}")
        } catch (e: Exception) {
            logger.error("Error processing chunk-created event for $chunkId", e)
        }
    }
}
```

Dieses Muster wiederholt sich in 5 Producern und ~13 Consumern mit minimalen Variationen (Topic-Name, Felder, Service-Aufruf).

### Soll-Zustand

```kotlin
// EventBus — einziger Publisher fuer einfache Events
@Service
class EventBus(private val kafka: KafkaTemplate<String, GenericRecord>) {
    private val schemaCache = ConcurrentHashMap<String, Schema>()

    fun publish(
        topic: String,
        key: String,
        source: String,
        type: String,
        schema: String,
        fields: GenericData.Record.() -> Unit
    ) {
        val avroSchema = schemaCache.computeIfAbsent(schema) {
            Schema.Parser().parse(javaClass.getResourceAsStream("/avro/$it"))
        }
        val record = GenericData.Record(avroSchema).apply(fields)
        val producerRecord = ProducerRecord<String, GenericRecord>(topic, key, record)
        producerRecord.headers().add("ce_type", type.toByteArray())
        producerRecord.headers().add("ce_source", source.toByteArray())
        producerRecord.headers().add("ce_id", UUID.randomUUID().toString().toByteArray())
        kafka.send(producerRecord).whenComplete { _, ex ->
            if (ex != null) logger.error("Failed to publish $type to $topic", ex)
        }
    }
}
```

```kotlin
// TypedKafkaConsumer — abstrakte Basisklasse fuer alle Consumer
abstract class TypedKafkaConsumer<T>(private val eventName: String) {
    abstract fun decode(value: GenericRecord): T?
    abstract fun process(event: T)

    protected fun consume(record: ConsumerRecord<String, GenericRecord>) {
        val event = decode(record.value())
        if (event == null) {
            logger.warn("Failed to decode $eventName event, skipping")
            return
        }
        try {
            process(event)
        } catch (e: DocumentNotFoundException) {
            logger.warn("Document not found while processing $eventName: ${e.message}")
        } catch (e: Exception) {
            logger.error("Error processing $eventName event", e)
        }
    }
}
```

```kotlin
// ChunkEmbeddingConsumer — nach Umbau (~12 LOC)
@Component
class ChunkEmbeddingConsumer(
    private val embeddingService: EmbeddingService
) : TypedKafkaConsumer<ChunkRef>("chunk-created") {

    override fun decode(value: GenericRecord) = ChunkRef(
        chunkId = value.get("chunkId")?.toString() ?: return null,
        collectionId = value.get("collectionId")?.toString() ?: return null,
    )

    override fun process(event: ChunkRef) =
        embeddingService.embedChunk(event.chunkId, event.collectionId)

    @KafkaListener(topics = ["chunk-created"], groupId = "embedding-consumer")
    fun onMessage(record: ConsumerRecord<String, GenericRecord>) = consume(record)
}
```

```kotlin
// Shared Event-Typ fuer 5+ Consumer mit identischer Struktur
data class ChunkRef(val chunkId: String, val collectionId: String)
```

### Subsection 1: EventBus — Schema-Cache und Header-Assembly

Der `EventBus` uebernimmt drei Verantwortlichkeiten, die bisher in jedem Producer dupliziert waren:

- **Schema-Caching**: Avro-Schemas werden beim ersten Zugriff geladen und in einer `ConcurrentHashMap` gecacht. Heute parst jeder Producer sein Schema im Konstruktor — bei 5 Producern 5x derselbe Mechanismus.
- **CloudEvent-Header**: `ce_type`, `ce_source`, `ce_id` werden einheitlich erzeugt. Heute hat jeder Producer eigene Header-Logik, teils mit subtilen Unterschieden.
- **Async-Error-Logging**: `whenComplete`-Callback loggt fehlgeschlagene Sends. Heute wird der `CompletableFuture` von `kafka.send()` in den meisten Producern ignoriert.

Aufrufe im Service-Code werden zu Einzeilern:

```kotlin
// Vorher (im ChunkerService):
chunkCreatedProducer.send(chunkId, collectionId)

// Nachher:
eventBus.publish("chunk-created", chunkId, "chunker", "ChunkCreated", "chunk-created.avsc") {
    put("chunkId", chunkId)
    put("collectionId", collectionId)
}
```

### Subsection 2: TypedKafkaConsumer — Decode/Process-Trennung

Die abstrakte Basisklasse trennt zwei Concerns, die heute in jeder `consume`-Methode vermischt sind:

- **`decode(GenericRecord): T?`** — extrahiert die relevanten Felder und gibt `null` zurueck, wenn Pflichtfelder fehlen. Heute: manuelle `get()`-Aufrufe mit individuellen Null-Checks.
- **`process(event: T)`** — enthaelt den eigentlichen Service-Aufruf. Heute: inline im `try`-Block.

Die `consume()`-Methode der Basisklasse orchestriert den Flow: Decode → Null-Check → Process mit einheitlichem Exception-Handling. Konkrete Consumer muessen nur noch `decode()` und `process()` implementieren und eine `@KafkaListener`-Methode bereitstellen, die an `consume()` delegiert.

### Subsection 3: Shared Event-Typen

Mehrere Consumer verarbeiten Events mit identischer Struktur (z.B. `chunkId` + `collectionId`). Statt in jedem Consumer die Felder einzeln zu extrahieren, definiert ein gemeinsamer `data class` die Struktur:

```kotlin
data class ChunkRef(val chunkId: String, val collectionId: String)
```

Weitere Shared-Typen koennen bei Bedarf ergaenzt werden (z.B. `DocumentRef` fuer `documentId` + `collectionId`).

### Subsection 4: ExplainabilityEventProducer bleibt

Der `ExplainabilityEventProducer` arbeitet mit verschachtelten Avro-Records (nested `ExplainabilityStep`-Arrays), die sich nicht sinnvoll ueber die `fields`-Lambda-API des `EventBus` abbilden lassen. Diese Klasse bleibt als eigenstaendiger Producer bestehen und wird nicht in den `EventBus` migriert.

## Betroffene Dateien

### Backend

| Datei                                                                                                 | Aenderung                                                                                |
|-------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/messaging/EventBus.kt`                                      | NEU — zentraler Publisher-Service mit Schema-Cache und CloudEvent-Headers                  |
| `src/main/kotlin/com/agentwork/graphmesh/messaging/TypedKafkaConsumer.kt`                             | NEU — abstrakte Basisklasse fuer typisierte Consumer                                      |
| `src/main/kotlin/com/agentwork/graphmesh/messaging/ChunkRef.kt`                                      | NEU — Shared Event-Typ fuer Chunk-bezogene Events                                         |
| `src/main/kotlin/com/agentwork/graphmesh/messaging/DocumentIngestedProducer.kt`                      | LOESCHEN — Logik wandert in Service + `EventBus.publish()`                                |
| `src/main/kotlin/com/agentwork/graphmesh/messaging/ConfigChangeProducer.kt`                          | LOESCHEN — Logik wandert in Service + `EventBus.publish()`                                |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/chunker/ChunkCreatedProducer.kt`                 | LOESCHEN — Logik wandert in Service + `EventBus.publish()`                                |
| `src/main/kotlin/com/agentwork/graphmesh/extraction/decoder/PageExtractedProducer.kt`                | LOESCHEN — Logik wandert in Service + `EventBus.publish()`                                |
| `src/main/kotlin/com/agentwork/graphmesh/messaging/ExplainabilityEventProducer.kt`                   | BEHALTEN — zu komplex fuer EventBus-Abstraktion (nested Avro)                              |
| Alle ~13 Consumer-Klassen unter `messaging/` und `extraction/`                                        | AENDERN — von `TypedKafkaConsumer<T>` erben, `decode()`/`process()` implementieren         |
| `ChunkerService.kt`, `PdfDecoderService.kt`, weitere Services mit Producer-Injection                 | AENDERN — Producer-Abhaengigkeit durch `EventBus` ersetzen                                |

### Frontend

Keine Aenderungen.

### Tests

| Datei                                                                                          | Aenderung                                                                                       |
|------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/messaging/EventBusTest.kt`                           | NEU — Unit-Test: Schema-Caching funktioniert, CloudEvent-Headers korrekt, Error-Callback loggt  |
| `src/test/kotlin/com/agentwork/graphmesh/messaging/TypedKafkaConsumerTest.kt`                  | NEU — Unit-Test: Decode-Null wird geloggt und uebersprungen, DocumentNotFoundException wird gefangen, generische Exception wird geloggt |
| Bestehende Consumer-Tests                                                                      | AENDERN — an neue Vererbungsstruktur anpassen, `decode()`/`process()` separat testbar            |

## Akzeptanzkriterien

- [ ] `EventBus.publish()` sendet Events mit korrektem Avro-Schema, CloudEvent-Headers (`ce_type`, `ce_source`, `ce_id`) und dem uebergebenen Key.
- [ ] Schema-Cache in `EventBus` laedt jedes Schema nur einmal (nachgewiesen durch Test mit zwei `publish()`-Aufrufen fuer dasselbe Schema).
- [ ] `EventBus` loggt fehlgeschlagene Sends ueber den `whenComplete`-Callback (nachgewiesen durch Test mit fehlschlagendem `KafkaTemplate`).
- [ ] `TypedKafkaConsumer.consume()` ruft `process()` nur auf, wenn `decode()` nicht `null` zurueckgibt.
- [ ] `TypedKafkaConsumer.consume()` faengt `DocumentNotFoundException` und generische Exceptions ab und loggt sie, ohne den Consumer zu crashen.
- [ ] Alle 5 einfachen Producer-Klassen sind geloescht; ihre Funktionalitaet ist durch `EventBus.publish()`-Aufrufe in den jeweiligen Services ersetzt.
- [ ] `ExplainabilityEventProducer` bleibt unveraendert und funktionsfaehig.
- [ ] Alle ~13 Consumer-Klassen erben von `TypedKafkaConsumer<T>` und implementieren `decode()`/`process()`.
- [ ] `@KafkaListener`-Annotationen verbleiben auf den konkreten Consumer-Klassen (nicht auf der Basisklasse).
- [ ] Consumer-Klassen haben maximal ~15 LOC (exklusive Imports und Klassendeklaration).
- [ ] Bestehende Kafka-Funktionalitaet bleibt vollstaendig erhalten (Smoke-Test laeuft durch).
- [ ] Bestehende Tests bleiben gruen.
