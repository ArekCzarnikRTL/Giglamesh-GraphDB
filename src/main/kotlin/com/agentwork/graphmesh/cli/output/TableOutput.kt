package com.agentwork.graphmesh.cli.output

/**
 * Human-readable ASCII formatter. Renders lists as bordered tables with
 * per-column auto-width (truncated at [maxColWidth]), single objects as
 * key/value blocks, and hierarchies as ASCII trees.
 */
class TableOutput(
    private val sink: (String) -> Unit,
    private val maxColWidth: Int = 60
) : Output {

    // -- List rendering --

    override fun writeCollections(items: List<CollectionView>) {
        if (items.isEmpty()) { sink("(no collections)"); return }
        renderTable(
            headers = listOf("ID", "Name", "Tags", "Created"),
            rows = items.map { listOf(it.id, it.name, it.tags.joinToString(","), it.createdAt) }
        )
    }

    override fun writeDocuments(items: List<DocumentView>) {
        if (items.isEmpty()) { sink("(no documents)"); return }
        renderTable(
            headers = listOf("ID", "Title", "MIME", "Type", "State", "Created"),
            rows = items.map { listOf(it.id, it.title, it.mimeType, it.type, it.state, it.createdAt) }
        )
    }

    override fun writeConfigEntries(items: List<ConfigEntryView>) {
        if (items.isEmpty()) { sink("(no config entries)"); return }
        renderTable(
            headers = listOf("Type", "Key", "Value", "Version"),
            rows = items.map { listOf(it.type, it.key, it.value, it.version.toString()) }
        )
    }

    override fun writeExplanationSessions(items: List<QuestionExplanationView>) {
        if (items.isEmpty()) { sink("(no sessions)"); return }
        renderTable(
            headers = listOf("URI", "Question", "Mechanism", "Timestamp"),
            rows = items.map { listOf(it.uri, it.queryText, it.mechanism, it.timestamp) }
        )
    }

    // -- Simple confirmations --

    override fun writeCollectionCreated(item: CollectionView) = sink("Collection created: ${item.id}")
    override fun writeCollectionDeleted(id: String) = sink("Collection deleted: $id")
    override fun writeConfigEntry(entry: ConfigEntryView) = sink("${entry.type}:${entry.key} = ${entry.value}")
    override fun writeMessage(message: String) = sink(message)

    override fun writeDocumentUploaded(doc: DocumentInfoView, sizeBytes: Long) =
        sink("Uploaded document ${doc.id} (${sizeBytes} bytes, ${doc.mimeType})")

    // -- Key/value blocks --

    override fun writeDocumentInfo(doc: DocumentInfoView) {
        renderKeyValue(
            header = "=== Document ${doc.id} ===",
            pairs = listOf(
                "ID" to doc.id,
                "Collection" to doc.collectionId,
                "Title" to doc.title,
                "MIME" to doc.mimeType,
                "Type" to doc.type,
                "State" to doc.state,
                "Parent" to (doc.parentId ?: "-"),
                "Created" to doc.createdAt
            )
        )
        if (doc.metadata.isNotEmpty()) {
            sink("Metadata:")
            doc.metadata.forEach { (k, v) -> sink("  $k = $v") }
        }
    }

    override fun writeGraphRag(result: GraphRagResponseView) {
        sink(result.answer)
        sink("")
        sink("Session: ${result.sessionId}")
        sink("Retrieved edges: ${result.retrievedEdgeCount}")
        sink("Duration: ${result.durationMs} ms")
        if (result.selectedEdges.isNotEmpty()) {
            sink("")
            sink("Selected edges:")
            result.selectedEdges.forEachIndexed { i, edge ->
                sink("  ${i + 1}. (${edge.subject}, ${edge.predicate}, ${edge.objectValue})  score=${edge.relevanceScore}")
                sink("     ${edge.reasoning}")
            }
        }
    }

    override fun writeDocRag(result: DocRagResponseView) {
        sink(result.answer)
        sink("")
        sink("Session: ${result.sessionId}")
        sink("Retrieved chunks: ${result.retrievedChunkCount}")
        sink("Duration: ${result.durationMs} ms")
        if (result.sources.isNotEmpty()) {
            sink("")
            sink("Sources:")
            result.sources.forEachIndexed { i, src ->
                val page = src.pageNumber?.let { " p.$it" } ?: ""
                sink("  ${i + 1}. ${src.documentTitle}$page  (score=${src.score})")
                sink("     ${src.snippet}")
            }
        }
    }

    override fun writeNlp(result: NlpResponseView) {
        sink(result.answer)
        sink("")
        sink("Detected intent: ${result.detectedIntent} (confidence=${result.intentConfidence})")
        if (result.wasReformulated) sink("Effective question: ${result.effectiveQuestion}")
        sink("Duration: ${result.durationMs} ms")
        if (result.sources.isNotEmpty()) {
            sink("Sources: ${result.sources.joinToString(", ")}")
        }
    }

    override fun writeExplanationChain(chain: ExplanationChainView, maxAnswerChars: Int) {
        sink("--- Question ---")
        sink(chain.question.queryText)
        sink("Time: ${chain.question.timestamp}")
        sink("Mechanism: ${chain.mechanism}")
        sink("")
        chain.exploration?.let {
            sink("--- Exploration ---")
            sink("Retrieved edges: ${it.edgeCount}")
            sink("")
        }
        chain.focus?.let { focus ->
            sink("--- Focus ---")
            focus.selectedEdges.forEachIndexed { i, edge ->
                sink("  ${i + 1}. (${edge.subject}, ${edge.predicate}, ${edge.objectValue})")
                sink("     ${edge.reasoning}")
            }
            sink("")
        }
        chain.analyses?.forEachIndexed { i, a ->
            sink("--- Analysis ${i + 1} ---")
            sink("Thought: ${a.thought}")
            a.action?.let { sink("Action: $it") }
            if (a.arguments.isNotEmpty()) {
                a.arguments.forEach { (k, v) -> sink("  $k = $v") }
            }
            a.observation?.let { sink("Observation: $it") }
            sink("")
        }
        chain.synthesis?.let {
            sink("--- Synthesis ---")
            sink(truncateAnswer(it.answerText, maxAnswerChars))
            sink("")
        }
        chain.conclusion?.let {
            sink("--- Conclusion ---")
            sink(truncateAnswer(it.answerText, maxAnswerChars))
        }
    }

    // -- Tree rendering --

    override fun writeDocumentHierarchy(root: DocumentNodeView) {
        sink("${root.title}  [${root.id}, ${root.type}]")
        renderTreeChildren(root.children, prefix = "")
    }

    // -- Private helpers --

    private fun renderTable(headers: List<String>, rows: List<List<String>>) {
        val all = listOf(headers) + rows
        val widths = IntArray(headers.size)
        for (row in all) {
            for ((i, cell) in row.withIndex()) {
                val t = truncate(cell)
                if (t.length > widths[i]) widths[i] = t.length
            }
        }
        val sep = widths.joinToString(separator = "-+-", prefix = "+-", postfix = "-+") { "-".repeat(it) }
        val headerLine = headers.mapIndexed { i, h -> truncate(h).padEnd(widths[i]) }
            .joinToString(separator = " | ", prefix = "| ", postfix = " |")
        sink(sep)
        sink(headerLine)
        sink(sep)
        for (row in rows) {
            val line = row.mapIndexed { i, cell -> truncate(cell).padEnd(widths[i]) }
                .joinToString(separator = " | ", prefix = "| ", postfix = " |")
            sink(line)
        }
        sink(sep)
    }

    private fun renderKeyValue(header: String, pairs: List<Pair<String, String>>) {
        sink(header)
        val maxKey = pairs.maxOfOrNull { it.first.length } ?: 0
        pairs.forEach { (k, v) ->
            sink("${k.padEnd(maxKey)} : $v")
        }
    }

    private fun renderTreeChildren(children: List<DocumentNodeView>, prefix: String) {
        val last = children.lastIndex
        children.forEachIndexed { i, child ->
            val connector = if (i == last) "└─ " else "├─ "
            sink("$prefix$connector${child.title}  [${child.id}, ${child.type}]")
            val childPrefix = prefix + if (i == last) "   " else "│  "
            renderTreeChildren(child.children, childPrefix)
        }
    }

    private fun truncate(s: String): String =
        if (s.length <= maxColWidth) s else s.take(maxColWidth - 1) + "…"

    private fun truncateAnswer(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max) + "…[truncated]"
}
