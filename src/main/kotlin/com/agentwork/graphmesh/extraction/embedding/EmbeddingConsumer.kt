package com.agentwork.graphmesh.extraction.embedding

import com.agentwork.graphmesh.librarian.DocumentNotFoundException
import com.agentwork.graphmesh.librarian.LibrarianService
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class EmbeddingConsumer(
    private val embeddingService: EmbeddingService,
    private val librarianService: LibrarianService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.chunk.created"], groupId = "graphmesh-embedding")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val chunkId = value["chunkId"].toString()
        val documentId = value["documentId"].toString()
        val collectionId = value["collectionId"].toString()

        logger.info("Chunk received for embedding: chunkId={}", chunkId)

        try {
            // Resolve collection name from the document's parent
            val doc = librarianService.findById(documentId)
            val collectionName = doc?.collectionId ?: collectionId

            embeddingService.embed(chunkId, documentId, collectionName)
        } catch (e: DocumentNotFoundException) {
            logger.info("Skip embedding for chunk {}: deleted before processing", chunkId)
        } catch (e: Exception) {
            logger.error("Embedding failed for chunk {}", chunkId, e)
        }
    }
}
