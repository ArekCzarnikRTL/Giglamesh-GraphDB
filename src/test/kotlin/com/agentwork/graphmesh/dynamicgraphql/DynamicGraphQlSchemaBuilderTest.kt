package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.collection.CollectionOntologyRecord
import com.agentwork.graphmesh.collection.CollectionOntologyService
import com.agentwork.graphmesh.ontology.DatatypeProperty
import com.agentwork.graphmesh.ontology.ObjectProperty
import com.agentwork.graphmesh.ontology.Ontology
import com.agentwork.graphmesh.ontology.OntologyCache
import com.agentwork.graphmesh.ontology.OntologyClass
import com.agentwork.graphmesh.ontology.OntologyMetadata
import com.agentwork.graphmesh.rdf.XsdTypes
import com.agentwork.graphmesh.storage.QuadStore
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicGraphQlSchemaBuilderTest {

    private val quadStore = mockk<QuadStore>()
    private val ontologyCache = mockk<OntologyCache>()
    private val collectionOntologyService = mockk<CollectionOntologyService>()
    private val registry = DynamicGraphQlRegistry()

    private val builder = DynamicGraphQlSchemaBuilder(
        quadStore, ontologyCache, collectionOntologyService, registry
    )

    private val personOntology = Ontology(
        metadata = OntologyMetadata(name = "test", namespace = "http://example.org/", version = "1.0"),
        classes = mapOf(
            "Person" to OntologyClass(id = "Person", uri = "http://example.org/Person"),
            "Company" to OntologyClass(id = "Company", uri = "http://example.org/Company"),
        ),
        objectProperties = mapOf(
            "worksAt" to ObjectProperty(id = "worksAt", uri = "http://example.org/worksAt", domain = "Person", range = "Company", functional = false),
        ),
        datatypeProperties = mapOf(
            "name" to DatatypeProperty(id = "name", uri = "http://example.org/name", domain = "Person", range = XsdTypes.STRING, functional = true),
            "age" to DatatypeProperty(id = "age", uri = "http://example.org/age", domain = "Person", range = XsdTypes.INTEGER, functional = true),
            "companyName" to DatatypeProperty(id = "companyName", uri = "http://example.org/companyName", domain = "Company", range = XsdTypes.STRING, functional = true),
        ),
    )

    @Test
    fun `rebuildIfOntologyAssigned builds schema when ontology is assigned`() {
        every { collectionOntologyService.listForCollection("col-1") } returns listOf(
            CollectionOntologyRecord("col-1", "test", "primary", Instant.now(), "user")
        )
        every { ontologyCache.get("test") } returns personOntology
        builder.rebuildIfOntologyAssigned("col-1")
        assertTrue(registry.has("col-1"))
    }

    @Test
    fun `rebuildIfOntologyAssigned skips when no ontology assigned`() {
        every { collectionOntologyService.listForCollection("col-1") } returns emptyList()
        builder.rebuildIfOntologyAssigned("col-1")
        assertTrue(!registry.has("col-1"))
    }

    @Test
    fun `generated schema has top-level query per class`() {
        every { collectionOntologyService.listForCollection("col-1") } returns listOf(
            CollectionOntologyRecord("col-1", "test", "primary", Instant.now(), "user")
        )
        every { ontologyCache.get("test") } returns personOntology
        builder.rebuildIfOntologyAssigned("col-1")
        val schema = registry.get("col-1")!!.graphQLSchema
        val queryType = schema.queryType
        assertNotNull(queryType.getFieldDefinition("Person"))
        assertNotNull(queryType.getFieldDefinition("PersonById"))
        assertNotNull(queryType.getFieldDefinition("Company"))
        assertNotNull(queryType.getFieldDefinition("CompanyById"))
    }

    @Test
    fun `Person type has expected fields`() {
        every { collectionOntologyService.listForCollection("col-1") } returns listOf(
            CollectionOntologyRecord("col-1", "test", "primary", Instant.now(), "user")
        )
        every { ontologyCache.get("test") } returns personOntology
        builder.rebuildIfOntologyAssigned("col-1")
        val schema = registry.get("col-1")!!.graphQLSchema
        val personType = schema.getObjectType("Person")
        assertNotNull(personType)
        assertNotNull(personType.getFieldDefinition("id"))
        assertNotNull(personType.getFieldDefinition("name"))
        assertNotNull(personType.getFieldDefinition("age"))
        assertNotNull(personType.getFieldDefinition("worksAt"))
    }

    @Test
    fun `rebuildIfOntologyAssigned replaces existing schema`() {
        every { collectionOntologyService.listForCollection("col-1") } returns listOf(
            CollectionOntologyRecord("col-1", "test", "primary", Instant.now(), "user")
        )
        every { ontologyCache.get("test") } returns personOntology
        builder.rebuildIfOntologyAssigned("col-1")
        val first = registry.get("col-1")
        builder.rebuildIfOntologyAssigned("col-1")
        val second = registry.get("col-1")
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first !== second)
    }
}
