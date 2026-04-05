package com.agentwork.graphmesh.extraction.structured

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test

class StructuredDataExtractorConsumerTest {

    @Test
    fun `handle delegates to extractor service`() {
        val extractorService = mockk<StructuredDataExtractorService>(relaxed = true)
        val consumer = StructuredDataExtractorConsumer(extractorService)

        val genericRecord = mockk<GenericRecord>()
        every { genericRecord["chunkId"] } returns "chunk-1"
        every { genericRecord["collectionId"] } returns "col-1"

        val record = ConsumerRecord<String, GenericRecord>("graphmesh.chunk.created", 0, 0L, "key", genericRecord)
        consumer.handle(record)

        verify { extractorService.extract("chunk-1", "col-1") }
    }

    @Test
    fun `handle catches and logs exceptions`() {
        val extractorService = mockk<StructuredDataExtractorService>()
        val consumer = StructuredDataExtractorConsumer(extractorService)

        every { extractorService.extract(any(), any()) } throws RuntimeException("LLM timeout")

        val genericRecord = mockk<GenericRecord>()
        every { genericRecord["chunkId"] } returns "chunk-fail"
        every { genericRecord["collectionId"] } returns "col-1"

        val record = ConsumerRecord<String, GenericRecord>("graphmesh.chunk.created", 0, 0L, "key", genericRecord)

        // Should not throw — exception is caught internally
        consumer.handle(record)
    }
}
