package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.ontology.OntologyMetadata
import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.blob.BlobStore
import com.agentwork.graphmesh.storage.vector.CollectionNaming
import com.agentwork.graphmesh.storage.vector.VectorStore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

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

    @PostConstruct
    fun init() {
        blobStore.ensureBucket(BUCKET)
    }
    companion object {
        private const val BUCKET = "graphmesh-context-cores"
    }

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
            createdAt = Instant.now(),
            createdBy = request.createdBy,
            description = request.description,
            tags = request.tags,
            stats = stats,
            embeddingModel = request.embeddingModel,
            embeddingDimension = request.embeddingDimension,
            checksum = ""
        )

        val zipBytes = BundleWriter.write(manifest, nquads, ontologyTtl, embeddings, request.retrievalPolicies, mapper)
        val blobKey = "cores/${request.coreId}/${request.version}.zip"
        blobStore.put(BUCKET, blobKey, zipBytes, "application/zip")

        val reader = BundleReader(zipBytes, mapper)
        val finalManifest = reader.readManifest()

        registry.register(finalManifest, blobKey)
        logger.info("Context core built: {} bytes, checksum={}", zipBytes.size, finalManifest.checksum)
        return finalManifest
    }

    fun import(request: ImportRequest): ImportResult {
        val record = registry.find(request.coreId, request.version)
            ?: error("Unknown context core: ${request.coreId}@${request.version}")

        val blobData = blobStore.get(BUCKET, record.blobKey)
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
                    OntologyMetadata(
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
        blobStore.delete(BUCKET, record.blobKey)
        registry.unregister(coreId, version)
        logger.info("Context core deleted: {}@{}", coreId, version)
    }

    fun tag(coreId: String, version: String, tag: String): CoreManifest? {
        registry.addTag(coreId, version, tag)
        return registry.find(coreId, version)?.manifest
    }
}
