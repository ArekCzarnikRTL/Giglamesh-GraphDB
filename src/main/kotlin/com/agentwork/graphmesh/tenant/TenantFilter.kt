package com.agentwork.graphmesh.tenant

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class TenantFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val tenantId = request.getHeader("X-Tenant-Id")
        val userId = request.getHeader("X-User-Id")

        if (tenantId != null && userId != null) {
            TenantContext.set(TenantContext(tenantId = tenantId, userId = userId))
            log.debug("TenantContext set: tenantId={}, userId={}", tenantId, userId)
        }

        try {
            filterChain.doFilter(request, response)
        } finally {
            TenantContext.clear()
        }
    }
}
