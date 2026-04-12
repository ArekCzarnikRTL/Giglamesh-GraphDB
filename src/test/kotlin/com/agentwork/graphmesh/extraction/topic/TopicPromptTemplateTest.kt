package com.agentwork.graphmesh.extraction.topic

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicPromptTemplateTest {

    @Test
    fun `systemPrompt without hints contains JSONL instruction`() {
        val prompt = TopicPromptTemplate.systemPrompt()
        assertContains(prompt, "JSONL")
        assertContains(prompt, "topic")
        assertContains(prompt, "confidence")
    }

    @Test
    fun `systemPrompt without hints does not contain preferred concepts block`() {
        val prompt = TopicPromptTemplate.systemPrompt()
        assertFalse(prompt.contains("Bevorzuge folgende"))
    }

    @Test
    fun `systemPrompt with hints includes preferred concepts`() {
        val hints = listOf("Insolvenzrecht", "Photosynthese", "EU-Datenschutz")
        val prompt = TopicPromptTemplate.systemPrompt(hints)
        assertContains(prompt, "Bevorzuge folgende")
        assertContains(prompt, "Insolvenzrecht")
        assertContains(prompt, "Photosynthese")
        assertContains(prompt, "EU-Datenschutz")
    }

    @Test
    fun `systemPrompt with empty hints does not contain preferred concepts block`() {
        val prompt = TopicPromptTemplate.systemPrompt(emptyList())
        assertFalse(prompt.contains("Bevorzuge folgende"))
    }

    @Test
    fun `userPrompt contains chunk text`() {
        val prompt = TopicPromptTemplate.userPrompt("Dies ist ein Testtext.")
        assertContains(prompt, "Dies ist ein Testtext.")
        assertContains(prompt, "JSONL")
    }
}
