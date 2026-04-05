# Feature 19: Definition Extractor — Design Spec

## Goal

Extract entity definitions from text chunks via LLM and store them as `rdfs:comment` triples in the knowledge graph. Mirrors the `RelationshipExtractorService` pattern but targets definitions (what an entity IS) rather than relationships (how entities relate).

## Data Flow

```
chunk.created Kafka event
  → DefinitionExtractorConsumer (chunkId, collectionId)
  → DefinitionExtractorService.extract(chunkId, collectionId)
    → librarianService.getContent(chunkId) → chunk text (ByteArray → UTF-8)
    → Koog PromptExecutor with definition prompt → JSONL response
    → parseJsonlDefinitions(response) → List<DefinitionResult>
    → Generate quads:
      1. Knowledge: (entityId, rdfs:comment, definition) in DEFAULT graph
      2. Labels: (entityId, rdfs:label, entityName) in DEFAULT graph (deduplicated)
      3. Provenance: (<<knowledge triple>>, gm:extractedFrom, urn:chunk:X) in SOURCE graph
    → QuadConverter.toStoredQuad() + quadStore.insertBatch(collectionId, quads)
```

## Files

All under `src/main/kotlin/com/agentwork/graphmesh/extraction/definition/`:

| File | Responsibility |
|------|---------------|
| `DefinitionExtractorModels.kt` | `DefinitionResult(entity: String, definition: String)` and `DefinitionExtractionResult(chunkId: String, definitionsExtracted: Int, entitiesFound: List<String>)` |
| `DefinitionPromptTemplate.kt` | `object` with `systemPrompt()` and `userPrompt(chunkText: String)`. Instructs LLM to extract entity definitions as JSONL: `{"entity": "...", "definition": "..."}` per line. |
| `DefinitionExtractorService.kt` | `@Service` with dependencies: `PromptExecutor`, `QuadStore`, `LibrarianService`, `@Value modelName`. Method `extract(chunkId: String, collectionId: String): DefinitionExtractionResult`. Internal method `parseJsonlDefinitions(llmResponse: String): List<DefinitionResult>`. |
| `DefinitionExtractorConsumer.kt` | `@Component` with `@KafkaListener(topics = ["graphmesh.chunk.created"], groupId = "graphmesh-definition-extractor")`. Method `handle(record: ConsumerRecord<String, GenericRecord>)` extracts chunkId + collectionId from Avro record and calls `extractorService.extract()`. |

## Implementation Details

### LLM Integration

Uses Koog `PromptExecutor` with DSL (same as `RelationshipExtractorService`):

```kotlin
val extractionPrompt = prompt("definition-extraction") {
    system(DefinitionPromptTemplate.systemPrompt())
    user(DefinitionPromptTemplate.userPrompt(chunkText))
}
val llmResponse = runBlocking {
    promptExecutor.execute(extractionPrompt, LLModel(LLMProvider.OpenAI, modelName))
}
val responseText = llmResponse.first().content
```

### JSONL Parsing

Uses Jackson `ObjectMapper` (already in project via `jackson-module-kotlin`):

```kotlin
internal fun parseJsonlDefinitions(llmResponse: String): List<DefinitionResult> {
    return llmResponse.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("```") }
        .mapNotNull { line ->
            try {
                val map = objectMapper.readValue<Map<String, String>>(line)
                val entity = map["entity"]?.takeIf { it.isNotBlank() }
                val definition = map["definition"]?.takeIf { it.isNotBlank() }
                if (entity != null && definition != null) DefinitionResult(entity, definition) else null
            } catch (_: Exception) { null }
        }
}
```

### Quad Generation

Three types of quads per definition, same URI constants as feature doc:

- **Knowledge quad**: `(EntityIdGenerator.generate(entity), rdfs:comment, Literal(definition))` — DEFAULT graph
- **Label quad**: `(EntityIdGenerator.generate(entity), rdfs:label, Literal(entity))` — DEFAULT graph, deduplicated by subject
- **Provenance quad**: `(QuotedTriple(knowledge.triple), gm:extractedFrom, Uri("urn:chunk:$chunkId"))` — SOURCE graph

### Kafka Consumer

Follows `RelationshipExtractorConsumer` pattern exactly:

```kotlin
@KafkaListener(topics = ["graphmesh.chunk.created"], groupId = "graphmesh-definition-extractor")
fun handle(record: ConsumerRecord<String, GenericRecord>) {
    val chunkId = record.value()["chunkId"].toString()
    val collectionId = record.value()["collectionId"].toString()
    extractorService.extract(chunkId, collectionId)
}
```

## Tests

Single test file at `src/test/kotlin/com/agentwork/graphmesh/extraction/definition/DefinitionExtractorServiceTest.kt`:

### JSONL Parsing Tests (standalone copy pattern)
- Valid JSONL lines → parsed correctly
- Blank lines → skipped
- Lines without valid JSON → skipped
- Lines with missing entity/definition fields → skipped
- Lines with empty entity/definition values → skipped
- Markdown code fences (` ``` `) → stripped
- Empty response → empty list
- Truncated last line → skipped gracefully

### Full extract() Tests (MockK)
- Mocks: `PromptExecutor`, `QuadStore`, `LibrarianService`
- Verifies correct quad generation (knowledge + label + provenance)
- Verifies `insertBatch` called with correct collectionId
- Verifies `DefinitionExtractionResult` fields
- Verifies blank chunk text returns zero-result early

## Key Decisions

- `collectionId` is `String` (not `UUID`) — matches existing `RelationshipExtractorService`
- Jackson for JSONL parsing — already in project, no new dependency
- Standalone parsing logic copy in tests — follows existing project test pattern
- Same Kafka topic as RelationshipExtractor with different consumer group — both extractors process chunks independently
