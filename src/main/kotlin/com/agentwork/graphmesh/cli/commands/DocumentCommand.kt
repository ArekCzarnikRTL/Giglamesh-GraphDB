package com.agentwork.graphmesh.cli.commands

import com.agentwork.graphmesh.cli.BaseCommand
import com.agentwork.graphmesh.cli.generated.GetDocument
import com.agentwork.graphmesh.cli.generated.ListDocuments
import com.agentwork.graphmesh.cli.generated.UploadDocument
import com.agentwork.graphmesh.cli.generated.enums.DocumentType
import com.agentwork.graphmesh.cli.generated.inputs.UploadDocumentInput
import com.agentwork.graphmesh.cli.generated.uploaddocument.KeyValue as UploadKeyValue
import com.agentwork.graphmesh.cli.generated.getdocument.Document as GetDocumentDoc
import com.agentwork.graphmesh.cli.generated.getdocument.KeyValue
import com.agentwork.graphmesh.cli.output.DocumentInfoView
import com.agentwork.graphmesh.cli.output.DocumentView
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import java.nio.file.Files
import java.util.Base64

class DocumentCommand : BaseCommand("document") {
    init {
        subcommands(DocumentUpload(), DocumentList(), DocumentInfo())
    }

    override suspend fun run() = Unit
}

class DocumentUpload : BaseCommand("upload") {
    private val collectionId by option("--collection", "-c", help = "Collection ID").required()
    private val file by option("--file", help = "Path to file to upload")
        .file(mustExist = true, canBeDir = false)
        .required()
    private val titleOverride by option("--title", help = "Override document title")
    private val mimeOverride by option("--mime", help = "Override MIME type")

    override suspend fun run() {
        val bytes = file.readBytes()
        val base64 = Base64.getEncoder().encodeToString(bytes)
        val mime = mimeOverride ?: Files.probeContentType(file.toPath()) ?: "application/octet-stream"
        val input = UploadDocumentInput(
            collectionId = collectionId,
            title = titleOverride ?: file.name,
            mimeType = mime,
            content = base64,
            metadata = null
        )
        val result = gateway().execute(UploadDocument(UploadDocument.Variables(input = input)))
        val d = result.uploadDocument
        out.writeDocumentUploaded(
            DocumentInfoView(
                id = d.id,
                collectionId = d.collectionId,
                title = d.title,
                mimeType = d.mimeType,
                type = d.type.toString(),
                state = d.state.toString(),
                parentId = d.parentId,
                createdAt = d.createdAt,
                metadata = d.metadata.map { kv: UploadKeyValue -> kv.key to kv.value }
            ),
            sizeBytes = bytes.size.toLong()
        )
    }
}

class DocumentList : BaseCommand("list") {
    private val collectionId by option("--collection", "-c", help = "Collection ID").required()
    private val typeStr by option("--type", help = "Filter by document type (SOURCE, PAGE, CHUNK)")

    override suspend fun run() {
        val docType = typeStr?.let { s -> DocumentType.values().find { it.name == s } }
        val result = gateway().execute(
            ListDocuments(ListDocuments.Variables(collectionId = collectionId, type = docType))
        )
        val items = result.documents.items.map { doc ->
            DocumentView(
                id = doc.id,
                title = doc.title,
                mimeType = doc.mimeType,
                type = doc.type.toString(),
                state = doc.state.toString(),
                createdAt = doc.createdAt
            )
        }
        out.writeDocuments(items)
    }
}

class DocumentInfo : BaseCommand("info") {
    private val docId by argument(help = "Document ID")

    override suspend fun run() {
        val result = gateway().execute(GetDocument(GetDocument.Variables(id = docId)))
        if (result.document == null) {
            out.writeMessage("Document $docId not found")
            return
        }
        val d: GetDocumentDoc = result.document!!
        val dId: String = d.id
        val dCollectionId: String = d.collectionId
        val dParentId: String? = d.parentId
        val dType: String = d.type.toString()
        val dState: String = d.state.toString()
        val dTitle: String = d.title
        val dMimeType: String = d.mimeType
        val dCreatedAt: String = d.createdAt
        val rawMeta = d.metadata
        val dMeta: List<Pair<String, String>> = rawMeta.map { kv ->
            val k: String = kv.key
            val v: String = kv.value
            Pair(k, v)
        }
        out.writeDocumentInfo(
            DocumentInfoView(
                id = dId,
                collectionId = dCollectionId,
                title = dTitle,
                mimeType = dMimeType,
                type = dType,
                state = dState,
                parentId = dParentId,
                createdAt = dCreatedAt,
                metadata = dMeta
            )
        )
    }
}
