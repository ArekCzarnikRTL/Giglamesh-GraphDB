package com.agentwork.graphmesh.rdf

import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.StoredQuad

object QuadConverter {

    fun toStoredQuad(quad: Quad): StoredQuad = StoredQuad(
        subject = serializeTerm(quad.subject),
        predicate = quad.predicate.value,
        objectValue = serializeTerm(quad.objectTerm),
        dataset = quad.graph,
        objectType = when (quad.objectTerm) {
            is RdfTerm.Uri -> ObjectType.URI
            is RdfTerm.Literal -> ObjectType.LITERAL
            is RdfTerm.BlankNode -> ObjectType.URI
            is RdfTerm.QuotedTriple -> ObjectType.QUOTED_TRIPLE
        },
        datatype = (quad.objectTerm as? RdfTerm.Literal)?.datatype ?: "",
        language = (quad.objectTerm as? RdfTerm.Literal)?.language ?: ""
    )

    fun fromStoredQuad(stored: StoredQuad): Quad {
        val subject = RdfTerm.Uri(stored.subject)
        val predicate = RdfTerm.Uri(stored.predicate)
        val objectTerm = when (stored.objectType) {
            ObjectType.URI -> RdfTerm.Uri(stored.objectValue)
            ObjectType.LITERAL -> RdfTerm.Literal(
                value = stored.objectValue,
                datatype = stored.datatype.ifEmpty { XsdTypes.STRING },
                language = stored.language.ifEmpty { null }
            )
            ObjectType.QUOTED_TRIPLE -> deserializeQuotedTriple(stored.objectValue)
        }
        return Quad(subject, predicate, objectTerm, stored.dataset)
    }

    private fun serializeTerm(term: RdfTerm): String = when (term) {
        is RdfTerm.Uri -> term.value
        is RdfTerm.Literal -> term.value
        is RdfTerm.BlankNode -> "_:${term.id}"
        is RdfTerm.QuotedTriple ->
            "<<${serializeTerm(term.triple.subject)}|${term.triple.predicate.value}|${serializeTerm(term.triple.objectTerm)}>>"
    }

    private fun deserializeQuotedTriple(value: String): RdfTerm.QuotedTriple {
        val inner = value.removePrefix("<<").removeSuffix(">>")
        val parts = inner.split("|", limit = 3)
        require(parts.size == 3) { "Invalid quoted triple format: $value" }
        return RdfTerm.QuotedTriple(
            Triple(
                subject = RdfTerm.Uri(parts[0]),
                predicate = RdfTerm.Uri(parts[1]),
                objectTerm = RdfTerm.Uri(parts[2])
            )
        )
    }
}
