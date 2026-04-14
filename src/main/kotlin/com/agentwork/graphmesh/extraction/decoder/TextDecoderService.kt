package com.agentwork.graphmesh.extraction.decoder

import com.agentwork.graphmesh.librarian.DocumentState
import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.librarian.LibrarianService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class TextDecoderService(
    private val librarianService: LibrarianService,
    private val pageExtractedProducer: PageExtractedProducer,
    private val markdownSplitter: MarkdownSplitter
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun decode(documentId: String) {
        librarianService.updateState(documentId, DocumentState.PROCESSING)

        try {
            val content = librarianService.getContent(documentId)
            val doc = librarianService.findById(documentId)
                ?: throw TextDecodingException(documentId, IllegalStateException("Document not found"))

            val text = String(content, Charsets.UTF_8)
            val pages = markdownSplitter.split(text)

            pages.forEachIndexed { index, pageText ->
                if (pageText.isBlank()) return@forEachIndexed
                val pageDoc = librarianService.createChildDocument(
                    parentId = documentId,
                    type = DocumentType.PAGE,
                    title = "Seite ${index + 1}",
                    content = pageText.toByteArray(Charsets.UTF_8),
                    mimeType = "text/plain"
                )
                pageExtractedProducer.send(
                    PageExtractedEvent(
                        documentId = pageDoc.id,
                        parentDocumentId = documentId,
                        collectionId = doc.collectionId,
                        pageNumber = index + 1,
                        charCount = pageText.length
                    )
                )
            }

            librarianService.updateState(documentId, DocumentState.EXTRACTED)
            logger.info("Text document decoded: documentId={}, pages={}", documentId, pages.size)

        } catch (e: TextDecodingException) {
            throw e
        } catch (e: Exception) {
            librarianService.updateState(documentId, DocumentState.FAILED)
            throw TextDecodingException(documentId, e)
        }
    }
}

class TextDecodingException(
    documentId: String,
    cause: Throwable
) : RuntimeException("Text decoding failed for document '$documentId': ${cause.message}", cause)
