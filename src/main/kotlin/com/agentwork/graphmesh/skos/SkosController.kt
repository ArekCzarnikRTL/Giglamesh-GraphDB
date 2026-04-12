package com.agentwork.graphmesh.skos

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

@Controller
class SkosController(private val skosService: SkosService) {

    @QueryMapping
    fun skosConceptSchemes(@Argument collectionId: String): List<SkosConceptScheme> =
        skosService.getConceptSchemes(collectionId)

    @QueryMapping
    fun skosConcepts(@Argument collectionId: String, @Argument schemeUri: String): List<SkosConcept> =
        skosService.getConcepts(collectionId, schemeUri)

    @QueryMapping
    fun skosConcept(@Argument collectionId: String, @Argument conceptUri: String): SkosConcept? =
        skosService.getConcept(collectionId, conceptUri)

    @QueryMapping
    fun skosSearch(@Argument collectionId: String, @Argument label: String): List<SkosConcept> =
        skosService.findByLabel(collectionId, label)

    @SchemaMapping(typeName = "SkosConceptScheme", field = "topConcepts")
    fun topConcepts(@Argument collectionId: String, scheme: SkosConceptScheme): List<SkosConcept> =
        skosService.getTopConcepts(collectionId, scheme.uri)

    @SchemaMapping(typeName = "SkosConceptScheme", field = "conceptCount")
    fun conceptCount(@Argument collectionId: String, scheme: SkosConceptScheme): Int =
        skosService.countConcepts(collectionId, scheme.uri)

    @SchemaMapping(typeName = "SkosConcept", field = "broader")
    fun broader(@Argument collectionId: String, concept: SkosConcept): List<SkosConcept> =
        skosService.getBroader(collectionId, concept.uri)

    @SchemaMapping(typeName = "SkosConcept", field = "narrower")
    fun narrower(@Argument collectionId: String, concept: SkosConcept): List<SkosConcept> =
        skosService.getNarrower(collectionId, concept.uri)

    @SchemaMapping(typeName = "SkosConcept", field = "related")
    fun related(@Argument collectionId: String, concept: SkosConcept): List<SkosConcept> =
        skosService.getRelated(collectionId, concept.uri)
}
