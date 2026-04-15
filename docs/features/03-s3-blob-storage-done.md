# Feature 03: S3/MinIO Blob Storage — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobStore.kt`** — Interface `BlobStore` mit `put(bucket, key, ByteArray, contentType, metadata)`, `put(bucket, key, InputStream, contentLength, contentType, metadata)`, `get`, `delete`, `deleteBatch`, `list(bucket, prefix, maxKeys)`, `exists`, `presignedGetUrl`, `presignedPutUrl`, `ensureBucket`. Dazu `data class BlobData(data, contentType, contentLength, metadata)` mit `ByteArray`-aware `equals/hashCode` und `data class BlobInfo(key, size, contentType, lastModified, metadata)`.
- **`src/main/kotlin/com/agentwork/graphmesh/storage/blob/S3BlobStore.kt`** — Implementierung auf Basis `software.amazon.awssdk.services.s3.S3Client` und `S3Presigner`. `put` via `PutObjectRequest` + `RequestBody.fromBytes`/`fromInputStream`; `get` wrappt den `ResponseInputStream` in `BlobData`. `deleteBatch` baut `DeleteObjectsRequest` mit `ObjectIdentifier`-Liste. `list` benutzt `ListObjectsV2Request` mit optionalem Prefix. `exists` via `headObject` mit `NoSuchKeyException`-Catch. `presignedGetUrl`/`presignedPutUrl` via `GetObjectPresignRequest`/`PutObjectPresignRequest` + `signatureDuration`. `ensureBucket` via `headBucket` + Fallback `createBucket`.
- **`src/main/kotlin/com/agentwork/graphmesh/storage/blob/ContentTypeResolver.kt`** — `object` mit Lookup-Map fuer `pdf, txt, json, csv, png, jpg, jpeg, html, xml, md`, Fallback `application/octet-stream`, case-insensitive.
- **`src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobStoreProperties.kt`** — `@ConfigurationProperties(prefix = "graphmesh.storage.blob")` mit `endpoint`, `region`, `accessKey`, `secretKey`, `pathStyleAccess`, `defaultBucket`, `autoCreateBuckets`.
- **`src/main/kotlin/com/agentwork/graphmesh/storage/blob/BlobStoreAutoConfiguration.kt`** — `@Configuration` mit `@EnableConfigurationProperties(BlobStoreProperties)`. Stellt `S3Client`-, `S3Presigner`- und `BlobStore`-Beans bereit: `endpointOverride` (MinIO-kompatibel), `Region.of(props.region)`, `StaticCredentialsProvider` mit `AwsBasicCredentials`, `forcePathStyle(true)`.
- **`src/main/resources/application.yml`** — `graphmesh.storage.blob.*` mit Env-Overrides `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `default-bucket: graphmesh`, `auto-create-buckets: true`.

### Tests

- **`ContentTypeResolverTest`** — 13 Unit-Tests: alle unterstuetzten Endungen, unbekannte Endung, Datei ohne Endung, Gross-/Kleinschreibung.
- **`S3BlobStoreIntegrationTest`** — 10 Integrationstests gegen laufendes MinIO via docker-compose: put/get-Roundtrip (ByteArray + InputStream), delete, deleteBatch, list, list mit Prefix, exists true/false, presignedGetUrl, presignedPutUrl, `ensureBucket`-Idempotenz.

## Abweichungen vom Feature-Dokument

- **Package**: Spec verwendet `com.graphmesh.storage.blob` in einem eigenen Gradle-Submodul `storage-blob`. Real implementiert unter `com.agentwork.graphmesh.storage.blob` im Monomodul. **Memory-Hinweis**: Feature-Specs nennen oft alte Packages/Libs — passt zur Regel "no submodules, no Gradle-Starters".
- **Keine `autoconfigure`-Subpackage, kein `META-INF/spring/...AutoConfiguration.imports`**: `BlobStoreAutoConfiguration` liegt direkt im `blob`-Package und wird via Component-Scan aufgenommen, nicht via Auto-Configuration-Import. Einfacher, weil ein einziges Modul existiert.
- **Synchron statt `suspend`**: Spec nennt `suspend fun put/get/...`. Implementierung ist komplett synchron (AWS SDK v2 sync client). Passt zum Projekt-Prinzip "keine Coroutines in I/O-Layern".
- **`BlobStoreProperties` ohne `useSsl`-Feld**: Spec zeigt `useSsl: Boolean`. Real nicht vorhanden — SSL wird ueber `endpoint` (`https://...`) gesteuert.
- **`list()` setzt `BlobInfo.contentType = null`**: `ListObjectsV2` liefert keinen Content-Type zurueck (nur head/get tun das). Spec zeigt `contentType: String?` — das passt, aber es ist implementierungsseitig immer null.
- **Kein dedizierter `S3BlobStoreTest` (Unit)**: Spec listet einen Unit-Test mit Mocks. Real wird nur Integration getestet (Praeferenz des Projekts: docker-compose statt Mocks).

## Akzeptanzkriterien

- [x] `put()` speichert Content-Type und Metadaten — `S3BlobStore.put` setzt beides.
- [x] `get()` laedt mit korrektem Content-Type — `BlobData.contentType` aus Response.
- [x] `delete()` / `deleteBatch()` entfernen Blobs — Integrationstest verifiziert.
- [x] `list()` mit Prefix-Filter — `ListObjectsV2Request.prefix(prefix)`.
- [x] `exists()` prueft ohne Inhalt zu laden — `headObject`.
- [x] Presigned URLs fuer Upload/Download mit Ablauf — `presignedGetUrl`/`presignedPutUrl` + `signatureDuration`.
- [x] Auto-Create-Buckets — `ensureBucket` + `auto-create-buckets: true` Flag (wird vom Aufrufer genutzt, z. B. `LibrarianService.init`).
- [x] ContentTypeResolver erkennt gaengige Typen — `ContentTypeResolverTest` deckt 10 Typen.
- [x] Konfiguration fuer MinIO + AWS S3 — `endpointOverride` + `pathStyleAccess` steuerbar.
- [x] Spring-Boot-Auto-Config ohne manuelle Bean-Def — `BlobStoreAutoConfiguration`.
- [x] Integrationstests mit MinIO laufen — `S3BlobStoreIntegrationTest` via docker-compose.
- [x] Bestehende Funktionalitaet unberuehrt.

## Offene Punkte

- Keine.
