# Administration

Dieses Dokument beschreibt die Verwaltung von GraphMesh: Systemkonfiguration, Mandantenfähigkeit, Admin-Oberfläche und Kommandozeilen-Werkzeuge.

---

## Konfigurationsverwaltung

GraphMesh verwendet ein typisiertes Konfigurationssystem mit versionierten Einträgen. Jeder Konfigurationswert hat einen **Typ**, einen **Schlüssel** und einen **Wert** sowie eine Versionsnummer, die bei jeder Änderung automatisch hochgezählt wird.

### Konfigurationstypen

Die Konfiguration ist in logische Typen gegliedert, zum Beispiel:

- **llm** -- Einstellungen für LLM-Provider (Modellname, API-Endpunkte, Temperatur)
- **extraction** -- Parameter für die Wissensextraktion (Chunk-Grösse, Extraktionsstrategie)
- **embedding** -- Embedding-Konfiguration (Modell, Dimensionen)
- **pipeline** -- Verarbeitungs-Pipeline (Parallelität, Timeouts)

### Konfiguration anzeigen

Alle Konfigurationseinträge eines Typs auflisten:

**Frontend:** Navigieren Sie zu `/admin/config`. Dort sehen Sie alle Konfigurationstypen und ihre Werte in einer tabellarischen Übersicht.

**CLI:**
```bash
graphmesh config list --type llm
```

Einen einzelnen Wert abfragen:
```bash
graphmesh config get model-name --type llm
```

**GraphQL:**
```graphql
query {
  configKeys(type: "llm") {
    key
    value
    version
  }
}

query {
  configValue(key: "model-name", type: "llm") {
    key
    value
    version
  }
}
```

### Konfiguration ändern

**CLI:**
```bash
graphmesh config set model-name "gpt-4o" --type llm
```

**GraphQL:**
```graphql
mutation {
  setConfig(key: "model-name", value: "gpt-4o", type: "llm") {
    key
    value
    version
  }
}
```

Die Versionsnummer wird automatisch erhöht. So lässt sich nachvollziehen, wann eine Einstellung zuletzt geändert wurde.

**Frontend:** Unter `/admin/config` können Sie Werte direkt in der Oberfläche bearbeiten und speichern.

---

## Mandantenfähigkeit

GraphMesh unterstützt Multi-Tenancy: mehrere Organisationen können die gleiche Instanz nutzen, wobei ihre Daten vollständig voneinander isoliert sind.

### Konzept

Jeder Mandant (Tenant) hat eigene:

- **Collections** -- Dokumentensammlungen und deren Wissensgraphen
- **Dokumente** -- Hochgeladene Dateien und extrahierte Inhalte
- **Konfiguration** -- Mandantenspezifische Einstellungen (z.B. eigenes LLM-Modell)
- **Ontologien** -- Eigene Fachvokabulare und Ontologien

### Datenisolation

Die Mandantentrennung stellt sicher, dass:

- Suchanfragen nur Ergebnisse aus dem eigenen Mandanten liefern
- Wissensgraphen nicht mandantenübergreifend verknüpft werden
- Konfigurationsänderungen nur den eigenen Mandanten betreffen
- Dokumente und Collections anderer Mandanten nicht sichtbar sind

### Verwaltung

Mandanten werden über die Admin-Oberfläche (`/admin`) verwaltet. Dort können Sie:

- Neue Mandanten anlegen
- Mandantenspezifische Einstellungen konfigurieren
- Nutzungstatistiken pro Mandant einsehen

---

## Admin UI

Die webbasierte Administrationsoberfläche bietet zentrale Verwaltungsfunktionen.

### Bereiche

| Bereich | URL | Funktion |
|---------|-----|----------|
| Dashboard | `/admin` | Übersicht über System-Status und Statistiken |
| Konfiguration | `/admin/config` | Systemkonfiguration verwalten |
| Collections | `/admin/collections` | Dokumentensammlungen verwalten |
| Ontologien | `/admin/ontologies` | Ontologien importieren und verwalten |
| Pipeline | `/admin/pipeline` | Verarbeitungs-Pipeline überwachen |

### Konfiguration

Unter `/admin/config` sehen Sie alle Konfigurationseinträge gruppiert nach Typ. Jeder Eintrag zeigt Schlüssel, aktuellen Wert und Versionsnummer. Werte lassen sich direkt in der Oberfläche bearbeiten.

### Collection-Verwaltung

Unter `/admin/collections` können Sie:

- Alle Collections mit Name, Beschreibung und Tags anzeigen
- Neue Collections anlegen (Name, Beschreibung, Tags)
- Bestehende Collections bearbeiten oder löschen

### Ontologie-Verwaltung

Unter `/admin/ontologies` können Sie:

- Vorhandene Ontologien auflisten (mit Klassen- und Property-Anzahl)
- Neue Ontologien im Turtle- oder RDF/XML-Format importieren
- Ontologien entfernen

### Pipeline-Überwachung

Unter `/admin/pipeline` sehen Sie den aktuellen Status der Verarbeitungs-Pipeline:

- Dokumente im Status UPLOADED, PROCESSING, EXTRACTED oder FAILED
- Verarbeitungsfortschritt und eventuelle Fehler

---

## CLI-Werkzeuge

GraphMesh bietet eine vollständige Kommandozeilen-Schnittstelle für alle Operationen. Die CLI kommuniziert über die GraphQL-API mit dem Server.

### Verfügbare Befehle

#### collection -- Dokumentensammlungen verwalten

```bash
# Alle Collections auflisten
graphmesh collection list

# Nach Tags filtern
graphmesh collection list --tag forschung --tag intern

# Neue Collection erstellen
graphmesh collection create "Verträge 2026" \
  --description "Alle Vertragsunterlagen" \
  --tag vertraege --tag legal

# Collection löschen
graphmesh collection delete <collection-id>
```

#### document -- Dokumente verwalten

```bash
# Dokument hochladen
graphmesh document upload \
  --collection <collection-id> \
  --file ./vertrag.pdf

# Mit benutzerdefiniertem Titel
graphmesh document upload \
  --collection <collection-id> \
  --file ./bericht.pdf \
  --title "Quartalsbericht Q1 2026"

# Dokumente einer Collection auflisten
graphmesh document list --collection <collection-id>

# Nach Typ filtern (SOURCE, PAGE, CHUNK)
graphmesh document list --collection <collection-id> --type SOURCE

# Dokumentdetails anzeigen
graphmesh document info <document-id>
```

Das Dokument durchläuft nach dem Upload automatisch die Verarbeitungs-Pipeline: UPLOADED -> PROCESSING -> EXTRACTED.

#### query -- Wissen abfragen

```bash
# Graph RAG: Abfrage über den Wissensgraphen
graphmesh query graphrag "Welche Vertragspartner sind beteiligt?" \
  --collection <collection-id>

# Mit Feinsteuerung
graphmesh query graphrag "Welche Vertragspartner sind beteiligt?" \
  --collection <collection-id> \
  --max-edges 200 \
  --max-depth 3 \
  --max-selected 50

# Document RAG: Abfrage über Dokumenteninhalt
graphmesh query docrag "Was sind die wichtigsten Vertragsbedingungen?" \
  --collection <collection-id>

# Mit Feinsteuerung
graphmesh query docrag "Was sind die wichtigsten Vertragsbedingungen?" \
  --collection <collection-id> \
  --top-k 15 \
  --threshold 0.6

# NLP: Automatische Erkennung des Abfragetyps
graphmesh query nlp "Wer hat den Vertrag unterschrieben?" \
  --collection <collection-id>

# Intent erzwingen
graphmesh query nlp "Wer hat den Vertrag unterschrieben?" \
  --collection <collection-id> \
  --force-intent GRAPH_QUERY
```

#### config -- Konfiguration verwalten

```bash
# Alle Einträge eines Typs auflisten
graphmesh config list --type llm

# Einzelnen Wert abfragen
graphmesh config get model-name --type llm

# Wert setzen
graphmesh config set model-name "gpt-4o" --type llm
```

#### explain -- Nachvollziehbarkeit

```bash
# Vergangene Abfrage-Sessions auflisten
graphmesh explain sessions --collection <collection-id>

# Nach Mechanismus filtern
graphmesh explain sessions --collection <collection-id> --mechanism GRAPH_RAG

# Erklärungskette einer Abfrage anzeigen
graphmesh explain trace <session-uri> --collection <collection-id>

# Dokumenthierarchie anzeigen (Quelle -> Seiten -> Chunks)
graphmesh explain document <document-id> --collection <collection-id>
```

Der `explain trace`-Befehl zeigt die vollständige Erklärungskette einer Abfrage: erkannte Frage, Exploration des Graphen, Fokussierung auf relevante Kanten, Analyse-Schritte und Synthese der Antwort.

### Ausgabeformate

Die CLI gibt Ergebnisse in lesbarem Textformat aus. Bei Abfragen werden zusätzlich angezeigt:

- **Graph RAG:** Antwort, ausgewählte Kanten mit Relevanz-Score und Begründung, Dauer
- **Document RAG:** Antwort, Quellen mit Dokumenttitel, Seitenzahl, Score und Textausschnitt, Dauer
- **NLP:** Antwort, erkannter Intent mit Konfidenz, ob die Frage umformuliert wurde, Dauer
