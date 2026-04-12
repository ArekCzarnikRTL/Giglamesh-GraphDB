# Dokumentenverwaltung

GraphMesh organisiert Dokumente in **Collections** (thematische Sammlungen) und verarbeitet hochgeladene Dateien automatisch: Texterkennung, Aufteilung in semantische Abschnitte und Vorbereitung fuer die Wissensextraktion.

---

## Collections

### Collection-Verwaltung

**Problem:** Ohne Struktur landen alle Dokumente in einem grossen Topf -- Suchergebnisse werden ungenau, Zugriffssteuerung ist unmoeglich.

**Loesung:** Collections buendeln zusammengehoerige Dokumente unter einem Namen mit optionaler Beschreibung und Tags. Jede Abfrage (Suche, RAG, Graph) arbeitet auf genau einer Collection.

#### Anwendung

- **Frontend:** Unter `/admin/collections` koennen Collections angelegt, bearbeitet und geloescht werden.
- **API:**
  ```graphql
  # Alle Collections auflisten (optional nach Tags filtern)
  query {
    collections(tags: ["forschung"]) {
      id
      name
      description
      tags
      createdAt
    }
  }

  # Neue Collection anlegen
  mutation {
    createCollection(input: {
      name: "Forschungsberichte"
      description: "Berichte aus der F&E-Abteilung"
      tags: ["forschung", "2026"]
    }) {
      id
      name
    }
  }

  # Collection aktualisieren
  mutation {
    updateCollection(id: "col-123", input: {
      description: "Aktualisierte Beschreibung"
      tags: ["forschung", "2026", "Q2"]
    }) {
      id
      name
      tags
    }
  }

  # Collection loeschen
  mutation {
    deleteCollection(id: "col-123")
  }
  ```
- **CLI:**
  ```bash
  # Collections auflisten
  graphmesh collection list
  graphmesh collection list --tag forschung

  # Neue Collection anlegen
  graphmesh collection create "Forschungsberichte" \
      --description "Berichte aus der F&E-Abteilung" \
      --tag forschung --tag 2026

  # Collection loeschen
  graphmesh collection delete col-123
  ```
- **MCP:** Das Tool `collectionList` liefert alle verfuegbaren Collections fuer KI-Agenten.

#### Beispiel

1. Der Teamleiter legt die Collection "Vertragsarchiv 2026" mit den Tags `vertrag` und `2026` an.
2. Mitarbeitende laden Vertraege in diese Collection hoch.
3. Spaeter filtert das Team nach Tag `vertrag`, um alle vertragsrelevanten Collections auf einen Blick zu sehen.

---

## Dokumente

### Dokument-Upload und -Verwaltung

**Problem:** Dokumente muessen ins System gelangen und dort auffindbar bleiben -- mit Status-Transparenz, damit Nutzer wissen, wann die Verarbeitung abgeschlossen ist.

**Loesung:** Dokumente werden in eine Collection hochgeladen. GraphMesh speichert die Datei, erkennt den Dateityp und startet automatisch die Verarbeitungspipeline. Der Verarbeitungsstatus ist jederzeit einsehbar.

Unterstuetzte Dokumentstatus:
- **UPLOADED** -- Datei empfangen, Verarbeitung steht aus
- **PROCESSING** -- Texterkennung und Aufbereitung laufen
- **EXTRACTED** -- Verarbeitung abgeschlossen, Wissen wurde extrahiert
- **FAILED** -- Verarbeitung fehlgeschlagen

#### Anwendung

- **Frontend:**
  - `/documents` -- Uebersicht aller Dokumente in einer Collection
  - `/documents/upload` -- Datei-Upload mit Drag-and-Drop
  - `/documents/[id]` -- Detailansicht eines Dokuments mit Status und Metadaten
- **API:**
  ```graphql
  # Dokument hochladen
  mutation {
    uploadDocument(input: {
      collectionId: "col-123"
      title: "Jahresbericht 2025"
      mimeType: "application/pdf"
      content: "<Base64-codierter Dateiinhalt>"
    }) {
      id
      title
      state
      createdAt
    }
  }

  # Dokumente einer Collection auflisten (mit Paginierung und Filter)
  query {
    documents(
      collectionId: "col-123"
      filter: { type: SOURCE, state: EXTRACTED }
      page: 0
      pageSize: 20
    ) {
      items {
        id
        title
        mimeType
        type
        state
        createdAt
      }
      totalCount
      hasNextPage
    }
  }

  # Einzelnes Dokument abrufen
  query {
    document(id: "doc-456") {
      id
      title
      mimeType
      type
      state
      parentId
      metadata { key value }
      children { id title type }
      createdAt
    }
  }

  # Dokument loeschen
  mutation {
    deleteDocument(id: "doc-456")
  }
  ```
- **CLI:**
  ```bash
  # Dokument hochladen
  graphmesh document upload --collection col-123 --file ./jahresbericht-2025.pdf

  # Mit Titel-Override
  graphmesh document upload --collection col-123 \
      --file ./report.pdf --title "Jahresbericht 2025"

  # Dokumente auflisten
  graphmesh document list --collection col-123
  graphmesh document list --collection col-123 --type SOURCE

  # Dokumentdetails anzeigen
  graphmesh document info doc-456
  ```
- **MCP:** Das Tool `documentSearch` ermoeglicht KI-Agenten die Suche nach Dokumenten.

#### Beispiel

1. Ein Mitarbeiter oeffnet `/documents/upload` und zieht eine PDF-Datei in den Upload-Bereich.
2. Das Dokument erscheint mit Status **UPLOADED** in der Dokumentliste.
3. Nach wenigen Sekunden wechselt der Status zu **PROCESSING** und schliesslich zu **EXTRACTED**.
4. Der Mitarbeiter klickt auf das Dokument und sieht die extrahierten Metadaten.

---

## Automatische Verarbeitung

### PDF-Texterkennung

**Problem:** PDF-Dateien enthalten Text in einem Format, das nicht direkt durchsuchbar oder fuer KI-Analyse nutzbar ist.

**Loesung:** Nach dem Upload erkennt GraphMesh PDF-Dateien automatisch und extrahiert den Volltext seitenweise. Jede Seite wird als eigenes Teildokument (Typ `PAGE`) abgelegt, das dem Originaldokument zugeordnet bleibt.

#### Anwendung

- **Frontend:** In der Detailansicht eines Dokuments (`/documents/[id]`) werden die extrahierten Seiten als Kindelemente angezeigt.
- **API:**
  ```graphql
  # Dokumenthierarchie anzeigen (Original -> Seiten -> Chunks)
  query {
    documentHierarchy(collectionId: "col-123", documentId: "doc-456") {
      id
      title
      type
      children {
        id
        title
        type
        children { id title type }
      }
    }
  }
  ```

#### Beispiel

1. Ein 30-seitiges PDF wird hochgeladen.
2. GraphMesh erzeugt automatisch 30 Seiten-Dokumente (PAGE) als Kinder des Originals.
3. In der Dokumenthierarchie sieht man: `Jahresbericht.pdf` -> `Seite 1`, `Seite 2`, ..., `Seite 30`.

---

### Semantisches Chunking

**Problem:** Ganze Seiten oder Dokumente sind zu gross fuer praezise Suche und Wissensextraktion. Relevante Informationen gehen in langen Texten unter.

**Loesung:** GraphMesh teilt extrahierte Seiten automatisch in semantisch sinnvolle Textabschnitte (Chunks) auf. Die Chunks ueberlappen leicht, damit Kontext an Abschnittsgrenzen nicht verloren geht. Jeder Chunk wird als eigenes Teildokument (Typ `CHUNK`) abgelegt.

#### Anwendung

- **Frontend:** In der Detailansicht eines Dokuments werden Chunks als unterste Ebene der Hierarchie angezeigt.
- **API:**
  ```graphql
  # Alle Chunks eines Dokuments abrufen
  query {
    documentChunks(documentId: "doc-456") {
      id
      title
      type
      state
    }
  }

  # Dokumente nach Typ filtern, um nur Chunks anzuzeigen
  query {
    documents(
      collectionId: "col-123"
      filter: { type: CHUNK }
    ) {
      items { id title state }
      totalCount
    }
  }
  ```

#### Beispiel

1. Aus einer 10-seitigen PDF entstehen nach der Texterkennung 10 Seiten-Dokumente.
2. Jede Seite wird automatisch in 2--4 Chunks aufgeteilt.
3. Die Dokumenthierarchie zeigt drei Ebenen: `Original (SOURCE)` -> `Seiten (PAGE)` -> `Chunks (CHUNK)`.
4. Nachfolgende Wissensextraktion und Vektorsuche arbeiten auf diesen Chunks -- praezise und kontextbewusst.

---

### Dokument-Oberflaeche (Frontend)

**Problem:** Nutzer brauchen eine intuitive Oberflaeche, um Dokumente zu durchsuchen, hochzuladen und den Verarbeitungsstatus im Blick zu behalten.

**Loesung:** Das GraphMesh-Frontend bietet eine vollstaendige Dokumentenverwaltung mit Collection-Navigation, Upload-Funktion und Detailansichten.

#### Anwendung

- **Frontend:**
  - `/admin/collections` -- Collections anlegen, bearbeiten, loeschen
  - `/documents` -- Dokumentliste mit Filter nach Collection, Typ und Status
  - `/documents/upload` -- Datei-Upload per Drag-and-Drop oder Dateiauswahl
  - `/documents/[id]` -- Detailansicht mit Metadaten, Verarbeitungsstatus und Dokumenthierarchie (Seiten, Chunks)

#### Beispiel

1. Ein neuer Nutzer oeffnet `/admin/collections` und legt die Collection "Projektdokumentation" an.
2. Unter `/documents/upload` waehlt er die Collection aus und laedt drei PDF-Dateien hoch.
3. Auf `/documents` sieht er die drei Dokumente mit ihrem aktuellen Status.
4. Er klickt auf einen Bericht und sieht unter `/documents/[id]` die Seitenstruktur und extrahierten Chunks.
