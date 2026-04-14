package com.agentwork.graphmesh.extraction.decoder

import org.springframework.stereotype.Component

@Component
class MarkdownSplitter {

    companion object {
        private const val MIN_PAGE_LENGTH = 50
        private val HEADING_REGEX = Regex("^(#{1,2})\\s+.+$")
    }

    fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val lines = text.lines()
        val pages = mutableListOf<StringBuilder>()
        var current = StringBuilder()
        var inCodeBlock = false

        for (line in lines) {
            if (line.trimStart().startsWith("```")) inCodeBlock = !inCodeBlock

            val isHeading = !inCodeBlock && HEADING_REGEX.matches(line)

            if (isHeading && current.isNotEmpty()) {
                pages.add(current)
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        if (current.isNotEmpty()) pages.add(current)

        // Merge pages shorter than MIN_PAGE_LENGTH into the previous page.
        // A page is only eligible for merging if it has no body content beyond its heading line.
        val merged = mutableListOf<StringBuilder>()
        for (page in pages) {
            val trimmed = page.toString().trim()
            val hasBodyContent = trimmed.lines().size > 1 ||
                !HEADING_REGEX.matches(trimmed.lines().first())
            if (merged.isNotEmpty() && trimmed.length < MIN_PAGE_LENGTH && !hasBodyContent) {
                merged.last().append('\n').append(trimmed)
            } else {
                merged.add(StringBuilder(trimmed))
            }
        }

        return merged.map { it.toString() }
    }
}
