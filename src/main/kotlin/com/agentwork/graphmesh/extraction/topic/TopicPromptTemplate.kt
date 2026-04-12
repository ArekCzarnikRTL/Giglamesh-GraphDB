package com.agentwork.graphmesh.extraction.topic

object TopicPromptTemplate {

    fun systemPrompt(hints: List<String> = emptyList()): String {
        val base = """
            Du bist ein Wissensextraktions-Assistent. Deine Aufgabe ist es,
            die **Themen** zu identifizieren, die einen Text inhaltlich praegen.

            Themen sind abstrakte Konzepte oder Sachgebiete -- NICHT einzelne
            Entitaeten. Beispiele:
              richtig:  "Insolvenzrecht", "Photosynthese", "EU-Datenschutz"
              falsch:   "Angela Merkel", "Berlin", "BMW AG" (das sind Entitaeten)

            Extrahiere fuer jedes Thema ein JSON-Objekt pro Zeile im JSONL-Format:
              {"topic": "<Thema>", "confidence": <0.0..1.0>, "rationale": "<kurzer Grund>"}

            Regeln:
              - Maximal 5 Themen pro Text, nur die wichtigsten.
              - `confidence` spiegelt wider, wie deutlich das Thema im Text auftritt.
              - `rationale` ist ein kurzer Halbsatz (max. 10 Woerter).
              - Verwende kanonische, wiederverwendbare Bezeichnungen.
              - KEINE Entitaeten, Personen, Orte, Firmen.
              - Jedes JSON-Objekt auf einer eigenen Zeile, kein Markdown.
        """.trimIndent()

        if (hints.isEmpty()) return base

        val hintsBlock = """

            Bevorzuge folgende bekannte Konzepte, falls sie zum Text passen:
            ${hints.joinToString("\n") { "  - $it" }}
        """.trimIndent()

        return base + "\n\n" + hintsBlock
    }

    fun userPrompt(chunkText: String): String = """
        Extrahiere die Themen aus folgendem Text:

        ---
        $chunkText
        ---

        Antworte NUR mit JSON-Objekten im JSONL-Format, eines pro Zeile.
    """.trimIndent()
}
