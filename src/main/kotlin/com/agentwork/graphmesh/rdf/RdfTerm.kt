package com.agentwork.graphmesh.rdf

sealed class RdfTerm {

    data class Uri(val value: String) : RdfTerm() {
        override fun toNTriples(): String = "<$value>"
    }

    data class Literal(
        val value: String,
        val datatype: String = XsdTypes.STRING,
        val language: String? = null
    ) : RdfTerm() {
        init {
            require(language == null || datatype == XsdTypes.STRING || datatype == RdfTypes.LANG_STRING) {
                "Literal with language tag must have xsd:string or rdf:langString as datatype"
            }
        }

        override fun toNTriples(): String = when {
            language != null -> "\"$value\"@$language"
            datatype != XsdTypes.STRING -> "\"$value\"^^<$datatype>"
            else -> "\"$value\""
        }
    }

    data class BlankNode(val id: String) : RdfTerm() {
        override fun toNTriples(): String = "_:$id"
    }

    data class QuotedTriple(val triple: Triple) : RdfTerm() {
        override fun toNTriples(): String =
            "<< ${triple.subject.toNTriples()} ${triple.predicate.toNTriples()} ${triple.objectTerm.toNTriples()} >>"
    }

    abstract fun toNTriples(): String
}

object XsdTypes {
    const val STRING = "http://www.w3.org/2001/XMLSchema#string"
    const val INTEGER = "http://www.w3.org/2001/XMLSchema#integer"
    const val LONG = "http://www.w3.org/2001/XMLSchema#long"
    const val DOUBLE = "http://www.w3.org/2001/XMLSchema#double"
    const val FLOAT = "http://www.w3.org/2001/XMLSchema#float"
    const val BOOLEAN = "http://www.w3.org/2001/XMLSchema#boolean"
    const val DATE = "http://www.w3.org/2001/XMLSchema#date"
    const val DATE_TIME = "http://www.w3.org/2001/XMLSchema#dateTime"
    const val ANY_URI = "http://www.w3.org/2001/XMLSchema#anyURI"
}

object RdfTypes {
    const val LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString"
}

object SkosTypes {
    private const val NS = "http://www.w3.org/2004/02/skos/core#"

    const val CONCEPT = "${NS}Concept"
    const val CONCEPT_SCHEME = "${NS}ConceptScheme"
    const val IN_SCHEME = "${NS}inScheme"
    const val HAS_TOP_CONCEPT = "${NS}hasTopConcept"
    const val TOP_CONCEPT_OF = "${NS}topConceptOf"
    const val PREF_LABEL = "${NS}prefLabel"
    const val ALT_LABEL = "${NS}altLabel"
    const val HIDDEN_LABEL = "${NS}hiddenLabel"
    const val BROADER = "${NS}broader"
    const val NARROWER = "${NS}narrower"
    const val RELATED = "${NS}related"
    const val NOTE = "${NS}note"
    const val SCOPE_NOTE = "${NS}scopeNote"
    const val DEFINITION = "${NS}definition"
}
