package com.agentwork.graphmesh.skos

import com.agentwork.graphmesh.ontology.LangLabel
import com.agentwork.graphmesh.rdf.SkosTypes
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.RDF_TYPE_URI
import com.agentwork.graphmesh.storage.StoredQuad
import org.springframework.stereotype.Service

@Service
class SkosService(private val quadStore: QuadStore) {

    fun getConceptSchemes(collectionId: String): List<SkosConceptScheme> {
        val schemeSubjects = quadStore.query(
            collectionId,
            QuadQuery(predicate = RDF_TYPE_URI, objectValue = SkosTypes.CONCEPT_SCHEME)
        ).map { it.subject }.distinct()
        return schemeSubjects.map { uri -> buildScheme(collectionId, uri) }
    }

    fun getConcepts(collectionId: String, schemeUri: String): List<SkosConcept> {
        val conceptUris = quadStore.query(
            collectionId,
            QuadQuery(predicate = SkosTypes.IN_SCHEME, objectValue = schemeUri)
        ).map { it.subject }.distinct()
        return conceptUris.mapNotNull { uri -> buildConcept(collectionId, uri) }
    }

    fun getTopConcepts(collectionId: String, schemeUri: String): List<SkosConcept> {
        val fromScheme = quadStore.query(
            collectionId,
            QuadQuery(subject = schemeUri, predicate = SkosTypes.HAS_TOP_CONCEPT)
        ).map { it.objectValue }
        val fromConcept = quadStore.query(
            collectionId,
            QuadQuery(predicate = SkosTypes.TOP_CONCEPT_OF, objectValue = schemeUri)
        ).map { it.subject }
        val topUris = (fromScheme + fromConcept).distinct()
        return topUris.mapNotNull { uri -> buildConcept(collectionId, uri) }
    }

    fun getNarrower(collectionId: String, conceptUri: String): List<SkosConcept> {
        val uris = quadStore.query(
            collectionId,
            QuadQuery(subject = conceptUri, predicate = SkosTypes.NARROWER)
        ).map { it.objectValue }.distinct()
        return uris.mapNotNull { uri -> buildConcept(collectionId, uri) }
    }

    fun getBroader(collectionId: String, conceptUri: String): List<SkosConcept> {
        val uris = quadStore.query(
            collectionId,
            QuadQuery(subject = conceptUri, predicate = SkosTypes.BROADER)
        ).map { it.objectValue }.distinct()
        return uris.mapNotNull { uri -> buildConcept(collectionId, uri) }
    }

    fun getRelated(collectionId: String, conceptUri: String): List<SkosConcept> {
        val uris = quadStore.query(
            collectionId,
            QuadQuery(subject = conceptUri, predicate = SkosTypes.RELATED)
        ).map { it.objectValue }.distinct()
        return uris.mapNotNull { uri -> buildConcept(collectionId, uri) }
    }

    fun findByLabel(collectionId: String, label: String): List<SkosConcept> {
        val needle = label.lowercase()
        val prefLabelQuads = quadStore.query(collectionId, QuadQuery(predicate = SkosTypes.PREF_LABEL))
        val altLabelQuads = quadStore.query(collectionId, QuadQuery(predicate = SkosTypes.ALT_LABEL))
        val matchingUris = (prefLabelQuads + altLabelQuads)
            .filter { it.objectValue.lowercase().contains(needle) }
            .map { it.subject }
            .distinct()
        return matchingUris.mapNotNull { uri -> buildConcept(collectionId, uri) }
    }

    fun getConcept(collectionId: String, conceptUri: String): SkosConcept? =
        buildConcept(collectionId, conceptUri)

    fun countConcepts(collectionId: String, schemeUri: String): Int =
        quadStore.query(collectionId, QuadQuery(predicate = SkosTypes.IN_SCHEME, objectValue = schemeUri))
            .map { it.subject }.distinct().size

    private fun buildScheme(collectionId: String, uri: String): SkosConceptScheme {
        val quads = quadStore.query(collectionId, QuadQuery(subject = uri))
        val prefLabels = quads.filter { it.predicate == SkosTypes.PREF_LABEL }
            .map { LangLabel(value = it.objectValue, lang = it.language.ifEmpty { "en" }) }
        val topConcepts = quads.filter { it.predicate == SkosTypes.HAS_TOP_CONCEPT }
            .map { it.objectValue }
        return SkosConceptScheme(uri = uri, prefLabels = prefLabels, topConcepts = topConcepts, collectionId = collectionId)
    }

    private fun buildConcept(collectionId: String, uri: String): SkosConcept? {
        val quads = quadStore.query(collectionId, QuadQuery(subject = uri))
        if (quads.isEmpty()) return null
        val isConcept = quads.any { it.predicate == RDF_TYPE_URI && it.objectValue == SkosTypes.CONCEPT }
        if (!isConcept) return null
        return SkosConcept(
            uri = uri,
            prefLabels = quads.extractLabels(SkosTypes.PREF_LABEL),
            altLabels = quads.extractLabels(SkosTypes.ALT_LABEL),
            broader = quads.extractUris(SkosTypes.BROADER),
            narrower = quads.extractUris(SkosTypes.NARROWER),
            related = quads.extractUris(SkosTypes.RELATED),
            inScheme = quads.firstOrNull { it.predicate == SkosTypes.IN_SCHEME }?.objectValue,
            scopeNote = quads.firstOrNull { it.predicate == SkosTypes.SCOPE_NOTE }?.objectValue,
            definition = quads.firstOrNull { it.predicate == SkosTypes.DEFINITION }?.objectValue,
            collectionId = collectionId
        )
    }

    private fun List<StoredQuad>.extractLabels(predicate: String): List<LangLabel> =
        filter { it.predicate == predicate }
            .map { LangLabel(value = it.objectValue, lang = it.language.ifEmpty { "en" }) }

    private fun List<StoredQuad>.extractUris(predicate: String): List<String> =
        filter { it.predicate == predicate }.map { it.objectValue }
}
