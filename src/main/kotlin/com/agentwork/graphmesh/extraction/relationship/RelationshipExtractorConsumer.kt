package com.agentwork.graphmesh.extraction.relationship

import com.agentwork.graphmesh.librarian.DocumentNotFoundException
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class RelationshipExtractorConsumer(
    private val extractorService: RelationshipExtractorService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.chunk.created"], groupId = "graphmesh-relationship-extractor")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val chunkId = value["chunkId"].toString()
        val collectionId = value["collectionId"].toString()

        logger.info("Chunk received for relationship extraction: chunkId={}", chunkId)

        try {
            val result = extractorService.extract(chunkId, collectionId)
            logger.info(
                "Relationship extraction complete: chunkId={}, triples={}, entities={}",
                chunkId, result.triplesExtracted, result.entitiesFound
            )
        } catch (e: DocumentNotFoundException) {
            logger.info("Skip relationship extraction for chunk {}: deleted before processing", chunkId)
        } catch (e: Exception) {
            logger.error("Relationship extraction failed for chunk {}", chunkId, e)
        }
    }
}
