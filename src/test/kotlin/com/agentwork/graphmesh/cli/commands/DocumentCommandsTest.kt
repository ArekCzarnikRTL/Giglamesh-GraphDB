package com.agentwork.graphmesh.cli.commands

import com.agentwork.graphmesh.cli.CliConfig
import com.agentwork.graphmesh.cli.FakeGateway
import com.agentwork.graphmesh.cli.GraphMeshCli
import com.agentwork.graphmesh.cli.OutputFormat
import com.agentwork.graphmesh.cli.generated.GetDocument
import com.agentwork.graphmesh.cli.generated.ListDocuments
import com.agentwork.graphmesh.cli.generated.UploadDocument
import com.agentwork.graphmesh.cli.generated.enums.DocumentState
import com.agentwork.graphmesh.cli.generated.enums.DocumentType
import com.agentwork.graphmesh.cli.generated.getdocument.Document as GetDocumentResult
import com.agentwork.graphmesh.cli.generated.getdocument.KeyValue as GetDocumentKeyValue
import com.agentwork.graphmesh.cli.generated.listdocuments.Document as ListedDocument
import com.agentwork.graphmesh.cli.generated.uploaddocument.Document as UploadedDocument
import com.agentwork.graphmesh.cli.generated.uploaddocument.KeyValue as UploadKeyValue
import com.github.ajalt.clikt.command.test
import com.github.ajalt.clikt.core.subcommands
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentCommandsTest {

    private fun cliWith(fake: FakeGateway, format: OutputFormat = OutputFormat.TABLE): GraphMeshCli {
        val root = GraphMeshCli()
        root.setTestConfig(
            CliConfig(
                endpoint = "http://test",
                token = "",
                format = format,
                gatewayFactory = { fake }
            )
        )
        root.subcommands(DocumentCommand())
        return root
    }

    @Test
    fun `document upload sends base64-encoded file content`(@TempDir tempDir: File) = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val tmpFile = File(tempDir, "test.bin")
        tmpFile.writeBytes(bytes)
        val expectedBase64 = Base64.getEncoder().encodeToString(bytes)

        var capturedInput: com.agentwork.graphmesh.cli.generated.inputs.UploadDocumentInput? = null
        val fake = FakeGateway.builder()
            .on(UploadDocument::class) { req ->
                capturedInput = req.variables.input
                UploadDocument.Result(
                    uploadDocument = UploadedDocument(
                        id = "doc-1",
                        collectionId = "col-1",
                        title = "test.bin",
                        mimeType = "application/octet-stream",
                        type = DocumentType.SOURCE,
                        state = DocumentState.UPLOADED,
                        parentId = null,
                        createdAt = "2026-01-01T00:00:00Z",
                        metadata = listOf()
                    )
                )
            }
            .build()

        val result = cliWith(fake).test("document upload -c col-1 --file ${tmpFile.absolutePath}")

        assertEquals(0, result.statusCode, "Non-zero exit: ${result.stderr}")
        assertEquals(expectedBase64, capturedInput?.content, "Base64 content mismatch")
        assertTrue(result.stdout.contains("doc-1"), "Expected 'doc-1' in stdout: ${result.stdout}")
    }

    @Test
    fun `document list renders table with document title`() = runBlocking {
        val fake = FakeGateway.builder()
            .on(ListDocuments::class) { _ ->
                ListDocuments.Result(
                    documents = listOf(
                        ListedDocument(
                            id = "doc-42",
                            title = "MyReport",
                            mimeType = "application/pdf",
                            type = DocumentType.SOURCE,
                            state = DocumentState.EXTRACTED,
                            createdAt = "2026-01-01T00:00:00Z"
                        )
                    )
                )
            }
            .build()

        val result = cliWith(fake).test("document list -c col-1")

        assertEquals(0, result.statusCode, "Non-zero exit: ${result.stderr}")
        assertTrue(result.stdout.contains("MyReport"), "Expected 'MyReport' in stdout: ${result.stdout}")
    }

    @Test
    fun `document info shows document fields`() = runBlocking {
        val fake = FakeGateway.builder()
            .on(GetDocument::class) { _ ->
                GetDocument.Result(
                    document = GetDocumentResult(
                        id = "doc-99",
                        collectionId = "col-1",
                        title = "Design Spec",
                        mimeType = "application/pdf",
                        type = DocumentType.SOURCE,
                        state = DocumentState.EXTRACTED,
                        parentId = null,
                        createdAt = "2026-01-01T00:00:00Z",
                        metadata = listOf(GetDocumentKeyValue(key = "author", value = "Alice"))
                    )
                )
            }
            .build()

        val result = cliWith(fake).test("document info doc-99")

        assertEquals(0, result.statusCode, "Non-zero exit: ${result.stderr}")
        assertTrue(result.stdout.contains("Design Spec"), "Expected 'Design Spec' in stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("doc-99"), "Expected 'doc-99' in stdout: ${result.stdout}")
    }
}
