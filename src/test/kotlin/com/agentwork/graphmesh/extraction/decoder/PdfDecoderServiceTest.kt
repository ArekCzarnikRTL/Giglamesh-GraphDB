package com.agentwork.graphmesh.extraction.decoder

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfDecoderServiceTest {

    @Test
    fun `extracts text from single page PDF`() {
        val pdfBytes = createTestPdf(listOf("Hello World"))
        val pages = extractPages(pdfBytes)

        assertEquals(1, pages.size)
        assertTrue(pages[0].text.contains("Hello World"))
        assertEquals(1, pages[0].pageNumber)
        assertEquals(1, pages[0].totalPages)
    }

    @Test
    fun `extracts text from multi-page PDF`() {
        val pdfBytes = createTestPdf(listOf("Page One Content", "Page Two Content", "Page Three Content"))
        val pages = extractPages(pdfBytes)

        assertEquals(3, pages.size)
        assertTrue(pages[0].text.contains("Page One Content"))
        assertTrue(pages[1].text.contains("Page Two Content"))
        assertTrue(pages[2].text.contains("Page Three Content"))
        assertEquals(1, pages[0].pageNumber)
        assertEquals(2, pages[1].pageNumber)
        assertEquals(3, pages[2].pageNumber)
        assertEquals(3, pages[0].totalPages)
    }

    @Test
    fun `skips empty pages`() {
        val pdfBytes = createTestPdf(listOf("Content", "", "More Content"))
        val pages = extractPages(pdfBytes)

        // Empty page should be skipped
        assertEquals(2, pages.size)
        assertTrue(pages[0].text.contains("Content"))
        assertTrue(pages[1].text.contains("More Content"))
    }

    @Test
    fun `handles PDF with no extractable text`() {
        // Create a PDF with just a blank page (no content stream)
        val doc = PDDocument()
        doc.addPage(PDPage())
        val baos = ByteArrayOutputStream()
        doc.save(baos)
        doc.close()

        val pages = extractPages(baos.toByteArray())
        assertEquals(0, pages.size)
    }

    private fun extractPages(pdfContent: ByteArray): List<PageExtractionResult> {
        val document = Loader.loadPDF(RandomAccessReadBuffer(pdfContent))
        val results = mutableListOf<PageExtractionResult>()

        try {
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            val totalPages = document.numberOfPages

            for (pageNum in 1..totalPages) {
                stripper.startPage = pageNum
                stripper.endPage = pageNum
                val text = stripper.getText(document).trim()

                if (text.isNotEmpty()) {
                    results.add(PageExtractionResult(pageNum, text, totalPages))
                }
            }
        } finally {
            document.close()
        }

        return results
    }

    private fun createTestPdf(pageTexts: List<String>): ByteArray {
        val doc = PDDocument()

        for (text in pageTexts) {
            val page = PDPage()
            doc.addPage(page)

            if (text.isNotEmpty()) {
                val contentStream = PDPageContentStream(doc, page)
                contentStream.beginText()
                contentStream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                contentStream.newLineAtOffset(50f, 700f)
                contentStream.showText(text)
                contentStream.endText()
                contentStream.close()
            }
        }

        val baos = ByteArrayOutputStream()
        doc.save(baos)
        doc.close()
        return baos.toByteArray()
    }
}
