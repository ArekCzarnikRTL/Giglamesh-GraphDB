package com.agentwork.graphmesh.tenant

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TenantContextTest {

    @Test
    fun `set and get returns context`() {
        val ctx = TenantContext(tenantId = "acme", userId = "user-1")
        TenantContext.set(ctx)
        try {
            assertEquals("acme", TenantContext.get().tenantId)
            assertEquals("user-1", TenantContext.get().userId)
        } finally {
            TenantContext.clear()
        }
    }

    @Test
    fun `getOrNull returns null when not set`() {
        TenantContext.clear()
        assertNull(TenantContext.getOrNull())
    }

    @Test
    fun `get throws when not set`() {
        TenantContext.clear()
        assertThrows<IllegalStateException> {
            TenantContext.get()
        }
    }

    @Test
    fun `clear removes context`() {
        TenantContext.set(TenantContext(tenantId = "acme", userId = "user-1"))
        TenantContext.clear()
        assertNull(TenantContext.getOrNull())
    }
}
