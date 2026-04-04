package com.agentwork.graphmesh.extraction.chunker

import com.agentwork.graphmesh.messaging.CloudEventHeaders
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class ChunkerConsumer(
    private val chunkerService: ChunkerService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.page.extracted"], groupId = "graphmesh-chunker")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val documentId = value["documentId"].toString()
        val collectionId = value["collectionId"].toString()

        logger.info("Page received for chunking: documentId={}", documentId)

        try {
            chunkerService.chunkDocument(documentId, collectionId)
        } catch (e: Exception) {
            logger.error("Chunking failed for document {}", documentId, e)
        }
    }
}
