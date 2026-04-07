package com.agentwork.graphmesh.extraction.structured

import com.agentwork.graphmesh.llm.resolveLlmModel

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@OptIn(kotlin.time.ExperimentalTime::class)
@Service
class TableDetector(
    private val promptExecutor: PromptExecutor,
    @Value("\${graphmesh.extraction.model:gpt-4o}") private val modelName: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    fun detect(chunkText: String): DetectionResult {
        if (chunkText.isBlank()) {
            return DetectionResult(hasTable = false, confidence = 0.0)
        }

        val detectionPrompt = prompt("table-detection") {
            system(
                """
                Analysiere den folgenden Text und bestimme, ob er tabellarische Daten
                oder strukturierte Listen enthaelt, die als Tabelle dargestellt werden koennten.

                Antworte NUR mit einem JSON-Objekt:
                {"has_table": true/false, "confidence": 0.0-1.0, "description": "Kurzbeschreibung der Tabelle"}

                Beispiele fuer tabellarische Daten:
                - Preislisten, Produktkataloge
                - Mitarbeiterlisten, Kontaktdaten
                - Technische Spezifikationen
                - Vergleichstabellen
                - Aufzaehlungen mit wiederkehrender Struktur
                """.trimIndent()
            )
            user(chunkText)
        }

        return try {
            val llmResponse = runBlocking {
                promptExecutor.execute(detectionPrompt, resolveLlmModel(modelName))
            }
            val responseText = llmResponse.first().content
            parseDetectionResult(responseText)
        } catch (e: Exception) {
            logger.error("Table detection failed", e)
            DetectionResult(hasTable = false, confidence = 0.0)
        }
    }

    internal fun parseDetectionResult(response: String): DetectionResult {
        return try {
            val cleaned = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val map = objectMapper.readValue<Map<String, Any?>>(cleaned)
            DetectionResult(
                hasTable = map["has_table"] as? Boolean ?: false,
                confidence = (map["confidence"] as? Number)?.toDouble() ?: 0.0,
                tableDescription = map["description"] as? String
            )
        } catch (_: Exception) {
            DetectionResult(hasTable = false, confidence = 0.0)
        }
    }
}
