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
