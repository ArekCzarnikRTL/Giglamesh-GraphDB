# Wissensextraktion

Nach dem Upload verarbeitet GraphMesh Dokumente automatisch: Der Text wird in Abschnitte aufgeteilt, und aus jedem Abschnitt extrahiert das System strukturiertes Wissen. Nutzer muessen die Extraktion nicht manuell ausloesen -- sie laeuft im Hintergrund, sobald ein Dokument hochgeladen wurde.

---

## Beziehungsextraktion

**Problem:** In Freitexten stecken Fakten und Zusammenhaenge, die fuer Suche und Analyse nur nutzbar sind, wenn sie strukturiert vorliegen.

**Loesung:** GraphMesh erkennt automatisch Beziehungen in Textabschnitten und speichert sie als Subjekt-Praedikat-Objekt-Tripel (z.B. "Firma X" -- "hat Hauptsitz in" -- "Berlin"). Diese Tripel bilden den Knowledge Graph, der anschliessend durchsucht und fuer RAG-Abfragen genutzt werden kann.

#### Anwendung

- **API:**
  ```graphql
  # Extrahierte Tripel durchsuchen
  query {
    triples(
      collectionId: "col-123"
      subject: "Firma X"
      limit: 50
    ) {
      subject
      predicate
      object
      dataset
    }
  }

  # Nach Praedikat filtern
  query {
    triples(
      collectionId: "col-123"
      predicate: "hat Hauptsitz in"
    ) {
      subject
      predicate
      object
    }
  }

  # Entitaeten suchen (Autovervollstaendigung)
  query {
    entitySearch(
      collectionId: "col-123"
      prefix: "Firma"
      limit: 10
    )
  }

  # Graph-Metadaten abrufen
  query {
    graphMetadata(collectionId: "col-123") {
      datasets
      predicates
      entityTypes
    }
  }
  ```

#### Beispiel

1. Ein Geschaeftsbericht wird hochgeladen: "Die Firma Muster GmbH hat ihren Hauptsitz in Muenchen und beschaeftigt 500 Mitarbeiter."
2. GraphMesh extrahiert automatisch zwei Tripel:
   - `Muster GmbH` -- `hat Hauptsitz in` -- `Muenchen`
   - `Muster GmbH` -- `beschaeftigt` -- `500 Mitarbeiter`
3. Ueber die API lassen sich alle Beziehungen der "Muster GmbH" oder alle Entitaeten mit Hauptsitz in "Muenchen" abfragen.

---

## Ontologie-gestuetzte Extraktion

**Problem:** Ohne Vorgaben erkennt die KI zwar Beziehungen, nutzt aber inkonsistente Bezeichnungen -- "Firmensitz", "Hauptsitz" und "Standort" meinen dasselbe, werden aber als verschiedene Praedikate gespeichert.

**Loesung:** Durch den Import einer Ontologie (Begriffssystem mit definierten Klassen und Beziehungen) erhaelt die Extraktion einen Leitfaden. Die KI bevorzugt dann Begriffe aus der Ontologie und erzeugt konsistentere, besser verknuepfte Tripel.

#### Anwendung

- **API:**
  ```graphql
  # Ontologie importieren (Turtle- oder RDF/XML-Format)
  mutation {
    importOntology(input: {
      key: "unternehmen-ontologie"
      name: "Unternehmensontologie"
      namespace: "https://example.org/ontology/unternehmen#"
      content: "<Ontologie im Turtle-Format>"
      format: TURTLE
      version: "1.0"
    }) {
      key
      name
      classCount
      objectPropertyCount
      datatypePropertyCount
    }
  }

  # Verfuegbare Ontologien auflisten
  query {
    listOntologies {
      key
      name
      namespace
      version
      classCount
      objectPropertyCount
    }
  }

  # Details einer Ontologie abrufen
  query {
    ontology(key: "unternehmen-ontologie") {
      key
      name
      classCount
      objectPropertyCount
      datatypePropertyCount
    }
  }

  # Ontologie loeschen
  mutation {
    deleteOntology(key: "unternehmen-ontologie")
  }
  ```

#### Beispiel

1. Der Wissensmanager importiert eine Unternehmensontologie, die Klassen wie "Organisation", "Person", "Standort" und Beziehungen wie "hatHauptsitz", "istMitarbeiterVon" definiert.
2. Bei der naechsten Dokumentverarbeitung nutzt GraphMesh diese Ontologie als Leitfaden.
3. Statt "Firmensitz" und "Hauptsitz" als getrennte Praedikate erscheint nun einheitlich `hatHauptsitz`.
4. Die konsistenten Bezeichnungen verbessern Suchergebnisse und Graph-Abfragen deutlich.

---

## Strukturierte Daten Extraktion

**Problem:** Dokumente enthalten neben Fliesstext oft Tabellen, Listen und strukturierte Daten, die bei reiner Textextraktion verloren gehen oder falsch interpretiert werden.

**Loesung:** GraphMesh erkennt strukturierte Bereiche in Dokumenten und extrahiert deren Inhalte als typisierte Tripel. Tabellarische Daten werden dabei zeilenweise in Beziehungen ueberfuehrt, sodass einzelne Datenpunkte direkt abfragbar sind.

Jedes Tripel traegt einen Datentyp (`objectType`), der angibt, ob der Wert ein Text, eine Zahl, ein Datum oder eine Referenz auf eine andere Entitaet ist.

#### Anwendung

- **API:**
  ```graphql
  # Tripel nach Dataset filtern (z.B. aus einer bestimmten Tabelle)
  query {
    triples(
      collectionId: "col-123"
      dataset: "Finanzkennzahlen"
      limit: 100
    ) {
      subject
      predicate
      object
      objectType
      datatype
    }
  }
  ```

#### Beispiel

1. Ein Finanzbericht enthaelt eine Tabelle mit Quartalszahlen.
2. GraphMesh extrahiert die Tabelle und erzeugt Tripel wie:
   - `Q1 2025` -- `Umsatz` -- `12.5 Mio EUR` (Datentyp: Waehrungsbetrag)
   - `Q1 2025` -- `EBIT` -- `2.1 Mio EUR` (Datentyp: Waehrungsbetrag)
3. Diese strukturierten Daten lassen sich gezielt abfragen, ohne den gesamten Bericht durchsuchen zu muessen.

---

## Agenten-basierte Extraktion

**Problem:** Einfache Einmal-Extraktion stoesst bei komplexen Dokumenten an Grenzen -- Kontext geht verloren, mehrstufige Zusammenhaenge werden nicht erkannt.

**Loesung:** GraphMesh setzt KI-Agenten ein, die Dokumente in mehreren Schritten analysieren. Der Agent kann dabei auf bereits extrahiertes Wissen im Graphen zurueckgreifen, Rueckfragen an das LLM stellen und iterativ tiefere Zusammenhaenge erkennen.

Die Agenten-Extraktion laeuft automatisch als Teil der Verarbeitungspipeline. Zusaetzlich koennen Agenten auch interaktiv fuer Frage-Antwort-Szenarien genutzt werden.

#### Anwendung

- **API:**
  ```graphql
  # Agenten-Abfrage starten
  mutation {
    askAgent(input: {
      question: "Welche Geschaeftsrisiken werden im Jahresbericht erwaehnt?"
      collectionId: "col-123"
      maxIterations: 10
    }) {
      sessionId
      answer
      durationMs
    }
  }

  # Verfuegbare Agent-Tools anzeigen
  query {
    agentTools {
      name
      description
      groups
    }
  }

  # Tool-Gruppen anzeigen
  query {
    toolGroups {
      name
      description
      toolNames
    }
  }
  ```

#### Beispiel

1. Ein Analyst stellt die Frage: "Welche Geschaeftsrisiken werden im Jahresbericht erwaehnt?"
2. Der Agent durchsucht zunaechst den Knowledge Graph nach relevanten Tripeln.
3. Dann analysiert er die zugehoerigen Textabschnitte im Detail.
4. Nach mehreren Iterationen liefert er eine zusammenfassende Antwort mit Quellenverweisen.

---

## Themenextraktion

**Problem:** Bei grossen Dokumentsammlungen fehlt der Ueberblick -- welche Themen werden behandelt, wie haengen Dokumente inhaltlich zusammen?

**Loesung:** GraphMesh erkennt automatisch Themen und Kategorien in jedem Textabschnitt. Die erkannten Themen werden als strukturierte Konzepte (SKOS) im Knowledge Graph abgelegt und koennen hierarchisch durchsucht werden. Themen mit niedriger Erkennungssicherheit werden automatisch herausgefiltert.

#### Anwendung

- **API:**
  ```graphql
  # Themen-Schemata einer Collection abrufen
  query {
    skosConceptSchemes(collectionId: "col-123") {
      uri
      prefLabels { value lang }
      topConcepts { uri prefLabels { value lang } }
      conceptCount
    }
  }

  # Alle Konzepte eines Schemas auflisten
  query {
    skosConcepts(
      collectionId: "col-123"
      schemeUri: "https://example.org/topics"
    ) {
      uri
      prefLabels { value lang }
      broader { uri prefLabels { value lang } }
      narrower { uri prefLabels { value lang } }
      scopeNote
    }
  }

  # Nach einem Thema suchen
  query {
    skosSearch(collectionId: "col-123", label: "Nachhaltigkeit") {
      uri
      prefLabels { value lang }
      related { uri prefLabels { value lang } }
      definition
    }
  }

  # Einzelnes Konzept mit Hierarchie abrufen
  query {
    skosConcept(
      collectionId: "col-123"
      conceptUri: "https://example.org/topics/nachhaltigkeit"
    ) {
      uri
      prefLabels { value lang }
      broader { uri prefLabels { value lang } }
      narrower { uri prefLabels { value lang } }
      related { uri prefLabels { value lang } }
      scopeNote
      definition
    }
  }
  ```

#### Beispiel

1. Eine Sammlung von 50 Forschungsberichten wird hochgeladen.
2. GraphMesh erkennt automatisch Themen wie "Kuenstliche Intelligenz", "Nachhaltigkeit", "Lieferketten" und ordnet sie hierarchisch:
   - `Technologie` -> `Kuenstliche Intelligenz` -> `Maschinelles Lernen`
   - `Nachhaltigkeit` -> `CO2-Reduktion`
3. Ueber die Themensuche findet ein Analyst alle Dokumente, die mit "Nachhaltigkeit" oder verwandten Unterthemen verknuepft sind.
4. Die hierarchische Struktur erlaubt es, von einem Oberthema aus in speziellere Unterthemen zu navigieren.
