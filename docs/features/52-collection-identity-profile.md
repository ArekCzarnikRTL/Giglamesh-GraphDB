# Feature 52: Collection Identity Profile (Layer-0 Context)

## Problem

Jeder GraphRAG-/DocumentRAG-/NLP-Query-Call haengt aktuell **nackt** an das
Antwort-LLM — das Modell weiss nicht, worum es in der Collection geht, wer
Zielnutzer ist, in welchem Stil geantwortet werden soll. Folgen:

1. **Generische Antworten.** Eine Collection mit interner
   Compliance-Dokumentation wird genauso beantwortet wie ein
   Marketing-Glossar — beide bekommen den gleichen Systemprompt.
2. **Token-Verschwendung.** Nutzer bauen dieselben Kontext-Hinweise
   ("antworte auf Deutsch", "Zielgruppe sind Entwickler", "vermeide
   Marketing-Sprache") immer wieder in ihre Fragen ein.
3. **Keine Identitaet pro Collection.** Mehrere parallele Wissensdomains
   (Produkte, Support, Forschung, HR) bedienen sich am gleichen generischen
   Prompt, obwohl sie radikal unterschiedlich tickenshould.

MemPalace (Artikel vom 08.04.2026) loest das mit einer
**Layer-0-Identity** — einer kleinen Textdatei (~50–100 Tokens) mit Name,
Rolle, Stil, aktuellem Fokus. Sie wird in jede Session geladen. Fuer
GraphMesh ist das Aequivalent ein **Collection-Profile-Header**.

## Ziel

Jede `Collection` erhaelt ein optionales, strukturiertes **Identity
Profile**, das bei jedem RAG-/NLP-Call als System-Prompt-Praefix
automatisch mitgegeben wird.

1. **Datenmodell** — Neue Felder an `Collection` (persona, audience, style,
   focus, languageHint).
2. **GraphQL-API** — CRUD ueber `updateCollectionProfile`-Mutation und
   Rueckgabe in `collection { profile { … } }`.
3. **Prompt-Injection** — `GraphRagService`, `DocumentRagService`,
   `NlpQueryService` und `StreamingAgentService` bauen vor jedem LLM-Call
   das Profile in den System-Prompt ein (via `PromptTemplateRegistry` aus
   Feature 40).
4. **Frontend** — Ein kleiner "Collection-Profile"-Editor im Admin/Collection
   Detail UI.

## Voraussetzungen

| Abhaengigkeit                       | Status       | Blocker? |
|-------------------------------------|--------------|----------|
| Feature 08 (Collection Management)  | Implementiert | Ja       |
| Feature 15 (Graph RAG)              | Implementiert | Ja       |
| Feature 16 (Document RAG)           | Implementiert | Ja       |
| Feature 18 (NLP Query Service)      | Implementiert | Ja       |
| Feature 40 (Prompt Template Registry)| Implementiert | Nein (Fallback: hardcoded System-Prompt) |

## Architektur

### Datenmodell

```kotlin
// com.agentwork.graphmesh.collection
data class CollectionProfile(
    val persona: String? = null,       // z.B. "Senior Compliance Auditor"
    val audience: String? = null,      // z.B. "Product-Managers, non-technical"
    val style: String? = null,         // z.B. "Terse, bullet-points, avoid marketing-speak"
    val focus: String? = null,         // z.B. "2026 GDPR amendments; exclude US regulations"
    val languageHint: String? = null   // z.B. "de-DE" oder "en-US"
)

// Bestehende Collection-Entitaet ergaenzen
data class Collection(
    val id: String,
    val name: String,
    // ... bisherige Felder ...
    val profile: CollectionProfile? = null
)
```

Token-Budget: **max 600 Zeichen gesamt** (ca. 150 Tokens). Harter Cutoff
beim Speichern, damit das Profile nicht zum Dokument wird.

### Cassandra-Schema

Profile wird in den bestehenden `collections`-Table inline als JSON-Spalte
gelegt (keine neue Tabelle — YAGNI):

```cql
ALTER TABLE collections ADD profile_json TEXT;
```

`CollectionRepository` serialisiert `CollectionProfile` via Jackson.
`null` = kein Profile gesetzt = Default-Verhalten wie heute.

### Prompt-Injection

Zentrale Helper-Klasse, die alle Services nutzen:

```kotlin
// com.agentwork.graphmesh.prompt
@Component
class CollectionIdentityPromptBuilder(
    private val collectionService: CollectionService
) {
    /**
     * Baut einen Praefix-Block, der VOR den Task-Anweisungen an das LLM
     * geschickt wird. Leer, wenn kein Profile gesetzt.
     */
    fun buildIdentityBlock(collectionId: String): String {
        val profile = collectionService.getById(collectionId)?.profile ?: return ""

        return buildString {
            appendLine("## Collection Identity")
            profile.persona?.let      { appendLine("- Role: $it") }
            profile.audience?.let     { appendLine("- Audience: $it") }
            profile.style?.let        { appendLine("- Style: $it") }
            profile.focus?.let        { appendLine("- Focus: $it") }
            profile.languageHint?.let { appendLine("- Language: $it") }
        }.trim()
    }
}
```

### Integration in bestehende Services

`GraphRagService.query(...)` (analog in DocumentRag, NlpQuery,
StreamingAgent):

```kotlin
val identity = collectionIdentityPromptBuilder.buildIdentityBlock(query.collectionId)
val systemPrompt = if (identity.isNotBlank()) "$identity\n\n$baseSystemPrompt" else baseSystemPrompt

val prompt = prompt("graph-rag") {
    system(systemPrompt)
    user(query.question)
}
```

### GraphQL

```graphql
input CollectionProfileInput {
  persona: String
  audience: String
  style: String
  focus: String
  languageHint: String
}

type CollectionProfile {
  persona: String
  audience: String
  style: String
  focus: String
  languageHint: String
}

extend type Collection {
  profile: CollectionProfile
}

extend type Mutation {
  updateCollectionProfile(collectionId: ID!, profile: CollectionProfileInput!): Collection!
}
```

### Frontend

Ein einfaches Formular auf der Collection-Detail-Seite mit fuenf Textfeldern
+ Zeichenzaehler (Cap 600). Apollo-Mutation auf Save-Button.

## Betroffene Dateien

### Backend

| Datei                                                              | Aenderung                              |
|--------------------------------------------------------------------|----------------------------------------|
| `collection/CollectionProfile.kt`                                  | NEU — data class                       |
| `collection/Collection.kt`                                         | Feld `profile` ergaenzen               |
| `collection/CollectionRepository.kt`                               | JSON-Serialisierung persistieren/lesen |
| `collection/CollectionService.kt`                                  | `updateProfile(id, profile)`           |
| `prompt/CollectionIdentityPromptBuilder.kt`                        | NEU — Praefix-Builder                  |
| `query/graphrag/GraphRagService.kt`                                | Identity-Block in SystemPrompt         |
| `query/docrag/DocumentRagService.kt`                               | dito                                   |
| `nlp/NlpQueryService.kt`                                           | dito                                   |
| `streaming/StreamingAgentServiceImpl.kt`                           | dito                                   |
| `api/graphql/CollectionResolver.kt`                                | GraphQL-Feld + Mutation                |
| `src/main/resources/graphql/schema.graphqls`                       | Schema-Erweiterung                     |
| `src/main/resources/db/migration/V??__add_collection_profile.cql`  | NEU — ALTER TABLE                      |

### Frontend

| Datei                                                        | Aenderung                        |
|--------------------------------------------------------------|----------------------------------|
| `frontend/src/components/collection/CollectionProfileForm.tsx`| NEU                              |
| `frontend/src/app/collections/[id]/page.tsx`                 | Section "Identity" integrieren   |
| `frontend/src/graphql/collections.graphql`                   | Mutation + Fragment              |

### Tests

| Datei                                                             | Aenderung                    |
|-------------------------------------------------------------------|------------------------------|
| `prompt/CollectionIdentityPromptBuilderTest.kt`                   | NEU — Leer/Full-Profile      |
| `collection/CollectionServiceTest.kt`                             | `updateProfile`              |
| `query/graphrag/GraphRagServiceIntegrationTest.kt`                | Profile wirkt im Prompt      |

## Akzeptanzkriterien

- [ ] Collection ohne Profile verhaelt sich **identisch** zu heute (kein Regress).
- [ ] Gesetztes Profile erscheint deterministisch als erster Block im System-Prompt.
- [ ] Profile ist ueber GraphQL lesbar und per Mutation editierbar.
- [ ] Zeichenlimit (600) wird beim Save validiert, nicht erst beim LLM-Call.
- [ ] Frontend-Editor zeigt Live-Zeichenzaehler und speichert ohne Page-Reload.
- [ ] `NlpQueryService` und RAG-Services nutzen denselben `CollectionIdentityPromptBuilder` (kein Copy&Paste).
- [ ] Integrationstest (mit docker-compose) zeigt, dass gesetzter `languageHint: "de-DE"` zu deutscher Antwort fuehrt.
