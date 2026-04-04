package com.agentwork.graphmesh.storage.blob

import java.io.InputStream
import java.net.URL
import java.time.Duration

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
