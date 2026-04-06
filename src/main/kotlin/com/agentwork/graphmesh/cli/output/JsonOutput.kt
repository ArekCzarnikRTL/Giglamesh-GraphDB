package com.agentwork.graphmesh.cli.output

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * JSON formatter backed by Jackson. Each `writeX` call serializes the view record
 * and emits it as one pretty-printed JSON document through the supplied sink.
 */
class JsonOutput(
    private val sink: (String) -> Unit,
    private val mapper: ObjectMapper
) : Output {

    private val writer = mapper.writerWithDefaultPrettyPrinter()

    private fun write(any: Any) = sink(writer.writeValueAsString(any))

    override fun writeCollections(items: List<CollectionView>) = write(items)
    override fun writeCollectionCreated(item: CollectionView) = write(item)
    override fun writeCollectionDeleted(id: String) = write(mapOf("deletedId" to id))
    override fun writeDocuments(items: List<DocumentView>) = write(items)
    override fun writeDocumentInfo(doc: DocumentInfoView) = write(doc)
    override fun writeDocumentUploaded(doc: DocumentInfoView, sizeBytes: Long) =
        write(mapOf("document" to doc, "sizeBytes" to sizeBytes))
    override fun writeGraphRag(result: GraphRagResponseView) = write(result)
    override fun writeDocRag(result: DocRagResponseView) = write(result)
    override fun writeNlp(result: NlpResponseView) = write(result)
    override fun writeConfigEntries(items: List<ConfigEntryView>) = write(items)
    override fun writeConfigEntry(entry: ConfigEntryView) = write(entry)
    override fun writeExplanationSessions(items: List<QuestionExplanationView>) = write(items)
    override fun writeExplanationChain(chain: ExplanationChainView, maxAnswerChars: Int) = write(chain)
    override fun writeDocumentHierarchy(root: DocumentNodeView) = write(root)
    override fun writeMessage(message: String) = write(mapOf("message" to message))
}
