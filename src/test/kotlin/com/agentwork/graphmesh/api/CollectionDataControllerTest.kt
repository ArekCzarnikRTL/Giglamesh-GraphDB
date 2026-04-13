package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.collection.CollectionOntologyRecord
import com.agentwork.graphmesh.collection.CollectionOntologyService
import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.QuadStoreStats
import io.mockk.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionDataControllerTest {

    private val ontologyService = mockk<CollectionOntologyService>()
    private val quadStore = mockk<QuadStore>()
    private val ontService = mockk<OntologyService>()
    private val controller = CollectionDataController(ontologyService, quadStore, ontService)

    @Test
    fun `assignOntology returns payload`() {
        val record = CollectionOntologyRecord("col-1", "onto-1", "domain", Instant.now(), "admin")
        every { ontologyService.assign("col-1", "onto-1", "domain", "system") } returns record
        every { ontService.get("onto-1") } returns null

        val result = controller.assignOntology("col-1", "onto-1", "domain")

        assertEquals("onto-1", result.ontologyKey)
        assertEquals("domain", result.role)
    }

    @Test
    fun `unassignOntology returns true`() {
        every { ontologyService.unassign("col-1", "onto-1") } just runs

        val result = controller.unassignOntology("col-1", "onto-1")

        assertTrue(result)
    }

    @Test
    fun `collectionDataStats returns stats`() {
        every { quadStore.stats("col-1") } returns QuadStoreStats(
            tripleCount = 100, entityCount = 50, predicateCount = 10, datasets = listOf("default")
        )

        val result = controller.collectionDataStats("col-1")

        assertEquals(100, result.tripleCount)
        assertEquals(50, result.entityCount)
        assertEquals(listOf("default"), result.datasets)
    }

    @Test
    fun `deleteTriples with dataset delegates to deleteByDataset`() {
        every { quadStore.deleteByDataset("col-1", "test-ds") } returns 42L

        val result = controller.deleteTriples("col-1", "test-ds")

        assertEquals(42, result)
    }

    @Test
    fun `deleteTriples without dataset delegates to deleteCollection`() {
        every { quadStore.deleteCollection("col-1") } just runs

        controller.deleteTriples("col-1", null)

        verify { quadStore.deleteCollection("col-1") }
    }
}
