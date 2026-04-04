# S3/MinIO Blob Storage - Design Spec

## Ziel

S3-kompatible Blob-Storage-Schicht fuer GraphMesh. Speichert Dokumente (PDFs, Bilder, Textdateien) ausserhalb von Cassandra. Unterstuetzt MinIO lokal und AWS S3/R2/Spaces in Produktion.

## Ansatz

AWS SDK v2 `S3Client` + `S3Presigner` direkt — konsistent mit dem Cassandra-Muster (low-level Client, kein Framework-Wrapper). Synchrone API, kein Coroutines.

## Paketstruktur

```
com.agentwork.graphmesh.storage.blob/
  BlobStore.kt                   -- Interface: put, get, delete, deleteBatch, list, exists, presignedGetUrl, presignedPutUrl, ensureBucket
  BlobData.kt                    -- data class: data (ByteArray), contentType, contentLength, metadata
  BlobInfo.kt                    -- data class: key, size, contentType, lastModified, metadata
  ContentTypeResolver.kt         -- object: Dateiendung -> MIME-Type Mapping
  S3BlobStore.kt                 -- Implementierung mit S3Client + S3Presigner
  BlobStoreProperties.kt         -- @ConfigurationProperties(prefix = "graphmesh.storage.blob")
  BlobStoreAutoConfiguration.kt  -- @Configuration: S3Client, S3Presigner, S3BlobStore Beans
```

## Interface

```kotlin
interface BlobStore {
    fun put(bucket: String, key: String, data: ByteArray, contentType: String = "application/octet-stream", metadata: Map<String, String> = emptyMap())
    fun put(bucket: String, key: String, inputStream: InputStream, contentLength: Long, contentType: String = "application/octet-stream", metadata: Map<String, String> = emptyMap())
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

Alle Methoden synchron (kein `suspend`). Passt zum bestehenden `QuadStoreService`-Muster.

## S3BlobStore Implementierung

- Constructor: `S3Client` + `S3Presigner`
- `put(ByteArray)`: `s3Client.putObject(PutObjectRequest, RequestBody.fromBytes(data))`
- `put(InputStream)`: `s3Client.putObject(PutObjectRequest, RequestBody.fromInputStream(inputStream, contentLength))`
- `get`: `s3Client.getObject(GetObjectRequest)` -> liest ResponseBytes, mappt auf `BlobData`
- `delete`: `s3Client.deleteObject(DeleteObjectRequest)`
- `deleteBatch`: `s3Client.deleteObjects(DeleteObjectsRequest)` mit ObjectIdentifier-Liste
- `list`: `s3Client.listObjectsV2(ListObjectsV2Request)` -> mappt auf `BlobInfo`-Liste
- `exists`: `s3Client.headObject(HeadObjectRequest)` mit try/catch auf `NoSuchKeyException`
- `presignedGetUrl`: `presigner.presignGetObject(GetObjectPresignRequest)`
- `presignedPutUrl`: `presigner.presignPutObject(PutObjectPresignRequest)`
- `ensureBucket`: `headBucket` -> catch `NoSuchBucketException` -> `createBucket`

## Auto-Configuration

```kotlin
@Configuration
@EnableConfigurationProperties(BlobStoreProperties::class)
class BlobStoreAutoConfiguration {

    @Bean
    fun s3Client(props: BlobStoreProperties): S3Client =
        S3Client.builder()
            .endpointOverride(URI.create(props.endpoint))
            .region(Region.of(props.region))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKey, props.secretKey)))
            .forcePathStyle(props.pathStyleAccess)
            .build()

    @Bean
    fun s3Presigner(props: BlobStoreProperties): S3Presigner =
        S3Presigner.builder()
            .endpointOverride(URI.create(props.endpoint))
            .region(Region.of(props.region))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKey, props.secretKey)))
            .build()

    @Bean
    fun blobStore(s3Client: S3Client, presigner: S3Presigner): BlobStore =
        S3BlobStore(s3Client, presigner)
}
```

## Properties

```kotlin
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

## application.yml

```yaml
graphmesh:
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

## Docker-Compose

MinIO Service hinzufuegen zu `docker-compose.yaml`:

```yaml
minio:
  image: minio/minio:latest
  container_name: minio
  ports:
    - "9000:9000"
    - "9001:9001"
  environment:
    MINIO_ROOT_USER: minioadmin
    MINIO_ROOT_PASSWORD: minioadmin
  command: server /data --console-address ":9001"
  volumes:
    - minio-data:/data
```

Volume `minio-data` zum volumes-Block hinzufuegen.

## ContentTypeResolver

Statisches Mapping Dateiendung -> MIME-Type fuer die gaengigsten Typen (pdf, txt, json, csv, png, jpg, jpeg, html, xml, md). Fallback: `application/octet-stream`.

## Dependencies (build.gradle.kts)

```kotlin
implementation(platform("software.amazon.awssdk:bom:2.31.1"))
implementation("software.amazon.awssdk:s3")
```

## Tests

### ContentTypeResolverTest (Unit)
- Testet alle bekannten Dateiendungen
- Testet unbekannte Endung -> Fallback
- Testet Dateiname ohne Endung

### S3BlobStoreIntegrationTest
- `@SpringBootTest` mit `@ActiveProfiles("test")`
- Nutzt echtes MinIO aus docker-compose (wie Cassandra-Tests)
- Excludes fuer Qdrant/Kafka Autoconfiguration (bestehendes Muster)
- Tests:
  - `put` + `get` roundtrip mit Content-Type und Metadata
  - `put` mit InputStream
  - `delete` einzeln
  - `deleteBatch` mehrere Keys
  - `list` mit und ohne Prefix-Filter
  - `exists` true/false
  - `presignedGetUrl` generiert gueltige URL
  - `presignedPutUrl` generiert gueltige URL
  - `ensureBucket` erstellt Bucket idempotent

### application-test.yml
```yaml
graphmesh:
  storage:
    blob:
      endpoint: http://localhost:9000
      access-key: minioadmin
      secret-key: minioadmin
      default-bucket: graphmesh-test
```

## Akzeptanzkriterien

- put/get roundtrip mit korrektem Content-Type und Metadaten
- delete und deleteBatch entfernen Blobs
- list mit Prefix-Filter funktioniert
- exists prueft ohne Download
- Presigned URLs werden generiert und sind zeitlich begrenzt
- ensureBucket ist idempotent
- ContentTypeResolver erkennt gaengige Dateitypen
- Auto-Configuration erstellt alle Beans ohne manuelle Definition
- Docker-Compose startet MinIO korrekt
- Integrationstests laufen gegen echtes MinIO
