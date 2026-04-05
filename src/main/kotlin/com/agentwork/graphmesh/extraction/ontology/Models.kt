package com.agentwork.graphmesh.extraction.ontology

import com.agentwork.graphmesh.ontology.DatatypeProperty
import com.agentwork.graphmesh.ontology.ObjectProperty
import com.agentwork.graphmesh.ontology.OntologyClass

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
