package com.agentwork.graphmesh.api.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke test against a RUNNING GraphMesh MCP server. Verifies the Streamable HTTP
 * endpoint (`/mcp`) answers `initialize` + `tools/list` and advertises the expected tools.
 *
 * Not part of the regular test run — requires the app to be running on localhost:8083
 * with `spring.ai.mcp.server.protocol=STREAMABLE`. Enable via:
 *
 *   MCP_SMOKE=1 ./gradlew test --tests "*McpServerSmokeTest"
 */
@EnabledIfEnvironmentVariable(named = "MCP_SMOKE", matches = "1")
class McpServerSmokeTest {

    private val baseUrl = System.getenv("MCP_URL") ?: "http://localhost:8083/mcp"
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val mapper = jacksonObjectMapper()

    @Test
    fun `initialize returns graphmesh server info and exposes expected tools`() {
        val (initResponse, sessionId) = sendJsonRpc(
            body = mapOf(
                "jsonrpc" to "2.0",
                "id" to 1,
                "method" to "initialize",
                "params" to mapOf(
                    "protocolVersion" to "2024-11-05",
                    "capabilities" to emptyMap<String, Any>(),
                    "clientInfo" to mapOf("name" to "graphmesh-smoke", "version" to "1.0.0")
                )
            ),
            sessionId = null
        )

        val initResult = initResponse["result"] as Map<*, *>
        val serverInfo = initResult["serverInfo"] as Map<*, *>
        assertEquals("graphmesh", serverInfo["name"])
        assertNotNull(sessionId, "server must return Mcp-Session-Id header")

        sendJsonRpc(
            body = mapOf(
                "jsonrpc" to "2.0",
                "method" to "notifications/initialized",
                "params" to emptyMap<String, Any>()
            ),
            sessionId = sessionId,
            expectBody = false
        )

        val (toolsResponse, _) = sendJsonRpc(
            body = mapOf(
                "jsonrpc" to "2.0",
                "id" to 2,
                "method" to "tools/list",
                "params" to emptyMap<String, Any>()
            ),
            sessionId = sessionId
        )

        val tools = (toolsResponse["result"] as Map<*, *>)["tools"] as List<*>
        val names = tools.map { (it as Map<*, *>)["name"] as String }.toSet()
        assertTrue("knowledgeQuery" in names, "knowledgeQuery missing, got: $names")
        assertTrue("documentQuery" in names, "documentQuery missing, got: $names")
    }

    private fun sendJsonRpc(
        body: Map<String, Any>,
        sessionId: String?,
        expectBody: Boolean = true
    ): Pair<Map<String, Any?>, String?> {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        sessionId?.let { builder.header("Mcp-Session-Id", it) }

        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        assertTrue(
            response.statusCode() in 200..299,
            "HTTP ${response.statusCode()} from $baseUrl: ${response.body()}"
        )

        val returnedSession = response.headers().firstValue("Mcp-Session-Id").orElse(null)
        val parsed: Map<String, Any?> = if (!expectBody || response.body().isNullOrBlank()) {
            emptyMap()
        } else {
            parseJsonOrSse(response.body())
        }
        return parsed to returnedSession
    }

    private fun parseJsonOrSse(body: String): Map<String, Any?> {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) return mapper.readValue(trimmed)
        val dataLine = trimmed.lines()
            .firstOrNull { it.startsWith("data:") }
            ?.removePrefix("data:")
            ?.trim()
            ?: error("No JSON or SSE data frame in response: $trimmed")
        return mapper.readValue(dataLine)
    }
}
