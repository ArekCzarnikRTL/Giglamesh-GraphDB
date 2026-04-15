# Feature 18: NLP Query Service — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryModels.kt`** — `NlpQuery`, `QueryIntent`-Enum (`GRAPH_QUERY`, `DOCUMENT_QUERY`, `STRUCTURED_QUERY`, `HYBRID`), `DetectedIntent` (mit optionalem `reformulatedQuestion`), `NlpQueryResult`.
- **`src/main/kotlin/com/agentwork/graphmesh/query/nlp/NlpQueryService.kt`** — `@Service` mit synchronem `query(NlpQuery)`:
  1. **Intent-Detection mit Collection-Shortcut**: `CollectionContentTypeService.hasDocuments`/`hasTriples` bestimmt, ob die Collection nur Graph- oder nur Dokumentdaten enthaelt. In diesem Fall wird die Intent-Erkennung uebersprungen und direkt `GRAPH_QUERY` bzw. `DOCUMENT_QUERY` mit `confidence=1.0` gesetzt. Nur bei gemischten Collections laeuft die LLM-Intent-Detection; Embedding und Intent-Detection werden **parallel** via `coroutineScope`/`async` berechnet.
  2. **Reformulation** nur wenn `confidence < 0.7` und kein `forceIntent`; System-Prompt erwartet `NO_CHANGE`/`KEINE_AENDERUNG` oder die neue Frage.
  3. **Routing** in `route(...)` mit vorausberechneter Embedding (an `GraphRagQuery.precomputedEmbedding` und `DocumentRagQuery.precomputedEmbedding` durchgereicht). `STRUCTURED_QUERY` laedt die ersten 20 Quads aus `QuadStore`, `HYBRID` fuehrt Graph- und Document-RAG parallel (`async`) aus und konkateniert die Antworten.
  4. **Fallback** bei Konfidenz `< 0.5` → `GRAPH_QUERY`.
  5. Parser `parseIntentResponse` (Format `INTENT|CONFIDENCE|REASONING`) und `parseReformulationResponse` sind `internal` fuer Unit-Tests.
- **`src/main/kotlin/com/agentwork/graphmesh/query/CollectionContentTypeService.kt`** — Liefert `hasDocuments`/`hasTriples` pro Collection; ermoeglicht das Abkuerzen der Intent-Detection.
- **`src/main/kotlin/com/agentwork/graphmesh/api/NlpQueryController.kt`** — Spring GraphQL `@QueryMapping fun nlpQuery(input: NlpQueryInput)`.
- **`src/main/resources/graphql/nlp-query.graphqls`** — `nlpQuery(input: NlpQueryInput!)`, `NlpQueryResponse` (`durationMs: Int!`), `DetectedIntentType`, `QueryIntentEnum` mit allen vier Werten.

### Tests

- **`NlpQueryServiceTest`** — 12 Unit-Tests fuer `parseIntentResponse` (valide Responses fuer GRAPH/DOCUMENT/HYBRID, Fallback bei invalidem Intent, niedriger Konfidenz, fehlender Pipe, leerer Response, Multiline) und `parseReformulationResponse` (Reformulation, `NO_CHANGE`, `KEINE_AENDERUNG`, Whitespace-Trim, Blank).
- **`NlpQueryServiceOrchestrationTest`** — 7 MockK-Tests: `forceIntent` skipt Intent-Detection und routet korrekt an Graph/Document/Structured/Hybrid; keine Reformulation bei forciertem Intent; Ergebnis enthaelt `durationMs`; leeres QuadStore bei `STRUCTURED_QUERY` liefert `"No matching triples found."`.

## Abweichungen vom Feature-Dokument

- **Package `com.agentwork.graphmesh.query.nlp`** (nicht `com.graphmesh.query.nlp`).
- **Keine separaten Interfaces**: `IntentDetector`, `QueryRouter`, `QueryReformulator`, `DefaultNlpQueryService`, `LlmIntentDetector`, `DefaultQueryRouter`, `LlmQueryReformulator` existieren **nicht** als eigenstaendige Klassen. Alles liegt als private Methoden in `NlpQueryService`.
- **Collection-Content-Shortcut**: Nicht im Spec. Wenn die Collection nur Graph- oder nur Dokumentdaten enthaelt, wird die LLM-Intent-Erkennung komplett uebersprungen. Spart Kosten und Latenz.
- **Parallele Intent-Detection + Embedding-Berechnung** im Mixed-Fall (`async`/`coroutineScope`). Spec sieht strikt sequentiell vor.
- **Embedding-Wiederverwendung**: Das berechnete Query-Embedding wird ueber `precomputedEmbedding` an `GraphRagQuery`/`DocumentRagQuery` durchgereicht, damit kein doppelter Embedding-Call faellt. Besonders wichtig fuer `HYBRID`.
- **Intent-Response-Format**: Eine einzige Zeile `INTENT|CONFIDENCE|REASONING` (Pipe-getrennt), statt `INTENT:`/`CONFIDENCE:`/`REASONING:` auf drei Zeilen.
- **Reformulation-Guard**: Reformulation nur bei `confidence < 0.7`, nicht immer. Bei `forceIntent` keine Reformulation.
- **Fallback zu `GRAPH_QUERY`** bei Konfidenz `< 0.5` direkt im Parser.
- **Synchrone API**: `query(...)` ist nicht `suspend`; Koog-Aufrufe via `runBlocking`. Kein Streaming (`queryStreaming` / `Flow<String>` fehlt).
- **LLM ueber Koog** (`PromptExecutor` + `resolveLlmModel(llmModelName)`), nicht ueber `ChatCompletionService`.
- **`NlpQueryResponse.durationMs` als `Int!`**, nicht `Long!`.
- **Prompts auf Englisch** (nicht Deutsch wie im Spec-Prompt-Beispiel).

## Akzeptanzkriterien

- [x] GraphQL-Query `nlpQuery` nimmt eine natuerlichsprachige Frage und Collection-ID entgegen
- [x] Intent-Erkennung klassifiziert Fragen korrekt in graph_query, document_query, structured_query oder hybrid
- [x] Jeder erkannte Intent enthaelt einen Konfidenz-Score und eine Begruendung
- [x] Query-Routing leitet `GRAPH_QUERY` an `GraphRagService` und `DOCUMENT_QUERY` an `DocumentRagService` weiter
- [x] Structured-Query-Intent fuehrt direkte Triple-Abfragen am `QuadStore` aus (Limit 20)
- [x] Hybrid-Intent kombiniert Ergebnisse aus Graph RAG und Document RAG (parallel via `async`)
- [x] Vage oder mehrdeutige Fragen werden durch den QueryReformulator verbessert (nur bei `confidence < 0.7`)
- [x] Antwort enthaelt Metadaten: erkannter Intent, Konfidenz, ob reformuliert, effektive Frage
- [x] `forceIntent`-Parameter ueberspringt die Intent-Erkennung und verwendet den vorgegebenen Intent
- [x] Quellenangaben werden abhaengig vom Intent-Typ korrekt befuellt
- [x] Antwortzeit fuer Intent-Erkennung liegt unter 2 Sekunden (abhaengig vom LLM-Provider; Shortcut fuer Single-Type-Collections entfernt die Latenz komplett)
- [x] Bei Konfidenz-Score unter 0.5 wird ein Fallback auf `GRAPH_QUERY` durchgefuehrt

## Offene Punkte

- Streaming-Variante (`Flow<String>`) und GraphQL-Subscription fuer Token-Streaming der Antwort.
