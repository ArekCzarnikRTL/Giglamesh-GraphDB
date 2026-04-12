package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.StoredQuad

object NamespaceRewriter {

    fun apply(quad: StoredQuad, rewrite: NamespaceRewrite): StoredQuad {
        return quad.copy(
            subject = rewriteUri(quad.subject, rewrite),
            predicate = rewriteUri(quad.predicate, rewrite),
            objectValue = if (quad.objectType == ObjectType.URI) rewriteUri(quad.objectValue, rewrite) else quad.objectValue
        )
    }

    fun applyOrNull(quad: StoredQuad, rewrite: NamespaceRewrite?): StoredQuad {
        return if (rewrite == null) quad else apply(quad, rewrite)
    }

    private fun rewriteUri(uri: String, rewrite: NamespaceRewrite): String {
        return if (uri.startsWith(rewrite.from)) {
            rewrite.to + uri.removePrefix(rewrite.from)
        } else {
            uri
        }
    }
}
