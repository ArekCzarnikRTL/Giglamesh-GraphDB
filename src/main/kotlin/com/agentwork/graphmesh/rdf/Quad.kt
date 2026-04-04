package com.agentwork.graphmesh.rdf

data class Triple(
    val subject: RdfTerm,
    val predicate: RdfTerm.Uri,
    val objectTerm: RdfTerm
) {
    fun toNTriples(): String =
        "${subject.toNTriples()} ${predicate.toNTriples()} ${objectTerm.toNTriples()} ."
}

data class Quad(
    val subject: RdfTerm,
    val predicate: RdfTerm.Uri,
    val objectTerm: RdfTerm,
    val graph: String = NamedGraph.DEFAULT
) {
    val triple: Triple get() = Triple(subject, predicate, objectTerm)

    fun toNQuads(): String =
        if (graph == NamedGraph.DEFAULT)
            "${subject.toNTriples()} ${predicate.toNTriples()} ${objectTerm.toNTriples()} ."
        else
            "${subject.toNTriples()} ${predicate.toNTriples()} ${objectTerm.toNTriples()} <$graph> ."
}

object NamedGraph {
    const val DEFAULT = ""
    const val SOURCE = "urn:graph:source"
    const val RETRIEVAL = "urn:graph:retrieval"

    fun isStandardGraph(graph: String): Boolean =
        graph in setOf(DEFAULT, SOURCE, RETRIEVAL)
}
