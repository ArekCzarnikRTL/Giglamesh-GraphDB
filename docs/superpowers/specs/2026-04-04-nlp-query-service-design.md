# NLP Query Service Design

## Zusammenfassung

Vervollstaendigung des teilweise implementierten NLP Query Service (Feature 18). Der Service nimmt natuerlichsprachige Fragen entgegen, erkennt per LLM den optimalen Abfragetyp, reformuliert bei Bedarf vage Fragen, und routet an den passenden Backend-Service.

## Entscheidungen

| Entscheidung | Wahl | Begruendung |
|---|---|---|
| LLM-Framework | Koog PromptExecutor | Konsistent mit bestehendem Code (GraphRag, DocumentRag, MCP-Tools) |
| Architektur | Monolithischer NlpQueryService | Weniger Dateien, bestehende Struktur beibehalten |
| Streaming | Nicht implementiert | Bestehender Code durchgehend synchron mit runBlocking |
| Reformulator | Private Methode in NlpQueryService | Passt zum monolithischen Ansatz |

## Ist-Zustand

Bereits implementiert:
- `NlpQueryService.kt` — Intent-Detection und Routing (monolithisch, Koog PromptExecutor)
- `NlpQueryModels.kt` — NlpQuery, QueryIntent, DetectedIntent, NlpQueryResult
- `NlpQueryController.kt` — GraphQL @QueryMapping Controller
- `nlp-query.graphqls` — GraphQL-Schema
- `NlpQueryServiceTest.kt` — 8 Parsing-Tests fuer Intent-Response-Parsing

Fehlend:
- QueryReformulator-Logik
- `wasReformulated`-Feld in NlpQueryResult und GraphQL-Schema
- `reformulatedQuestion`-Feld in DetectedIntent
- Vollstaendige Unit-Tests (Routing, Reformulator, Integration)

## Aenderungen

### 1. Datenmodell-Erweiterungen (NlpQueryModels.kt)

**DetectedIntent** — neues Feld:
```kotlin
val reformulatedQuestion: String? = null
```

**NlpQueryResult** — neues Feld:
```kotlin
val wasReformulated: Boolean
```

### 2. GraphQL-Schema (nlp-query.graphqls)

`NlpQueryResponse` erhaelt `wasReformulated: Boolean!`.

### 3. Reformulator-Logik (NlpQueryService.kt)

Neue private Methode `reformulate(question: String, intent: QueryIntent): String?`:
- Eigener System-Prompt, der das LLM anweist vage Fragen zu verbessern
- Wird nach Intent-Detection aufgerufen, nur wenn `confidence < 0.7`
- Gibt reformulierte Frage zurueck oder `null` wenn keine Aenderung noetig
- Nutzt bestehenden PromptExecutor mit Format: Input = Original-Frage + Intent, Output = verbesserte Frage oder "KEINE_AENDERUNG"

**Angepasster Flow in `query()`:**
1. Intent-Detection (bestehend)
2. NEU: Wenn `confidence < 0.7` -> `reformulate(question, intent)`
3. `effectiveQuestion` = reformulierte Frage oder Original
4. Routing an passenden Service (bestehend)
5. Result mit `wasReformulated`-Flag zurueckgeben

### 4. Tests (NlpQueryServiceTest.kt)

Bestehende 8 Parsing-Tests bleiben. Neue Unit-Tests mit gemockten Dependencies (PromptExecutor, GraphRagService, DocumentRagService, QuadStoreService):

- `query()` mit `forceIntent` ueberspringt Intent-Detection
- `query()` routet GRAPH_QUERY an GraphRagService
- `query()` routet DOCUMENT_QUERY an DocumentRagService
- `query()` routet STRUCTURED_QUERY an QuadStore
- `query()` HYBRID kombiniert Graph- und Document-Ergebnisse
- Reformulator wird bei niedriger Konfidenz (< 0.7) aufgerufen
- Reformulator wird bei hoher Konfidenz uebersprungen
- `wasReformulated` wird korrekt gesetzt
- Low-Confidence-Fallback (< 0.5) auf GRAPH_QUERY

Kein GraphQL-Controller-Test (duenner Adapter, Logik im Service getestet).

## Betroffene Dateien

| Datei | Aenderung |
|---|---|
| `src/main/kotlin/.../query/nlp/NlpQueryModels.kt` | Felder ergaenzen |
| `src/main/kotlin/.../query/nlp/NlpQueryService.kt` | Reformulator-Methode, wasReformulated-Logik |
| `src/main/resources/graphql/nlp-query.graphqls` | wasReformulated ergaenzen |
| `src/test/kotlin/.../query/nlp/NlpQueryServiceTest.kt` | Unit-Tests erweitern |
