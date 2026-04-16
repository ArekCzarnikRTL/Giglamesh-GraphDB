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

        val checksums = files.map { (name, data) ->
            "${sha256(data)}  $name"
        }.joinToString("\n")
        files["checksums.sha256"] = checksums.toByteArray(Charsets.UTF_8)

        val overallChecksum = sha256(checksums.toByteArray(Charsets.UTF_8))
        val finalManifest = manifest.copy(checksum = overallChecksum)
        files["manifest.json"] = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(finalManifest)

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
            mapper.writeValueAsString(mapOf(
                "id" to point.id,
                "vector" to point.vector.toList(),
                "payload" to point.payload.toMap()
            ))
        }.toByteArray(Charsets.UTF_8)
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
