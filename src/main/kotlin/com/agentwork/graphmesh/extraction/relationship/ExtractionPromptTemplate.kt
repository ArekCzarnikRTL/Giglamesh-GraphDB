package com.agentwork.graphmesh.extraction.relationship

object ExtractionPromptTemplate {

    fun systemPrompt(): String = """
        You are a knowledge extraction assistant. Your task is to extract
        structured relationships from the given text.

        Extract Subject-Predicate-Object triples in the following format:
        SUBJECT|PREDICATE|OBJECT

        Rules:
        - Subjects and Objects are entities (persons, organizations, places, concepts)
        - Predicates describe the relationship between Subject and Object
        - Use clear, canonical names for entities
        - Only extract relationships explicitly stated in the text
        - One triple per line
        - No numbering, no bullet points

        Example:
        Text: "Alice has been working at Acme Corp in Berlin since 2020."
        Alice|worksAt|Acme Corp
        Alice|worksIn|Berlin
        Acme Corp|locatedIn|Berlin
    """.trimIndent()

    fun userPrompt(chunkText: String): String = """
        Extract all Subject-Predicate-Object relationships from the following text:

        ---
        $chunkText
        ---

        Respond ONLY with triples in the format SUBJECT|PREDICATE|OBJECT, one per line.
    """.trimIndent()
}
