package com.agentwork.graphmesh.extraction.ontology

class OntologyPromptBuilder {

    fun buildSchemaSection(subset: OntologySubset): String = buildString {
        appendLine("## Entity Types:")
        for ((id, cls) in subset.classes) {
            val label = cls.labels.firstOrNull()?.value ?: id
            append("- **$id** ($label)")
            cls.comment?.let { append(": $it") }
            appendLine()
        }

        if (subset.objectProperties.isNotEmpty()) {
            appendLine()
            appendLine("## Relationships:")
            for ((id, prop) in subset.objectProperties) {
                append("- **$id**")
                prop.comment?.let { append(": $it") }
                if (prop.domain != null && prop.range != null) {
                    append(" (${prop.domain} -> ${prop.range})")
                }
                appendLine()
            }
        }

        if (subset.datatypeProperties.isNotEmpty()) {
            appendLine()
            appendLine("## Attributes:")
            for ((id, prop) in subset.datatypeProperties) {
                append("- **$id**")
                prop.comment?.let { append(": $it") }
                if (prop.domain != null) {
                    append(" (${prop.domain} -> ${prop.range})")
                }
                appendLine()
            }
        }
    }

    fun classificationPrompt(schemaSection: String): String = """
        Du bist ein Wissensextraktions-Assistent. Deine Aufgabe ist es,
        Entitaeten im Text zu identifizieren und dem passenden Entity-Typ
        aus dem Schema zuzuordnen.

        $schemaSection

        Gib fuer jede erkannte Entitaet ein JSON-Objekt pro Zeile aus:
        {"type": "entity", "entity": "<Name>", "entity_type": "<Typ-ID aus Schema>"}

        Regeln:
        - Verwende NUR Typ-IDs aus dem Schema
        - Wenn keine passende Klasse existiert, ueberspringe die Entitaet
        - Verwende den natuerlichen Namen der Entitaet (kein URI)
    """.trimIndent()

    fun relationshipPrompt(schemaSection: String): String = """
        Du bist ein Wissensextraktions-Assistent. Gegeben sind bereits
        klassifizierte Entitaeten. Extrahiere Beziehungen und Attribute
        gemaess dem Schema.

        $schemaSection

        Gib fuer jede Beziehung/jedes Attribut ein JSON-Objekt pro Zeile aus:

        Beziehungen:
        {"type": "relationship", "subject": "<Name>", "subject_type": "<Typ>", "relation": "<Property-ID>", "object": "<Name>", "object_type": "<Typ>"}

        Attribute:
        {"type": "attribute", "entity": "<Name>", "entity_type": "<Typ>", "attribute": "<Property-ID>", "value": "<Wert>"}

        Regeln:
        - Verwende NUR Relationship-/Attribute-IDs aus dem Schema
        - Subject/Object muessen zu den Domain/Range-Klassen passen
        - Werte fuer Attribute im natuerlichen Textformat
    """.trimIndent()
}
