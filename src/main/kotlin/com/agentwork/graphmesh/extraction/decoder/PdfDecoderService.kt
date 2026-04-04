package com.agentwork.graphmesh.extraction.decoder

import com.agentwork.graphmesh.librarian.DocumentState
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.librarian.LibrarianService
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class PdfDecoderService(
    private val librarianService: LibrarianService,
    private val pageExtractedProducer: PageExtractedProducer
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun decode(documentId: String) {
        librarianService.updateState(documentId, DocumentState.PROCESSING)

        try {
            val pdfContent = librarianService.getContent(documentId)
            val doc = librarianService.findById(documentId)
                ?: throw PdfDecodingException(documentId, IllegalStateException("Document not found"))

            val pages = extractPages(pdfContent)

            for (result in pages) {
                val pageDoc = librarianService.createChildDocument(
                    parentId = documentId,
                    type = DocumentType.PAGE,
                    title = "Page ${result.pageNumber}",
                    content = result.text.toByteArray(Charsets.UTF_8),
                    mimeType = "text/plain"
                )

                pageExtractedProducer.send(
                    PageExtractedEvent(
                        documentId = pageDoc.id,
                        parentDocumentId = documentId,
                        collectionId = doc.collectionId,
                        pageNumber = result.pageNumber,
                        charCount = result.text.length
                    )
                )
            }

            librarianService.updateState(documentId, DocumentState.EXTRACTED)
            logger.info("PDF decoded: documentId={}, pages={}", documentId, pages.size)

        } catch (e: PdfDecodingException) {
            throw e
        } catch (e: Exception) {
            librarianService.updateState(documentId, DocumentState.FAILED)
            throw PdfDecodingException(documentId, e)
        }
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
}
