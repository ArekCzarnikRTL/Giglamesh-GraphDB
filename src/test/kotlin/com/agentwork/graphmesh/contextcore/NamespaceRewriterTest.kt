package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.StoredQuad
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NamespaceRewriterTest {

    @Test
    fun `rewrites subject URI prefix`() {
        val quad = StoredQuad("http://old.org/Alice", "http://old.org/knows", "http://old.org/Bob", "d", ObjectType.URI)
        val rewrite = NamespaceRewrite("http://old.org/", "http://new.org/")
        val result = NamespaceRewriter.apply(quad, rewrite)
        assertEquals("http://new.org/Alice", result.subject)
        assertEquals("http://new.org/knows", result.predicate)
        assertEquals("http://new.org/Bob", result.objectValue)
    }

    @Test
    fun `does not rewrite non-matching URIs`() {
        val quad = StoredQuad("http://other.org/X", "http://other.org/p", "http://other.org/Y", "d", ObjectType.URI)
        val rewrite = NamespaceRewrite("http://old.org/", "http://new.org/")
        val result = NamespaceRewriter.apply(quad, rewrite)
        assertEquals(quad, result)
    }

    @Test
    fun `does not rewrite literal objects`() {
        val quad = StoredQuad("http://old.org/A", "http://old.org/name", "http://old.org/not-a-uri", "d", ObjectType.LITERAL)
        val rewrite = NamespaceRewrite("http://old.org/", "http://new.org/")
        val result = NamespaceRewriter.apply(quad, rewrite)
        assertEquals("http://new.org/A", result.subject)
        assertEquals("http://new.org/name", result.predicate)
        assertEquals("http://old.org/not-a-uri", result.objectValue)
    }

    @Test
    fun `null rewrite returns original quad`() {
        val quad = StoredQuad("http://old.org/A", "http://old.org/p", "http://old.org/B", "d", ObjectType.URI)
        val result = NamespaceRewriter.applyOrNull(quad, null)
        assertSame(quad, result)
    }
}
