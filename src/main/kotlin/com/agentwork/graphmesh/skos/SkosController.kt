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
    fun topConcepts(scheme: SkosConceptScheme): List<SkosConcept> =
        skosService.getTopConcepts(scheme.collectionId, scheme.uri)

    @SchemaMapping(typeName = "SkosConceptScheme", field = "conceptCount")
    fun conceptCount(scheme: SkosConceptScheme): Int =
        skosService.countConcepts(scheme.collectionId, scheme.uri)

    @SchemaMapping(typeName = "SkosConcept", field = "broader")
    fun broader(concept: SkosConcept): List<SkosConcept> =
        skosService.getBroader(concept.collectionId, concept.uri)

    @SchemaMapping(typeName = "SkosConcept", field = "narrower")
    fun narrower(concept: SkosConcept): List<SkosConcept> =
        skosService.getNarrower(concept.collectionId, concept.uri)

    @SchemaMapping(typeName = "SkosConcept", field = "related")
    fun related(concept: SkosConcept): List<SkosConcept> =
        skosService.getRelated(concept.collectionId, concept.uri)
}
