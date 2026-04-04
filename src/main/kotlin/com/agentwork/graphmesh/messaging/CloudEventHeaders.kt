package com.agentwork.graphmesh.messaging

import org.apache.kafka.common.header.Headers
import java.time.Instant
import java.util.UUID

object CloudEventHeaders {
    const val ID = "ce_id"
    const val SOURCE = "ce_source"
    const val SPEC_VERSION = "ce_specversion"
    const val TYPE = "ce_type"
    const val TIME = "ce_time"
    const val TRACEPARENT = "ce_traceparent"
    const val CORRELATION_ID = "ce_correlationid"
    const val CAUSATION_ID = "ce_causationid"
    const val CONTENT_TYPE = "content-type"
    const val SUBJECT = "ce_subject"

    private val CE_HEADERS = setOf(
        ID, SOURCE, SPEC_VERSION, TYPE, TIME, TRACEPARENT,
        CORRELATION_ID, CAUSATION_ID, CONTENT_TYPE, SUBJECT
    )

    fun build(
        source: String,
        type: String,
        subject: String? = null,
        correlationId: String? = null,
        causationId: String? = null,
    ): Map<String, String> {
        val headers = mutableMapOf(
            ID to UUID.randomUUID().toString(),
            SOURCE to source,
            SPEC_VERSION to "1.0",
            TYPE to type,
            TIME to Instant.now().toString(),
            TRACEPARENT to generateTraceparent(),
            CONTENT_TYPE to "application/avro",
        )
        subject?.let { headers[SUBJECT] = it }
        correlationId?.let { headers[CORRELATION_ID] = it }
        causationId?.let { headers[CAUSATION_ID] = it }
        return headers
    }

    fun extract(headers: Headers): Map<String, String> =
        headers
            .filter { it.key() in CE_HEADERS }
            .associate { it.key() to String(it.value()) }

    private fun generateTraceparent(): String {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        return "00-$traceId-$spanId-01"
    }
}
