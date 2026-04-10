package com.agentwork.graphmesh.ontology

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import java.util.Base64

@Controller
class OntologyController(
    private val ontologyService: OntologyService,
) {

    @QueryMapping
    fun listOntologies(): List<OntologyInfoPayload> =
        ontologyService.list().mapNotNull { key ->
            ontologyService.get(key)?.toPayload(key)
        }

    @QueryMapping
    fun ontology(@Argument key: String): OntologyInfoPayload? =
        ontologyService.get(key)?.toPayload(key)

    @MutationMapping
    fun importOntology(@Argument input: ImportOntologyInput): OntologyInfoPayload {
        val content = String(Base64.getDecoder().decode(input.content), Charsets.UTF_8)
        val metadata = OntologyMetadata(
            name = input.name,
            namespace = input.namespace,
            version = input.version,
        )
        val ontology = when (input.format) {
            OntologyFormat.TURTLE -> ontologyService.importTurtle(input.key, content, metadata)
            OntologyFormat.RDFXML -> ontologyService.importRdfXml(input.key, content, metadata)
        }
        return ontology.toPayload(input.key)
    }

    @MutationMapping
    fun deleteOntology(@Argument key: String): Boolean {
        ontologyService.delete(key)
        return true
    }

    private fun Ontology.toPayload(key: String) = OntologyInfoPayload(
        key = key,
        name = metadata.name,
        namespace = metadata.namespace,
        version = metadata.version,
        classCount = classes.size,
        objectPropertyCount = objectProperties.size,
        datatypePropertyCount = datatypeProperties.size,
    )
}

enum class OntologyFormat { TURTLE, RDFXML }

data class ImportOntologyInput(
    val key: String,
    val content: String,
    val format: OntologyFormat,
    val name: String,
    val namespace: String,
    val version: String = "1.0",
)

data class OntologyInfoPayload(
    val key: String,
    val name: String,
    val namespace: String,
    val version: String,
    val classCount: Int,
    val objectPropertyCount: Int,
    val datatypePropertyCount: Int,
)
