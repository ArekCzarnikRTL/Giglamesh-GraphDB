# Feature 48: MCP-Tools Erweiterung (Agent, NLP, Ontologie, Daten)

## Problem

Der MCP-Server in GraphMesh bietet aktuell nur 4 Tools (`knowledgeQuery`, `documentQuery`,
`collectionList`, `documentSearch`). Externe MCP-Clients (z.B. Claude Desktop, Cursor,
andere Agenten) koennen zwar den Wissensgraph und Dokumente abfragen, aber nicht:

1. **Den ReAct-Agent nutzen** — Der Agent mit Tool-Binding kann nur ueber GraphQL-Streaming angesprochen werden.
2. **NLP-Queries stellen** — Die Intent-erkannte NLP-Abfrage ist nur ueber GraphQL verfuegbar.
3. **Ontologien verwalten** — Kein Zugriff auf Import, Export, Delete oder Zuordnung von Ontologien.
4. **RDF-Daten verwalten** — Kein Import, keine Statistiken, kein Loeschen von Datasets.

Das schraenkt die Autonomie von externen Agenten ein: Ein Agent kann Wissen abfragen,
aber nicht selbst Wissen pflegen oder den GraphMesh-Agenten als Sub-Tool nutzen.

## Ziel

Die bestehende `GraphMeshMcpTools`-Klasse um 11 neue Tools erweitern, die Agent-Zugriff,
NLP-Query und vollstaendige Ontologie-/Datenverwaltung via MCP ermoeglichen.

1. **Agent-Zugriff** — Ein MCP-Client kann den GraphMesh-ReAct-Agent als Sub-Tool einbinden.
2. **NLP-Query** — Einfache semantische Suche mit Intent-Erkennung.
3. **Ontologie-CRUD** — Import, Delete, Read, Zuordnen, Entfernen der Zuordnung.
4. **Daten-CRUD** — RDF-Import, Stats, Dataset-Loeschen.

## Voraussetzungen

| Abhaengigkeit                                              | Status        | Blocker? |
|------------------------------------------------------------|---------------|----------|
| Feature 17: MCP Tool Interface (Spring AI MCP)             | Implementiert | Ja       |
| Feature 18: NLP Query Service                              | Implementiert | Ja       |
| Feature 20/44: Ontology System + OntologyService           | Implementiert | Ja       |
| Feature 25: Agent System (AgentService + ReAct Loop)       | Implementiert | Ja       |
| Feature 43: RDF Data Import (RdfImportService)             | Implementiert | Ja       |
| Feature 47: Collection Data Management (CollectionOntologyService) | Implementiert | Ja |

## Architektur

### Erweiterung von `GraphMeshMcpTools`

Die bestehende Klasse `com.agentwork.graphmesh.api.mcp.GraphMeshMcpTools` wird um die
neuen Tools erweitert. Keine neue Klasse — alles bleibt in einer Datei mit klarer
Gliederung durch Kommentar-Blocks.

**Neue Dependencies (Konstruktor-Parameter):**

- `AgentService`
- `NlpQueryService`
- `OntologyService`
- `CollectionOntologyService`
- `RdfImportService`
- `QuadStore`

### Tool-Uebersicht (11 neue Tools)

| # | Tool | Parameter | Delegiert an |
|---|------|-----------|--------------|
| 1 | `agentQuery` | question, collectionId, allowedGroups? | `AgentService.query()` |
| 2 | `nlpQuery` | question, collectionId | `NlpQueryService.query()` |
| 3 | `importOntology` | key, content, format, name, namespace, version? | `OntologyService.importTurtle/importRdfXml` |
| 4 | `deleteOntology` | key | `OntologyService.delete()` |
| 5 | `getOntology` | key | `OntologyService.exportTurtle()` |
| 6 | `listCollectionOntologies` | collectionId | `CollectionOntologyService.listForCollection()` |
| 7 | `assignOntology` | collectionId, ontologyKey, role | `CollectionOntologyService.assign()` |
| 8 | `unassignOntology` | collectionId, ontologyKey | `CollectionOntologyService.unassign()` |
| 9 | `importRdf` | collectionId, content, format, dataset?, generateEmbeddings? | `RdfImportService.importRdf()` |
| 10 | `collectionDataStats` | collectionId | `QuadStore.stats()` |
| 11 | `deleteTriples` | collectionId, dataset? | `QuadStore.deleteByDataset()` / `deleteCollection()` |

### Content-Uebertragung

MCP ist JSON-RPC-basiert. Im Gegensatz zu GraphQL (wo grosse Payloads per Base64 ueber
HTTP fliessen) werden TTL- und RDF-Inhalte als **Plain-Text-Strings** uebertragen. Die
Tools kodieren intern falls der unterliegende Service Base64 erwartet (z.B.
`importOntology` kodiert vor dem Aufruf).

### Agent-Integration

Der `agentQuery`-Tool arbeitet **synchron und blockierend** — er wartet auf das
Ergebnis des ReAct-Agenten. Streaming wird bewusst nicht unterstuetzt, da MCP
aktuell keine Streaming-Antworten hat. Die Tool-Beschreibung weist darauf hin,
dass komplexe Fragen laenger dauern koennen.

Der optionale `allowedGroups`-Parameter (als kommagetrennter String) wird vor
dem Service-Aufruf in ein `Set<String>` konvertiert. Default: `"all"`.

### Antwortformat

Alle Tools geben formatierten Text zurueck (konsistent mit den bestehenden Tools).
Beispiele:

- `agentQuery`: `<Antwort>\n\n--- Session: <uuid>, Duration: Xms ---`
- `importOntology`: `Imported ontology 'pharma-onto': 42 classes, 15 object properties, 8 datatype properties`
- `getOntology`: der rohe Turtle-Export
- `collectionDataStats`: `Collection 'col-1': 847 triples, 312 entities, 24 predicates.\nDatasets: default, import-2026-04`
- `deleteTriples`: `Deleted 42 triples from dataset 'test-ds'` / `Deleted all triples from collection 'col-1'`

### Struktur-Gliederung in `GraphMeshMcpTools`

Die Datei waechst auf ~15 Methoden und wird mit Kommentar-Blocks gegliedert:

```kotlin
// ==========================================================================
// RAG Tools (existing)
// ==========================================================================
@McpTool fun knowledgeQuery(...)
@McpTool fun documentQuery(...)
@McpTool fun collectionList(...)
@McpTool fun documentSearch(...)

// ==========================================================================
// Agent & NLP Tools
// ==========================================================================
@McpTool fun agentQuery(...)
@McpTool fun nlpQuery(...)

// ==========================================================================
// Ontology Tools
// ==========================================================================
@McpTool fun importOntology(...)
@McpTool fun deleteOntology(...)
@McpTool fun getOntology(...)
@McpTool fun listCollectionOntologies(...)
@McpTool fun assignOntology(...)
@McpTool fun unassignOntology(...)

// ==========================================================================
// Data Tools
// ==========================================================================
@McpTool fun importRdf(...)
@McpTool fun collectionDataStats(...)
@McpTool fun deleteTriples(...)
```

## Betroffene Dateien

### Backend

| Datei                                                                           | Aenderung                                                      |
|---------------------------------------------------------------------------------|----------------------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/api/mcp/GraphMeshMcpTools.kt`         | UPDATE — 11 neue Tools + 6 neue Konstruktor-Dependencies       |

### Tests

| Datei                                                                                  | Aenderung                                           |
|----------------------------------------------------------------------------------------|-----------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/api/mcp/GraphMeshMcpToolsTest.kt`            | NEU — Unit-Tests fuer die 11 neuen Tools (MockK)    |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                                        |
|-------------------|-------------|--------------------------------------------------------------|
| Spring Boot (JVM) | Ja          | Spring AI MCP-Server ist JVM-only                            |

## Akzeptanzkriterien

- [ ] `agentQuery`-Tool startet einen ReAct-Agent und gibt die vollstaendige Antwort zurueck (synchron).
- [ ] `agentQuery` unterstuetzt den optionalen `allowedGroups`-Parameter als kommagetrennten String.
- [ ] `nlpQuery`-Tool gibt Antwort, erkannten Intent und Duration zurueck.
- [ ] `importOntology` akzeptiert TTL/RDF-XML als Plain-Text, kodiert intern zu Base64 falls noetig.
- [ ] `deleteOntology` loescht die Ontologie global (Feature 44 Semantik).
- [ ] `getOntology` gibt den aktuellen Turtle-Export zurueck.
- [ ] `listCollectionOntologies` listet alle zugeordneten Ontologien einer Collection mit Rolle.
- [ ] `assignOntology` und `unassignOntology` funktionieren mit der n:m-Zuordnung aus Feature 47.
- [ ] `importRdf` akzeptiert TTL/RDF-XML/N-Triples als Plain-Text, mit optionalem Dataset-Namen.
- [ ] `collectionDataStats` liefert Tripel-Anzahl, Entitaeten, Praedikate und Datasets.
- [ ] `deleteTriples` loescht entweder nur ein Dataset oder die gesamte Collection (bei fehlendem Parameter).
- [ ] Alle Tools haben aussagekraeftige `description`-Strings im `@McpTool`-Annotation.
- [ ] Alle neuen Tools haben mindestens einen Unit-Test der Service-Delegation und Antwortformat prueft.
- [ ] Bestehende MCP-Tools funktionieren weiterhin (Regression).
- [ ] Die erweiterte `GraphMeshMcpTools`-Datei ist durch Kommentar-Blocks in vier logische Bereiche gegliedert.
