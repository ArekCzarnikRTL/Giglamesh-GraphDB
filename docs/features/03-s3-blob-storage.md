# Feature 03: S3/MinIO Blob Storage

## Problem

GraphMesh muss Dokumente (PDFs, Textdateien, Bilder) und andere binaere Inhalte persistent speichern. Ein direktes
Speichern grosser Blobs in Cassandra ist ineffizient und belastet den Graph-Speicher unnoetig. Es fehlt eine
einheitliche Abstraktionsschicht, die sowohl lokale MinIO-Instanzen als auch Cloud-basierte S3-Dienste (AWS S3, R2,
Spaces) transparent unterstuetzt.

## Ziel

Bereitstellung einer S3-kompatiblen Blob-Storage-Abstraktionsschicht mit Bucket-Management, Content-Type-Handling und
Presigned-URL-Unterstuetzung.

1. **BlobStore Interface** -- Provider-agnostische API fuer Put/Get/Delete/List-Operationen
2. **Bucket Management** -- Automatische Bucket-Erstellung und Konfiguration
3. **Content-Type Handling** -- Korrekte MIME-Type-Erkennung und -Speicherung
4. **Presigned URLs** -- Temporaere Upload/Download-URLs fuer direkten Client-Zugriff
5. **Spring Boot Auto-Configuration** -- Integration mit AWS SDK v2 und automatische Konfiguration

## Voraussetzungen

| Abhaengigkeit                             | Status     | Blocker? |
|-------------------------------------------|------------|----------|
| S3-kompatibler Speicher (MinIO/AWS S3/R2) | Geplant    | Nein     |
| AWS SDK v2 for Java                       | Verfuegbar | Nein     |
| Spring Boot 3.x                           | Verfuegbar | Nein     |

## Architektur

### BlobStore Interface

```kotlin
package com.graphmesh.storage.blob

import java.io.InputStream
import java.net.URL
import java.time.Duration

/**
 * Provider-agnostische Schnittstelle fuer Blob-Speicherung.
 * Unterstuetzt S3, MinIO, R2 und andere S3-kompatible Backends.
 */
interface BlobStore {

    /**
     * Speichert einen Blob unter dem angegebenen Pfad.
     */
    suspend fun put(
        bucket: String,
        key: String,
        data: ByteArray,
        contentType: String = "application/octet-stream",
        metadata: Map<String, String> = emptyMap()
    )

    /**
     * Speichert einen Blob aus einem InputStream (fuer grosse Dateien).
     */
    suspend fun put(
        bucket: String,
        key: String,
        inputStream: InputStream,
        contentLength: Long,
        contentType: String = "application/octet-stream",
        metadata: Map<String, String> = emptyMap()
    )

    /**
     * Laedt einen Blob als ByteArray.
     */
    suspend fun get(bucket: String, key: String): BlobData

    /**
     * Loescht einen Blob.
     */
    suspend fun delete(bucket: String, key: String)

    /**
     * Loescht mehrere Blobs.
     */
    suspend fun deleteBatch(bucket: String, keys: List<String>)

    /**
     * Listet alle Blobs in einem Bucket mit optionalem Prefix.
     */
    suspend fun list(bucket: String, prefix: String = "", maxKeys: Int = 1000): List<BlobInfo>

    /**
     * Prueft ob ein Blob existiert.
     */
    suspend fun exists(bucket: String, key: String): Boolean

    /**
     * Generiert eine Presigned URL fuer direkten Download.
     */
    suspend fun presignedGetUrl(bucket: String, key: String, expiration: Duration = Duration.ofHours(1)): URL

    /**
     * Generiert eine Presigned URL fuer direkten Upload.
     */
    suspend fun presignedPutUrl(bucket: String, key: String, contentType: String, expiration: Duration = Duration.ofHours(1)): URL

    /**
     * Stellt sicher, dass ein Bucket existiert.
     */
    suspend fun ensureBucket(bucket: String)
}
```

### Datenmodell

```kotlin
package com.graphmesh.storage.blob

/**
 * Wrapper fuer geladene Blob-Daten.
 */
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

/**
 * Metadaten eines gespeicherten Blobs.
 */
data class BlobInfo(
    val key: String,
    val size: Long,
    val contentType: String?,
    val lastModified: java.time.Instant,
    val metadata: Map<String, String> = emptyMap()
)
```

### S3 Implementierung

```kotlin
package com.graphmesh.storage.blob.impl

import com.graphmesh.storage.blob.*
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.core.sync.RequestBody
import org.slf4j.LoggerFactory

class S3BlobStore(
    private val s3Client: S3Client,
    private val presigner: S3Presigner
) : BlobStore {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun put(
        bucket: String, key: String, data: ByteArray,
        contentType: String, metadata: Map<String, String>
    ) {
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .metadata(metadata)
                .build(),
            RequestBody.fromBytes(data)
        )
        log.debug("Blob gespeichert: {}/{} ({} bytes)", bucket, key, data.size)
    }

    override suspend fun ensureBucket(bucket: String) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
            log.debug("Bucket existiert bereits: {}", bucket)
        } catch (e: NoSuchBucketException) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
            log.info("Bucket erstellt: {}", bucket)
        }
    }

    // ... weitere Implementierungen
}
```

### Spring Boot Auto-Configuration

```kotlin
package com.graphmesh.storage.blob.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "graphmesh.storage.blob")
data class GraphMeshBlobProperties(
    val endpoint: String = "http://localhost:9000",
    val region: String = "us-east-1",
    val accessKey: String = "minioadmin",
    val secretKey: String = "minioadmin",
    val useSsl: Boolean = false,
    val pathStyleAccess: Boolean = true,
    val defaultBucket: String = "graphmesh",
    val autoCreateBuckets: Boolean = true
)
```

### Content-Type-Erkennung

```kotlin
package com.graphmesh.storage.blob

/**
 * Erkennt den MIME-Type anhand der Dateiendung.
 */
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

### application.yml Beispiel

```yaml
graphmesh:
  storage:
    blob:
      endpoint: http://minio:9000
      region: us-east-1
      access-key: minioadmin
      secret-key: minioadmin
      use-ssl: false
      path-style-access: true
      default-bucket: graphmesh
      auto-create-buckets: true
```

## Betroffene Dateien

### Backend

| Datei                                                                                                              | Aenderung                             |
|--------------------------------------------------------------------------------------------------------------------|---------------------------------------|
| `storage-blob/src/main/kotlin/com/graphmesh/storage/blob/BlobStore.kt`                                             | NEU - BlobStore-Interface             |
| `storage-blob/src/main/kotlin/com/graphmesh/storage/blob/BlobData.kt`                                              | NEU - Blob-Datenmodell                |
| `storage-blob/src/main/kotlin/com/graphmesh/storage/blob/BlobInfo.kt`                                              | NEU - Blob-Metadaten                  |
| `storage-blob/src/main/kotlin/com/graphmesh/storage/blob/ContentTypeResolver.kt`                                   | NEU - MIME-Type-Erkennung             |
| `storage-blob/src/main/kotlin/com/graphmesh/storage/blob/impl/S3BlobStore.kt`                                      | NEU - S3-Implementierung              |
| `storage-blob/src/main/kotlin/com/graphmesh/storage/blob/autoconfigure/GraphMeshBlobAutoConfiguration.kt`          | NEU - Auto-Configuration              |
| `storage-blob/src/main/kotlin/com/graphmesh/storage/blob/autoconfigure/GraphMeshBlobProperties.kt`                 | NEU - Properties                      |
| `storage-blob/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | NEU - Auto-Configuration-Registration |
| `storage-blob/build.gradle.kts`                                                                                    | NEU - Gradle-Modul                    |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                                      | Aenderung                                          |
|--------------------------------------------------------------------------------------------|----------------------------------------------------|
| `storage-blob/src/test/kotlin/com/graphmesh/storage/blob/S3BlobStoreTest.kt`               | NEU - BlobStore-Unit-Tests                         |
| `storage-blob/src/test/kotlin/com/graphmesh/storage/blob/ContentTypeResolverTest.kt`       | NEU - ContentType-Tests                            |
| `storage-blob/src/test/kotlin/com/graphmesh/storage/blob/integration/S3IntegrationTest.kt` | NEU - Integrationstests mit Testcontainers (MinIO) |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                              |
|-------------------|-------------|----------------------------------------------------|
| Spring Boot (JVM) | Ja          | AWS SDK v2 bietet volle S3-Unterstuetzung          |
| KMP Library       | Nein        | AWS SDK v2 ist JVM-only (Kotlin-SDK experimentell) |
| Ktor/Wasm         | Nein        | Kein S3-Client fuer Wasm/JS verfuegbar             |

## Akzeptanzkriterien

- [ ] `BlobStore.put()` speichert Blobs korrekt mit Content-Type und Metadaten
- [ ] `BlobStore.get()` laedt Blobs mit korrektem Content-Type zurueck
- [ ] `BlobStore.delete()` und `deleteBatch()` entfernen Blobs zuverlaessig
- [ ] `BlobStore.list()` listet Blobs mit Prefix-Filter korrekt auf
- [ ] `BlobStore.exists()` prueft Blob-Existenz ohne den gesamten Inhalt zu laden
- [ ] Presigned URLs fuer Upload und Download werden korrekt generiert und sind zeitlich begrenzt
- [ ] Buckets werden automatisch erstellt, wenn `auto-create-buckets=true`
- [ ] ContentTypeResolver erkennt gaengige Dateitypen korrekt
- [ ] Konfiguration funktioniert sowohl mit MinIO als auch mit AWS S3
- [ ] Spring Boot Auto-Configuration funktioniert ohne manuelle Bean-Definition
- [ ] Integrationstests mit Testcontainers (MinIO) laufen erfolgreich
- [ ] Bestehende Funktionalitaet bleibt unberuehrt
