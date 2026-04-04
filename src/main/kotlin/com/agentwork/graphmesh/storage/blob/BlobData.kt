package com.agentwork.graphmesh.storage.blob

data class BlobData(
    val data: ByteArray,
    val contentType: String,
    val contentLength: Long,
    val metadata: Map<String, String> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlobData) return false
        return data.contentEquals(other.data) && contentType == other.contentType
    }

    override fun hashCode(): Int = data.contentHashCode()
}
