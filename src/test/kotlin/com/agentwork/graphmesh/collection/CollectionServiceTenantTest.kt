package com.agentwork.graphmesh.collection

import com.agentwork.graphmesh.tenant.AccessDeniedException
import com.agentwork.graphmesh.tenant.TenantContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.assertEquals

class CollectionServiceTenantTest {

    private val collectionStore = mockk<CollectionStore>(relaxed = true)
    private val lifecycleManager = mockk<CollectionLifecycleManager>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val collectionEventProducer = mockk<CollectionEventProducer>(relaxed = true)

    private val service = CollectionService(
        collectionStore, lifecycleManager,
        eventPublisher, collectionEventProducer
    )

    @AfterEach
    fun cleanup() {
        TenantContext.clear()
    }

    @Test
    fun `findAll without tenant returns all collections`() {
        every { collectionStore.findAll() } returns listOf(
            Collection(id = "1", name = "A", tenantId = "acme"),
            Collection(id = "2", name = "B", tenantId = "other"),
            Collection(id = "3", name = "C", tenantId = null)
        )

        val result = service.findAll()
        assertEquals(3, result.size)
    }

    @Test
    fun `findAll with tenant filters by tenantId`() {
        TenantContext.set(TenantContext(tenantId = "acme", userId = "user-1"))

        every { collectionStore.findAll() } returns listOf(
            Collection(id = "1", name = "A", tenantId = "acme"),
            Collection(id = "2", name = "B", tenantId = "other"),
            Collection(id = "3", name = "C", tenantId = null)
        )

        val result = service.findAll()
        assertEquals(2, result.size) // acme + null (shared)
    }

    @Test
    fun `findById with matching tenant succeeds`() {
        TenantContext.set(TenantContext(tenantId = "acme", userId = "user-1"))

        every { collectionStore.findById("1") } returns Collection(id = "1", name = "A", tenantId = "acme")

        val result = service.findById("1")
        assertEquals("A", result?.name)
    }

    @Test
    fun `findById with different tenant throws AccessDeniedException`() {
        TenantContext.set(TenantContext(tenantId = "acme", userId = "user-1"))

        every { collectionStore.findById("2") } returns Collection(id = "2", name = "B", tenantId = "other")

        assertThrows<AccessDeniedException> {
            service.findById("2")
        }
    }

    @Test
    fun `findById without tenant allows access to any collection`() {
        every { collectionStore.findById("2") } returns Collection(id = "2", name = "B", tenantId = "other")

        val result = service.findById("2")
        assertEquals("B", result?.name)
    }

    @Test
    fun `findById allows access to collections without tenant`() {
        TenantContext.set(TenantContext(tenantId = "acme", userId = "user-1"))

        every { collectionStore.findById("3") } returns Collection(id = "3", name = "C", tenantId = null)

        val result = service.findById("3")
        assertEquals("C", result?.name)
    }

    @Test
    fun `create with tenant sets tenantId and ownerId`() {
        TenantContext.set(TenantContext(tenantId = "acme", userId = "user-1"))

        every { collectionStore.findByName("New") } returns null

        val result = service.create(name = "New")
        assertEquals("acme", result.tenantId)
        assertEquals("user-1", result.ownerId)
    }

    @Test
    fun `create without tenant leaves tenantId null`() {
        every { collectionStore.findByName("New") } returns null

        val result = service.create(name = "New")
        assertEquals(null, result.tenantId)
        assertEquals(null, result.ownerId)
    }
}
