package com.agentwork.graphmesh.extraction.structured

import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class StructuredDataExtractorConsumer(
    private val extractorService: StructuredDataExtractorService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.chunk.created"], groupId = "graphmesh-structured-extractor")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val chunkId = value["chunkId"].toString()
        val collectionId = value["collectionId"].toString()

        logger.info("Chunk received for structured data extraction: chunkId={}", chunkId)

        try {
            val result = extractorService.extract(chunkId, collectionId)
            logger.info(
                "Structured data extraction complete: chunkId={}, tableDetected={}, schema={}, rows={}",
                chunkId, result.tableDetected, result.schemaName, result.rowsExtracted
            )
        } catch (e: Exception) {
            logger.error("Structured data extraction failed for chunk {}", chunkId, e)
        }
    }
}
