package com.agentwork.graphmesh.extraction.decoder

import com.agentwork.graphmesh.messaging.CloudEventHeaders
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class PdfDecoderConsumer(
    private val pdfDecoderService: PdfDecoderService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.document.ingested"], groupId = "graphmesh-pdf-decoder")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val documentId = value["documentId"].toString()
        val mimeType = value["mimeType"].toString()

        if (mimeType != "application/pdf") {
            logger.debug("Skipping non-PDF document: id={}, mimeType={}", documentId, mimeType)
            return
        }

        logger.info("PDF document received for decoding: id={}", documentId)

        try {
            pdfDecoderService.decode(documentId)
        } catch (e: Exception) {
            logger.error("PDF decoding failed for document {}", documentId, e)
        }
    }
}
