package com.agentwork.graphmesh.ontology

import com.agentwork.graphmesh.config.ConfigAction
import com.agentwork.graphmesh.config.ConfigChangedEvent
import com.agentwork.graphmesh.config.ConfigType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OntologyCacheTest {

    private val store = mockk<OntologyStore>()
    private val cache = OntologyCache(store)

    private val sampleOntology = Ontology(
        metadata = OntologyMetadata(name = "Animals", namespace = "http://example.org/animals/"),
        classes = mapOf(
            "Animal" to OntologyClass(id = "Animal", uri = "http://example.org/animals/Animal")
        )
    )

    @Test
    fun `get loads from store on cache miss`() {
        every { store.load("animals") } returns sampleOntology
        val result = cache.get("animals")
        assertNotNull(result)
        assertEquals("Animals", result.metadata.name)
        verify(exactly = 1) { store.load("animals") }
    }

    @Test
    fun `get returns cached value on second call without hitting store`() {
        every { store.load("animals") } returns sampleOntology
        cache.get("animals")
        val result = cache.get("animals")
        assertNotNull(result)
        assertEquals("Animals", result.metadata.name)
        verify(exactly = 1) { store.load("animals") }
    }

    @Test
    fun `get returns null when store returns null`() {
        every { store.load("nonexistent") } returns null
        assertNull(cache.get("nonexistent"))
    }

    @Test
    fun `onConfigChanged invalidates cache on UPDATED event`() {
        every { store.load("animals") } returns sampleOntology
        cache.get("animals")
        cache.onConfigChanged(ConfigChangedEvent(
            configId = "id-1", configType = ConfigType.ONTOLOGY,
            key = "animals", action = ConfigAction.UPDATED, version = 2
        ))
        cache.get("animals")
        verify(exactly = 2) { store.load("animals") }
    }

    @Test
    fun `onConfigChanged invalidates cache on DELETED event`() {
        every { store.load("animals") } returns sampleOntology
        cache.get("animals")
        cache.onConfigChanged(ConfigChangedEvent(
            configId = "id-1", configType = ConfigType.ONTOLOGY,
            key = "animals", action = ConfigAction.DELETED, version = 1
        ))
        every { store.load("animals") } returns null
        assertNull(cache.get("animals"))
    }

    @Test
    fun `onConfigChanged ignores non-ONTOLOGY events`() {
        every { store.load("animals") } returns sampleOntology
        cache.get("animals")
        cache.onConfigChanged(ConfigChangedEvent(
            configId = "id-1", configType = ConfigType.PARAMETER,
            key = "animals", action = ConfigAction.UPDATED, version = 2
        ))
        cache.get("animals")
        verify(exactly = 1) { store.load("animals") }
    }
}
