package com.agentwork.graphmesh.storage.blob

import java.io.InputStream
import java.net.URL
import java.time.Duration
import java.time.Instant

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

data class BlobInfo(
    val key: String,
    val size: Long,
    val contentType: String?,
    val lastModified: Instant,
    val metadata: Map<String, String> = emptyMap()
)

interface BlobStore {

    fun put(
        bucket: String,
        key: String,
        data: ByteArray,
        contentType: String = "application/octet-stream",
        metadata: Map<String, String> = emptyMap()
    )

    fun put(
        bucket: String,
        key: String,
        inputStream: InputStream,
        contentLength: Long,
        contentType: String = "application/octet-stream",
        metadata: Map<String, String> = emptyMap()
    )

    fun get(bucket: String, key: String): BlobData

    fun delete(bucket: String, key: String)

    fun deleteBatch(bucket: String, keys: List<String>)

    fun list(bucket: String, prefix: String = "", maxKeys: Int = 1000): List<BlobInfo>

    fun exists(bucket: String, key: String): Boolean

    fun presignedGetUrl(bucket: String, key: String, expiration: Duration = Duration.ofHours(1)): URL

    fun presignedPutUrl(bucket: String, key: String, contentType: String, expiration: Duration = Duration.ofHours(1)): URL

    fun ensureBucket(bucket: String)
}
