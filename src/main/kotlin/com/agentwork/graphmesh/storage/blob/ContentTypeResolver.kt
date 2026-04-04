package com.agentwork.graphmesh.storage.blob

object ContentTypeResolver {
    private val mimeTypes = mapOf(
        "pdf" to "application/pdf",
        "txt" to "text/plain",
        "json" to "application/json",
        "csv" to "text/csv",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "html" to "text/html",
        "xml" to "application/xml",
        "md" to "text/markdown"
    )

    fun resolve(filename: String): String {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return mimeTypes[extension] ?: "application/octet-stream"
    }
}
