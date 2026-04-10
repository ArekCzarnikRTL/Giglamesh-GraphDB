package com.agentwork.graphmesh.rdfimport

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals

class RdfImportControllerTest {

    private val service = mockk<RdfImportService>()
    private val controller = RdfImportController(service)

    @Test
    fun `importRdf decodes base64 and delegates to service`() {
        val turtleContent = "@prefix ex: <http://example.org/> .\nex:A ex:knows ex:B ."
        val encoded = Base64.getEncoder().encodeToString(turtleContent.toByteArray())

        val contentSlot = slot<String>()
        every {
            service.importRdf("coll-1", capture(contentSlot), RdfFormat.TURTLE, null, false)
        } returns RdfImportService.ImportResult(tripleCount = 1, skippedCount = 0, durationMs = 42, embeddingsGenerated = 0)

        val input = ImportRdfInput(
            collectionId = "coll-1",
            content = encoded,
            format = RdfFormat.TURTLE,
        )
        val result = controller.importRdf(input)

        assertEquals(turtleContent, contentSlot.captured)
        assertEquals(1, result.tripleCount)
        assertEquals(0, result.skippedCount)
        assertEquals(42, result.durationMs)
    }

    @Test
    fun `importRdf passes dataset to service`() {
        val content = "<http://a.org/A> <http://a.org/p> <http://a.org/B> ."
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray())

        every {
            service.importRdf("coll-2", any(), RdfFormat.NTRIPLES, "my-dataset", false)
        } returns RdfImportService.ImportResult(tripleCount = 1, skippedCount = 0, durationMs = 10, embeddingsGenerated = 0)

        val input = ImportRdfInput(
            collectionId = "coll-2",
            content = encoded,
            format = RdfFormat.NTRIPLES,
            dataset = "my-dataset",
        )
        val result = controller.importRdf(input)

        assertEquals(1, result.tripleCount)
    }
}
