package com.agentwork.graphmesh.cli.output

interface Output {
    fun writeCollections(items: List<CollectionView>)
    fun writeCollectionCreated(item: CollectionView)
    fun writeCollectionDeleted(id: String)
    fun writeDocuments(items: List<DocumentView>)
    fun writeDocumentInfo(doc: DocumentInfoView)
    fun writeDocumentUploaded(doc: DocumentInfoView, sizeBytes: Long)
    fun writeGraphRag(result: GraphRagResponseView)
    fun writeDocRag(result: DocRagResponseView)
    fun writeNlp(result: NlpResponseView)
    fun writeConfigEntries(items: List<ConfigEntryView>)
    fun writeConfigEntry(entry: ConfigEntryView)
    fun writeExplanationSessions(items: List<QuestionExplanationView>)
    fun writeExplanationChain(chain: ExplanationChainView, maxAnswerChars: Int)
    fun writeDocumentHierarchy(root: DocumentNodeView)
    fun writeMessage(message: String)
}
