package com.agentwork.graphmesh.extraction.ontology

import com.agentwork.graphmesh.librarian.DocumentNotFoundException
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class OntologyGuidedExtractorConsumer(
    private val extractorService: OntologyGuidedExtractorService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["graphmesh.chunk.created"], groupId = "graphmesh-ontology-extractor")
    fun handle(record: ConsumerRecord<String, GenericRecord>) {
        val value = record.value()
        val chunkId = value["chunkId"].toString()
        val collectionId = value["collectionId"].toString()

        logger.info("Chunk received for ontology-guided extraction: chunkId={}", chunkId)

        try {
            val result = extractorService.extract(chunkId, collectionId)
            logger.info(
                "Ontology extraction complete: chunkId={}, mode={}, entities={}, relationships={}, attributes={}, failures={}",
                chunkId, result.mode, result.entitiesExtracted, result.relationshipsExtracted,
                result.attributesExtracted, result.validationFailures
            )
        } catch (e: DocumentNotFoundException) {
            logger.info("Skip ontology extraction for chunk {}: deleted before processing", chunkId)
        } catch (e: Exception) {
            logger.error("Ontology extraction failed for chunk {}", chunkId, e)
        }
    }
}
