package com.agentwork.graphmesh.tenant

data class TenantContext(
    val tenantId: String,
    val userId: String
) {
    companion object {
        private val current = ThreadLocal<TenantContext>()

        fun set(context: TenantContext) = current.set(context)
        fun get(): TenantContext = current.get()
            ?: throw IllegalStateException("No TenantContext set for current thread.")
        fun getOrNull(): TenantContext? = current.get()
        fun clear() = current.remove()
    }
}

class AccessDeniedException(message: String) : RuntimeException(message)
