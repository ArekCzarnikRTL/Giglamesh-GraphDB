# S3/MinIO Blob Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add S3-compatible blob storage to GraphMesh using AWS SDK v2 with MinIO for local development.

**Architecture:** Direct AWS SDK v2 `S3Client` + `S3Presigner` wrapped in a `BlobStore` interface with Spring auto-configuration. Follows existing pattern: low-level client, no framework wrappers.

**Tech Stack:** AWS SDK v2 (S3), MinIO (docker-compose), Spring Boot `@ConfigurationProperties`

---

## File Structure

| File | Responsibility |
|------|---------------|
| `src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobStore.kt` | Interface defining all blob operations |
| `src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobData.kt` | Data class for blob content + metadata |
| `src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobInfo.kt` | Data class for blob listing metadata |
| `src/main/kotlin/com/agentwork/graphmesh/storage/blob/ContentTypeResolver.kt` | Static MIME-type resolution from file extension |
| `src/main/kotlin/com/agentwork/graphmesh/storage/blob/S3BlobStore.kt` | BlobStore implementation using S3Client + S3Presigner |
| `src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobStoreProperties.kt` | `@ConfigurationProperties` for S3/MinIO connection |
| `src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobStoreAutoConfiguration.kt` | `@Configuration` creating S3Client, S3Presigner, S3BlobStore beans |
| `src/test/kotlin/com/agentwork/graphmesh/storage/blob/ContentTypeResolverTest.kt` | Unit tests for MIME-type resolution |
| `src/test/kotlin/com/agentwork/graphmesh/storage/blob/S3BlobStoreIntegrationTest.kt` | Integration tests against real MinIO |

---

### Task 1: Docker-Compose + Dependencies

**Files:**
- Modify: `docker-compose.yaml`
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`

- [ ] **Step 1: Add MinIO service to docker-compose.yaml**

Add after the `cassandra` service block:

```yaml
  minio:
    image: minio/minio:latest
    hostname: minio
    ports:
      - "9000:9000"
      - "9001:9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    command: server /data --console-address ":9001"
```

- [ ] **Step 2: Add AWS SDK v2 S3 dependency to build.gradle.kts**

Add to the `dependencies` block:

```kotlin
implementation(platform("software.amazon.awssdk:bom:2.42.28"))
implementation("software.amazon.awssdk:s3")
```

- [ ] **Step 3: Add blob storage config to application.yml**

Append to the end of `application.yml`:

```yaml
  storage:
    blob:
      endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
      region: us-east-1
      access-key: ${MINIO_ACCESS_KEY:minioadmin}
      secret-key: ${MINIO_SECRET_KEY:minioadmin}
      path-style-access: true
      default-bucket: graphmesh
      auto-create-buckets: true
```

This goes under the existing `graphmesh:` block (same level as `cassandra:`).

- [ ] **Step 4: Add blob storage config to application-test.yml**

Append to the end of `application-test.yml`:

```yaml
  storage:
    blob:
      endpoint: http://localhost:9000
      access-key: minioadmin
      secret-key: minioadmin
      default-bucket: graphmesh-test
```

This goes under the existing `graphmesh:` block.

- [ ] **Step 5: Start MinIO and verify it works**

Run: `docker compose up -d minio && sleep 3 && curl -s -o /dev/null -w "%{http_code}" http://localhost:9000/minio/health/live`

Expected: `200`

- [ ] **Step 6: Verify build compiles**

Run: `./gradlew compileKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yaml build.gradle.kts src/main/resources/application.yml src/test/resources/application-test.yml
git commit -m "feat(storage): add MinIO docker service and AWS SDK v2 S3 dependency"
```

---

### Task 2: Data Models + ContentTypeResolver + Unit Tests

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobData.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobInfo.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/blob/ContentTypeResolver.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/storage/blob/ContentTypeResolverTest.kt`

- [ ] **Step 1: Write ContentTypeResolver test**

Create `src/test/kotlin/com/agentwork/graphmesh/storage/blob/ContentTypeResolverTest.kt`:

```kotlin
package com.agentwork.graphmesh.storage.blob

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ContentTypeResolverTest {

    @Test
    fun `resolves PDF content type`() {
        assertEquals("application/pdf", ContentTypeResolver.resolve("document.pdf"))
    }

    @Test
    fun `resolves text content type`() {
        assertEquals("text/plain", ContentTypeResolver.resolve("readme.txt"))
    }

    @Test
    fun `resolves JSON content type`() {
        assertEquals("application/json", ContentTypeResolver.resolve("data.json"))
    }

    @Test
    fun `resolves CSV content type`() {
        assertEquals("text/csv", ContentTypeResolver.resolve("export.csv"))
    }

    @Test
    fun `resolves PNG content type`() {
        assertEquals("image/png", ContentTypeResolver.resolve("image.png"))
    }

    @Test
    fun `resolves JPG content type`() {
        assertEquals("image/jpeg", ContentTypeResolver.resolve("photo.jpg"))
    }

    @Test
    fun `resolves JPEG content type`() {
        assertEquals("image/jpeg", ContentTypeResolver.resolve("photo.jpeg"))
    }

    @Test
    fun `resolves HTML content type`() {
        assertEquals("text/html", ContentTypeResolver.resolve("page.html"))
    }

    @Test
    fun `resolves XML content type`() {
        assertEquals("application/xml", ContentTypeResolver.resolve("data.xml"))
    }

    @Test
    fun `resolves Markdown content type`() {
        assertEquals("text/markdown", ContentTypeResolver.resolve("README.md"))
    }

    @Test
    fun `returns octet-stream for unknown extension`() {
        assertEquals("application/octet-stream", ContentTypeResolver.resolve("file.xyz"))
    }

    @Test
    fun `returns octet-stream for file without extension`() {
        assertEquals("application/octet-stream", ContentTypeResolver.resolve("Makefile"))
    }

    @Test
    fun `handles uppercase extensions`() {
        assertEquals("application/pdf", ContentTypeResolver.resolve("DOCUMENT.PDF"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.blob.ContentTypeResolverTest"`

Expected: FAIL — `ContentTypeResolver` does not exist yet.

- [ ] **Step 3: Create BlobData.kt**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobData.kt`:

```kotlin
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
```

- [ ] **Step 4: Create BlobInfo.kt**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobInfo.kt`:

```kotlin
package com.agentwork.graphmesh.storage.blob

import java.time.Instant

data class BlobInfo(
    val key: String,
    val size: Long,
    val contentType: String?,
    val lastModified: Instant,
    val metadata: Map<String, String> = emptyMap()
)
```

- [ ] **Step 5: Create ContentTypeResolver.kt**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/blob/ContentTypeResolver.kt`:

```kotlin
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
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.blob.ContentTypeResolverTest"`

Expected: BUILD SUCCESSFUL, all 14 tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobData.kt \
        src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobInfo.kt \
        src/main/kotlin/com/agentwork/graphmesh/storage/blob/ContentTypeResolver.kt \
        src/test/kotlin/com/agentwork/graphmesh/storage/blob/ContentTypeResolverTest.kt
git commit -m "feat(storage): add blob data models and ContentTypeResolver with tests"
```

---

### Task 3: BlobStore Interface + Properties + AutoConfiguration

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobStore.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobStoreProperties.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobStoreAutoConfiguration.kt`

- [ ] **Step 1: Create BlobStore.kt**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobStore.kt`:

```kotlin
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
```

- [ ] **Step 2: Create BlobStoreProperties.kt**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobStoreProperties.kt`:

```kotlin
package com.agentwork.graphmesh.storage.blob

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "graphmesh.storage.blob")
data class BlobStoreProperties(
    val endpoint: String = "http://localhost:9000",
    val region: String = "us-east-1",
    val accessKey: String = "minioadmin",
    val secretKey: String = "minioadmin",
    val pathStyleAccess: Boolean = true,
    val defaultBucket: String = "graphmesh",
    val autoCreateBuckets: Boolean = true
)
```

- [ ] **Step 3: Create BlobStoreAutoConfiguration.kt**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobStoreAutoConfiguration.kt`:

```kotlin
package com.agentwork.graphmesh.storage.blob

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

@Configuration
@EnableConfigurationProperties(BlobStoreProperties::class)
class BlobStoreAutoConfiguration {

    @Bean
    fun s3Client(props: BlobStoreProperties): S3Client =
        S3Client.builder()
            .endpointOverride(URI.create(props.endpoint))
            .region(Region.of(props.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey, props.secretKey)
                )
            )
            .forcePathStyle(props.pathStyleAccess)
            .build()

    @Bean
    fun s3Presigner(props: BlobStoreProperties): S3Presigner =
        S3Presigner.builder()
            .endpointOverride(URI.create(props.endpoint))
            .region(Region.of(props.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey, props.secretKey)
                )
            )
            .build()

    @Bean
    fun blobStore(s3Client: S3Client, s3Presigner: S3Presigner): BlobStore =
        S3BlobStore(s3Client, s3Presigner)
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileKotlin`

Expected: FAIL — `S3BlobStore` class does not exist yet. That is correct at this point.

- [ ] **Step 5: Commit (interface + properties only, skip AutoConfiguration for now)**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobStore.kt \
        src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobStoreProperties.kt \
        src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobStoreAutoConfiguration.kt
git commit -m "feat(storage): add BlobStore interface, properties, and auto-configuration"
```

---

### Task 4: S3BlobStore Implementation

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/storage/blob/S3BlobStore.kt`

- [ ] **Step 1: Create S3BlobStore.kt**

Create `src/main/kotlin/com/agentwork/graphmesh/storage/blob/S3BlobStore.kt`:

```kotlin
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
```

- [ ] **Step 2: Verify full project compiles**

Run: `./gradlew compileKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/blob/S3BlobStore.kt
git commit -m "feat(storage): implement S3BlobStore with all blob operations"
```

---

### Task 5: Integration Tests

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/storage/blob/S3BlobStoreIntegrationTest.kt`

**Prerequisite:** MinIO must be running via `docker compose up -d minio`.

- [ ] **Step 1: Write integration tests**

Create `src/test/kotlin/com/agentwork/graphmesh/storage/blob/S3BlobStoreIntegrationTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run integration tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.storage.blob.S3BlobStoreIntegrationTest"`

Expected: BUILD SUCCESSFUL, all 11 tests pass.

- [ ] **Step 3: Run all tests to ensure nothing is broken**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL, all existing tests still pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/storage/blob/S3BlobStoreIntegrationTest.kt
git commit -m "test(storage): add S3 blob store integration tests against MinIO"
```
