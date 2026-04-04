package com.agentwork.graphmesh.storage.blob

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration," +
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    ]
)
@ActiveProfiles("test")
class S3BlobStoreIntegrationTest {

    @Autowired
    lateinit var blobStore: BlobStore

    private lateinit var bucket: String

    @BeforeEach
    fun setUp() {
        bucket = "test-${UUID.randomUUID()}"
        blobStore.ensureBucket(bucket)
    }

    @Test
    fun `put and get roundtrip with content type and metadata`() {
        val data = "Hello, GraphMesh!".toByteArray()
        val metadata = mapOf("author" to "test")

        blobStore.put(bucket, "doc.txt", data, "text/plain", metadata)
        val result = blobStore.get(bucket, "doc.txt")

        assertEquals("Hello, GraphMesh!", String(result.data))
        assertEquals("text/plain", result.contentType)
        assertEquals(data.size.toLong(), result.contentLength)
        assertEquals("test", result.metadata["author"])
    }

    @Test
    fun `put from InputStream`() {
        val content = "Stream content"
        val bytes = content.toByteArray()
        val stream = ByteArrayInputStream(bytes)

        blobStore.put(bucket, "stream.txt", stream, bytes.size.toLong(), "text/plain")
        val result = blobStore.get(bucket, "stream.txt")

        assertEquals(content, String(result.data))
    }

    @Test
    fun `delete removes blob`() {
        blobStore.put(bucket, "to-delete.txt", "data".toByteArray(), "text/plain")
        assertTrue(blobStore.exists(bucket, "to-delete.txt"))

        blobStore.delete(bucket, "to-delete.txt")
        assertFalse(blobStore.exists(bucket, "to-delete.txt"))
    }

    @Test
    fun `deleteBatch removes multiple blobs`() {
        blobStore.put(bucket, "a.txt", "a".toByteArray(), "text/plain")
        blobStore.put(bucket, "b.txt", "b".toByteArray(), "text/plain")
        blobStore.put(bucket, "c.txt", "c".toByteArray(), "text/plain")

        blobStore.deleteBatch(bucket, listOf("a.txt", "b.txt"))

        assertFalse(blobStore.exists(bucket, "a.txt"))
        assertFalse(blobStore.exists(bucket, "b.txt"))
        assertTrue(blobStore.exists(bucket, "c.txt"))
    }

    @Test
    fun `list returns all blobs in bucket`() {
        blobStore.put(bucket, "file1.txt", "1".toByteArray(), "text/plain")
        blobStore.put(bucket, "file2.txt", "2".toByteArray(), "text/plain")

        val result = blobStore.list(bucket)
        assertEquals(2, result.size)
        assertTrue(result.any { it.key == "file1.txt" })
        assertTrue(result.any { it.key == "file2.txt" })
    }

    @Test
    fun `list with prefix filter`() {
        blobStore.put(bucket, "docs/a.pdf", "a".toByteArray(), "application/pdf")
        blobStore.put(bucket, "docs/b.pdf", "b".toByteArray(), "application/pdf")
        blobStore.put(bucket, "images/c.png", "c".toByteArray(), "image/png")

        val result = blobStore.list(bucket, prefix = "docs/")
        assertEquals(2, result.size)
        assertTrue(result.all { it.key.startsWith("docs/") })
    }

    @Test
    fun `exists returns true for existing blob`() {
        blobStore.put(bucket, "exists.txt", "yes".toByteArray(), "text/plain")
        assertTrue(blobStore.exists(bucket, "exists.txt"))
    }

    @Test
    fun `exists returns false for missing blob`() {
        assertFalse(blobStore.exists(bucket, "not-here.txt"))
    }

    @Test
    fun `presignedGetUrl generates valid URL`() {
        blobStore.put(bucket, "presign.txt", "content".toByteArray(), "text/plain")
        val url = blobStore.presignedGetUrl(bucket, "presign.txt")
        assertNotNull(url)
        assertTrue(url.toString().contains("presign.txt"))
        assertTrue(url.toString().contains("X-Amz-Signature"))
    }

    @Test
    fun `presignedPutUrl generates valid URL`() {
        val url = blobStore.presignedPutUrl(bucket, "upload.txt", "text/plain")
        assertNotNull(url)
        assertTrue(url.toString().contains("upload.txt"))
        assertTrue(url.toString().contains("X-Amz-Signature"))
    }

    @Test
    fun `ensureBucket is idempotent`() {
        val idempotentBucket = "idempotent-${UUID.randomUUID()}"
        blobStore.ensureBucket(idempotentBucket)
        blobStore.ensureBucket(idempotentBucket)
        // No exception = success
    }
}
