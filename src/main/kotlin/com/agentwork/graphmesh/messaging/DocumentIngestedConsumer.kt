package com.agentwork.graphmesh.messaging

import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class DocumentIngestedConsumer {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.document.ingested"], groupId = "graphmesh")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val ceHeaders = CloudEventHeaders.extract(record.headers())
        val documentId = record.value()["documentId"].toString()
        val fileName = record.value()["fileName"].toString()

        logger.info(
            "Document ingested: documentId={}, fileName={}, eventType={}",
            documentId, fileName, ceHeaders[CloudEventHeaders.TYPE]
        )
    }
}
