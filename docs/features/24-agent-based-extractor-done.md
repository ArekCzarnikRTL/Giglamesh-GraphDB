# Feature 24: Agent-based Extractor — Done

## Zusammenfassung

ReAct-Style Extraction Agent implementiert, der iterativ Wissen aus Textchunks extrahiert. Nutzt Koog's eingebautes `AIAgent`-Framework mit `reActStrategy` und 3 spezialisierten Tools. Der Agent konsultiert den Knowledge Graph, validiert Entitäten und erweitert den Kontext, bevor er JSONL-Output als RDF-Quads speichert.

### Implementierte Komponenten

| Datei | Zweck |
|-------|-------|
| `extraction/agent/Models.kt` | ExtractionStrategy, OutputType, ExtractedItem (sealed class), AgentExtractionResult |
| `extraction/agent/ExtractionTools.kt` | 3 Koog SimpleTool: GraphQueryTool, ValidateEntityTool, ContextExpandTool |
| `extraction/agent/AgentExtractorService.kt` | @Service — AIAgent mit reActStrategy, JSONL-Parsing, Quad-Konvertierung |
| `extraction/agent/AgentExtractorConsumer.kt` | @Component — @KafkaListener auf graphmesh.chunk.created |

### Tests

| Test | Anzahl |
|------|--------|
| ExtractionToolsTest | 5 Tests (GraphQuery, ValidateEntity, ContextExpand) |
| AgentExtractorServiceTest | 13 Tests (JSONL-Parsing + Quad-Konvertierung) |
| AgentExtractorConsumerTest | 2 Tests (Delegation, Error Handling) |

## Abweichungen vom Feature-Dokument

1. **Koog AIAgent + reActStrategy** statt manuellem ReAct-Loop — Koog bietet das Framework out-of-the-box
2. **Kein ExtractionAgent-Klasse** — `AIAgent` übernimmt die Rolle direkt
3. **Kein AgentIteration/AgentAction/AgentRunResult** — Koog managed den ReAct-Loop intern
4. **Koog SimpleTool** statt custom McpServer/McpTool — passt zu AIAgent + ToolRegistry
5. **QuadStore.insertBatch()** statt `saveAll()`
6. **Jackson** statt kotlinx.serialization für JSONL-Parsing
7. **@KafkaListener + Avro** statt custom MessageConsumer
8. **runBlocking** statt suspend für den Service-Layer

## Offene Punkte

- Agent-Performance hängt stark vom LLM-Modell ab — gpt-4o empfohlen für beste Ergebnisse
- maxIterations im DEFAULT_STRATEGY auf 5 gesetzt — kann bei Bedarf angepasst werden
