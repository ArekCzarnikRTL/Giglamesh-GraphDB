package com.agentwork.graphmesh.extraction.ontology

import com.agentwork.graphmesh.llm.resolveLlmModel

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.agentwork.graphmesh.collection.CollectionService
import com.agentwork.graphmesh.librarian.LibrarianService
import com.agentwork.graphmesh.ontology.Ontology
import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.rdf.EntityIdGenerator
import com.agentwork.graphmesh.rdf.NamedGraph
import com.agentwork.graphmesh.rdf.Quad
import com.agentwork.graphmesh.rdf.QuadConverter
import com.agentwork.graphmesh.rdf.RdfTerm
import com.agentwork.graphmesh.storage.QuadStore
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@OptIn(kotlin.time.ExperimentalTime::class)
@Service
class OntologyGuidedExtractorService(
    private val promptExecutor: PromptExecutor,
    private val ontologyService: OntologyService,
    private val collectionService: CollectionService,
    private val quadStore: QuadStore,
    private val librarianService: LibrarianService,
    private val objectMapper: ObjectMapper,
    @Value("\${graphmesh.extraction.ontology.model:gpt-4o}") private val modelName: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val promptBuilder = OntologyPromptBuilder()

    fun extract(chunkId: String, collectionId: String): OntologyExtractionResult {
        // 1. Get ontologyKey from collection metadata
        val collection = collectionService.findById(collectionId)
        val ontologyKey = collection?.metadata?.get("ontologyKey")

        if (ontologyKey == null) {
            logger.debug("No ontologyKey in collection {}, skipping ontology extraction", collectionId)
            return freeResult(chunkId)
        }

        // 2. Load ontology
        val ontology = ontologyService.get(ontologyKey)
        if (ontology == null) {
            logger.warn("Ontology '{}' not found, skipping ontology extraction", ontologyKey)
            return freeResult(chunkId)
        }

        // 3. Get chunk text
        val content = librarianService.getContent(chunkId)
        val chunkText = String(content, Charsets.UTF_8)

        if (chunkText.isBlank()) {
            return freeResult(chunkId)
        }

        // 4. Build ontology subset (full ontology for now)
        val subset = OntologySubset(
            classes = ontology.classes,
            objectProperties = ontology.objectProperties,
            datatypeProperties = ontology.datatypeProperties
        )
        val schemaSection = promptBuilder.buildSchemaSection(subset)
        val filter = OntologyValidationFilter(ontology)

        // 5. Pass 1: Entity classification
        val classificationPrompt = prompt("ontology-classification") {
            system(promptBuilder.classificationPrompt(schemaSection))
            user(chunkText)
        }
        val classificationResponse = runBlocking {
            promptExecutor.execute(classificationPrompt, resolveLlmModel(modelName))
        }
        val entities = parseEntities(classificationResponse.first().content)
            .filter { filter.validateEntity(it.entityType) }

        // 6. Pass 2: Relationship/Attribute extraction
        val entityContext = entities.joinToString("\n") { "${it.entity} (${it.entityType})" }
        val relationshipPrompt = prompt("ontology-relationship") {
            system(promptBuilder.relationshipPrompt(schemaSection))
            user("Bekannte Entitaeten:\n$entityContext\n\nText:\n$chunkText")
        }
        val relationshipResponse = runBlocking {
            promptExecutor.execute(relationshipPrompt, resolveLlmModel(modelName))
        }
        val extractionItems = parseExtractionItems(relationshipResponse.first().content)

        // 7. Validate and filter
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

        // 8. Build and persist quads
        val quads = buildQuads(entities, validRelationships, validAttributes, ontology, chunkId)
        if (quads.isNotEmpty()) {
            val storedQuads = quads.map { QuadConverter.toStoredQuad(it) }
            quadStore.insertBatch(collectionId, storedQuads)
        }

        logger.info(
            "Ontology extraction: chunk={}, entities={}, relationships={}, attributes={}, failures={}",
            chunkId, entities.size, validRelationships.size, validAttributes.size, validationFailures
        )

        return OntologyExtractionResult(
            chunkId = chunkId,
            mode = ExtractionMode.ONTOLOGY_GUIDED,
            entitiesExtracted = entities.size,
            relationshipsExtracted = validRelationships.size,
            attributesExtracted = validAttributes.size,
            validationFailures = validationFailures
        )
    }

    internal fun parseEntities(response: String): List<ExtractedEntity> {
        return response.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val node = objectMapper.readTree(line)
                    val entity = node["entity"]?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val entityType = node["entity_type"]?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ExtractedEntity(entity, entityType)
                } catch (_: Exception) {
                    null
                }
            }
    }

    internal fun parseExtractionItems(response: String): List<ExtractionItem> {
        return response.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .mapNotNull { line ->
                try {
                    val node = objectMapper.readTree(line)
                    when (node["type"]?.asText()) {
                        "relationship" -> ExtractionItem.Relationship(
                            subject = node["subject"]?.asText() ?: return@mapNotNull null,
                            subjectType = node["subject_type"]?.asText() ?: return@mapNotNull null,
                            relation = node["relation"]?.asText() ?: return@mapNotNull null,
                            objectValue = node["object"]?.asText() ?: return@mapNotNull null,
                            objectType = node["object_type"]?.asText() ?: return@mapNotNull null
                        )
                        "attribute" -> ExtractionItem.Attribute(
                            entity = node["entity"]?.asText() ?: return@mapNotNull null,
                            entityType = node["entity_type"]?.asText() ?: return@mapNotNull null,
                            attribute = node["attribute"]?.asText() ?: return@mapNotNull null,
                            value = node["value"]?.asText() ?: return@mapNotNull null
                        )
                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }
            }
    }

    private fun buildQuads(
        entities: List<ExtractedEntity>,
        relationships: List<ExtractionItem.Relationship>,
        attributes: List<ExtractionItem.Attribute>,
        ontology: Ontology,
        chunkId: String
    ): List<Quad> {
        val quads = mutableListOf<Quad>()

        // rdf:type and rdfs:label for entities
        for (entity in entities) {
            val entityUri = EntityIdGenerator.generate(entity.entity, entity.entityType)
            val classUri = ontology.classes[entity.entityType]?.uri ?: continue

            quads.add(
                Quad(
                    subject = entityUri,
                    predicate = RdfTerm.Uri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                    objectTerm = RdfTerm.Uri(classUri),
                    graph = NamedGraph.DEFAULT
                )
            )

            quads.add(
                Quad(
                    subject = entityUri,
                    predicate = RdfTerm.Uri("http://www.w3.org/2000/01/rdf-schema#label"),
                    objectTerm = RdfTerm.Literal(entity.entity),
                    graph = NamedGraph.DEFAULT
                )
            )
        }

        // Relationship triples
        for (rel in relationships) {
            val subjectUri = EntityIdGenerator.generate(rel.subject, rel.subjectType)
            val objectUri = EntityIdGenerator.generate(rel.objectValue, rel.objectType)
            val propertyUri = ontology.objectProperties[rel.relation]?.uri ?: continue

            quads.add(
                Quad(
                    subject = subjectUri,
                    predicate = RdfTerm.Uri(propertyUri),
                    objectTerm = objectUri,
                    graph = NamedGraph.DEFAULT
                )
            )
        }

        // Attribute triples
        for (attr in attributes) {
            val entityUri = EntityIdGenerator.generate(attr.entity, attr.entityType)
            val propertyUri = ontology.datatypeProperties[attr.attribute]?.uri ?: continue

            quads.add(
                Quad(
                    subject = entityUri,
                    predicate = RdfTerm.Uri(propertyUri),
                    objectTerm = RdfTerm.Literal(attr.value),
                    graph = NamedGraph.DEFAULT
                )
            )
        }

        // Provenance quads (RDF-Star)
        val knowledgeQuads = quads.toList()
        for (quad in knowledgeQuads) {
            quads.add(
                Quad(
                    subject = RdfTerm.QuotedTriple(quad.triple),
                    predicate = RdfTerm.Uri("http://graphmesh.io/ontology/extractedFrom"),
                    objectTerm = RdfTerm.Uri("urn:chunk:$chunkId"),
                    graph = NamedGraph.SOURCE
                )
            )
        }

        return quads
    }

    private fun freeResult(chunkId: String) = OntologyExtractionResult(
        chunkId = chunkId,
        mode = ExtractionMode.FREE,
        entitiesExtracted = 0,
        relationshipsExtracted = 0,
        attributesExtracted = 0,
        validationFailures = 0
    )
}
