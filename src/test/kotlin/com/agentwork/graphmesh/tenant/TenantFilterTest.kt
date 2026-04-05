package com.agentwork.graphmesh.tenant

import jakarta.servlet.FilterChain
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TenantFilterTest {

    @Test
    fun `sets TenantContext when both headers present`() {
        val filter = TenantFilter()
        val request = MockHttpServletRequest()
        request.addHeader("X-Tenant-Id", "acme")
        request.addHeader("X-User-Id", "user-1")
        val response = MockHttpServletResponse()

        var capturedTenant: TenantContext? = null
        val chain = FilterChain { _, _ ->
            capturedTenant = TenantContext.getOrNull()
        }

        filter.doFilter(request, response, chain)

        assertEquals("acme", capturedTenant?.tenantId)
        assertEquals("user-1", capturedTenant?.userId)
        // Context should be cleared after filter
        assertNull(TenantContext.getOrNull())
    }

    @Test
    fun `does not set TenantContext when headers missing`() {
        val filter = TenantFilter()
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        var capturedTenant: TenantContext? = null
        val chain = FilterChain { _, _ ->
            capturedTenant = TenantContext.getOrNull()
        }

        filter.doFilter(request, response, chain)

        assertNull(capturedTenant)
    }

    @Test
    fun `does not set TenantContext when only tenant header present`() {
        val filter = TenantFilter()
        val request = MockHttpServletRequest()
        request.addHeader("X-Tenant-Id", "acme")
        val response = MockHttpServletResponse()

        var capturedTenant: TenantContext? = null
        val chain = FilterChain { _, _ ->
            capturedTenant = TenantContext.getOrNull()
        }

        filter.doFilter(request, response, chain)

        assertNull(capturedTenant)
    }

    @Test
    fun `clears TenantContext even on exception`() {
        val filter = TenantFilter()
        val request = MockHttpServletRequest()
        request.addHeader("X-Tenant-Id", "acme")
        request.addHeader("X-User-Id", "user-1")
        val response = MockHttpServletResponse()

        val chain = FilterChain { _, _ ->
            throw RuntimeException("boom")
        }

        try {
            filter.doFilter(request, response, chain)
        } catch (_: RuntimeException) {}

        assertNull(TenantContext.getOrNull())
    }
}
