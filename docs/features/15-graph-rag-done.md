# Feature 15: Graph RAG — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagModels.kt`** — `GraphRagQuery` (mit optionalem `precomputedEmbedding: FloatArray` fuer NLP-Pipeline-Integration), `GraphRagResult` (inkl. `sessionId: UUID` fuer Explainability) und `SelectedEdge` (Felder `subject`, `predicate`, `objectValue`, `dataset`, `reasoning`, `relevanceScore`).
- **`src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`** — `@Service`-Klasse mit synchronem `query(GraphRagQuery)`. Retrieval kombiniert Vektor-Suche (chunk- und entitaetsbasiert) ueber `VectorStore`, `QuadStore.findSubgraphsForChunks` und `findByEntities`, plus 1-Hop-Entity-Expansion. Fallback-Scan (`fallbackQuadStoreScan`) laedt alle Quads der Collection, wenn keine Vector-Hits vorliegen. Phase 2+3 sind in einem kombinierten LLM-Call `selectAndSynthesize` zusammengefasst (Format `ANSWER:` + `EDGES:` mit `index|reasoning`-Zeilen). LLM-Aufruf ueber Koog `PromptExecutor` + `resolveLlmModel(name)`. Leere Subgraphen liefern sofort `"No relevant knowledge found for this question."`. Alle Durchlaeufe emittieren ein Explainability-Event via `ExplainabilityEventProducer.sendGraphRagEvent`.
- **`src/main/kotlin/com/agentwork/graphmesh/api/GraphRagController.kt`** — Spring GraphQL `@QueryMapping fun graphRag(input: GraphRagInput)` mit Defaults 150/2/30.
- **`src/main/resources/graphql/graph-rag.graphqls`** — `graphRag(input: GraphRagInput!)`-Query, `GraphRagResponse` inkl. `sessionId: ID!` und `durationMs: Int!`, `SelectedEdgeType` mit `dataset`-Feld (statt `graph`).

### Tests

- **`GraphRagServiceTest`** — 15 Unit-Tests fuer `parseEdgeSelection` (valide/invalide Indizes, ohne Pipe, blank, leer, nicht-numerisch, Relevance-Score-Berechnung), `collectEntityUris` (Subject- und Object-Position, Filterung, Dedup), `splitSearchResults` (Chunk- vs. Entity-Hits) und `parseSelectAndSynthesize` (Answer+Edges, fehlende Marker, leere Antwort).

## Abweichungen vom Feature-Dokument

- **Kein multi-modulares Projekt**: Package ist `com.agentwork.graphmesh.query.graphrag` (nicht `com.graphmesh.query.graphrag`), alles in einem Gradle-Projekt.
- **Keine separaten Interfaces**: `SubgraphRetriever`, `EdgeSelector`, `AnswerSynthesizer` und `DefaultGraphRagService` existieren nicht als eigenstaendige Klassen. Alles ist in einer einzigen `GraphRagService`-Klasse (private Methoden). Keine Interface-Extraktion — YAGNI.
- **Phase 2+3 zusammengefasst**: Statt zweier LLM-Calls (Select, dann Synthesize) nutzt die Implementierung einen **einzigen** LLM-Call `selectAndSynthesize` mit `ANSWER:`/`EDGES:`-Format. Spart Latenz/Kosten. Der separate `selectEdges`-Helper existiert zwar im Code, wird aber nicht vom Haupt-Flow genutzt.
- **Kein BFS-Retrieval**: Spec-Skizze zeigt BFS-Traversierung ueber `maxDepth`. Tatsaechlich wird `maxDepth` **nicht** verwendet — der Retriever nutzt Vector-Hits + direkte Subgraph-Lookups und genau eine 1-Hop-Entity-Expansion. `maxDepth` bleibt im Query-Modell aus API-Kompatibilitaet.
- **Synchrone API**: `query(...)` ist nicht `suspend`. Interner Koog-Aufruf laeuft ueber `runBlocking`.
- **Kein Streaming**: `queryStreaming` und `Flow<String>` nicht implementiert. GraphQL-Subscription entfaellt.
- **LLM ueber Koog**: `PromptExecutor` + `resolveLlmModel(llmModelName)` (Modellname via `graphmesh.extraction.model`), nicht ueber fiktiven `ChatCompletionService`.
- **`sessionId` + Explainability**: Nicht im Spec; Service emittiert pro Call ein `ExplainabilityEvent` und liefert die `sessionId` im Ergebnis zurueck.
- **`GraphRagResponse.durationMs` ist `Int!`**, nicht `Long!`; kein `Long`-Scalar registriert.
- **`SelectedEdgeType`-Feld heisst `dataset`** statt `graph`.
- **`precomputedEmbedding`**: Neues Feld in `GraphRagQuery`, damit `NlpQueryService` im Hybrid-Modus die Einbettung nur einmal berechnen muss.

## Akzeptanzkriterien

- [x] GraphQL-Query `graphRag` nimmt eine natuerlichsprachige Frage und Collection-ID entgegen
- [x] Phase 1 (Retrieval) findet relevante Entitaeten via Embedding-Similarity und traversiert deren Kanten (hier: Chunk-Subgraph-Lookup + 1-Hop-Entity-Expansion, kein echtes BFS)
- [x] Phase 2 (Selection) laesst das LLM relevante Kanten auswaehlen und gibt pro Kante eine Begruendung zurueck
- [x] Phase 3 (Synthesis) generiert eine Antwort ausschliesslich auf Basis der selektierten Kanten (im gleichen LLM-Call wie Phase 2)
- [x] Jede selektierte Kante enthaelt Subject, Predicate, Object, Reasoning und Relevanz-Score
- [x] Die Pipeline haelt konfigurierbare Limits ein (`maxEdges`, `maxSelectedEdges`)
- [ ] BFS-Traversierung erkennt Zyklen und terminiert fruehzeitig bei Limit-Erreichung — **nicht** implementiert, `maxDepth` wird ignoriert
- [ ] Streaming-Modus liefert Antwort-Tokens als Flow aus der Synthese-Phase — **nicht** implementiert
- [x] Antwortzeit fuer typische Anfragen liegt unter 10 Sekunden (durch einstufigen LLM-Call statt zwei)
- [x] Pipeline gibt `durationMs` und `retrievedEdgeCount` als Metriken zurueck

## Offene Punkte

- Echte BFS-Traversierung mit `maxDepth`, Zykluserkennung und Early-Termination nachruesten, falls tiefere Kantenverfolgung benoetigt wird.
- Streaming-Variante (`Flow<String>`) fuer die Synthese-Phase + GraphQL-Subscription, falls UI-Clients Token-Streaming wollen.
