package com.agentwork.graphmesh.extraction.chunker

import com.agentwork.graphmesh.librarian.DocumentType
import com.agentwork.graphmesh.librarian.LibrarianService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ChunkerService(
    private val librarianService: LibrarianService,
    private val chunkCreatedProducer: ChunkCreatedProducer,
    private val config: ChunkConfig
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun chunkDocument(documentId: String, collectionId: String) {
        val content = librarianService.getContent(documentId)
        val text = String(content, Charsets.UTF_8)

        if (text.isBlank()) {
            logger.debug("Skipping empty document: {}", documentId)
            return
        }

        val chunks = splitIntoChunks(text)

        for ((index, chunkResult) in chunks.withIndex()) {
            val chunkDoc = librarianService.createChildDocument(
                parentId = documentId,
                type = DocumentType.CHUNK,
                title = "Chunk ${index + 1}",
                content = chunkResult.text.toByteArray(Charsets.UTF_8),
                mimeType = "text/plain"
            )

            chunkCreatedProducer.send(
                ChunkCreatedEvent(
                    chunkId = chunkDoc.id,
                    documentId = documentId,
                    collectionId = collectionId,
                    chunkIndex = index,
                    charOffset = chunkResult.charOffset,
                    charLength = chunkResult.text.length
                )
            )
        }

        logger.info("Document chunked: documentId={}, chunks={}", documentId, chunks.size)
    }

    internal fun splitIntoChunks(text: String): List<ChunkResult> {
        val chunks = mutableListOf<ChunkResult>()
        val step = config.chunkSize - config.overlapSize
        var offset = 0

        while (offset < text.length) {
            val end = minOf(offset + config.chunkSize, text.length)
            val chunkText = text.substring(offset, end)

            chunks.add(ChunkResult(chunkText, offset, chunks.size))

            offset += step

            // Avoid tiny trailing chunks smaller than overlap
            if (offset < text.length && text.length - offset < config.overlapSize) {
                break
            }
        }

        // Append remaining text if not already covered
        if (offset < text.length && chunks.lastOrNull()?.let {
                it.charOffset + it.text.length < text.length
            } == true) {
            chunks.add(ChunkResult(text.substring(offset), offset, chunks.size))
        }

        return chunks
    }
}
