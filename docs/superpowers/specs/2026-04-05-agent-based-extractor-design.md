# Feature 24: Agent-based Extractor — Design Spec

## Zusammenfassung

ReAct-Style Extraction Agent basierend auf Koog's `AIAgent` + `reActStrategy`. Der Agent extrahiert iterativ Wissen aus Textchunks, konsultiert den Knowledge Graph und gibt JSONL-Output aus. Nutzt 3 Koog-Tools: graph-query, validate-entity, context-expand.

## Entscheidungen

| Entscheidung | Gewählt | Grund |
|---|---|---|
| Agent-Framework | Koog AIAgent + reActStrategy | Eingebauter ReAct-Loop, kein manueller Code nötig, koog-agents-jvm bereits als Dependency |
| Manueller ReAct-Loop | Weggelassen | Koog bietet das out-of-the-box, weniger Code, weniger Bugs |
| Tool-Scope | 3 Tools (graph-query, validate-entity, context-expand) | Unterschiedliche Kosten/Zwecke — validate-entity ist schneller Lookup ohne LLM |
| Tool-Interface | Koog Tool-Objekte | Passt zu AIAgent + ToolRegistry |

## Architektur

```
graphmesh.chunk.created (Kafka, Avro GenericRecord)
       │
       ▼
AgentExtractorConsumer (@KafkaListener, groupId: graphmesh-agent-extractor)
       │
       ▼
AgentExtractorService (Orchestrator)
       │
       ├─► LibrarianService.getContent(chunkId) → Chunk-Text laden
       │
       ├─► AIAgent erstellen:
       │     promptExecutor = injected PromptExecutor
       │     llmModel = LLModel(LLMProvider.OpenAI, modelName)
       │     strategy = reActStrategy(reasoningInterval=1, name="extraction_agent")
       │     toolRegistry = ToolRegistry { tool(graphQuery); tool(validateEntity); tool(contextExpand) }
       │     systemPrompt = ExtractionStrategy.systemPrompt
       │
       ├─► runBlocking { agent.run("Extrahiere Wissen aus: $chunkText") }
       │     Der Agent durchläuft intern den ReAct-Cycle:
       │     ├── Thought → Action (Tool-Aufruf) → Observation → Repeat
       │     └── Final Answer mit JSONL-Output
       │
       ├─► parseFinalOutput(agentResult) → List<ExtractedItem>
       │     Parst JSONL-Zeilen aus dem Agent-Result-String
       │
       ├─► convertToQuads(items, chunkId) → List<StoredQuad>
       │     ExtractedItem → Quad → QuadConverter.toStoredQuad()
       │
       └─► quadStore.insertBatch(collectionId, storedQuads)
```

## Komponenten

### Models.kt

```kotlin
data class ExtractionStrategy(
    val name: String,
    val systemPrompt: String,
    val maxIterations: Int = 5,
    val outputTypes: List<OutputType> = listOf(OutputType.RELATIONSHIP, OutputType.DEFINITION)
)

enum class OutputType { DEFINITION, RELATIONSHIP, ENTITY, ATTRIBUTE }

sealed class ExtractedItem {
    data class Definition(val entity: String, val definition: String) : ExtractedItem()
    data class Relationship(
        val subject: String,
        val predicate: String,
        val objectValue: String,
        val objectIsEntity: Boolean = true
    ) : ExtractedItem()
    data class Entity(val name: String, val entityType: String? = null) : ExtractedItem()
    data class Attribute(val entity: String, val attribute: String, val value: String) : ExtractedItem()
}

data class AgentExtractionResult(
    val chunkId: String,
    val extractedItems: List<ExtractedItem>,
    val strategy: String
)
```

### ExtractionTools.kt

3 Koog Tool-Funktionen, die als Factory-Methoden erstellt werden (benötigen Services als Parameter):

**`createGraphQueryTool(graphRagService: GraphRagService)`**
- Name: `graph-query`
- Description: "Frage den Knowledge Graph ab. Parameter: question (die Frage), collectionId (Collection-ID)"
- Input: String (JSON mit question + collectionId)
- Output: String (RAG-Antwort mit Sources)
- Intern: Ruft `GraphRagService.query(GraphRagQuery(...))` auf

**`createValidateEntityTool(quadStore: QuadStore)`**
- Name: `validate-entity`
- Description: "Prüfe ob eine Entität im Knowledge Graph existiert. Parameter: entityName, collectionId"
- Input: String (JSON mit entityName + collectionId)
- Output: String ("EXISTS: [list of predicates]" oder "NOT_FOUND")
- Intern: `EntityIdGenerator.generate(entityName)` → `quadStore.findByEntities(collectionId, [entityId])`

**`createContextExpandTool(librarianService: LibrarianService)`**
- Name: `context-expand`
- Description: "Lade benachbarte Chunks des gleichen Dokuments. Parameter: chunkId"
- Input: String (JSON mit chunkId)
- Output: String (Texte der Sibling-Chunks)
- Intern: `librarianService.findById(chunkId)` → `parentId` → `librarianService.findChildren(parentId)` → Content laden

### AgentExtractorService.kt (@Service)

**Dependencies:**
- `PromptExecutor` (Koog)
- `GraphRagService`
- `QuadStore`
- `LibrarianService`
- `@Value("${graphmesh.extraction.model:gpt-4o}")` modelName

**Default ExtractionStrategy:**
```
name = "default-extraction"
systemPrompt = """
    Du bist ein Wissensextraktions-Agent. Extrahiere Wissen aus dem gegebenen Text.
    
    Verwende die verfügbaren Tools um:
    1. Zu prüfen, ob Entitäten bereits im Graph existieren (validate-entity)
    2. Existierende Beziehungen zu konsultieren (graph-query)
    3. Den Kontext bei Bedarf zu erweitern (context-expand)
    
    Wenn du fertig bist, gib die extrahierten Ergebnisse im JSONL-Format aus.
    Jede Zeile ist ein JSON-Objekt mit einem "type"-Feld:
    {"type": "relationship", "subject": "...", "predicate": "...", "object": "...", "object_entity": true}
    {"type": "definition", "entity": "...", "definition": "..."}
    {"type": "entity", "entity": "...", "entity_type": "..."}
    {"type": "attribute", "entity": "...", "attribute": "...", "value": "..."}
    
    Ziel: Hochqualitative, nicht-redundante Triples.
"""
maxIterations = 5
```

**Methode:** `fun extract(chunkId: String, collectionId: String): AgentExtractionResult`

**JSONL-Parsing (`parseFinalOutput`):**
- Jackson ObjectMapper wie bei allen anderen Extractors
- Zeile für Zeile parsen, ungültige Zeilen überspringen
- Discriminated union via `type`-Feld → entsprechende ExtractedItem-Subklasse

**Quad-Konvertierung (`convertToQuads`):**

| ExtractedItem | → Quads |
|---|---|
| Definition | (entityId, rdfs:comment, definition) + (entityId, rdfs:label, entity) in DEFAULT |
| Relationship | (subjectId, ontology/predicate, objectId oder Literal) in DEFAULT |
| Entity | (entityId, rdfs:label, name) in DEFAULT |
| Attribute | (entityId, ontology/attribute, Literal(value)) in DEFAULT |

Plus Provenance-Quads: (<<knowledge triple>>, extractedFrom, urn:chunk:chunkId) in SOURCE graph.

### AgentExtractorConsumer.kt (@Component)

Identisch zum Pattern aller anderen Consumer:
- `@KafkaListener(topics=["graphmesh.chunk.created"], groupId="graphmesh-agent-extractor")`
- Avro GenericRecord → chunkId + collectionId
- Delegiert an `AgentExtractorService.extract()`
- Error-Handling mit try-catch + Logger

## Tests

| Test | Fokus |
|------|-------|
| `ExtractionToolsTest` | Tool-Factory-Methoden liefern korrekte Outputs: Graph-Query mit gemocktem GraphRagService, Entity-Validation mit gemocktem QuadStore, Context-Expand mit gemocktem LibrarianService |
| `AgentExtractorServiceTest` | JSONL-Parsing aller 4 Typen (definition, relationship, entity, attribute), Quad-Konvertierung, E2E extract() mit gemocktem AIAgent-Result, Provenance-Quads |
| `AgentExtractorConsumerTest` | Consumer delegiert an Service, Fehlerfall wird geloggt |

**Test-Pattern:** MockK, Tests der `internal` Parsing-Methoden direkt.

## Abweichungen vom Feature-Doc

1. Koog `AIAgent` + `reActStrategy` statt manuellem ReAct-Loop
2. Kein `ExtractionAgent`-Klasse — `AIAgent` übernimmt
3. Kein `AgentIteration`/`AgentAction`/`AgentRunResult` — Koog managed den Loop intern
4. Koog `ToolRegistry` + Tool-Factory-Methoden statt custom McpServer/McpTool
5. Package `com.agentwork.graphmesh.extraction.agent`
6. `QuadStore.insertBatch()` statt `saveAll()`
7. Jackson statt kotlinx.serialization
8. `@KafkaListener` + Avro statt custom MessageConsumer
9. `runBlocking` statt suspend
10. `AgentExtractionResult` vereinfacht (keine iterations/toolCallCount — Koog gibt keinen Zugriff darauf)
