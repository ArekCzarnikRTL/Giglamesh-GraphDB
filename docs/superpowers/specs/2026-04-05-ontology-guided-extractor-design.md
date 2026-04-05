# Feature 21: Ontology-guided Extractor — Design Spec

## Zusammenfassung

Ontologie-gesteuerter Extractor mit Zwei-Pass-Verfahren: (1) Entitäten nach Ontologie-Klassen klassifizieren, (2) typisierte Beziehungen und Attribute extrahieren. Extrahierte Triples werden gegen Domain/Range-Constraints validiert. Eigener Kafka Consumer hört auf `chunk.created` Events, liest `ontologyKey` aus Collection-Metadata.

## Entscheidungen

| Entscheidung | Wahl | Begründung |
|---|---|---|
| Ontologie-Auswahl | ontologyKey aus Collection.metadata | Collection kennt ihre Ontologie |
| Fallback | Nichts tun (ExtractionMode.FREE, 0 Ergebnisse) | Freie Extraktion läuft via RelationshipExtractorConsumer parallel |
| Kafka Consumer | Eigener Consumer mit eigener groupId | Konsistent mit bestehenden Consumern, unabhängig |
| LLM-Integration | Koog PromptExecutor mit prompt{} DSL | Bestehender Projekt-Standard |
| JSON-Parsing | Jackson ObjectMapper | Projekt-Standard, kein kotlinx.serialization |
| Ontologie-Subset | Ganze Ontologie (kein Embedding-Filtering) | YAGNI, Embedding-Selektion als Future Work |
| Sync/Async | runBlocking { promptExecutor.execute() } | Konsistent mit bestehenden Extractors |

## Paket

`com.agentwork.graphmesh.extraction.ontology`

## Datenmodelle

```kotlin
data class ExtractedEntity(val entity: String, val entityType: String)

sealed class ExtractionItem {
    data class Relationship(
        val subject: String, val subjectType: String,
        val relation: String,
        val objectValue: String, val objectType: String
    ) : ExtractionItem()

    data class Attribute(
        val entity: String, val entityType: String,
        val attribute: String, val value: String
    ) : ExtractionItem()
}

enum class ExtractionMode { FREE, ONTOLOGY_GUIDED }

data class OntologyExtractionResult(
    val chunkId: String,
    val mode: ExtractionMode,
    val entitiesExtracted: Int,
    val relationshipsExtracted: Int,
    val attributesExtracted: Int,
    val validationFailures: Int
)

data class OntologySubset(
    val classes: Map<String, OntologyClass>,
    val objectProperties: Map<String, ObjectProperty>,
    val datatypeProperties: Map<String, DatatypeProperty>
)
```

## OntologyPromptBuilder

Zustandslose Utility-Klasse (kein Spring Component):

```kotlin
class OntologyPromptBuilder {
    fun buildSchemaSection(subset: OntologySubset): String
    fun classificationPrompt(schemaSection: String): String
    fun relationshipPrompt(schemaSection: String): String
}
```

**buildSchemaSection:** Erzeugt drei Abschnitte:
- `## Entity Types:` — ID, Label (aus `cls.labels.firstOrNull()?.value`), Comment
- `## Relationships:` — ID, Comment, Domain→Range
- `## Attributes:` — ID, Comment, Domain→Range (range ist XSD-URI-String)

**classificationPrompt:** System-Prompt für Pass 1. Output-Format JSONL:
```
{"type": "entity", "entity": "<Name>", "entity_type": "<Typ-ID>"}
```

**relationshipPrompt:** System-Prompt für Pass 2. Output-Format JSONL:
```
{"type": "relationship", "subject": "<Name>", "subject_type": "<Typ>", "relation": "<Property-ID>", "object": "<Name>", "object_type": "<Typ>"}
{"type": "attribute", "entity": "<Name>", "entity_type": "<Typ>", "attribute": "<Property-ID>", "value": "<Wert>"}
```

## OntologyValidationFilter

Pro Extraktion instanziiert mit der konkreten Ontologie:

```kotlin
class OntologyValidationFilter(private val ontology: Ontology) {
    fun validateEntity(entityType: String): Boolean
    fun validateRelationship(subjectType: String, relation: String, objectType: String): Boolean
    fun validateAttribute(entityType: String, attribute: String): Boolean
    private fun isTypeOrSubtype(actualType: String, expectedType: String): Boolean
}
```

- validateEntity: Prüft ob entityType in ontology.classes existiert
- validateRelationship: ObjectProperty existiert + Domain/Range passen (mit Vererbung via getClassHierarchy)
- validateAttribute: DatatypeProperty existiert + Domain passt (mit Vererbung)
- isTypeOrSubtype: `actualType == expectedType || expectedType in ontology.getClassHierarchy(actualType)`

## OntologyGuidedExtractorService

```kotlin
@Service
class OntologyGuidedExtractorService(
    private val promptExecutor: PromptExecutor,
    private val ontologyService: OntologyService,
    private val collectionService: CollectionService,
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService,
    private val objectMapper: ObjectMapper
) {
    private val promptBuilder = OntologyPromptBuilder()

    fun extract(chunkId: String, collectionId: String): OntologyExtractionResult
}
```

**extract() Flow:**
1. `collectionService.findById(collectionId)` → `collection.metadata["ontologyKey"]`
2. Kein Key → return `OntologyExtractionResult(mode=FREE, alles 0)`
3. `ontologyService.get(ontologyKey)` → null → return FREE
4. `librarianService.getContent(chunkId)` → `String(bytes)`
5. OntologySubset = ganze Ontologie
6. Schema-Section bauen
7. **Pass 1:** Koog prompt DSL → PromptExecutor → JSONL parsen → validateEntity filtern
8. **Pass 2:** Koog prompt DSL (mit Entity-Context) → JSONL parsen → validateRelationship/Attribute filtern
9. Quads: rdf:type + rdfs:label für Entities, Property-URIs für Relationships/Attributes
10. `QuadConverter.toStoredQuad()` → `quadStore.insertBatch(collectionId, storedQuads)`
11. Return OntologyExtractionResult mit Zählern

**JSONL-Parsing:** Jackson `objectMapper.readTree(line)` pro Zeile, skippt invalide Zeilen.

**Model:** Konfigurierbar via `@Value("${graphmesh.extraction.ontology.model:gpt-4o}")`.

**Quad-Generierung:**
- Entity → `EntityIdGenerator.generate(entity, entityType)` als Subject-URI
- rdf:type Triple: Subject → rdf:type → OntologyClass.uri
- rdfs:label Triple: Subject → rdfs:label → Literal(entity)
- Relationship Triple: SubjectURI → ObjectProperty.uri → ObjectURI
- Attribute Triple: EntityURI → DatatypeProperty.uri → Literal(value)

## Kafka Consumer

```kotlin
@Component
class OntologyGuidedExtractorConsumer(
    private val extractorService: OntologyGuidedExtractorService
) {
    @KafkaListener(
        topics = ["graphmesh.chunk.created"],
        groupId = "graphmesh-ontology-extractor"
    )
    fun onChunkCreated(record: ConsumerRecord<String, GenericRecord>)
}
```

Eigene `groupId` — empfängt alle Chunk-Events unabhängig von anderen Consumern. AVRO-Record parsen → extract() aufrufen → Result loggen.

## Dateien

| Datei | Beschreibung |
|---|---|
| `extraction/ontology/Models.kt` | ExtractedEntity, ExtractionItem, ExtractionMode, OntologyExtractionResult, OntologySubset |
| `extraction/ontology/OntologyPromptBuilder.kt` | Schema-Section + Prompt-Generierung |
| `extraction/ontology/OntologyValidationFilter.kt` | Domain/Range-Validierung mit Vererbung |
| `extraction/ontology/OntologyGuidedExtractorService.kt` | Zwei-Pass-Extraktion, JSONL-Parsing, Quad-Generierung |
| `extraction/ontology/OntologyGuidedExtractorConsumer.kt` | Kafka Consumer für chunk.created |

## Tests

| Testklasse | Fokus |
|---|---|
| `OntologyPromptBuilderTest` | Schema-Section mit Klassen/Properties, Classification/Relationship-Prompts |
| `OntologyValidationFilterTest` | Entity/Relationship/Attribute-Validierung, Vererbungshierarchie |
| `OntologyGuidedExtractorServiceTest` | Zwei-Pass mit MockK, Fallback-Verhalten, JSONL-Parsing, Quad-Generierung, Validierungszähler |
