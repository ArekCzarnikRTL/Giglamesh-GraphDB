package com.agentwork.graphmesh.extraction.definition

object DefinitionPromptTemplate {

    fun systemPrompt(): String = """
        You are a knowledge extraction assistant. Your task is to extract
        definitions and descriptions of entities from the given text.

        For each entity that is defined or described in the text, output
        a JSON object per line in the following format:
        {"entity": "<entity name>", "definition": "<definition or description>"}

        Rules:
        - Only extract definitions explicitly stated in the text
        - A definition describes WHAT an entity IS or WHAT it DOES
        - Ignore pure relationships between entities (e.g., "A works at B")
        - Use clear, canonical names for entities
        - The definition should be a complete, understandable sentence
        - One JSON object per line (JSONL format)

        Example:
        Text: "Photosynthesis is the process by which plants convert sunlight
        into chemical energy. Chlorophyll is the green pigment that enables
        this process."

        {"entity": "Photosynthesis", "definition": "Process by which plants convert sunlight into chemical energy"}
        {"entity": "Chlorophyll", "definition": "Green pigment that enables the process of photosynthesis"}
    """.trimIndent()

    fun userPrompt(chunkText: String): String = """
        Extract all entity definitions from the following text:

        ---
        $chunkText
        ---

        Respond ONLY with JSON objects in JSONL format, one per line.
    """.trimIndent()
}
