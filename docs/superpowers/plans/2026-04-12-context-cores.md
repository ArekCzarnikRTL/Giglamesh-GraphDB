# Feature 37: Context Cores Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement versionierte, portable Wissens-Bundles (.zip) die den vollstaendigen Stand einer Collection exportieren und in andere Collections importieren koennen.

**Architecture:** ContextCoreService orchestriert Build/Import ueber QuadStore, VectorStore, OntologyService und BlobStore. BundleWriter/Reader serialisieren als .zip mit JDK ZipOutputStream. Registry in Cassandra indexiert alle Cores. GraphQL API exponiert alles via Spring GraphQL. Frontend zeigt Cores-Liste und Build/Import-Dialoge.

**Tech Stack:** Kotlin, Spring Boot 4, Spring GraphQL, Apache Jena 6 (N-Quads), Cassandra, Qdrant, MinIO (S3), Next.js + Apollo Client

---

## Task 1: Extend QuadStore Interface + InMemoryQuadStore

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt`
- Modify: `src/test/kotlin/com/agentwork/graphmesh/storage/InMemoryQuadStore.kt`

- [ ] **Step 1: Add scrollAll and isEmpty to QuadStore interface**

```kotlin
// Add to QuadStore interface after aggregateMetadata():

    /** Returns all quads in [collection]. Use for export only. */
    fun scrollAll(collection: String): List<StoredQuad>

    /** Returns true if [collection] has no quads. */
    fun isEmpty(collection: String): Boolean
```

- [ ] **Step 2: Implement in InMemoryQuadStore**

```kotlin
// Add to InMemoryQuadStore:

    override fun scrollAll(collection: String): List<StoredQuad> {
        return byCollection[collection]?.toList() ?: emptyList()
    }

    override fun isEmpty(collection: String): Boolean {
        return byCollection[collection]?.isEmpty() ?: true
    }
```

- [ ] **Step 3: Implement in QuadStoreService (the production Cassandra-backed impl)**

Find the production QuadStore implementation (likely `QuadStoreService.kt`) and add:

```kotlin
    override fun scrollAll(collection: String): List<StoredQuad> {
        return query(collection, QuadQuery())
    }

    override fun isEmpty(collection: String): Boolean {
        return query(collection, QuadQuery(), limit = 1).isEmpty()
    }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "*QuadStore*"`
Expected: All existing tests still pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(storage): add scrollAll and isEmpty to QuadStore interface"
```

---

## Task 2: Extend VectorStore Interface

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStore.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/storage/vector/QdrantVectorStore.kt`

- [ ] **Step 1: Add scroll method to VectorStore interface**

```kotlin
// Add to VectorStore interface:

    /** Scrolls all points from [collection]. Use for export only. */
    fun scroll(collection: String): List<VectorPoint>
```

- [ ] **Step 2: Implement in QdrantVectorStore**

```kotlin
    override fun scroll(collection: String): List<VectorPoint> {
        val result = mutableListOf<VectorPoint>()
        var offset: io.qdrant.client.grpc.Points.PointId? = null

        while (true) {
            val scrollRequest = io.qdrant.client.grpc.Points.ScrollPoints.newBuilder()
                .setCollectionName(collection)
                .setLimit(1000)
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .setWithVectors(io.qdrant.client.grpc.Points.WithVectorsSelector.newBuilder().setEnable(true).build())

            if (offset != null) {
                scrollRequest.setOffset(offset)
            }

            val response = client.scrollAsync(scrollRequest.build()).get()
            val points = response.resultList

            if (points.isEmpty()) break

            points.forEach { point ->
                val id = point.id.uuid.ifEmpty { point.id.num.toString() }
                val vector = point.vectors.vector.dataList.map { it }.toFloatArray()
                val payload = point.payloadMap.mapValues { (_, v) ->
                    when {
                        v.hasStringValue() -> v.stringValue
                        v.hasIntegerValue() -> v.integerValue
                        v.hasDoubleValue() -> v.doubleValue
                        v.hasBoolValue() -> v.boolValue
                        else -> v.stringValue
                    } as Any
                }
                result.add(VectorPoint(id = id, vector = vector, payload = payload))
            }

            offset = response.nextPageOffset
            if (!response.hasNextPageOffset()) break
        }

        log.debug("Scrolled {} points from {}", result.size, collection)
        return result
    }
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests "*VectorStore*"`
Expected: All existing tests pass.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(storage): add scroll method to VectorStore interface"
```

---

## Task 3: CoreManifest Data Classes

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/contextcore/CoreManifest.kt`

- [ ] **Step 1: Create the data classes file**

```kotlin
package com.agentwork.graphmesh.contextcore

import java.time.Instant

data class CoreManifest(
    val coreId: String,
    val version: String,
    val parentVersion: String? = null,
    val sourceCollection: String,
    val createdAt: Instant,
    val createdBy: String,
    val description: String? = null,
    val tags: Set<String> = emptySet(),
    val stats: CoreStats,
    val embeddingModel: String,
    val embeddingDimension: Int,
    val checksum: String
)

data class CoreStats(
    val quadCount: Long,
    val entityCount: Long,
    val chunkEmbeddingCount: Long,
    val ontologyAxiomCount: Long
)

data class BuildRequest(
    val coreId: String,
    val version: String,
    val sourceCollection: String,
    val embeddingModel: String,
    val embeddingDimension: Int,
    val createdBy: String = "system",
    val description: String? = null,
    val parentVersion: String? = null,
    val tags: Set<String> = emptySet(),
    val ontologyKey: String? = null,
    val retrievalPolicies: RetrievalPolicies = RetrievalPolicies()
)

data class ImportRequest(
    val coreId: String,
    val version: String,
    val targetCollection: String,
    val strategy: ConflictStrategy = ConflictStrategy.FAIL,
    val namespaceRewrite: NamespaceRewrite? = null
)

data class ImportResult(
    val coreId: String,
    val version: String,
    val quadsImported: Int,
    val embeddingsImported: Int
)

enum class ConflictStrategy { FAIL, MERGE, REPLACE }

data class NamespaceRewrite(val from: String, val to: String)

data class RetrievalPolicies(
    val graphRag: GraphRagDefaults = GraphRagDefaults(),
    val docRag: DocRagDefaults = DocRagDefaults()
)

data class GraphRagDefaults(val maxHops: Int = 2, val topK: Int = 20)
data class DocRagDefaults(val topK: Int = 8, val similarityThreshold: Float = 0.65f)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat(contextcore): add CoreManifest and related data classes"
```

---

## Task 4: NQuadsSerializer

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/contextcore/NQuadsSerializer.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/contextcore/NQuadsSerializerTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.StoredQuad
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NQuadsSerializerTest {

    private val serializer = NQuadsSerializer()

    @Test
    fun `serialize and deserialize URI quad roundtrip`() {
        val quad = StoredQuad(
            subject = "http://example.org/Alice",
            predicate = "http://example.org/knows",
            objectValue = "http://example.org/Bob",
            dataset = "default",
            objectType = ObjectType.URI
        )
        val nquads = serializer.serialize(listOf(quad))
        val result = serializer.deserialize(nquads)

        assertEquals(1, result.size)
        assertEquals(quad.subject, result[0].subject)
        assertEquals(quad.predicate, result[0].predicate)
        assertEquals(quad.objectValue, result[0].objectValue)
        assertEquals(ObjectType.URI, result[0].objectType)
    }

    @Test
    fun `serialize and deserialize literal quad roundtrip`() {
        val quad = StoredQuad(
            subject = "http://example.org/Alice",
            predicate = "http://example.org/age",
            objectValue = "30",
            dataset = "default",
            objectType = ObjectType.LITERAL,
            datatype = "http://www.w3.org/2001/XMLSchema#integer"
        )
        val nquads = serializer.serialize(listOf(quad))
        val result = serializer.deserialize(nquads)

        assertEquals(1, result.size)
        assertEquals("30", result[0].objectValue)
        assertEquals(ObjectType.LITERAL, result[0].objectType)
        assertEquals("http://www.w3.org/2001/XMLSchema#integer", result[0].datatype)
    }

    @Test
    fun `serialize and deserialize language-tagged literal`() {
        val quad = StoredQuad(
            subject = "http://example.org/Alice",
            predicate = "http://example.org/name",
            objectValue = "Alice",
            dataset = "default",
            objectType = ObjectType.LITERAL,
            language = "en"
        )
        val nquads = serializer.serialize(listOf(quad))
        val result = serializer.deserialize(nquads)

        assertEquals(1, result.size)
        assertEquals("Alice", result[0].objectValue)
        assertEquals("en", result[0].language)
    }

    @Test
    fun `serialize empty list returns empty string`() {
        assertEquals("", serializer.serialize(emptyList()))
    }

    @Test
    fun `deserialize empty string returns empty list`() {
        assertEquals(emptyList<StoredQuad>(), serializer.deserialize(""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*NQuadsSerializerTest*"`
Expected: FAIL — class not found.

- [ ] **Step 3: Implement NQuadsSerializer**

```kotlin
package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.StoredQuad
import org.apache.jena.graph.NodeFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.sparql.core.DatasetGraphFactory
import org.apache.jena.sparql.core.Quad
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class NQuadsSerializer {

    fun serialize(quads: List<StoredQuad>): String {
        if (quads.isEmpty()) return ""

        val dsg = DatasetGraphFactory.create()
        quads.forEach { sq ->
            val s = NodeFactory.createURI(sq.subject)
            val p = NodeFactory.createURI(sq.predicate)
            val o = when (sq.objectType) {
                ObjectType.URI -> NodeFactory.createURI(sq.objectValue)
                ObjectType.LITERAL -> {
                    when {
                        sq.language.isNotEmpty() -> NodeFactory.createLiteralLang(sq.objectValue, sq.language)
                        sq.datatype.isNotEmpty() -> NodeFactory.createLiteral(
                            sq.objectValue,
                            org.apache.jena.datatypes.TypeMapper.getInstance().getTypeByName(sq.datatype)
                        )
                        else -> NodeFactory.createLiteralString(sq.objectValue)
                    }
                }
                ObjectType.QUOTED_TRIPLE -> NodeFactory.createLiteralString(sq.objectValue)
            }
            val g = if (sq.dataset.isNotEmpty()) NodeFactory.createURI("urn:dataset:${sq.dataset}") else Quad.defaultGraphIRI
            dsg.add(Quad(g, s, p, o))
        }

        val out = ByteArrayOutputStream()
        RDFDataMgr.write(out, dsg, Lang.NQUADS)
        return out.toString(Charsets.UTF_8)
    }

    fun deserialize(nquads: String): List<StoredQuad> {
        if (nquads.isBlank()) return emptyList()

        val dsg = DatasetGraphFactory.create()
        RDFDataMgr.read(dsg, ByteArrayInputStream(nquads.toByteArray(Charsets.UTF_8)), Lang.NQUADS)

        val result = mutableListOf<StoredQuad>()
        dsg.find().forEach { quad ->
            val dataset = if (quad.graph == Quad.defaultGraphIRI || quad.graph == null) ""
            else quad.graph.uri?.removePrefix("urn:dataset:") ?: ""

            val obj = quad.`object`
            val (objectValue, objectType, datatype, language) = when {
                obj.isURI -> Quadruple(obj.uri, ObjectType.URI, "", "")
                obj.isLiteral -> {
                    val lang = obj.literalLanguage ?: ""
                    val dt = if (lang.isEmpty()) (obj.literalDatatypeURI ?: "") else ""
                    Quadruple(obj.literalLexicalForm, ObjectType.LITERAL, dt, lang)
                }
                else -> Quadruple(obj.toString(), ObjectType.LITERAL, "", "")
            }

            result.add(
                StoredQuad(
                    subject = quad.subject.uri,
                    predicate = quad.predicate.uri,
                    objectValue = objectValue,
                    dataset = dataset,
                    objectType = objectType,
                    datatype = datatype,
                    language = language
                )
            )
        }
        return result
    }

    private data class Quadruple(val value: String, val type: ObjectType, val datatype: String, val language: String)
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "*NQuadsSerializerTest*"`
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(contextcore): add NQuadsSerializer with Jena N-Quads support"
```

---

## Task 5: NamespaceRewriter

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/contextcore/NamespaceRewriter.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/contextcore/NamespaceRewriterTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.StoredQuad
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NamespaceRewriterTest {

    @Test
    fun `rewrites subject URI prefix`() {
        val quad = StoredQuad("http://old.org/Alice", "http://old.org/knows", "http://old.org/Bob", "d", ObjectType.URI)
        val rewrite = NamespaceRewrite("http://old.org/", "http://new.org/")
        val result = NamespaceRewriter.apply(quad, rewrite)
        assertEquals("http://new.org/Alice", result.subject)
        assertEquals("http://new.org/knows", result.predicate)
        assertEquals("http://new.org/Bob", result.objectValue)
    }

    @Test
    fun `does not rewrite non-matching URIs`() {
        val quad = StoredQuad("http://other.org/X", "http://other.org/p", "http://other.org/Y", "d", ObjectType.URI)
        val rewrite = NamespaceRewrite("http://old.org/", "http://new.org/")
        val result = NamespaceRewriter.apply(quad, rewrite)
        assertEquals(quad, result)
    }

    @Test
    fun `does not rewrite literal objects`() {
        val quad = StoredQuad("http://old.org/A", "http://old.org/name", "http://old.org/not-a-uri", "d", ObjectType.LITERAL)
        val rewrite = NamespaceRewrite("http://old.org/", "http://new.org/")
        val result = NamespaceRewriter.apply(quad, rewrite)
        assertEquals("http://new.org/A", result.subject)
        assertEquals("http://new.org/name", result.predicate)
        assertEquals("http://old.org/not-a-uri", result.objectValue) // literal unchanged
    }

    @Test
    fun `null rewrite returns original quad`() {
        val quad = StoredQuad("http://old.org/A", "http://old.org/p", "http://old.org/B", "d", ObjectType.URI)
        val result = NamespaceRewriter.applyOrNull(quad, null)
        assertSame(quad, result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*NamespaceRewriterTest*"`
Expected: FAIL

- [ ] **Step 3: Implement NamespaceRewriter**

```kotlin
package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.StoredQuad

object NamespaceRewriter {

    fun apply(quad: StoredQuad, rewrite: NamespaceRewrite): StoredQuad {
        return quad.copy(
            subject = rewriteUri(quad.subject, rewrite),
            predicate = rewriteUri(quad.predicate, rewrite),
            objectValue = if (quad.objectType == ObjectType.URI) rewriteUri(quad.objectValue, rewrite) else quad.objectValue
        )
    }

    fun applyOrNull(quad: StoredQuad, rewrite: NamespaceRewrite?): StoredQuad {
        return if (rewrite == null) quad else apply(quad, rewrite)
    }

    private fun rewriteUri(uri: String, rewrite: NamespaceRewrite): String {
        return if (uri.startsWith(rewrite.from)) {
            rewrite.to + uri.removePrefix(rewrite.from)
        } else {
            uri
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "*NamespaceRewriterTest*"`
Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(contextcore): add NamespaceRewriter for URI prefix rewriting"
```

---

## Task 6: BundleWriter and BundleReader

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/contextcore/BundleWriter.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/contextcore/BundleReader.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/contextcore/BundleWriterReaderTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.storage.vector.VectorPoint
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class BundleWriterReaderTest {

    private val mapper = jacksonObjectMapper().apply {
        findAndRegisterModules()
    }

    @Test
    fun `write and read bundle roundtrip`() {
        val manifest = CoreManifest(
            coreId = "test-core",
            version = "1.0.0",
            sourceCollection = "my-collection",
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            createdBy = "tester",
            stats = CoreStats(quadCount = 2, entityCount = 2, chunkEmbeddingCount = 1, ontologyAxiomCount = 0),
            embeddingModel = "text-embedding-3-small",
            embeddingDimension = 1536,
            checksum = "" // will be computed
        )
        val nquads = "<http://ex.org/A> <http://ex.org/p> <http://ex.org/B> .\n"
        val ontologyTtl = "@prefix ex: <http://ex.org/> .\nex:Person a owl:Class .\n"
        val embeddings = listOf(
            VectorPoint("chunk-1", floatArrayOf(0.1f, 0.2f, 0.3f), mapOf("text" to "hello"))
        )
        val policies = RetrievalPolicies()

        val zipBytes = BundleWriter.write(manifest, nquads, ontologyTtl, embeddings, policies, mapper)
        assertTrue(zipBytes.size > 100)

        val reader = BundleReader(zipBytes, mapper)
        val readManifest = reader.readManifest()
        assertEquals("test-core", readManifest.coreId)
        assertEquals("1.0.0", readManifest.version)

        val readNquads = reader.readNQuads()
        assertEquals(nquads, readNquads)

        val readOntology = reader.readOntology()
        assertEquals(ontologyTtl, readOntology)

        val readEmbeddings = reader.readEmbeddings()
        assertEquals(1, readEmbeddings.size)
        assertEquals("chunk-1", readEmbeddings[0].id)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), readEmbeddings[0].vector)

        assertTrue(reader.verifyChecksums())
    }

    @Test
    fun `checksum mismatch detected`() {
        val manifest = CoreManifest(
            coreId = "bad", version = "0.1.0", sourceCollection = "x",
            createdAt = Instant.now(), createdBy = "test",
            stats = CoreStats(0, 0, 0, 0),
            embeddingModel = "m", embeddingDimension = 3, checksum = ""
        )
        val zipBytes = BundleWriter.write(manifest, "", "", emptyList(), RetrievalPolicies(), mapper)

        // Tamper with zip (flip a byte)
        val tampered = zipBytes.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2] + 1).toByte()

        // Should either fail to read or fail checksum verification
        try {
            val reader = BundleReader(tampered, mapper)
            assertFalse(reader.verifyChecksums())
        } catch (_: Exception) {
            // Also acceptable: zip is unreadable after tampering
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*BundleWriterReaderTest*"`
Expected: FAIL

- [ ] **Step 3: Implement BundleWriter**

```kotlin
package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.storage.vector.VectorPoint
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BundleWriter {

    fun write(
        manifest: CoreManifest,
        nquads: String,
        ontologyTtl: String,
        embeddings: List<VectorPoint>,
        policies: RetrievalPolicies,
        mapper: ObjectMapper
    ): ByteArray {
        val files = mutableMapOf<String, ByteArray>()

        files["graph/default.nq"] = nquads.toByteArray(Charsets.UTF_8)
        files["ontology/ontology.ttl"] = ontologyTtl.toByteArray(Charsets.UTF_8)
        files["embeddings/chunks.jsonl"] = serializeEmbeddings(embeddings, mapper)
        files["policies/retrieval.json"] = mapper.writeValueAsBytes(policies)

        // Compute checksums
        val checksums = files.map { (name, data) ->
            "${sha256(data)}  $name"
        }.joinToString("\n")
        files["checksums.sha256"] = checksums.toByteArray(Charsets.UTF_8)

        // Compute overall checksum and update manifest
        val overallChecksum = sha256(checksums.toByteArray(Charsets.UTF_8))
        val finalManifest = manifest.copy(checksum = overallChecksum)
        files["manifest.json"] = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(finalManifest)

        // Write zip
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            files.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun serializeEmbeddings(embeddings: List<VectorPoint>, mapper: ObjectMapper): ByteArray {
        if (embeddings.isEmpty()) return ByteArray(0)
        return embeddings.joinToString("\n") { point ->
            mapper.writeValueAsString(
                mapOf(
                    "id" to point.id,
                    "vector" to point.vector.toList(),
                    "payload" to point.payload
                )
            )
        }.toByteArray(Charsets.UTF_8)
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Implement BundleReader**

```kotlin
package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.storage.vector.VectorPoint
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class BundleReader(
    zipBytes: ByteArray,
    private val mapper: ObjectMapper
) {
    private val entries: Map<String, ByteArray>

    init {
        val map = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                map[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        entries = map
    }

    fun readManifest(): CoreManifest {
        val bytes = entries["manifest.json"] ?: error("manifest.json not found in bundle")
        return mapper.readValue(bytes)
    }

    fun readNQuads(): String {
        return entries["graph/default.nq"]?.toString(Charsets.UTF_8) ?: ""
    }

    fun readOntology(): String {
        return entries["ontology/ontology.ttl"]?.toString(Charsets.UTF_8) ?: ""
    }

    fun readEmbeddings(): List<VectorPoint> {
        val bytes = entries["embeddings/chunks.jsonl"] ?: return emptyList()
        val content = bytes.toString(Charsets.UTF_8)
        if (content.isBlank()) return emptyList()

        return content.lines().filter { it.isNotBlank() }.map { line ->
            val map: Map<String, Any> = mapper.readValue(line)
            val id = map["id"] as String
            @Suppress("UNCHECKED_CAST")
            val vectorList = map["vector"] as List<Number>
            val vector = vectorList.map { it.toFloat() }.toFloatArray()
            @Suppress("UNCHECKED_CAST")
            val payload = (map["payload"] as? Map<String, Any>) ?: emptyMap()
            VectorPoint(id = id, vector = vector, payload = payload)
        }
    }

    fun verifyChecksums(): Boolean {
        val checksumFile = entries["checksums.sha256"]?.toString(Charsets.UTF_8) ?: return false
        return checksumFile.lines().filter { it.isNotBlank() }.all { line ->
            val parts = line.split("  ", limit = 2)
            if (parts.size != 2) return@all false
            val (expectedHash, fileName) = parts
            val fileBytes = entries[fileName] ?: return@all false
            sha256(fileBytes) == expectedHash
        }
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests "*BundleWriterReaderTest*"`
Expected: Both tests PASS.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(contextcore): add BundleWriter and BundleReader with zip format"
```

---

## Task 7: ContextCoreRegistry (Cassandra)

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/contextcore/ContextCoreRegistry.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/storage/CassandraSchemaInitializer.kt`

- [ ] **Step 1: Add context_cores table to CassandraSchemaInitializer**

Add at the end of `createTables()`:

```kotlin
        session.execute("""
            CREATE TABLE IF NOT EXISTS $keyspace.context_cores (
                core_id          text,
                version          text,
                parent_version   text,
                source_collection text,
                created_at       timestamp,
                created_by       text,
                description      text,
                tags             set<text>,
                embedding_model  text,
                embedding_dim    int,
                quad_count       bigint,
                entity_count     bigint,
                chunk_embedding_count bigint,
                ontology_axiom_count bigint,
                checksum         text,
                blob_key         text,
                PRIMARY KEY (core_id, version)
            ) WITH CLUSTERING ORDER BY (version DESC)
        """.trimIndent())
```

- [ ] **Step 2: Implement ContextCoreRegistry**

```kotlin
package com.agentwork.graphmesh.contextcore

import com.datastax.oss.driver.api.core.CqlSession
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant

data class CoreRecord(val manifest: CoreManifest, val blobKey: String)

@Component
class ContextCoreRegistry(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) {

    fun register(manifest: CoreManifest, blobKey: String) {
        session.execute(
            """
            INSERT INTO $keyspace.context_cores (
                core_id, version, parent_version, source_collection,
                created_at, created_by, description, tags,
                embedding_model, embedding_dim,
                quad_count, entity_count, chunk_embedding_count, ontology_axiom_count,
                checksum, blob_key
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            manifest.coreId, manifest.version, manifest.parentVersion, manifest.sourceCollection,
            java.time.Instant.from(manifest.createdAt), manifest.createdBy, manifest.description,
            manifest.tags,
            manifest.embeddingModel, manifest.embeddingDimension,
            manifest.stats.quadCount, manifest.stats.entityCount,
            manifest.stats.chunkEmbeddingCount, manifest.stats.ontologyAxiomCount,
            manifest.checksum, blobKey
        )
    }

    fun unregister(coreId: String, version: String) {
        session.execute(
            "DELETE FROM $keyspace.context_cores WHERE core_id = ? AND version = ?",
            coreId, version
        )
    }

    fun find(coreId: String, version: String): CoreRecord? {
        val row = session.execute(
            "SELECT * FROM $keyspace.context_cores WHERE core_id = ? AND version = ?",
            coreId, version
        ).one() ?: return null
        return rowToRecord(row)
    }

    fun findByTag(coreId: String, tag: String): CoreRecord? {
        val rows = session.execute(
            "SELECT * FROM $keyspace.context_cores WHERE core_id = ? ALLOW FILTERING",
            coreId
        )
        return rows.firstOrNull { row ->
            row.getSet("tags", String::class.java)?.contains(tag) == true
        }?.let { rowToRecord(it) }
    }

    fun listAll(): List<CoreRecord> {
        val rows = session.execute("SELECT * FROM $keyspace.context_cores")
        return rows.map { rowToRecord(it) }.toList()
    }

    fun addTag(coreId: String, version: String, tag: String) {
        session.execute(
            "UPDATE $keyspace.context_cores SET tags = tags + ? WHERE core_id = ? AND version = ?",
            setOf(tag), coreId, version
        )
    }

    private fun rowToRecord(row: com.datastax.oss.driver.api.core.cql.Row): CoreRecord {
        val manifest = CoreManifest(
            coreId = row.getString("core_id")!!,
            version = row.getString("version")!!,
            parentVersion = row.getString("parent_version"),
            sourceCollection = row.getString("source_collection")!!,
            createdAt = row.getInstant("created_at")!!,
            createdBy = row.getString("created_by")!!,
            description = row.getString("description"),
            tags = row.getSet("tags", String::class.java)?.toSet() ?: emptySet(),
            stats = CoreStats(
                quadCount = row.getLong("quad_count"),
                entityCount = row.getLong("entity_count"),
                chunkEmbeddingCount = row.getLong("chunk_embedding_count"),
                ontologyAxiomCount = row.getLong("ontology_axiom_count")
            ),
            embeddingModel = row.getString("embedding_model")!!,
            embeddingDimension = row.getInt("embedding_dim"),
            checksum = row.getString("checksum")!!
        )
        return CoreRecord(manifest, row.getString("blob_key")!!)
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(contextcore): add ContextCoreRegistry with Cassandra persistence"
```

---

## Task 8: ContextCoreService

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/contextcore/ContextCoreService.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/contextcore/ContextCoreServiceTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.storage.InMemoryQuadStore
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.StoredQuad
import com.agentwork.graphmesh.storage.blob.BlobData
import com.agentwork.graphmesh.storage.blob.BlobStore
import com.agentwork.graphmesh.storage.vector.CollectionNaming
import com.agentwork.graphmesh.storage.vector.VectorPoint
import com.agentwork.graphmesh.storage.vector.VectorStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class ContextCoreServiceTest {

    private lateinit var quadStore: InMemoryQuadStore
    private lateinit var vectorStore: VectorStore
    private lateinit var blobStore: BlobStore
    private lateinit var ontologyService: OntologyService
    private lateinit var registry: ContextCoreRegistry
    private lateinit var service: ContextCoreService

    @BeforeEach
    fun setup() {
        quadStore = InMemoryQuadStore()
        vectorStore = mock()
        blobStore = mock()
        ontologyService = mock()
        registry = mock()
        service = ContextCoreService(quadStore, vectorStore, ontologyService, blobStore, registry)
    }

    @Test
    fun `build creates bundle and registers in registry`() {
        // Setup: collection with quads
        val quad = StoredQuad("http://ex.org/A", "http://ex.org/p", "http://ex.org/B", "d", ObjectType.URI)
        quadStore.insertBatch("my-col", listOf(quad))

        whenever(ontologyService.exportTurtle(any())).thenReturn("@prefix ex: <http://ex.org/> .")
        whenever(vectorStore.scroll(any())).thenReturn(listOf(
            VectorPoint("c1", floatArrayOf(0.1f, 0.2f), mapOf("text" to "hello"))
        ))

        val request = BuildRequest(
            coreId = "test",
            version = "1.0.0",
            sourceCollection = "my-col",
            embeddingModel = "text-embedding-3-small",
            embeddingDimension = 2,
            ontologyKey = "my-onto"
        )

        val result = service.build(request)

        assertEquals("test", result.coreId)
        assertEquals("1.0.0", result.version)
        assertEquals(1L, result.stats.quadCount)
        assertTrue(result.checksum.isNotBlank())

        verify(blobStore).put(eq("graphmesh-context-cores"), any(), any<ByteArray>(), any(), any())
        verify(registry).register(any(), any())
    }

    @Test
    fun `import with FAIL strategy rejects non-empty collection`() {
        quadStore.insertBatch("target", listOf(
            StoredQuad("http://ex.org/X", "http://ex.org/p", "http://ex.org/Y", "d", ObjectType.URI)
        ))

        whenever(registry.find("test", "1.0.0")).thenReturn(CoreRecord(
            manifest = CoreManifest(
                coreId = "test", version = "1.0.0", sourceCollection = "src",
                createdAt = java.time.Instant.now(), createdBy = "u",
                stats = CoreStats(0, 0, 0, 0),
                embeddingModel = "m", embeddingDimension = 2, checksum = "x"
            ),
            blobKey = "cores/test/1.0.0.zip"
        ))

        // Build a minimal valid bundle to return from blobStore
        val zipBytes = BundleWriter.write(
            CoreManifest("test", "1.0.0", null, "src", java.time.Instant.now(), "u", null, emptySet(),
                CoreStats(0, 0, 0, 0), "m", 2, ""),
            "", "", emptyList(), RetrievalPolicies(),
            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().apply { findAndRegisterModules() }
        )
        whenever(blobStore.get("graphmesh-context-cores", "cores/test/1.0.0.zip"))
            .thenReturn(BlobData(zipBytes, "application/zip", zipBytes.size.toLong()))

        val request = ImportRequest("test", "1.0.0", "target", ConflictStrategy.FAIL)

        assertThrows(IllegalArgumentException::class.java) {
            service.import(request)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*ContextCoreServiceTest*"`
Expected: FAIL

- [ ] **Step 3: Implement ContextCoreService**

```kotlin
package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.blob.BlobStore
import com.agentwork.graphmesh.storage.vector.CollectionNaming
import com.agentwork.graphmesh.storage.vector.VectorStore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ContextCoreService(
    private val quadStore: QuadStore,
    private val vectorStore: VectorStore,
    private val ontologyService: OntologyService,
    private val blobStore: BlobStore,
    private val registry: ContextCoreRegistry
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper().apply { findAndRegisterModules() }
    private val nquadsSerializer = NQuadsSerializer()
    private val bucket = "graphmesh-context-cores"

    fun build(request: BuildRequest): CoreManifest {
        logger.info("Building context core: coreId={}, version={}, source={}",
            request.coreId, request.version, request.sourceCollection)

        val quads = quadStore.scrollAll(request.sourceCollection)
        val nquads = nquadsSerializer.serialize(quads)

        val ontologyTtl = if (request.ontologyKey != null) {
            try { ontologyService.exportTurtle(request.ontologyKey) } catch (_: Exception) { "" }
        } else ""

        val physicalCollection = CollectionNaming.physicalName(request.sourceCollection, request.embeddingDimension)
        val embeddings = try { vectorStore.scroll(physicalCollection) } catch (_: Exception) { emptyList() }

        val stats = CoreStats(
            quadCount = quads.size.toLong(),
            entityCount = quads.map { it.subject }.distinct().size.toLong(),
            chunkEmbeddingCount = embeddings.size.toLong(),
            ontologyAxiomCount = 0
        )

        val manifest = CoreManifest(
            coreId = request.coreId,
            version = request.version,
            parentVersion = request.parentVersion,
            sourceCollection = request.sourceCollection,
            createdAt = java.time.Instant.now(),
            createdBy = request.createdBy,
            description = request.description,
            tags = request.tags,
            stats = stats,
            embeddingModel = request.embeddingModel,
            embeddingDimension = request.embeddingDimension,
            checksum = "" // computed by BundleWriter
        )

        val zipBytes = BundleWriter.write(manifest, nquads, ontologyTtl, embeddings, request.retrievalPolicies, mapper)
        val blobKey = "cores/${request.coreId}/${request.version}.zip"
        blobStore.put(bucket, blobKey, zipBytes, "application/zip")

        // Read back the actual manifest (with computed checksum)
        val reader = BundleReader(zipBytes, mapper)
        val finalManifest = reader.readManifest()

        registry.register(finalManifest, blobKey)
        logger.info("Context core built: {} bytes, checksum={}", zipBytes.size, finalManifest.checksum)
        return finalManifest
    }

    fun import(request: ImportRequest): ImportResult {
        val record = registry.find(request.coreId, request.version)
            ?: error("Unknown context core: ${request.coreId}@${request.version}")

        val blobData = blobStore.get(bucket, record.blobKey)
        val reader = BundleReader(blobData.data, mapper)

        val manifest = reader.readManifest()
        require(reader.verifyChecksums()) { "Bundle checksum verification failed" }

        val target = request.targetCollection
        when (request.strategy) {
            ConflictStrategy.FAIL -> require(quadStore.isEmpty(target)) {
                "Target collection '$target' is not empty (use MERGE or REPLACE strategy)"
            }
            ConflictStrategy.REPLACE -> {
                quadStore.deleteCollection(target)
                val physicalTarget = CollectionNaming.physicalName(target, manifest.embeddingDimension)
                vectorStore.deleteCollection(physicalTarget)
            }
            ConflictStrategy.MERGE -> Unit
        }

        val nquadsContent = reader.readNQuads()
        val quads = nquadsSerializer.deserialize(nquadsContent)
            .map { NamespaceRewriter.applyOrNull(it, request.namespaceRewrite) }
        if (quads.isNotEmpty()) {
            quadStore.insertBatch(target, quads)
        }

        val points = reader.readEmbeddings()
        if (points.isNotEmpty()) {
            val physicalTarget = CollectionNaming.physicalName(target, manifest.embeddingDimension)
            vectorStore.upsert(physicalTarget, points)
        }

        val ontologyTtl = reader.readOntology()
        if (ontologyTtl.isNotBlank()) {
            try {
                ontologyService.importTurtle(
                    "${request.coreId}-${request.version}",
                    ontologyTtl,
                    com.agentwork.graphmesh.ontology.OntologyMetadata(
                        name = "${request.coreId} ontology",
                        namespace = "urn:context-core:${request.coreId}:",
                        version = request.version
                    )
                )
            } catch (e: Exception) {
                logger.warn("Failed to import ontology from core: {}", e.message)
            }
        }

        logger.info("Context core imported: {} quads, {} embeddings into '{}'", quads.size, points.size, target)
        return ImportResult(
            coreId = manifest.coreId,
            version = manifest.version,
            quadsImported = quads.size,
            embeddingsImported = points.size
        )
    }

    fun list(): List<CoreManifest> = registry.listAll().map { it.manifest }

    fun find(coreId: String, version: String): CoreManifest? = registry.find(coreId, version)?.manifest

    fun findByTag(coreId: String, tag: String): CoreManifest? = registry.findByTag(coreId, tag)?.manifest

    fun delete(coreId: String, version: String) {
        val record = registry.find(coreId, version) ?: return
        blobStore.delete(bucket, record.blobKey)
        registry.unregister(coreId, version)
        logger.info("Context core deleted: {}@{}", coreId, version)
    }

    fun tag(coreId: String, version: String, tag: String): CoreManifest? {
        registry.addTag(coreId, version, tag)
        return registry.find(coreId, version)?.manifest
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "*ContextCoreServiceTest*"`
Expected: Both tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(contextcore): add ContextCoreService with build and import"
```

---

## Task 9: GraphQL Schema + Controller

**Files:**
- Create: `src/main/resources/graphql/context-core.graphqls`
- Create: `src/main/kotlin/com/agentwork/graphmesh/api/ContextCoreController.kt`

- [ ] **Step 1: Create GraphQL schema**

```graphql
type ContextCore {
    coreId: String!
    version: String!
    parentVersion: String
    sourceCollection: String!
    createdAt: String!
    createdBy: String!
    description: String
    tags: [String!]!
    stats: ContextCoreStats!
    embeddingModel: String!
    embeddingDimension: Int!
    checksum: String!
}

type ContextCoreStats {
    quadCount: Int!
    entityCount: Int!
    chunkEmbeddingCount: Int!
    ontologyAxiomCount: Int!
}

type ImportResultDto {
    coreId: String!
    version: String!
    quadsImported: Int!
    embeddingsImported: Int!
}

enum ConflictStrategy {
    FAIL
    MERGE
    REPLACE
}

extend type Query {
    contextCores: [ContextCore!]!
    contextCore(coreId: String!, version: String!): ContextCore
    contextCoreByTag(coreId: String!, tag: String!): ContextCore
}

extend type Mutation {
    buildContextCore(
        coreId: String!
        version: String!
        sourceCollection: String!
        description: String
        tags: [String!]
        embeddingModel: String
        embeddingDimension: Int
        ontologyKey: String
    ): ContextCore!

    importContextCore(
        coreId: String!
        version: String!
        targetCollection: String!
        strategy: ConflictStrategy! = FAIL
        namespaceFrom: String
        namespaceTo: String
    ): ImportResultDto!

    tagContextCore(coreId: String!, version: String!, tag: String!): ContextCore!
    deleteContextCore(coreId: String!, version: String!): Boolean!
}
```

- [ ] **Step 2: Create ContextCoreController**

```kotlin
package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.contextcore.*
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class ContextCoreController(
    private val contextCoreService: ContextCoreService
) {

    @QueryMapping
    fun contextCores(): List<CoreManifest> = contextCoreService.list()

    @QueryMapping
    fun contextCore(@Argument coreId: String, @Argument version: String): CoreManifest? =
        contextCoreService.find(coreId, version)

    @QueryMapping
    fun contextCoreByTag(@Argument coreId: String, @Argument tag: String): CoreManifest? =
        contextCoreService.findByTag(coreId, tag)

    @MutationMapping
    fun buildContextCore(
        @Argument coreId: String,
        @Argument version: String,
        @Argument sourceCollection: String,
        @Argument description: String?,
        @Argument tags: List<String>?,
        @Argument embeddingModel: String?,
        @Argument embeddingDimension: Int?,
        @Argument ontologyKey: String?
    ): CoreManifest {
        val request = BuildRequest(
            coreId = coreId,
            version = version,
            sourceCollection = sourceCollection,
            embeddingModel = embeddingModel ?: "text-embedding-3-small",
            embeddingDimension = embeddingDimension ?: 1536,
            description = description,
            tags = tags?.toSet() ?: emptySet(),
            ontologyKey = ontologyKey
        )
        return contextCoreService.build(request)
    }

    @MutationMapping
    fun importContextCore(
        @Argument coreId: String,
        @Argument version: String,
        @Argument targetCollection: String,
        @Argument strategy: ConflictStrategy,
        @Argument namespaceFrom: String?,
        @Argument namespaceTo: String?
    ): ImportResult {
        val namespaceRewrite = if (namespaceFrom != null && namespaceTo != null) {
            NamespaceRewrite(namespaceFrom, namespaceTo)
        } else null

        val request = ImportRequest(
            coreId = coreId,
            version = version,
            targetCollection = targetCollection,
            strategy = strategy,
            namespaceRewrite = namespaceRewrite
        )
        return contextCoreService.import(request)
    }

    @MutationMapping
    fun tagContextCore(@Argument coreId: String, @Argument version: String, @Argument tag: String): CoreManifest? =
        contextCoreService.tag(coreId, version, tag)

    @MutationMapping
    fun deleteContextCore(@Argument coreId: String, @Argument version: String): Boolean {
        contextCoreService.delete(coreId, version)
        return true
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(contextcore): add GraphQL schema and controller"
```

---

## Task 10: Frontend — GraphQL Queries and Core List Page

**Files:**
- Create: `frontend/src/graphql/cores.ts`
- Create: `frontend/src/app/cores/page.tsx`
- Create: `frontend/src/app/cores/layout.tsx`

- [ ] **Step 1: Create GraphQL queries**

```typescript
// frontend/src/graphql/cores.ts
import { gql } from "@apollo/client";

export const CONTEXT_CORES_QUERY = gql`
  query ContextCores {
    contextCores {
      coreId
      version
      sourceCollection
      createdAt
      createdBy
      description
      tags
      stats {
        quadCount
        entityCount
        chunkEmbeddingCount
        ontologyAxiomCount
      }
      embeddingModel
      embeddingDimension
      checksum
    }
  }
`;

export const CONTEXT_CORE_QUERY = gql`
  query ContextCore($coreId: String!, $version: String!) {
    contextCore(coreId: $coreId, version: $version) {
      coreId
      version
      parentVersion
      sourceCollection
      createdAt
      createdBy
      description
      tags
      stats {
        quadCount
        entityCount
        chunkEmbeddingCount
        ontologyAxiomCount
      }
      embeddingModel
      embeddingDimension
      checksum
    }
  }
`;

export const BUILD_CONTEXT_CORE_MUTATION = gql`
  mutation BuildContextCore(
    $coreId: String!
    $version: String!
    $sourceCollection: String!
    $description: String
    $tags: [String!]
    $embeddingModel: String
    $embeddingDimension: Int
    $ontologyKey: String
  ) {
    buildContextCore(
      coreId: $coreId
      version: $version
      sourceCollection: $sourceCollection
      description: $description
      tags: $tags
      embeddingModel: $embeddingModel
      embeddingDimension: $embeddingDimension
      ontologyKey: $ontologyKey
    ) {
      coreId
      version
      checksum
      stats {
        quadCount
        entityCount
        chunkEmbeddingCount
      }
    }
  }
`;

export const IMPORT_CONTEXT_CORE_MUTATION = gql`
  mutation ImportContextCore(
    $coreId: String!
    $version: String!
    $targetCollection: String!
    $strategy: ConflictStrategy!
    $namespaceFrom: String
    $namespaceTo: String
  ) {
    importContextCore(
      coreId: $coreId
      version: $version
      targetCollection: $targetCollection
      strategy: $strategy
      namespaceFrom: $namespaceFrom
      namespaceTo: $namespaceTo
    ) {
      coreId
      version
      quadsImported
      embeddingsImported
    }
  }
`;

export const TAG_CONTEXT_CORE_MUTATION = gql`
  mutation TagContextCore($coreId: String!, $version: String!, $tag: String!) {
    tagContextCore(coreId: $coreId, version: $version, tag: $tag) {
      coreId
      version
      tags
    }
  }
`;

export const DELETE_CONTEXT_CORE_MUTATION = gql`
  mutation DeleteContextCore($coreId: String!, $version: String!) {
    deleteContextCore(coreId: $coreId, version: $version)
  }
`;
```

- [ ] **Step 2: Create cores layout**

```typescript
// frontend/src/app/cores/layout.tsx
export default function CoresLayout({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
```

- [ ] **Step 3: Create cores list page**

```typescript
// frontend/src/app/cores/page.tsx
"use client";

import { useQuery, useMutation } from "@apollo/client";
import { CONTEXT_CORES_QUERY, DELETE_CONTEXT_CORE_MUTATION } from "@/graphql/cores";
import { BuildCoreDialog } from "@/components/cores/BuildCoreDialog";
import { useState } from "react";

export default function CoresPage() {
  const { data, loading, refetch } = useQuery(CONTEXT_CORES_QUERY);
  const [deleteMutation] = useMutation(DELETE_CONTEXT_CORE_MUTATION);
  const [showBuild, setShowBuild] = useState(false);

  const handleDelete = async (coreId: string, version: string) => {
    if (!confirm(`Context Core ${coreId}@${version} wirklich löschen?`)) return;
    await deleteMutation({ variables: { coreId, version } });
    refetch();
  };

  return (
    <>
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold">Context Cores</h1>
        <button
          onClick={() => setShowBuild(true)}
          className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
        >
          Core bauen
        </button>
      </div>

      {loading && <p className="text-muted-foreground">Laden...</p>}

      {data?.contextCores?.length === 0 && (
        <p className="text-muted-foreground">Noch keine Context Cores vorhanden.</p>
      )}

      <div className="grid gap-4">
        {data?.contextCores?.map((core: any) => (
          <div
            key={`${core.coreId}-${core.version}`}
            className="rounded-lg border border-border bg-card p-4"
          >
            <div className="flex items-start justify-between">
              <div>
                <h3 className="font-semibold">
                  {core.coreId}
                  <span className="ml-2 text-sm font-normal text-muted-foreground">
                    v{core.version}
                  </span>
                </h3>
                {core.description && (
                  <p className="mt-1 text-sm text-muted-foreground">{core.description}</p>
                )}
                <div className="mt-2 flex gap-4 text-xs text-muted-foreground">
                  <span>{core.stats.quadCount} Quads</span>
                  <span>{core.stats.entityCount} Entitaeten</span>
                  <span>{core.stats.chunkEmbeddingCount} Embeddings</span>
                  <span>Collection: {core.sourceCollection}</span>
                </div>
                {core.tags.length > 0 && (
                  <div className="mt-2 flex gap-1">
                    {core.tags.map((tag: string) => (
                      <span
                        key={tag}
                        className="rounded-full bg-blue-500/10 px-2 py-0.5 text-xs text-blue-400"
                      >
                        {tag}
                      </span>
                    ))}
                  </div>
                )}
              </div>
              <button
                onClick={() => handleDelete(core.coreId, core.version)}
                className="text-xs text-red-400 hover:text-red-300"
              >
                Loeschen
              </button>
            </div>
          </div>
        ))}
      </div>

      {showBuild && (
        <BuildCoreDialog
          onClose={() => setShowBuild(false)}
          onSuccess={() => { setShowBuild(false); refetch(); }}
        />
      )}
    </>
  );
}
```

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(frontend): add Context Cores list page and GraphQL queries"
```

---

## Task 11: Frontend — Build and Import Dialogs

**Files:**
- Create: `frontend/src/components/cores/BuildCoreDialog.tsx`
- Create: `frontend/src/components/cores/ImportCoreDialog.tsx`

- [ ] **Step 1: Create BuildCoreDialog**

```typescript
// frontend/src/components/cores/BuildCoreDialog.tsx
"use client";

import { useMutation, useQuery } from "@apollo/client";
import { BUILD_CONTEXT_CORE_MUTATION } from "@/graphql/cores";
import { ADMIN_COLLECTIONS_QUERY } from "@/graphql/admin";
import { useState } from "react";

interface Props {
  onClose: () => void;
  onSuccess: () => void;
}

export function BuildCoreDialog({ onClose, onSuccess }: Props) {
  const [coreId, setCoreId] = useState("");
  const [version, setVersion] = useState("1.0.0");
  const [sourceCollection, setSourceCollection] = useState("");
  const [description, setDescription] = useState("");
  const [tags, setTags] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const { data: collectionsData } = useQuery(ADMIN_COLLECTIONS_QUERY);
  const [buildMutation] = useMutation(BUILD_CONTEXT_CORE_MUTATION);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      await buildMutation({
        variables: {
          coreId,
          version,
          sourceCollection,
          description: description || null,
          tags: tags ? tags.split(",").map((t) => t.trim()) : null,
        },
      });
      onSuccess();
    } catch (err: any) {
      setError(err.message || "Build fehlgeschlagen");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="w-full max-w-md rounded-lg border border-border bg-card p-6">
        <h2 className="mb-4 text-lg font-semibold">Context Core bauen</h2>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="mb-1 block text-sm">Core ID</label>
            <input
              value={coreId}
              onChange={(e) => setCoreId(e.target.value)}
              placeholder="z.B. pharma-base"
              required
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm">Version</label>
            <input
              value={version}
              onChange={(e) => setVersion(e.target.value)}
              placeholder="1.0.0"
              required
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm">Quell-Collection</label>
            <select
              value={sourceCollection}
              onChange={(e) => setSourceCollection(e.target.value)}
              required
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            >
              <option value="">Collection waehlen...</option>
              {collectionsData?.collections?.map((col: any) => (
                <option key={col.id} value={col.id}>
                  {col.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="mb-1 block text-sm">Beschreibung (optional)</label>
            <input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm">Tags (kommagetrennt, optional)</label>
            <input
              value={tags}
              onChange={(e) => setTags(e.target.value)}
              placeholder="stage, v1"
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            />
          </div>
          {error && <p className="text-sm text-red-400">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className="rounded-md px-4 py-2 text-sm">
              Abbrechen
            </button>
            <button
              type="submit"
              disabled={loading}
              className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {loading ? "Baue..." : "Core bauen"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Create ImportCoreDialog**

```typescript
// frontend/src/components/cores/ImportCoreDialog.tsx
"use client";

import { useMutation, useQuery } from "@apollo/client";
import { IMPORT_CONTEXT_CORE_MUTATION } from "@/graphql/cores";
import { ADMIN_COLLECTIONS_QUERY } from "@/graphql/admin";
import { useState } from "react";

interface Props {
  coreId: string;
  version: string;
  onClose: () => void;
  onSuccess: () => void;
}

export function ImportCoreDialog({ coreId, version, onClose, onSuccess }: Props) {
  const [targetCollection, setTargetCollection] = useState("");
  const [strategy, setStrategy] = useState("FAIL");
  const [namespaceFrom, setNamespaceFrom] = useState("");
  const [namespaceTo, setNamespaceTo] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const { data: collectionsData } = useQuery(ADMIN_COLLECTIONS_QUERY);
  const [importMutation] = useMutation(IMPORT_CONTEXT_CORE_MUTATION);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      await importMutation({
        variables: {
          coreId,
          version,
          targetCollection,
          strategy,
          namespaceFrom: namespaceFrom || null,
          namespaceTo: namespaceTo || null,
        },
      });
      onSuccess();
    } catch (err: any) {
      setError(err.message || "Import fehlgeschlagen");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="w-full max-w-md rounded-lg border border-border bg-card p-6">
        <h2 className="mb-4 text-lg font-semibold">
          Core importieren: {coreId}@{version}
        </h2>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="mb-1 block text-sm">Ziel-Collection</label>
            <select
              value={targetCollection}
              onChange={(e) => setTargetCollection(e.target.value)}
              required
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            >
              <option value="">Collection waehlen...</option>
              {collectionsData?.collections?.map((col: any) => (
                <option key={col.id} value={col.id}>
                  {col.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="mb-1 block text-sm">Konfliktstrategie</label>
            <select
              value={strategy}
              onChange={(e) => setStrategy(e.target.value)}
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            >
              <option value="FAIL">FAIL — Abbrechen wenn nicht leer</option>
              <option value="MERGE">MERGE — Hinzufuegen</option>
              <option value="REPLACE">REPLACE — Vorhandenes ersetzen</option>
            </select>
          </div>
          <div>
            <label className="mb-1 block text-sm">Namespace-Rewrite (optional)</label>
            <div className="flex gap-2">
              <input
                value={namespaceFrom}
                onChange={(e) => setNamespaceFrom(e.target.value)}
                placeholder="Von: http://old.org/"
                className="w-1/2 rounded-md border border-border bg-background px-3 py-2 text-sm"
              />
              <input
                value={namespaceTo}
                onChange={(e) => setNamespaceTo(e.target.value)}
                placeholder="Nach: http://new.org/"
                className="w-1/2 rounded-md border border-border bg-background px-3 py-2 text-sm"
              />
            </div>
          </div>
          {error && <p className="text-sm text-red-400">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className="rounded-md px-4 py-2 text-sm">
              Abbrechen
            </button>
            <button
              type="submit"
              disabled={loading}
              className="rounded-md bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-50"
            >
              {loading ? "Importiere..." : "Importieren"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat(frontend): add Build and Import dialogs for Context Cores"
```

---

## Task 12: Final Integration — Build Verification

**Files:** None new — verification only.

- [ ] **Step 1: Run full backend compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all context core tests**

Run: `./gradlew test --tests "*contextcore*"`
Expected: All tests PASS (NQuadsSerializerTest, NamespaceRewriterTest, BundleWriterReaderTest, ContextCoreServiceTest)

- [ ] **Step 3: Run full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (existing tests unaffected)

- [ ] **Step 4: Verify frontend builds**

Run: `cd frontend && pnpm build`
Expected: Build succeeds without errors

- [ ] **Step 5: Final commit if any fixes were needed**

```bash
git add -A && git commit -m "fix(contextcore): resolve integration issues"
```
