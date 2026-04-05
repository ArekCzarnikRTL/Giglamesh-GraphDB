package com.agentwork.graphmesh.extraction.definition

import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class DefinitionExtractorConsumer(
    private val extractorService: DefinitionExtractorService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.chunk.created"], groupId = "graphmesh-definition-extractor")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val chunkId = value["chunkId"].toString()
        val collectionId = value["collectionId"].toString()

        logger.info("Chunk received for definition extraction: chunkId={}", chunkId)

        try {
            val result = extractorService.extract(chunkId, collectionId)
            logger.info(
                "Definition extraction complete: chunkId={}, definitions={}, entities={}",
                chunkId, result.definitionsExtracted, result.entitiesFound.size
            )
        } catch (e: Exception) {
            logger.error("Definition extraction failed for chunk {}", chunkId, e)
        }
    }
}
