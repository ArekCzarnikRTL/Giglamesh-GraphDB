package com.agentwork.graphmesh.extraction.decoder

import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class TextDecoderConsumer(
    private val textDecoderService: TextDecoderService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.document.ingested"], groupId = "graphmesh-text-decoder")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val documentId = value["documentId"].toString()
        val mimeType = value["mimeType"].toString()

        if (mimeType != "text/markdown" && mimeType != "text/plain") {
            logger.debug("Skipping non-text document: id={}, mimeType={}", documentId, mimeType)
            return
        }

        logger.info("Text document received for decoding: id={}, mimeType={}", documentId, mimeType)

        try {
            textDecoderService.decode(documentId)
        } catch (e: Exception) {
            logger.error("Text decoding failed for document {}", documentId, e)
        }
    }
}
