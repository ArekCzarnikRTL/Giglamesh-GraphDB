package com.agentwork.graphmesh.storage.blob

import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.io.InputStream
import java.net.URL
import java.time.Duration

class S3BlobStore(
    private val s3Client: S3Client,
    private val presigner: S3Presigner
) : BlobStore {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun put(
        bucket: String, key: String, data: ByteArray,
        contentType: String, metadata: Map<String, String>
    ) {
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucket).key(key)
                .contentType(contentType)
                .metadata(metadata)
                .build(),
            RequestBody.fromBytes(data)
        )
        log.debug("Blob stored: {}/{} ({} bytes)", bucket, key, data.size)
    }

    override fun put(
        bucket: String, key: String, inputStream: InputStream, contentLength: Long,
        contentType: String, metadata: Map<String, String>
    ) {
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucket).key(key)
                .contentType(contentType)
                .contentLength(contentLength)
                .metadata(metadata)
                .build(),
            RequestBody.fromInputStream(inputStream, contentLength)
        )
        log.debug("Blob stored from stream: {}/{} ({} bytes)", bucket, key, contentLength)
    }

    override fun get(bucket: String, key: String): BlobData {
        val response = s3Client.getObject(
            GetObjectRequest.builder().bucket(bucket).key(key).build()
        )
        val bytes = response.readAllBytes()
        return BlobData(
            data = bytes,
            contentType = response.response().contentType() ?: "application/octet-stream",
            contentLength = response.response().contentLength(),
            metadata = response.response().metadata()
        )
    }

    override fun delete(bucket: String, key: String) {
        s3Client.deleteObject(
            DeleteObjectRequest.builder().bucket(bucket).key(key).build()
        )
        log.debug("Blob deleted: {}/{}", bucket, key)
    }

    override fun deleteBatch(bucket: String, keys: List<String>) {
        if (keys.isEmpty()) return
        val objectIds = keys.map { ObjectIdentifier.builder().key(it).build() }
        s3Client.deleteObjects(
            DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(objectIds).build())
                .build()
        )
        log.debug("Batch deleted {} blobs from {}", keys.size, bucket)
    }

    override fun list(bucket: String, prefix: String, maxKeys: Int): List<BlobInfo> {
        val request = ListObjectsV2Request.builder()
            .bucket(bucket)
            .maxKeys(maxKeys)
        if (prefix.isNotEmpty()) {
            request.prefix(prefix)
        }
        val response = s3Client.listObjectsV2(request.build())
        return response.contents().map { obj ->
            BlobInfo(
                key = obj.key(),
                size = obj.size(),
                contentType = null,
                lastModified = obj.lastModified()
            )
        }
    }

    override fun exists(bucket: String, key: String): Boolean {
        return try {
            s3Client.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(key).build()
            )
            true
        } catch (e: NoSuchKeyException) {
            false
        }
    }

    override fun presignedGetUrl(bucket: String, key: String, expiration: Duration): URL {
        val request = GetObjectPresignRequest.builder()
            .signatureDuration(expiration)
            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
            .build()
        return presigner.presignGetObject(request).url()
    }

    override fun presignedPutUrl(bucket: String, key: String, contentType: String, expiration: Duration): URL {
        val request = PutObjectPresignRequest.builder()
            .signatureDuration(expiration)
            .putObjectRequest(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build()
            )
            .build()
        return presigner.presignPutObject(request).url()
    }

    override fun ensureBucket(bucket: String) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
            log.debug("Bucket exists: {}", bucket)
        } catch (e: NoSuchBucketException) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
            log.info("Bucket created: {}", bucket)
        }
    }
}
