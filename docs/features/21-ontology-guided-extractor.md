# Feature 21: Ontology-guided Extractor

## Problem

Freie Wissensextraktion (Feature 12) erzeugt Triples ohne formale Constraints, was zu inkonsistenten Praedikaten,
fehlenden Typ-Informationen und schwer abfragbaren Graphen fuehrt. Wenn eine Ontologie (Feature 20) verfuegbar ist,
sollte der Extraktionsprozess deren Klassen und Properties als Leitstruktur nutzen, um typkonforme und validierte
Triples zu erzeugen. Dies erfordert einen Zwei-Pass-Ansatz: zuerst Entitaeten klassifizieren, dann typisierte
Beziehungen extrahieren.

## Ziel

Implementierung eines Ontologie-gesteuerten Extractors, der Ontologie-Schemata als Kontext in LLM-Prompts verwendet und
extrahierte Triples gegen die Ontologie validiert.

1. **Ontologie-Kontext im Prompt** -- Klassen und Properties der Ontologie werden als Schema im LLM-Prompt
   bereitgestellt
2. **Zwei-Pass-Extraktion** -- (1) Entitaeten nach Ontologie-Klassen klassifizieren, (2) typisierte Beziehungen
   extrahieren
3. **Ontologie-Validierung** -- Extrahierte Triples werden gegen Domain/Range-Constraints der Ontologie geprueft
4. **Fallback auf freie Extraktion** -- Wenn keine passende Ontologie existiert, wird uneingeschraenkt extrahiert
5. **Embedding-basierte Ontologie-Selektion** -- Relevante Ontologie-Elemente werden per Vektor-Aehnlichkeit ausgewaehlt

## Voraussetzungen

| Abhaengigkeit                                                                               | Status     | Blocker? |
|---------------------------------------------------------------------------------------------|------------|----------|
| Feature 05: LLM Provider Abstraction (LlmProvider, ChatCompletionService, EmbeddingService) | Geplant    | Ja       |
| Feature 07: RDF Graph Model (Quad, RdfTerm, EntityIdGenerator)                              | Geplant    | Ja       |
| Feature 11: Document Chunker (liefert chunk.created Events)                                 | Geplant    | Ja       |
| Feature 20: Ontology System (Ontology, OntologyService)                                     | Geplant    | Ja       |
| Spring Boot 4.x                                                                             | Verfuegbar | Nein     |

## Architektur

### OntologyPromptBuilder

```kotlin
package com.graphmesh.extraction.ontology

import com.graphmesh.ontology.Ontology
import com.graphmesh.ontology.OntologyClass
import com.graphmesh.ontology.ObjectProperty
import com.graphmesh.ontology.DatatypeProperty

/**
 * Baut LLM-Prompts auf Basis eines Ontologie-Subsets.
 *
 * Das Prompt-Format folgt dem vereinfachten Entity-Relationship-Attribute-Ansatz:
 * Das LLM extrahiert natuerlichsprachliche Entitaeten und deren Typen,
 * der Code uebernimmt die URI-Konstruktion und RDF-Konvertierung.
 */
class OntologyPromptBuilder {

    /**
     * Erzeugt den Schema-Abschnitt fuer den LLM-Prompt aus einem Ontologie-Subset.
     */
    fun buildSchemaSection(subset: OntologySubset): String = buildString {
        appendLine("## Entity Types:")
        for ((id, cls) in subset.classes) {
            val label = cls.labels.firstOrNull()?.value ?: id
            append("- **$id** ($label)")
            cls.comment?.let { append(": $it") }
            appendLine()
        }

        if (subset.objectProperties.isNotEmpty()) {
            appendLine()
            appendLine("## Relationships:")
            for ((id, prop) in subset.objectProperties) {
                append("- **$id**")
                prop.comment?.let { append(": $it") }
                if (prop.domain != null && prop.range != null) {
                    append(" (${prop.domain} -> ${prop.range})")
                }
                appendLine()
            }
        }

        if (subset.datatypeProperties.isNotEmpty()) {
            appendLine()
            appendLine("## Attributes:")
            for ((id, prop) in subset.datatypeProperties) {
                append("- **$id**")
                prop.comment?.let { append(": $it") }
                if (prop.domain != null) {
                    append(" (${prop.domain} -> ${prop.range.uri})")
                }
                appendLine()
            }
        }
    }

    /**
     * Erzeugt den System-Prompt fuer Pass 1: Entity-Klassifikation.
     */
    fun classificationPrompt(schemaSection: String): String = """
        Du bist ein Wissensextraktions-Assistent. Deine Aufgabe ist es,
        Entitaeten im Text zu identifizieren und dem passenden Entity-Typ
        aus dem Schema zuzuordnen.

        $schemaSection

        Gib fuer jede erkannte Entitaet ein JSON-Objekt pro Zeile aus:
        {"type": "entity", "entity": "<Name>", "entity_type": "<Typ-ID aus Schema>"}

        Regeln:
        - Verwende NUR Typ-IDs aus dem Schema
        - Wenn keine passende Klasse existiert, ueberspringe die Entitaet
        - Verwende den natuerlichen Namen der Entitaet (kein URI)
    """.trimIndent()

    /**
     * Erzeugt den System-Prompt fuer Pass 2: Relationship-Extraktion.
     */
    fun relationshipPrompt(schemaSection: String): String = """
        Du bist ein Wissensextraktions-Assistent. Gegeben sind bereits
        klassifizierte Entitaeten. Extrahiere Beziehungen und Attribute
        gemaess dem Schema.

        $schemaSection

        Gib fuer jede Beziehung/jedes Attribut ein JSON-Objekt pro Zeile aus:

        Beziehungen:
        {"type": "relationship", "subject": "<Name>", "subject_type": "<Typ>", "relation": "<Property-ID>", "object": "<Name>", "object_type": "<Typ>"}

        Attribute:
        {"type": "attribute", "entity": "<Name>", "entity_type": "<Typ>", "attribute": "<Property-ID>", "value": "<Wert>"}

        Regeln:
        - Verwende NUR Relationship-/Attribute-IDs aus dem Schema
        - Subject/Object muessen zu den Domain/Range-Klassen passen
        - Werte fuer Attribute im natuerlichen Textformat
    """.trimIndent()
}

/**
 * Relevantes Subset einer Ontologie fuer einen bestimmten Text.
 */
data class OntologySubset(
    val classes: Map<String, OntologyClass>,
    val objectProperties: Map<String, ObjectProperty>,
    val datatypeProperties: Map<String, DatatypeProperty>
)
```

### OntologyValidationFilter

```kotlin
package com.graphmesh.extraction.ontology

import com.graphmesh.ontology.Ontology
import com.graphmesh.ontology.OntologyClass

/**
 * Filtert und validiert extrahierte Ergebnisse gegen die Ontologie.
 * Entfernt Triples, die nicht den Domain/Range-Constraints entsprechen.
 */
class OntologyValidationFilter(
    private val ontology: Ontology
) {

    /**
     * Validiert eine extrahierte Entity-Klassifikation.
     * Prueft, ob der Entity-Typ in der Ontologie existiert.
     */
    fun validateEntity(entityType: String): Boolean =
        entityType in ontology.classes

    /**
     * Validiert eine extrahierte Beziehung gegen Domain/Range.
     * Beruecksichtigt Vererbungshierarchie (Subklassen sind zulaessig).
     */
    fun validateRelationship(
        subjectType: String,
        relation: String,
        objectType: String
    ): Boolean {
        val property = ontology.objectProperties[relation] ?: return false

        val domainValid = property.domain?.let { domain ->
            isTypeOrSubtype(subjectType, domain)
        } ?: true

        val rangeValid = property.range?.let { range ->
            isTypeOrSubtype(objectType, range)
        } ?: true

        return domainValid && rangeValid
    }

    /**
     * Validiert ein extrahiertes Attribut gegen Domain.
     */
    fun validateAttribute(
        entityType: String,
        attribute: String
    ): Boolean {
        val property = ontology.datatypeProperties[attribute] ?: return false
        return property.domain?.let { domain ->
            isTypeOrSubtype(entityType, domain)
        } ?: true
    }

    /**
     * Prueft, ob ein Typ gleich oder Subtyp eines erwarteten Typs ist.
     */
    private fun isTypeOrSubtype(actualType: String, expectedType: String): Boolean {
        if (actualType == expectedType) return true
        return ontology.getClassHierarchy(actualType).contains(expectedType)
    }
}
```

### OntologyGuidedExtractorService

```kotlin
package com.graphmesh.extraction.ontology

import com.graphmesh.llm.ChatCompletionService
import com.graphmesh.llm.ChatMessage
import com.graphmesh.llm.ChatRole
import com.graphmesh.llm.EmbeddingService
import com.graphmesh.ontology.Ontology
import com.graphmesh.ontology.OntologyService
import com.graphmesh.rdf.*
import com.graphmesh.storage.cassandra.QuadStore
import com.graphmesh.librarian.LibrarianService
import java.util.UUID

/**
 * Ontologie-gesteuerter Extractor.
 *
 * Verwendet das Ontologie-Schema als Kontext im LLM-Prompt.
 * Zwei-Pass-Verfahren:
 * 1. Entity-Klassifikation: Entitaeten nach Ontologie-Klassen zuordnen
 * 2. Relationship-Extraktion: Typisierte Beziehungen und Attribute extrahieren
 *
 * Falls keine passende Ontologie vorhanden ist, faellt der Extractor
 * auf freie Extraktion (Feature 12) zurueck.
 */
class OntologyGuidedExtractorService(
    private val chatService: ChatCompletionService,
    private val embeddingService: EmbeddingService,
    private val ontologyService: OntologyService,
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService,
    private val promptBuilder: OntologyPromptBuilder = OntologyPromptBuilder()
) {

    /**
     * Extrahiert Wissen aus einem Chunk unter Verwendung der Ontologie.
     */
    suspend fun extract(
        chunkId: String,
        collectionId: UUID,
        ontologyKey: String? = null
    ): OntologyExtractionResult {
        val content = librarianService.getContent(chunkId)
        val chunkText = content.toString(Charsets.UTF_8)

        // Ontologie laden (oder Fallback)
        val ontology = loadOntology(ontologyKey)
            ?: return OntologyExtractionResult(
                chunkId = chunkId,
                mode = ExtractionMode.FREE,
                entitiesExtracted = 0,
                relationshipsExtracted = 0,
                attributesExtracted = 0,
                validationFailures = 0
            )

        // Relevantes Ontologie-Subset per Embedding-Aehnlichkeit bestimmen
        val subset = selectOntologySubset(chunkText, ontology)
        val schemaSection = promptBuilder.buildSchemaSection(subset)
        val filter = OntologyValidationFilter(ontology)

        // Pass 1: Entity-Klassifikation
        val classificationMessages = listOf(
            ChatMessage(role = ChatRole.SYSTEM, content = promptBuilder.classificationPrompt(schemaSection)),
            ChatMessage(role = ChatRole.USER, content = chunkText)
        )
        val classificationResponse = chatService.complete(classificationMessages)
        val entities = parseEntities(classificationResponse.content)
            .filter { filter.validateEntity(it.entityType) }

        // Pass 2: Relationship- und Attribut-Extraktion
        val entityContext = entities.joinToString("\n") {
            "${it.entity} (${it.entityType})"
        }
        val relationshipMessages = listOf(
            ChatMessage(role = ChatRole.SYSTEM, content = promptBuilder.relationshipPrompt(schemaSection)),
            ChatMessage(
                role = ChatRole.USER,
                content = "Bekannte Entitaeten:\n$entityContext\n\nText:\n$chunkText"
            )
        )
        val relationshipResponse = chatService.complete(relationshipMessages)
        val extractionItems = parseExtractionItems(relationshipResponse.content)

        // Validierung und Filterung
        var validationFailures = 0
        val validRelationships = extractionItems.filterIsInstance<ExtractionItem.Relationship>()
            .filter { rel ->
                val valid = filter.validateRelationship(rel.subjectType, rel.relation, rel.objectType)
                if (!valid) validationFailures++
                valid
            }
        val validAttributes = extractionItems.filterIsInstance<ExtractionItem.Attribute>()
            .filter { attr ->
                val valid = filter.validateAttribute(attr.entityType, attr.attribute)
                if (!valid) validationFailures++
                valid
            }

        // Quads erzeugen und speichern
        val quads = buildQuads(entities, validRelationships, validAttributes, ontology)
        quadStore.saveAll(collectionId.toString(), quads)

        return OntologyExtractionResult(
            chunkId = chunkId,
            mode = ExtractionMode.ONTOLOGY_GUIDED,
            entitiesExtracted = entities.size,
            relationshipsExtracted = validRelationships.size,
            attributesExtracted = validAttributes.size,
            validationFailures = validationFailures
        )
    }

    /**
     * Waehlt das relevante Ontologie-Subset per Embedding-Aehnlichkeit aus.
     */
    private suspend fun selectOntologySubset(text: String, ontology: Ontology): OntologySubset {
        // Vereinfacht: In einer vollstaendigen Implementierung wuerde hier
        // FAISS oder ein aehnlicher In-Memory-Vector-Store verwendet
        val textEmbedding = embeddingService.embed(text)

        // Klassen, deren Label/Kommentar dem Text aehnlich ist, auswaehlen
        // und abhaengige Properties einschliessen
        // Fuer Details siehe OntoRAG-Spezifikation (docs/base/ontorag.md)

        return OntologySubset(
            classes = ontology.classes,
            objectProperties = ontology.objectProperties,
            datatypeProperties = ontology.datatypeProperties
        )
    }

    private suspend fun loadOntology(key: String?): Ontology? {
        if (key == null) return null
        return ontologyService.getOntology(key)
    }

    private fun parseEntities(response: String): List<ExtractedEntity> {
        return response.lines()
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val json = kotlinx.serialization.json.Json.parseToJsonElement(line)
                        .jsonObject
                    val entity = json["entity"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val type = json["entity_type"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    ExtractedEntity(entity, type)
                } catch (e: Exception) { null }
            }
    }

    private fun parseExtractionItems(response: String): List<ExtractionItem> {
        return response.lines()
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val json = kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
                    when (json["type"]?.jsonPrimitive?.content) {
                        "relationship" -> ExtractionItem.Relationship(
                            subject = json["subject"]!!.jsonPrimitive.content,
                            subjectType = json["subject_type"]!!.jsonPrimitive.content,
                            relation = json["relation"]!!.jsonPrimitive.content,
                            objectValue = json["object"]!!.jsonPrimitive.content,
                            objectType = json["object_type"]!!.jsonPrimitive.content
                        )
                        "attribute" -> ExtractionItem.Attribute(
                            entity = json["entity"]!!.jsonPrimitive.content,
                            entityType = json["entity_type"]!!.jsonPrimitive.content,
                            attribute = json["attribute"]!!.jsonPrimitive.content,
                            value = json["value"]!!.jsonPrimitive.content
                        )
                        else -> null
                    }
                } catch (e: Exception) { null }
            }
    }

    private fun buildQuads(
        entities: List<ExtractedEntity>,
        relationships: List<ExtractionItem.Relationship>,
        attributes: List<ExtractionItem.Attribute>,
        ontology: Ontology
    ): List<Quad> {
        val quads = mutableListOf<Quad>()

        // rdf:type Triples fuer Entitaeten
        for (entity in entities) {
            val entityUri = EntityIdGenerator.generate(entity.entity, entity.entityType)
            val classUri = ontology.classes[entity.entityType]?.uri
                ?: continue

            quads.add(Quad(
                subject = entityUri,
                predicate = RdfTerm.Uri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                objectTerm = RdfTerm.Uri(classUri),
                graph = NamedGraph.DEFAULT
            ))

            // rdfs:label
            quads.add(Quad(
                subject = entityUri,
                predicate = RdfTerm.Uri("http://www.w3.org/2000/01/rdf-schema#label"),
                objectTerm = RdfTerm.Literal(entity.entity),
                graph = NamedGraph.DEFAULT
            ))
        }

        // Relationship-Triples
        for (rel in relationships) {
            val subjectUri = EntityIdGenerator.generate(rel.subject, rel.subjectType)
            val objectUri = EntityIdGenerator.generate(rel.objectValue, rel.objectType)
            val propertyUri = ontology.objectProperties[rel.relation]?.uri ?: continue

            quads.add(Quad(
                subject = subjectUri,
                predicate = RdfTerm.Uri(propertyUri),
                objectTerm = objectUri,
                graph = NamedGraph.DEFAULT
            ))
        }

        // Attribute-Triples
        for (attr in attributes) {
            val entityUri = EntityIdGenerator.generate(attr.entity, attr.entityType)
            val propertyUri = ontology.datatypeProperties[attr.attribute]?.uri ?: continue

            quads.add(Quad(
                subject = entityUri,
                predicate = RdfTerm.Uri(propertyUri),
                objectTerm = RdfTerm.Literal(attr.value),
                graph = NamedGraph.DEFAULT
            ))
        }

        return quads
    }
}
```

### Datenmodelle

```kotlin
package com.graphmesh.extraction.ontology

/**
 * Extrahierte Entitaet mit Ontologie-Typ.
 */
data class ExtractedEntity(
    val entity: String,
    val entityType: String
)

/**
 * Extrahiertes Item: Beziehung oder Attribut.
 */
sealed class ExtractionItem {
    data class Relationship(
        val subject: String,
        val subjectType: String,
        val relation: String,
        val objectValue: String,
        val objectType: String
    ) : ExtractionItem()

    data class Attribute(
        val entity: String,
        val entityType: String,
        val attribute: String,
        val value: String
    ) : ExtractionItem()
}

enum class ExtractionMode {
    FREE,
    ONTOLOGY_GUIDED
}

data class OntologyExtractionResult(
    val chunkId: String,
    val mode: ExtractionMode,
    val entitiesExtracted: Int,
    val relationshipsExtracted: Int,
    val attributesExtracted: Int,
    val validationFailures: Int
)
```

## Betroffene Dateien

### Backend

| Datei                                                                                            | Aenderung                                            |
|--------------------------------------------------------------------------------------------------|------------------------------------------------------|
| `extraction/src/main/kotlin/com/graphmesh/extraction/ontology/OntologyGuidedExtractorService.kt` | NEU - Haupt-Extractor mit Zwei-Pass-Verfahren        |
| `extraction/src/main/kotlin/com/graphmesh/extraction/ontology/OntologyPromptBuilder.kt`          | NEU - Prompt-Generierung aus Ontologie-Schema        |
| `extraction/src/main/kotlin/com/graphmesh/extraction/ontology/OntologyValidationFilter.kt`       | NEU - Domain/Range-Validierung extrahierter Triples  |
| `extraction/src/main/kotlin/com/graphmesh/extraction/ontology/OntologySubset.kt`                 | NEU - Relevantes Ontologie-Subset                    |
| `extraction/src/main/kotlin/com/graphmesh/extraction/ontology/ExtractionItem.kt`                 | NEU - Sealed Class fuer Relationships und Attributes |
| `extraction/src/main/kotlin/com/graphmesh/extraction/ontology/OntologyExtractionResult.kt`       | NEU - Ergebnis-Datenklasse                           |
| `extraction/src/main/kotlin/com/graphmesh/extraction/ontology/ExtractedEntity.kt`                | NEU - Entity-Klassifikation                          |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                                | Aenderung                                                   |
|------------------------------------------------------------------------------------------------------|-------------------------------------------------------------|
| `extraction/src/test/kotlin/com/graphmesh/extraction/ontology/OntologyGuidedExtractorServiceTest.kt` | NEU - Zwei-Pass-Extraktion, Fallback-Verhalten              |
| `extraction/src/test/kotlin/com/graphmesh/extraction/ontology/OntologyPromptBuilderTest.kt`          | NEU - Prompt-Generierung aus verschiedenen Ontologien       |
| `extraction/src/test/kotlin/com/graphmesh/extraction/ontology/OntologyValidationFilterTest.kt`       | NEU - Domain/Range-Validierung mit Vererbungshierarchie     |
| `extraction/src/test/kotlin/com/graphmesh/extraction/ontology/ExtractionItemParsingTest.kt`          | NEU - JSONL-Parsing von Entities, Relationships, Attributes |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                           |
|-------------------|-------------|-------------------------------------------------|
| Spring Boot (JVM) | Ja          | LLM-Client, Embedding-Service, Ontology-Service |
| KMP Library       | Nein        | Abhaengigkeit zu JVM-spezifischen Clients       |
| Ktor/Wasm         | Nein        | LLM- und Embedding-Clients sind JVM-spezifisch  |

## Akzeptanzkriterien

- [ ] Ontologie-Schema wird als Entity-Types/Relationships/Attributes-Abschnitt im LLM-Prompt bereitgestellt
- [ ] Pass 1 klassifiziert Entitaeten gemaess Ontologie-Klassen
- [ ] Pass 2 extrahiert typisierte Beziehungen und Attribute basierend auf den klassifizierten Entitaeten
- [ ] Extrahierte Triples werden gegen Domain/Range-Constraints der Ontologie validiert
- [ ] Vererbungshierarchie wird bei der Validierung beruecksichtigt (Subklassen sind zulaessig)
- [ ] Ungueltige Triples (Domain/Range-Verletzung) werden gefiltert und gezaehlt
- [ ] rdf:type-Triples werden fuer jede klassifizierte Entitaet mit der Ontologie-Klassen-URI erzeugt
- [ ] rdfs:label-Triples werden automatisch fuer jede Entitaet generiert
- [ ] Bei fehlender Ontologie wird auf freie Extraktion (Feature 12) zurueckgefallen
- [ ] Entity-URIs werden deterministisch aus (Name, Typ)-Tupel generiert
- [ ] JSONL-Parsing ist truncation-resilient
- [ ] `OntologyExtractionResult` enthaelt Zaehler fuer Entities, Relationships, Attributes und Validierungsfehler
