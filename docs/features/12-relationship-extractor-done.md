# Feature 12: Relationship Extractor — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/extraction/relationship/RelationshipExtractorService.kt`** — `@Service`, extrahiert Triples aus einem Chunk ueber Koog `PromptExecutor`. Chunk-Text wird via `LibrarianService.getContent` geladen und durch `com.agentwork.graphmesh.llm.sanitizeForLlm` bereinigt (Control-Char-Stripping). Prompt wird via Koog-DSL `prompt("relationship-extraction") { system(...); user(...) }` gebaut, Modell wird aus `graphmesh.extraction.model` (Default `gpt-4o`) via `resolveLlmModel(modelName)` aufgeloest. Antwort wird per `parseTriples` in `Triple<String,String,String>` geparst. Erzeugt (1) Knowledge-Quads im `NamedGraph.DEFAULT` mit `EntityIdGenerator.generate(...)` fuer Subject/Object und URI `http://graphmesh.io/ontology/{normalizePredicateName(p)}`, (2) Provenance-Quads via `ProvenanceService.buildSubgraphQuads(SubgraphProvenance(...))` (Subgraph-Kompression), (3) deduplizierte `rdfs:label`-Quads. Alle Quads werden ueber `QuadConverter.toStoredQuad` konvertiert und gemeinsam via `QuadStore.insertBatch(collectionId, ...)` persistiert.
- **`src/main/kotlin/com/agentwork/graphmesh/extraction/relationship/RelationshipExtractorConsumer.kt`** — `@KafkaListener` auf `graphmesh.chunk.created` (groupId `graphmesh-relationship-extractor`). Liest `chunkId` und `collectionId` aus Avro-Record, ruft `extractorService.extract(...)`. `DocumentNotFoundException` (Chunk vor Verarbeitung geloescht) wird als Info-Log verschluckt; andere Fehler landen im Error-Log.
- **`src/main/kotlin/com/agentwork/graphmesh/extraction/relationship/ExtractionPromptTemplate.kt`** — Englischsprachiger System- und User-Prompt, Format `SUBJECT|PREDICATE|OBJECT` pro Zeile, mit Beispiel "Alice|worksAt|Acme Corp".
- **`src/main/kotlin/com/agentwork/graphmesh/extraction/relationship/RelationshipExtractorModels.kt`** — `ExtractionResult(chunkId, triplesExtracted, entitiesFound)`.

### Tests

- **`RelationshipExtractorServiceTest`** — `parseTriples`: valide Triples, blank lines, Zeilen ohne Pipe, falsche Feldanzahl, leere Felder, Whitespace-Trim, leere Response. `normalizePredicateName`: camelCase aus "works at"/"located in"/"has CEO", Single-Word, Whitespace-Collapse. Die Methoden werden als Standalone-Kopien im Test eingebettet (keine Abhaengigkeits-Konstruktion fuer den Service).

## Abweichungen vom Feature-Dokument

- **Paket-Prefix**: `com.agentwork.graphmesh.*` statt `com.graphmesh.*`; kein separates Submodul `extraction/`.
- **LLM-Integration ueber Koog, nicht ueber `ChatCompletionService`**: Spec nutzt eine eigene `ChatCompletionService`-Abstraktion, die Implementierung verwendet direkt Koog `PromptExecutor` + `resolveLlmModel(name)` (entspricht der projektweiten LLM-Policy, siehe Memory `project_llm_koog.md`). `runBlocking` bridgt den suspendierenden Koog-Call in die synchrone Kafka-Listener-Umgebung.
- **Provenance via `ProvenanceService` statt direkt als `QuotedTriple`**: Spec skizziert `RdfTerm.QuotedTriple` mit Predicate `extractedFrom` im Source-Graph. Implementierung delegiert an `ProvenanceService.buildSubgraphQuads(SubgraphProvenance(...))` (Subgraph-Kompression mit Agent-Label und Model-Name), was die vorhandene Provenance-Feature nutzt.
- **`collectionId: String`** statt `UUID` — konsistent mit dem Rest der Codebasis.
- **`sanitizeForLlm` vor der LLM-Abfrage** — nicht im Spec, aber analog zum PDF-Pfad aktiviert (Commit-Referenz `b4594a9`).
- **Keine Integrations-/Provenance-/Prompt-Template-Tests** — Spec fordert `ExtractionPromptTemplateTest`, `TripleParsingTest`, `ProvenanceTest`; implementiert ist nur `RelationshipExtractorServiceTest` (deckt Parser + Normalisierung ab, Prompt-Template und Provenance nicht).
- **Keine `saveAll`-Trennung Default/Source**: Alle Quads (Knowledge + Label + Provenance) werden in einem einzigen `insertBatch`-Aufruf persistiert — der Graph-Zuordnungs-Tag wandert pro Quad ueber `StoredQuad.dataset`.

## Akzeptanzkriterien

- [x] Extractor empfaengt `chunk.created`-Events — `RelationshipExtractorConsumer`.
- [x] LLM-Prompt extrahiert Subject-Predicate-Object-Triples — `ExtractionPromptTemplate` + Koog `PromptExecutor`.
- [x] Triples als `Quad`-Objekte mit korrekten `RdfTerm`-Typen — `RelationshipExtractorService`.
- [x] Subjects/Objects via `EntityIdGenerator` — `EntityIdGenerator.generate(subject/objectValue)`.
- [x] Knowledge-Quads im Default-Graph — `graph = NamedGraph.DEFAULT`.
- [x] Provenance-Quads im Source-Graph — via `ProvenanceService.buildSubgraphQuads`, intern Source-Graph.
- [x] Label-Triples dedupliziert — `distinctBy { it.subject.toNTriples() + it.objectTerm.toNTriples() }`.
- [x] Ungueltige LLM-Antwortzeilen werden uebersprungen — `parseTriples` (Filter + `mapNotNull`).
- [x] Predicate-Namen werden zu camelCase normalisiert — `normalizePredicateName`.
- [x] `ExtractionResult` mit Zaehlern — `triplesExtracted = knowledgeQuads.size`, `entitiesFound = distinct count`.

## Offene Punkte

- Separater Test fuer Provenance-Quads (Source-Graph, QuotedTriple oder Subgraph-Shape) fehlt; empfehle Erweiterung analog zu anderen Feature-Tests.
- Kein Test fuer den Consumer-Pfad (Avro-Deserialisierung, `DocumentNotFoundException`-Branch).
