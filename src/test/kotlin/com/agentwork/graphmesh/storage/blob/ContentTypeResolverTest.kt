package com.agentwork.graphmesh.storage.blob

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ContentTypeResolverTest {

    @Test
    fun `resolves PDF content type`() {
        assertEquals("application/pdf", ContentTypeResolver.resolve("document.pdf"))
    }

    @Test
    fun `resolves text content type`() {
        assertEquals("text/plain", ContentTypeResolver.resolve("readme.txt"))
    }

    @Test
    fun `resolves JSON content type`() {
        assertEquals("application/json", ContentTypeResolver.resolve("data.json"))
    }

    @Test
    fun `resolves CSV content type`() {
        assertEquals("text/csv", ContentTypeResolver.resolve("export.csv"))
    }

    @Test
    fun `resolves PNG content type`() {
        assertEquals("image/png", ContentTypeResolver.resolve("image.png"))
    }

    @Test
    fun `resolves JPG content type`() {
        assertEquals("image/jpeg", ContentTypeResolver.resolve("photo.jpg"))
    }

    @Test
    fun `resolves JPEG content type`() {
        assertEquals("image/jpeg", ContentTypeResolver.resolve("photo.jpeg"))
    }

    @Test
    fun `resolves HTML content type`() {
        assertEquals("text/html", ContentTypeResolver.resolve("page.html"))
    }

    @Test
    fun `resolves XML content type`() {
        assertEquals("application/xml", ContentTypeResolver.resolve("data.xml"))
    }

    @Test
    fun `resolves Markdown content type`() {
        assertEquals("text/markdown", ContentTypeResolver.resolve("README.md"))
    }

    @Test
    fun `returns octet-stream for unknown extension`() {
        assertEquals("application/octet-stream", ContentTypeResolver.resolve("file.xyz"))
    }

    @Test
    fun `returns octet-stream for file without extension`() {
        assertEquals("application/octet-stream", ContentTypeResolver.resolve("Makefile"))
    }

    @Test
    fun `handles uppercase extensions`() {
        assertEquals("application/pdf", ContentTypeResolver.resolve("DOCUMENT.PDF"))
    }
}
