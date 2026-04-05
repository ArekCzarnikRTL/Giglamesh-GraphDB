package com.agentwork.graphmesh.extraction.agent

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test

class AgentExtractorConsumerTest {

    @Test
    fun `handle delegates to extractor service`() {
        val extractorService = mockk<AgentExtractorService>(relaxed = true)
        val consumer = AgentExtractorConsumer(extractorService)

        val genericRecord = mockk<GenericRecord>()
        every { genericRecord["chunkId"] } returns "chunk-1"
        every { genericRecord["collectionId"] } returns "col-1"

        val record = ConsumerRecord<String, GenericRecord>("graphmesh.chunk.created", 0, 0L, "key", genericRecord)
        consumer.handle(record)

        verify { extractorService.extract("chunk-1", "col-1") }
    }

    @Test
    fun `handle catches and logs exceptions`() {
        val extractorService = mockk<AgentExtractorService>()
        val consumer = AgentExtractorConsumer(extractorService)

        every { extractorService.extract(any(), any()) } throws RuntimeException("Agent timeout")

        val genericRecord = mockk<GenericRecord>()
        every { genericRecord["chunkId"] } returns "chunk-fail"
        every { genericRecord["collectionId"] } returns "col-1"

        val record = ConsumerRecord<String, GenericRecord>("graphmesh.chunk.created", 0, 0L, "key", genericRecord)
        consumer.handle(record)
    }
}
