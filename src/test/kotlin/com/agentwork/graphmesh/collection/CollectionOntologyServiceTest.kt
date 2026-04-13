package com.agentwork.graphmesh.collection

import io.mockk.*
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.datastax.oss.driver.api.core.cql.Row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionOntologyServiceTest {

    private val session = mockk<CqlSession>(relaxed = true)
    private val service = CollectionOntologyService(session, "graphmesh")

    @Test
    fun `assign creates record`() {
        every { session.execute(any<String>()) } returns mockk(relaxed = true)

        val record = service.assign("col-1", "pharma-onto", "domain", "admin")

        assertEquals("col-1", record.collectionId)
        assertEquals("pharma-onto", record.ontologyKey)
        assertEquals("domain", record.role)
        assertEquals("admin", record.assignedBy)
    }

    @Test
    fun `unassign deletes record`() {
        every { session.execute(any<String>()) } returns mockk(relaxed = true)

        service.unassign("col-1", "pharma-onto")

        verify { session.execute(match<String> { it.contains("DELETE") }) }
    }

    @Test
    fun `listForCollection returns records`() {
        val row = mockk<Row>(relaxed = true)
        every { row.getString("ontology_key") } returns "pharma-onto"
        every { row.getString("role") } returns "domain"
        every { row.getString("assigned_by") } returns "admin"
        every { row.getInstant("assigned_at") } returns java.time.Instant.now()
        val rs = mockk<ResultSet>()
        every { rs.iterator() } returns mutableListOf(row).iterator()
        every { session.execute(match<String> { it.contains("SELECT") && it.contains("collection_id") }) } returns rs

        val results = service.listForCollection("col-1")

        assertEquals(1, results.size)
        assertEquals("pharma-onto", results[0].ontologyKey)
    }
}
