package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.collection.CollectionOntologyService
import com.agentwork.graphmesh.collection.CollectionService
import com.agentwork.graphmesh.ontology.OntologyMetadata
import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.rdfimport.RdfFormat
import com.agentwork.graphmesh.rdfimport.RdfImportService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Requires: docker-compose up (Cassandra, Qdrant, MinIO, Kafka)
 */
@SpringBootTest
@AutoConfigureMockMvc
class DynamicGraphQlIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var collectionService: CollectionService
    @Autowired lateinit var collectionOntologyService: CollectionOntologyService
    @Autowired lateinit var ontologyService: OntologyService
    @Autowired lateinit var rdfImportService: RdfImportService
    @Autowired lateinit var registry: DynamicGraphQlRegistry

    @Test
    fun `import RDF with ontology creates dynamic endpoint`() {
        val collection = collectionService.create(
            name = "dyngql-test-${System.currentTimeMillis()}",
            description = "integration test"
        )

        val ontoKey = "dyngql-test-onto-${System.currentTimeMillis()}"
        ontologyService.importTurtle(
            key = ontoKey,
            content = """
                @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                @prefix : <http://test.org/> .

                :Person a owl:Class .
                :name a owl:DatatypeProperty ;
                    rdfs:domain :Person ;
                    rdfs:range xsd:string .
            """.trimIndent(),
            metadata = OntologyMetadata(name = "TestOntology", namespace = "http://test.org/", version = "1.0")
        )

        collectionOntologyService.assign(collection.id, ontoKey, "primary", "test")

        val turtle = """
            @prefix : <http://test.org/> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .

            :alice rdf:type :Person ;
                :name "Alice" .

            :bob rdf:type :Person ;
                :name "Bob" .
        """.trimIndent()

        rdfImportService.importRdf(collection.id, turtle, RdfFormat.TURTLE, null, false)

        assertTrue(registry.has(collection.id), "Schema should be registered after import")

        mockMvc.post("/graphql/${collection.name}") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"query": "{ Person(limit: 10) { id name } }"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.Person") { isArray() }
        }
    }
}
