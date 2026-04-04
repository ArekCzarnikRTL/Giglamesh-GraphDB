package com.agentwork.graphmesh.rdf

import java.security.MessageDigest

object EntityIdGenerator {

    private const val NAMESPACE = "http://graphmesh.io/entity/"

    fun generate(label: String): RdfTerm.Uri {
        val normalized = label.trim().lowercase().replace(Regex("\\s+"), " ")
        val hash = sha256(normalized)
        return RdfTerm.Uri("${NAMESPACE}${hash}")
    }

    fun generate(vararg fields: String): RdfTerm.Uri {
        val combined = fields.joinToString("|") { it.trim().lowercase() }
        val hash = sha256(combined)
        return RdfTerm.Uri("${NAMESPACE}${hash}")
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
