package com.agentwork.graphmesh.storage.vector

import io.qdrant.client.ConditionFactory
import io.qdrant.client.PointIdFactory
import io.qdrant.client.QdrantClient
import io.qdrant.client.ValueFactory
import io.qdrant.client.VectorsFactory
import io.qdrant.client.WithPayloadSelectorFactory
import io.qdrant.client.grpc.Collections.Distance
import io.qdrant.client.grpc.Collections.VectorParams
import io.qdrant.client.grpc.Points.Condition
import io.qdrant.client.grpc.Points.Filter
import io.qdrant.client.grpc.Points.PointStruct
import io.qdrant.client.grpc.Points.SearchPoints
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class QdrantVectorStore(
    private val client: QdrantClient
) : VectorStore {

    private val log = LoggerFactory.getLogger(javaClass)
    private val knownCollections = ConcurrentHashMap.newKeySet<String>()

    override fun upsert(collection: String, points: List<VectorPoint>) {
        if (points.isEmpty()) return

        val dimension = points.first().dimension
        require(points.all { it.dimension == dimension }) {
            "All points must have the same dimension, found mixed: ${points.map { it.dimension }.distinct()}"
        }
        val physicalName = CollectionNaming.physicalName(collection, dimension)

        ensureCollection(physicalName, dimension)

        val pointStructs = points.map { point ->
            val ps = PointStruct.newBuilder()
                .setId(PointIdFactory.id(deterministicUuid(point.id)))
                .setVectors(VectorsFactory.vectors(*point.vector))
            buildPayload(point.id, point.payload).forEach { (k, v) -> ps.putPayload(k, v) }
            ps.build()
        }

        client.upsertAsync(physicalName, pointStructs).get()
        log.debug("Upserted {} points into {}", points.size, physicalName)
    }

    override fun search(
        collection: String,
        queryVector: FloatArray,
        limit: Int,
        filter: VectorFilter?,
        scoreThreshold: Float?
    ): List<SearchResult> {
        val dimension = queryVector.size
        val physicalName = CollectionNaming.physicalName(collection, dimension)

        if (!collectionExists(collection, dimension)) {
            log.debug("Collection {} does not exist, returning empty results", physicalName)
            return emptyList()
        }

        val searchBuilder = SearchPoints.newBuilder()
            .setCollectionName(physicalName)
            .addAllVector(queryVector.toList())
            .setLimit(limit.toLong())
            .setWithPayload(WithPayloadSelectorFactory.enable(true))

        if (filter != null) {
            searchBuilder.setFilter(toQdrantFilter(filter))
        }

        if (scoreThreshold != null) {
            searchBuilder.setScoreThreshold(scoreThreshold)
        }

        val results = client.searchAsync(searchBuilder.build()).get()

        return results.map { scored ->
            val payload = scored.payloadMap
            val originalId = payload["_original_id"]?.stringValue ?: scored.id.uuid
            SearchResult(
                id = originalId,
                score = scored.score,
                payload = payload
                    .filterKeys { it != "_original_id" }
                    .mapValues { extractPayloadValue(it.value) }
            )
        }
    }

    override fun delete(collection: String, dimension: Int, ids: List<String>) {
        if (ids.isEmpty()) return
        val physicalName = CollectionNaming.physicalName(collection, dimension)
        val pointIds = ids.map { PointIdFactory.id(deterministicUuid(it)) }
        client.deleteAsync(physicalName, pointIds).get()
        log.debug("Deleted {} points from {}", ids.size, physicalName)
    }

    override fun deleteCollection(collection: String) {
        val allCollections = client.listCollectionsAsync().get()
        val prefix = CollectionNaming.prefixPattern(collection)
        val matching = allCollections.filter { it.startsWith(prefix) }

        matching.forEach { name ->
            client.deleteCollectionAsync(name).get()
            knownCollections.remove(name)
            log.info("Collection deleted: {}", name)
        }
        log.info("{} collection(s) deleted for '{}'", matching.size, collection)
    }

    override fun collectionExists(collection: String, dimension: Int): Boolean {
        val physicalName = CollectionNaming.physicalName(collection, dimension)
        if (physicalName in knownCollections) return true
        return client.collectionExistsAsync(physicalName).get()
    }

    @Synchronized
    private fun ensureCollection(physicalName: String, dimension: Int) {
        if (physicalName in knownCollections) return

        val exists = client.collectionExistsAsync(physicalName).get()
        if (!exists) {
            client.createCollectionAsync(
                physicalName,
                VectorParams.newBuilder()
                    .setSize(dimension.toLong())
                    .setDistance(Distance.Cosine)
                    .build()
            ).get()
            log.info("Collection created: {} (dimension={})", physicalName, dimension)
        }
        knownCollections.add(physicalName)
    }

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

    private fun deterministicUuid(id: String): UUID =
        UUID.nameUUIDFromBytes(id.toByteArray())

    private fun buildPayload(originalId: String, payload: Map<String, Any>): Map<String, io.qdrant.client.grpc.JsonWithInt.Value> {
        val result = mutableMapOf<String, io.qdrant.client.grpc.JsonWithInt.Value>()
        result["_original_id"] = ValueFactory.value(originalId)
        payload.forEach { (key, value) ->
            result[key] = when (value) {
                is String -> ValueFactory.value(value)
                is Int -> ValueFactory.value(value.toLong())
                is Long -> ValueFactory.value(value)
                is Double -> ValueFactory.value(value)
                is Float -> ValueFactory.value(value.toDouble())
                is Boolean -> ValueFactory.value(value)
                else -> ValueFactory.value(value.toString())
            }
        }
        return result
    }

    private fun extractPayloadValue(value: io.qdrant.client.grpc.JsonWithInt.Value): Any {
        return when {
            value.hasStringValue() -> value.stringValue
            value.hasIntegerValue() -> value.integerValue
            value.hasDoubleValue() -> value.doubleValue
            value.hasBoolValue() -> value.boolValue
            else -> value.stringValue
        }
    }

    private fun toQdrantFilter(filter: VectorFilter): Filter {
        return when (filter) {
            is VectorFilter.Equals -> {
                val condition = when (val v = filter.value) {
                    is String -> ConditionFactory.matchKeyword(filter.field, v)
                    is Int -> ConditionFactory.match(filter.field, v.toLong())
                    is Long -> ConditionFactory.match(filter.field, v)
                    is Boolean -> ConditionFactory.match(filter.field, v)
                    else -> ConditionFactory.matchKeyword(filter.field, v.toString())
                }
                Filter.newBuilder().addMust(condition).build()
            }
            is VectorFilter.In -> {
                val values = filter.values
                val condition = if (values.all { it is String }) {
                    ConditionFactory.matchKeywords(filter.field, values.map { it as String })
                } else {
                    ConditionFactory.matchValues(filter.field, values.map { (it as Number).toLong() })
                }
                Filter.newBuilder().addMust(condition).build()
            }
            is VectorFilter.And -> {
                val builder = Filter.newBuilder()
                filter.filters.forEach { sub ->
                    builder.addMust(Condition.newBuilder().setFilter(toQdrantFilter(sub)).build())
                }
                builder.build()
            }
            is VectorFilter.Or -> {
                val builder = Filter.newBuilder()
                filter.filters.forEach { sub ->
                    builder.addShould(Condition.newBuilder().setFilter(toQdrantFilter(sub)).build())
                }
                builder.build()
            }
            is VectorFilter.Not -> {
                Filter.newBuilder()
                    .addMustNot(Condition.newBuilder().setFilter(toQdrantFilter(filter.filter)).build())
                    .build()
            }
        }
    }
}
