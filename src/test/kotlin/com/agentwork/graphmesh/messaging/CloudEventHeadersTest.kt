package com.agentwork.graphmesh.messaging

import org.apache.kafka.common.header.internals.RecordHeaders
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CloudEventHeadersTest {

    @Test
    fun `build produces all six MUST headers`() {
        val headers = CloudEventHeaders.build(
            source = "graphmesh/document-service",
            type = "graphmesh.document.ingested.v1"
        )

        assertNotNull(headers[CloudEventHeaders.ID])
        assertTrue(runCatching { UUID.fromString(headers[CloudEventHeaders.ID]) }.isSuccess)
        assertEquals("graphmesh/document-service", headers[CloudEventHeaders.SOURCE])
        assertEquals("1.0", headers[CloudEventHeaders.SPEC_VERSION])
        assertEquals("graphmesh.document.ingested.v1", headers[CloudEventHeaders.TYPE])
        assertNotNull(headers[CloudEventHeaders.TIME])
        assertTrue(runCatching { Instant.parse(headers[CloudEventHeaders.TIME]) }.isSuccess)
        assertNotNull(headers[CloudEventHeaders.TRACEPARENT])
    }

    @Test
    fun `build includes optional headers when provided`() {
        val headers = CloudEventHeaders.build(
            source = "graphmesh/document-service",
            type = "graphmesh.document.ingested.v1",
            subject = "doc-123",
            correlationId = "corr-456",
            causationId = "cause-789"
        )

        assertEquals("doc-123", headers[CloudEventHeaders.SUBJECT])
        assertEquals("corr-456", headers[CloudEventHeaders.CORRELATION_ID])
        assertEquals("cause-789", headers[CloudEventHeaders.CAUSATION_ID])
        assertEquals("application/avro", headers[CloudEventHeaders.CONTENT_TYPE])
    }

    @Test
    fun `build omits optional headers when not provided`() {
        val headers = CloudEventHeaders.build(
            source = "graphmesh/document-service",
            type = "graphmesh.document.ingested.v1"
        )

        assertTrue(CloudEventHeaders.SUBJECT !in headers)
        assertTrue(CloudEventHeaders.CORRELATION_ID !in headers)
        assertTrue(CloudEventHeaders.CAUSATION_ID !in headers)
    }

    @Test
    fun `extract reads ce_ headers from Kafka Headers`() {
        val kafkaHeaders = RecordHeaders()
        kafkaHeaders.add("ce_id", "test-id".toByteArray())
        kafkaHeaders.add("ce_source", "test-source".toByteArray())
        kafkaHeaders.add("ce_specversion", "1.0".toByteArray())
        kafkaHeaders.add("ce_type", "test.type.v1".toByteArray())
        kafkaHeaders.add("ce_time", "2026-04-04T12:00:00Z".toByteArray())
        kafkaHeaders.add("ce_traceparent", "00-abc-def-01".toByteArray())
        kafkaHeaders.add("content-type", "application/avro".toByteArray())

        val extracted = CloudEventHeaders.extract(kafkaHeaders)

        assertEquals("test-id", extracted[CloudEventHeaders.ID])
        assertEquals("test-source", extracted[CloudEventHeaders.SOURCE])
        assertEquals("1.0", extracted[CloudEventHeaders.SPEC_VERSION])
        assertEquals("test.type.v1", extracted[CloudEventHeaders.TYPE])
        assertEquals("2026-04-04T12:00:00Z", extracted[CloudEventHeaders.TIME])
        assertEquals("00-abc-def-01", extracted[CloudEventHeaders.TRACEPARENT])
        assertEquals("application/avro", extracted[CloudEventHeaders.CONTENT_TYPE])
    }
}
