package com.agentwork.graphmesh.extraction.decoder

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownSplitterTest {

    private val splitter = MarkdownSplitter()

    @Test
    fun `empty text produces no pages`() {
        assertEquals(emptyList(), splitter.split(""))
    }

    @Test
    fun `text without headings is a single page`() {
        val text = "Just some plain paragraph text.\nNo headings here."
        assertEquals(listOf(text), splitter.split(text))
    }

    @Test
    fun `splits at level-1 headings`() {
        val text = """
            # First
            Content A.
            # Second
            Content B.
        """.trimIndent()
        val pages = splitter.split(text)
        assertEquals(2, pages.size)
        assertEquals("# First\nContent A.", pages[0])
        assertEquals("# Second\nContent B.", pages[1])
    }

    @Test
    fun `splits at level-2 headings`() {
        val text = """
            ## Alpha
            A.
            ## Beta
            B.
        """.trimIndent()
        val pages = splitter.split(text)
        assertEquals(2, pages.size)
        assertEquals("## Alpha\nA.", pages[0])
    }

    @Test
    fun `does not split at level-3 or deeper headings`() {
        val text = """
            # Top
            Intro.
            ### Deep
            Details.
            ### Other
            More.
        """.trimIndent()
        val pages = splitter.split(text)
        assertEquals(1, pages.size)
    }

    @Test
    fun `does not split inside fenced code blocks`() {
        val text = """
            # Real heading
            Intro.
            ```
            # not a heading inside code
            ```
            After code.
        """.trimIndent()
        val pages = splitter.split(text)
        assertEquals(1, pages.size)
    }

    @Test
    fun `appends pages shorter than 50 chars to previous page`() {
        val text = """
            # Long section
            This is a long section with plenty of content to fill at least fifty characters easily.
            # A
            # Another long section with enough content to be its own page without any issue at all.
        """.trimIndent()
        val pages = splitter.split(text)
        assertEquals(2, pages.size)
    }

    @Test
    fun `leading content before first heading forms its own page`() {
        val text = """
            Some intro without heading.

            # First
            Body.
        """.trimIndent()
        val pages = splitter.split(text)
        assertEquals(2, pages.size)
        assertEquals("Some intro without heading.", pages[0].trim())
    }
}
