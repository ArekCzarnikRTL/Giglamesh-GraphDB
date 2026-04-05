package com.agentwork.graphmesh.ontology

import com.agentwork.graphmesh.rdf.XsdTypes

data class LangLabel(val value: String, val lang: String = "en")

data class Cardinality(val min: Int? = null, val max: Int? = null, val exact: Int? = null) {
    init {
        require(min == null || max == null || min <= max) {
            "minCardinality ($min) darf nicht größer als maxCardinality ($max) sein"
        }
        require(exact == null || (min == null && max == null)) {
            "exact und min/max können nicht gleichzeitig gesetzt werden"
        }
    }
}

data class OntologyClass(
    val id: String,
    val uri: String,
    val labels: List<LangLabel> = emptyList(),
    val comment: String? = null,
    val subClassOf: List<String> = emptyList(),
    val equivalentClasses: List<String> = emptyList(),
    val disjointWith: List<String> = emptyList()
)

data class ObjectProperty(
    val id: String,
    val uri: String,
    val labels: List<LangLabel> = emptyList(),
    val comment: String? = null,
    val domain: String? = null,
    val range: String? = null,
    val inverseOf: String? = null,
    val functional: Boolean = false,
    val inverseFunctional: Boolean = false
)

data class DatatypeProperty(
    val id: String,
    val uri: String,
    val labels: List<LangLabel> = emptyList(),
    val comment: String? = null,
    val domain: String? = null,
    val range: String = XsdTypes.STRING,
    val functional: Boolean = false,
    val cardinality: Cardinality? = null
)

data class OntologyMetadata(
    val name: String,
    val description: String? = null,
    val version: String = "1.0.0",
    val namespace: String,
    val imports: List<String> = emptyList()
)

data class Ontology(
    val metadata: OntologyMetadata,
    val classes: Map<String, OntologyClass> = emptyMap(),
    val objectProperties: Map<String, ObjectProperty> = emptyMap(),
    val datatypeProperties: Map<String, DatatypeProperty> = emptyMap()
) {
    fun getSubClasses(classId: String): List<String> =
        classes.filter { (_, cls) -> classId in cls.subClassOf }.map { it.key }

    fun getClassHierarchy(classId: String): List<String> {
        val hierarchy = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var current = listOf(classId)
        while (current.isNotEmpty()) {
            val next = mutableListOf<String>()
            for (id in current) {
                if (id in visited) continue
                visited.add(id)
                hierarchy.add(id)
                classes[id]?.subClassOf?.let { next.addAll(it) }
            }
            current = next
        }
        return hierarchy
    }
}
