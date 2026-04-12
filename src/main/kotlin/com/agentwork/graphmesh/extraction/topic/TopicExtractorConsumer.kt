package com.agentwork.graphmesh.extraction.topic

import com.agentwork.graphmesh.librarian.DocumentNotFoundException
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class TopicExtractorConsumer(
    private val extractorService: TopicExtractorService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.chunk.created"], groupId = "graphmesh-topic-extractor")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val chunkId = value["chunkId"].toString()
        val collectionId = value["collectionId"].toString()

        logger.info("Chunk received for topic extraction: chunkId={}", chunkId)

        try {
            val result = extractorService.extract(chunkId, collectionId)
            logger.info(
                "Topic extraction complete: chunkId={}, topics={}",
                chunkId, result.topicsExtracted
            )
        } catch (e: DocumentNotFoundException) {
            logger.info("Skip topic extraction for chunk {}: deleted before processing", chunkId)
        } catch (e: Exception) {
            logger.error("Topic extraction failed for chunk {}", chunkId, e)
        }
    }
}
