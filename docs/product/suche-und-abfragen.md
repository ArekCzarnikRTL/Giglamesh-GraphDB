# Suche und Abfragen

GraphMesh bietet verschiedene Wege, Wissen aus Ihren Dokumenten und dem Knowledge Graph abzufragen -- von gezielten Graph-Abfragen ueber semantische Dokumentensuche bis hin zu automatisch optimierten Freitext-Fragen.

---

## Graph RAG

**Problem:** Traditionelle Suchsysteme finden nur Dokumente, die bestimmte Begriffe enthalten. Zusammenhaenge zwischen Konzepten -- etwa "Welche Lieferanten sind von Regulierung X betroffen?" -- bleiben verborgen.

**Loesung:** Graph RAG durchsucht den Knowledge Graph nach relevanten Wissens-Beziehungen und generiert eine Antwort, die auf konkreten Fakten (Tripeln) basiert. Jede Antwort nennt die verwendeten Beziehungen mit Relevanz-Score und Begruendung.

### Anwendung

**GraphQL API:**

```graphql
query {
  graphRag(input: {
    question: "Welche Technologien nutzt Projekt Alpha?"
    collectionId: "meine-sammlung"
    maxEdges: 150
    maxDepth: 2
    maxSelectedEdges: 30
  }) {
    sessionId
    answer
    retrievedEdgeCount
    durationMs
    selectedEdges {
      subject
      predicate
      objectValue
      reasoning
      relevanceScore
    }
  }
}
```

Parameter:
- `question` -- Die Frage in natuerlicher Sprache
- `collectionId` -- Die Wissenssammlung, die durchsucht wird
- `maxEdges` -- Maximale Anzahl abgerufener Kanten (Standard: 150)
- `maxDepth` -- Suchtiefe im Graph (Standard: 2)
- `maxSelectedEdges` -- Maximale Kanten fuer die Antwort-Generierung (Standard: 30)

**CLI:**

```bash
graphmesh query graphrag "Welche Technologien nutzt Projekt Alpha?" \
  --collection meine-sammlung \
  --max-edges 150 \
  --max-depth 2 \
  --max-selected 30
```

**MCP:** Tool `knowledgeQuery` mit den gleichen Parametern.

**Frontend:** Seite `/query` -- Fragefeld mit Auswahl des Query-Typs "Graph RAG".

### Beispiel

> **Szenario:** Ein Berater moechte wissen, welche Compliance-Anforderungen fuer einen bestimmten Geschaeftsprozess gelten.

1. Der Berater stellt die Frage: "Welche Compliance-Anforderungen gelten fuer den Beschaffungsprozess?"
2. GraphMesh durchsucht den Knowledge Graph und findet 87 relevante Kanten.
3. Das LLM waehlt die 12 relevantesten Beziehungen aus, z.B. `Beschaffungsprozess -- unterliegt -- ISO-27001`, `Beschaffungsprozess -- erfordert -- Lieferantenaudit`.
4. Die Antwort fasst die Anforderungen zusammen und listet jede verwendete Beziehung mit Begruendung und Relevanz-Score auf.

---

## Document RAG

**Problem:** Bei grossen Dokumentenbestaenden ist es schwierig, die relevante Textstelle zu finden. Volltextsuche versagt bei umformulierten Fragen oder Synonymen.

**Loesung:** Document RAG sucht semantisch in Dokumenten-Chunks und generiert eine quellenbasierte Antwort. Jede Antwort verweist auf die konkreten Dokumente, Seiten und Textausschnitte.

### Anwendung

**GraphQL API:**

```graphql
query {
  documentRag(input: {
    question: "Wie ist die Kuendigungsfrist im Rahmenvertrag geregelt?"
    collectionId: "vertraege"
    topK: 10
    similarityThreshold: 0.5
  }) {
    sessionId
    answer
    retrievedChunkCount
    durationMs
    sources {
      documentTitle
      pageNumber
      score
      snippet
    }
  }
}
```

Parameter:
- `question` -- Die Frage in natuerlicher Sprache
- `collectionId` -- Die Dokumentensammlung
- `topK` -- Maximale Anzahl abgerufener Textabschnitte (Standard: 10)
- `similarityThreshold` -- Mindest-Aehnlichkeit fuer relevante Treffer (Standard: 0.5)

**CLI:**

```bash
graphmesh query docrag "Wie ist die Kuendigungsfrist geregelt?" \
  --collection vertraege \
  --top-k 10 \
  --threshold 0.6
```

**MCP:** Tool `documentQuery` mit den gleichen Parametern.

**Frontend:** Seite `/query` -- Fragefeld mit Auswahl des Query-Typs "Document RAG".

### Beispiel

> **Szenario:** Eine Juristin sucht nach Haftungsklauseln in einem Vertragswerk mit 200 Dokumenten.

1. Sie fragt: "Welche Haftungsbeschraenkungen gelten fuer Unterauftragnehmer?"
2. GraphMesh findet 7 semantisch relevante Textabschnitte aus 4 verschiedenen Vertraegen.
3. Die Antwort nennt die konkreten Regelungen und verweist auf jedes Quelldokument mit Seitenzahl und Textausschnitt.
4. Die Juristin kann direkt zur relevanten Stelle im Originaldokument navigieren.

---

## NLP Query Service

**Problem:** Nutzer muessen nicht wissen, ob ihre Frage besser ueber den Knowledge Graph, die Dokumentensuche oder eine strukturierte Abfrage beantwortet wird.

**Loesung:** Der NLP Query Service erkennt automatisch die beste Abfrage-Strategie (Intent) und leitet die Frage entsprechend weiter. Bei Bedarf reformuliert er die Frage fuer bessere Ergebnisse.

### Anwendung

**GraphQL API:**

```graphql
query {
  nlpQuery(input: {
    question: "Welche Projekte nutzen Kubernetes?"
    collectionId: "it-landschaft"
  }) {
    answer
    detectedIntent {
      intent        # GRAPH_QUERY, DOCUMENT_QUERY, STRUCTURED_QUERY oder HYBRID
      confidence
      reasoning
    }
    wasReformulated
    effectiveQuestion
    durationMs
    sources
  }
}
```

Intent-Typen:
- `GRAPH_QUERY` -- Beziehungsfragen, die ueber den Knowledge Graph beantwortet werden
- `DOCUMENT_QUERY` -- Inhaltsfragen, die in Dokumenten gesucht werden
- `STRUCTURED_QUERY` -- Praezise Abfragen mit klarer Struktur
- `HYBRID` -- Kombination mehrerer Strategien fuer komplexe Fragen

Optional kann ein Intent erzwungen werden:

```graphql
nlpQuery(input: {
  question: "Welche Projekte nutzen Kubernetes?"
  collectionId: "it-landschaft"
  forceIntent: GRAPH_QUERY
})
```

**CLI:**

```bash
graphmesh query nlp "Welche Projekte nutzen Kubernetes?" \
  --collection it-landschaft

# Intent erzwingen:
graphmesh query nlp "Welche Projekte nutzen Kubernetes?" \
  --collection it-landschaft \
  --force-intent GRAPH_QUERY
```

**Frontend:** Seite `/query` -- Standard-Fragefeld (NLP ist der voreingestellte Query-Modus).

### Beispiel

> **Szenario:** Ein Projektmanager stellt verschiedene Fragen zu seiner IT-Landschaft.

1. Frage: "Welche Systeme kommunizieren mit dem ERP?" -- Intent erkannt: `GRAPH_QUERY` (Confidence: 0.92). Die Frage wird ueber den Knowledge Graph beantwortet, der die Systembeziehungen kennt.
2. Frage: "Was steht im Migrationsplan zu SAP?" -- Intent erkannt: `DOCUMENT_QUERY` (Confidence: 0.88). Die Frage wird in den Dokumenten gesucht.
3. Frage: "Vergleiche die Sicherheitskonzepte aller Cloud-Projekte" -- Intent erkannt: `HYBRID` (Confidence: 0.78). GraphMesh kombiniert Graph- und Dokumentensuche fuer ein umfassendes Ergebnis.

---

## Query Explainability

**Problem:** Bei KI-generierten Antworten ist oft unklar, wie das Ergebnis zustande kam. Fuer Audits, Qualitaetssicherung und Vertrauen braucht man Nachvollziehbarkeit.

**Loesung:** Jede Abfrage erzeugt eine Erklaerungskette (Explanation Chain), die den gesamten Denkprozess dokumentiert -- von der Exploration ueber die Fokussierung und Analyse bis zur Synthese.

### Anwendung

**Vergangene Abfragen auflisten:**

```graphql
query {
  explanationSessions(
    collectionId: "meine-sammlung"
    mechanism: GRAPH_RAG   # oder DOC_RAG, AGENT
    limit: 50
  ) {
    uri
    queryText
    timestamp
    mechanism
  }
}
```

**Erklaerungskette einer Abfrage abrufen:**

```graphql
query {
  explanationChain(
    collectionId: "meine-sammlung"
    sessionUri: "session-123"
  ) {
    mechanism
    question { queryText timestamp }
    exploration { edgeCount }
    focus {
      selectedEdges {
        subject
        predicate
        objectValue
        reasoning
      }
    }
    analyses {
      iterationIndex
      thought
      action
      observation
    }
    synthesis { answerText }
    conclusion { answerText }
  }
}
```

Stufen der Erklaerungskette:
- **Exploration** -- Wie viele Wissens-Kanten wurden gefunden?
- **Focus** -- Welche Kanten wurden ausgewaehlt und warum?
- **Analysis** -- Welche Denkschritte hat das LLM durchlaufen? (Thought, Action, Observation)
- **Synthesis** -- Wie wurde die Antwort formuliert?
- **Conclusion** -- Die finale Antwort

**CLI:**

```bash
# Vergangene Abfragen auflisten
graphmesh explain sessions --collection meine-sammlung --mechanism GRAPH_RAG --limit 20

# Erklaerungskette anzeigen
graphmesh explain trace session-123 --collection meine-sammlung --max-answer 500

# Dokument-Hierarchie anzeigen
graphmesh explain document doc-456 --collection meine-sammlung
```

**Frontend:** Seite `/query` -- Nach jeder Antwort kann die Erklaerungskette aufgeklappt werden.

### Beispiel

> **Szenario:** Ein Auditor prueft, ob eine KI-generierte Antwort korrekt und nachvollziehbar ist.

1. Der Auditor ruft die Liste vergangener Abfragen auf und waehlt eine Frage aus: "Welche Datenschutz-Anforderungen gelten fuer Cloud-Services?"
2. In der Erklaerungskette sieht er: **Exploration** -- 124 Kanten gefunden.
3. **Focus** -- 8 Kanten ausgewaehlt, z.B. `CloudService -- unterliegt -- DSGVO-Art-28` mit Begruendung "Auftragsverarbeitung relevant fuer Cloud-Dienste".
4. **Analysis** -- 3 Iterationen: Zuerst Identifikation der Cloud-Services, dann Zuordnung der Datenschutz-Normen, schliesslich Pruefung auf Vollstaendigkeit.
5. **Synthesis/Conclusion** -- Die zusammengefasste Antwort mit allen Datenschutz-Anforderungen.
6. Der Auditor kann jeden Schritt nachvollziehen und die Qualitaet der Antwort bewerten.

---

## Query Performance Optimization

**Problem:** Bei grossen Wissensbestaenden koennen Abfragen langsam werden, besonders wenn viele Kanten traversiert oder viele Dokumente durchsucht werden muessen.

**Loesung:** GraphMesh optimiert Abfragen automatisch durch Caching haeufig angefragter Ergebnisse, effiziente Graph-Traversierung mit konfigurierbarer Suchtiefe und Ergebnis-Begrenzung.

### Anwendung

Die Performance-Optimierung arbeitet im Hintergrund. Nutzer steuern sie ueber die Abfrage-Parameter:

- **Graph RAG:** `maxEdges` begrenzt die Exploration, `maxDepth` die Suchtiefe, `maxSelectedEdges` die Antwort-Komplexitaet.
- **Document RAG:** `topK` begrenzt die Anzahl abgerufener Textabschnitte, `similarityThreshold` filtert irrelevante Treffer.
- **Antwortzeiten** werden bei jeder Abfrage als `durationMs` zurueckgegeben, sodass Engpaesse sichtbar werden.

### Beispiel

> **Szenario:** Ein Team fuehrt regelmaessig dieselben Abfragen auf einer grossen Wissensbasis durch.

1. Die erste Abfrage "Welche Risiken bestehen fuer Projekt X?" dauert 2.300 ms bei 200 traversierten Kanten.
2. Durch Reduzierung von `maxEdges` auf 80 und `maxDepth` auf 1 sinkt die Antwortzeit auf 850 ms -- bei gleicher Antwortqualitaet fuer diese spezifische Frage.
3. Wiederholte Abfragen profitieren vom Cache und antworten noch schneller.
