package com.agentwork.graphmesh.extraction.agent

import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class AgentExtractorConsumer(
    private val extractorService: AgentExtractorService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.chunk.created"], groupId = "graphmesh-agent-extractor")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val chunkId = value["chunkId"].toString()
        val collectionId = value["collectionId"].toString()

        logger.info("Chunk received for agent extraction: chunkId={}", chunkId)

        try {
            val result = extractorService.extract(chunkId, collectionId)
            logger.info(
                "Agent extraction complete: chunkId={}, items={}, strategy={}",
                chunkId, result.extractedItems.size, result.strategy
            )
        } catch (e: Exception) {
            logger.error("Agent extraction failed for chunk {}", chunkId, e)
        }
    }
}
