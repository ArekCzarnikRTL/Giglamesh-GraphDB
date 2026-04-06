package com.agentwork.graphmesh.cli.output

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableOutputTest {

    private val lines = mutableListOf<String>()
    private val out = TableOutput(sink = { lines += it })

    // --- Task 2.5: List rendering ---

    @Test
    fun `writeCollections renders an ASCII table with aligned columns`() {
        out.writeCollections(listOf(
            CollectionView("c1", "Alpha", "desc", listOf("x"), "2026-01-01T00:00:00Z"),
            CollectionView("c22", "Beta",  null,   listOf(),    "2026-01-02T00:00:00Z")
        ))

        val text = lines.joinToString("\n")
        assertTrue(text.contains("ID"), "Header present")
        assertTrue(text.contains("Alpha"))
        assertTrue(text.contains("Beta"))
        assertTrue(text.lines().any { it.all { ch -> ch == '-' || ch == '+' || ch == ' ' } && it.isNotBlank() })
    }

    @Test
    fun `writeCollections prints placeholder when list is empty`() {
        out.writeCollections(emptyList())
        assertEquals(1, lines.size)
        assertEquals("(no collections)", lines.single())
    }

    @Test
    fun `writeDocuments truncates long titles with ellipsis`() {
        val longTitle = "a".repeat(80)
        out.writeDocuments(listOf(
            DocumentView("d1", longTitle, "application/pdf", "SOURCE", "UPLOADED", "2026-01-01T00:00:00Z")
        ))

        val text = lines.joinToString("\n")
        assertTrue(text.contains("…"), "Truncation marker is present: $text")
        assertTrue(!text.contains("a".repeat(80)), "Full 80-char title should not appear")
    }

    // --- Task 2.6: Key/value blocks ---

    @Test
    fun `writeDocumentInfo renders key-value block`() {
        lines.clear()
        out.writeDocumentInfo(DocumentInfoView(
            id = "d1", collectionId = "c1", title = "Paper", mimeType = "application/pdf",
            type = "SOURCE", state = "EXTRACTED", parentId = null,
            createdAt = "2026-01-01T00:00:00Z",
            metadata = listOf("author" to "Alice")
        ))
        val text = lines.joinToString("\n")
        assertTrue(text.contains("ID"))
        assertTrue(text.contains("d1"))
        assertTrue(text.contains("author"))
    }

    @Test
    fun `writeGraphRag renders answer and selected edges`() {
        lines.clear()
        out.writeGraphRag(GraphRagResponseView(
            sessionId = "s1",
            answer = "The answer.",
            retrievedEdgeCount = 10,
            durationMs = 200,
            selectedEdges = listOf(SelectedEdgeView("S", "P", "O", "why", 0.7))
        ))
        val text = lines.joinToString("\n")
        assertTrue(text.contains("The answer."))
        assertTrue(text.contains("Retrieved edges: 10"))
        assertTrue(text.contains("(S, P, O)"))
    }

    @Test
    fun `writeExplanationChain prints section headers and truncates long answers`() {
        lines.clear()
        val chain = ExplanationChainView(
            question = QuestionExplanationView("q-1", "What is X?", "2026-01-01T00:00:00Z", "GRAPH_RAG"),
            mechanism = "GRAPH_RAG",
            exploration = ExplorationView("e-1", 42),
            focus = FocusView("f-1", listOf(FocusEdgeView("A", "B", "C", "reason"))),
            analyses = listOf(AnalysisView("a-1", 0, "think", "act", listOf("k" to "v"), "obs")),
            synthesis = null,
            conclusion = ConclusionView("c-1", "a".repeat(200))
        )
        out.writeExplanationChain(chain, maxAnswerChars = 50)
        val text = lines.joinToString("\n")
        assertTrue(text.contains("--- Question ---"))
        assertTrue(text.contains("What is X?"))
        assertTrue(text.contains("--- Exploration ---"))
        assertTrue(text.contains("42"))
        assertTrue(text.contains("--- Focus ---"))
        assertTrue(text.contains("(A, B, C)"))
        assertTrue(text.contains("--- Analysis 1 ---"))
        assertTrue(text.contains("--- Conclusion ---"))
        assertTrue(text.contains("[truncated]"))
    }

    // --- Task 2.7: Tree rendering ---

    @Test
    fun `writeDocumentHierarchy renders ASCII tree`() {
        lines.clear()
        val root = DocumentNodeView("r", "Root", "SOURCE", listOf(
            DocumentNodeView("r/a", "Child A", "PAGE", listOf(
                DocumentNodeView("r/a/1", "Chunk 1", "CHUNK", emptyList())
            )),
            DocumentNodeView("r/b", "Child B", "PAGE", emptyList())
        ))
        out.writeDocumentHierarchy(root)
        val text = lines.joinToString("\n")
        assertTrue(text.contains("Root"))
        assertTrue(text.contains("├─"))
        assertTrue(text.contains("└─"))
        assertTrue(text.contains("Chunk 1"))
    }
}
