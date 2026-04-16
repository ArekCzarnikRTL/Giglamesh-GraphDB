package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.storage.vector.VectorPayload
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
            val rawPayload = (map["payload"] as? Map<String, Any>) ?: emptyMap()
            VectorPoint(id = id, vector = vector, payload = VectorPayload.fromMap(rawPayload))
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
