---
title: Tutorials und Best Practices
nav_order: 11
---

# Tutorials und Best Practices

Schritt-für-Schritt-Anleitungen für typische Anwendungsfälle und bewährte Vorgehensweisen für den effektiven Einsatz von GraphMesh.

---

## Tutorials

### Wissensbasis aufbauen

**Ziel:** Sie lernen, wie Sie Dokumente hochladen, die automatische Wissensextraktion abwarten und die Ergebnisse im Wissensgraphen überprüfen.

**Voraussetzungen:** GraphMesh ist gestartet und über den Browser oder die CLI erreichbar.

#### Schritt-für-Schritt

1. **Collection anlegen** -- Erstellen Sie eine Dokumentensammlung als Container für Ihre Wissensbasis.

   *Frontend:* Navigieren Sie zu `/admin/collections` und klicken Sie auf "Neue Collection". Vergeben Sie einen Namen (z.B. "Projektdokumentation"), eine optionale Beschreibung und Tags zur Kategorisierung.

   *CLI:*
   ```bash
   graphmesh collection create "Projektdokumentation" \
     --description "Alle Projektunterlagen Q1-Q2 2026" \
     --tag projekt --tag intern
   ```

2. **Dokumente hochladen** -- Laden Sie PDF-Dokumente in die Collection.

   *Frontend:* Navigieren Sie zu `/documents`, wählen Sie die Collection aus und laden Sie Dateien über den Upload-Dialog hoch.

   *CLI:*
   ```bash
   graphmesh document upload \
     --collection <collection-id> \
     --file ./projektplan.pdf

   graphmesh document upload \
     --collection <collection-id> \
     --file ./anforderungen.pdf \
     --title "Anforderungsspezifikation v2"
   ```

3. **Verarbeitung abwarten** -- GraphMesh verarbeitet die Dokumente automatisch: Dekodierung, Aufteilung in Abschnitte, Wissensextraktion durch das LLM und Speicherung im Wissensgraphen. Der Status wechselt von UPLOADED über PROCESSING zu EXTRACTED.

   *CLI:*
   ```bash
   graphmesh document list --collection <collection-id> --type SOURCE
   ```
   Prüfen Sie die Spalte "State". Alle Dokumente sollten nach der Verarbeitung den Status EXTRACTED haben.

4. **Ergebnisse prüfen** -- Überprüfen Sie den extrahierten Wissensgraphen.

   *Frontend:* Navigieren Sie zu `/graph` und wählen Sie die Collection. Der Graph-Explorer zeigt die extrahierten Entitäten und Beziehungen visuell an.

   *GraphQL:*
   ```graphql
   query {
     graphMetadata(collectionId: "<collection-id>") {
       datasets
       predicates
       entityTypes
     }
   }
   ```

5. **Erste Abfrage stellen** -- Testen Sie Ihre Wissensbasis mit einer Frage.

   *CLI:*
   ```bash
   graphmesh query nlp "Was sind die wichtigsten Projektmeilensteine?" \
     --collection <collection-id>
   ```

#### Ergebnis

Sie haben eine funktionsfähige Wissensbasis mit automatisch extrahierten Entitäten und Beziehungen, die über verschiedene Abfragetypen durchsucht werden kann.

---

### Ontologie-gestützte Extraktion

**Ziel:** Sie lernen, wie eine Ontologie die Qualität der Wissensextraktion verbessert, indem sie das LLM mit einem Fachvokabular (Klassen, Beziehungstypen) leitet.

**Voraussetzungen:** Eine Collection mit mindestens einem Dokument ist vorhanden (siehe Tutorial "Wissensbasis aufbauen").

#### Schritt-für-Schritt

1. **Ontologie vorbereiten** -- Erstellen Sie eine Ontologie-Datei im Turtle-Format (.ttl), die Ihre Fachbegriffe und Beziehungstypen definiert. Beispiel für eine Vertrags-Ontologie:

   ```turtle
   @prefix contract: <http://example.org/contract#> .
   @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

   contract:Vertrag a rdfs:Class ;
       rdfs:label "Vertrag" .

   contract:Vertragspartner a rdfs:Class ;
       rdfs:label "Vertragspartner" .

   contract:hatVertragspartner a rdf:Property ;
       rdfs:domain contract:Vertrag ;
       rdfs:range contract:Vertragspartner .

   contract:hatLaufzeit a rdf:Property ;
       rdfs:domain contract:Vertrag .
   ```

2. **Ontologie importieren**

   *Frontend:* Navigieren Sie zu `/admin/ontologies` und importieren Sie die Datei. Vergeben Sie einen Schlüssel (z.B. "contract"), einen Namen und den Namespace.

   *GraphQL:*
   ```graphql
   mutation {
     importOntology(input: {
       key: "contract"
       name: "Vertrags-Ontologie"
       namespace: "http://example.org/contract#"
       content: "... Turtle-Inhalt ..."
       format: TURTLE
     }) {
       key
       classCount
       objectPropertyCount
     }
   }
   ```

3. **Import überprüfen** -- Stellen Sie sicher, dass die Ontologie korrekt erkannt wurde.

   *GraphQL:*
   ```graphql
   query {
     listOntologies {
       key
       name
       classCount
       objectPropertyCount
       datatypePropertyCount
     }
   }
   ```

   Die Antwort sollte die erwartete Anzahl an Klassen und Properties zeigen.

4. **Dokumente hochladen** -- Laden Sie nun Vertragsdokumente in Ihre Collection hoch. Die Extraktion verwendet automatisch die importierten Ontologien, um das LLM zu leiten.

5. **Ergebnisse vergleichen** -- Prüfen Sie im Graph-Explorer (`/graph`), ob die extrahierten Beziehungen den Ontologie-Begriffen entsprechen. Sie sollten konsistentere Entitätsnamen und Beziehungstypen sehen als ohne Ontologie.

#### Ergebnis

Die Wissensextraktion nutzt Ihr Fachvokabular und liefert strukturiertere, konsistentere Ergebnisse. Entitäten und Beziehungen folgen den definierten Klassen und Properties.

---

### RAG-Abfragen optimieren

**Ziel:** Sie lernen die drei Abfragetypen kennen und wissen, wann welcher Typ die besten Ergebnisse liefert.

**Voraussetzungen:** Eine Collection mit extrahierten Dokumenten ist vorhanden.

#### Schritt-für-Schritt

1. **Graph RAG -- Beziehungen und Zusammenhänge**

   Graph RAG durchsucht den Wissensgraphen und findet relevante Entitäten und ihre Beziehungen. Ideal für Fragen über Zusammenhänge, Verbindungen und Strukturen.

   ```bash
   graphmesh query graphrag \
     "Welche Abteilungen arbeiten am Projekt Alpha zusammen?" \
     --collection <collection-id>
   ```

   Die Antwort enthält die ausgewählten Kanten mit Relevanz-Score und Begründung, warum jede Kante relevant ist.

   **Parameter-Tuning:**
   - `--max-edges` (Standard: 150) -- Mehr Kanten durchsuchen für umfassendere Antworten
   - `--max-depth` (Standard: 2) -- Tiefere Graph-Traversierung für indirekte Zusammenhänge
   - `--max-selected` (Standard: 30) -- Mehr ausgewählte Kanten in der Antwort

2. **Document RAG -- Textbasierte Antworten mit Quellenangaben**

   Document RAG sucht semantisch ähnliche Textabschnitte und generiert Antworten mit konkreten Quellenverweisen (Dokument, Seite, Textausschnitt). Ideal für Detailfragen und Zitate.

   ```bash
   graphmesh query docrag \
     "Was sind die Kündigungsfristen laut Vertrag?" \
     --collection <collection-id>
   ```

   **Parameter-Tuning:**
   - `--top-k` (Standard: 10) -- Mehr Textabschnitte für umfassendere Antworten
   - `--threshold` (Standard: 0.5) -- Höherer Wert für präzisere, niedrigerer für breitere Ergebnisse

3. **NLP-Abfrage -- Automatische Intent-Erkennung**

   Die NLP-Abfrage erkennt automatisch, welcher Abfragetyp am besten passt, und leitet die Frage entsprechend weiter. Sie zeigt den erkannten Intent und die Konfidenz an.

   ```bash
   graphmesh query nlp \
     "Wer ist der Projektleiter von Projekt Alpha?" \
     --collection <collection-id>
   ```

   Mögliche Intents:
   - **GRAPH_QUERY** -- Fragen zu Beziehungen und Zusammenhängen
   - **DOCUMENT_QUERY** -- Fragen zu konkreten Textstellen
   - **STRUCTURED_QUERY** -- Strukturierte Abfragen
   - **HYBRID** -- Kombination mehrerer Strategien

   Sie können den Intent auch erzwingen:
   ```bash
   graphmesh query nlp "..." \
     --collection <collection-id> \
     --force-intent GRAPH_QUERY
   ```

4. **Nachvollziehbarkeit prüfen** -- Nutzen Sie die Explain-Funktion, um die Abfragekette zu verstehen.

   ```bash
   graphmesh explain sessions --collection <collection-id>
   graphmesh explain trace <session-uri> --collection <collection-id>
   ```

   Die Erklärungskette zeigt: Frage -> Graph-Exploration -> Fokussierung -> Analyse-Schritte -> Synthese der Antwort.

#### Ergebnis

Sie können gezielt den passenden Abfragetyp wählen und die Parameter für Ihre Anforderungen optimieren. Die Explain-Funktion hilft beim Verständnis und der Fehleranalyse.

---

### MCP-Integration

**Ziel:** Sie lernen, wie Sie GraphMesh als Werkzeug in einen KI-Assistenten (z.B. Claude, ChatGPT mit Plugins) über das Model Context Protocol (MCP) einbinden.

**Voraussetzungen:** GraphMesh läuft und enthält mindestens eine Collection mit extrahierten Dokumenten.

#### Schritt-für-Schritt

1. **MCP-Endpunkt konfigurieren** -- GraphMesh stellt einen MCP-Server bereit, der vier Werkzeuge anbietet. Der MCP-Endpunkt ist unter der Standard-URL des Servers verfügbar.

2. **Verfügbare MCP-Werkzeuge** -- Folgende Tools stehen dem KI-Assistenten zur Verfügung:

   | Werkzeug | Beschreibung |
   |----------|-------------|
   | `knowledgeQuery` | Graph-RAG-Abfrage: Findet relevante Entitäten und Beziehungen im Wissensgraphen |
   | `documentQuery` | Document-RAG-Abfrage: Sucht semantisch ähnliche Textabschnitte mit Quellenangaben |
   | `collectionList` | Listet alle verfügbaren Collections mit Name, Beschreibung und Tags |
   | `documentSearch` | Sucht Dokumente in einer Collection nach Titel |

3. **MCP-Server in Claude Desktop einbinden** -- Fügen Sie in der Claude Desktop-Konfiguration den GraphMesh-MCP-Server hinzu:

   ```json
   {
     "mcpServers": {
       "graphmesh": {
         "url": "http://localhost:8080/mcp"
       }
     }
   }
   ```

4. **Werkzeuge nutzen** -- Der KI-Assistent kann nun auf Ihre Wissensbasis zugreifen. Beispiel-Interaktionen:

   - *"Welche Collections gibt es?"* -- Der Assistent ruft `collectionList` auf
   - *"Suche nach Verträgen in der Collection Recht"* -- Der Assistent ruft `documentSearch` auf
   - *"Was steht im Vertrag über Kündigungsfristen?"* -- Der Assistent ruft `documentQuery` oder `knowledgeQuery` auf
   - *"Welche Beziehungen hat Firma X?"* -- Der Assistent ruft `knowledgeQuery` auf

5. **Parameter der Werkzeuge**

   **knowledgeQuery:**
   - `question` (Pflicht) -- Die Frage in natürlicher Sprache
   - `collectionId` (Pflicht) -- ID der Collection
   - `maxEdges` (Optional, Standard: 150) -- Maximale Anzahl zu durchsuchender Kanten

   **documentQuery:**
   - `question` (Pflicht) -- Die Frage in natürlicher Sprache
   - `collectionId` (Pflicht) -- ID der Collection
   - `topK` (Optional, Standard: 10) -- Anzahl abzurufender Textabschnitte

   **collectionList:**
   - `tags` (Optional) -- Kommagetrennte Tags zum Filtern

   **documentSearch:**
   - `collectionId` (Pflicht) -- ID der Collection
   - `titleFilter` (Optional) -- Suchbegriff für den Dokumenttitel

#### Ergebnis

Ihr KI-Assistent kann auf die gesamte Wissensbasis zugreifen und Fragen sowohl über den Wissensgraphen als auch über Dokumenteninhalte beantworten -- inklusive Quellenangaben.

---

## Best Practices

### Collection-Struktur

Eine durchdachte Collection-Struktur verbessert die Qualität der Abfrageergebnisse.

**Empfehlungen:**

- **Thematisch gruppieren** -- Fassen Sie inhaltlich zusammenhängende Dokumente in einer Collection zusammen (z.B. "Verträge 2026", "Technische Dokumentation", "Personalrichtlinien"). Das LLM liefert bessere Antworten, wenn der Kontext kohärent ist.

- **Tags konsequent nutzen** -- Vergeben Sie Tags systematisch, um Collections übergreifend zu filtern:
  ```bash
  graphmesh collection create "Verträge 2026" --tag legal --tag 2026
  graphmesh collection create "Verträge 2025" --tag legal --tag 2025
  graphmesh collection list --tag legal   # Alle Vertrags-Collections
  ```

- **Granularität wählen** -- Zu kleine Collections (wenige Dokumente) limitieren die Möglichkeiten der Graphensuche. Zu grosse Collections (hunderte unzusammenhängende Dokumente) verschlechtern die Relevanz. Ein guter Richtwert: 10-50 thematisch verwandte Dokumente pro Collection.

- **Beschreibungen pflegen** -- Eine aussagekräftige Beschreibung hilft bei der MCP-Integration, da KI-Assistenten anhand der Beschreibung die richtige Collection auswählen.

### Ontologie-Design

Ontologien leiten die Wissensextraktion. Gut entworfene Ontologien verbessern die Ergebnisqualität deutlich.

**Empfehlungen:**

- **Klein anfangen** -- Starten Sie mit den 5-10 wichtigsten Klassen und Beziehungstypen Ihrer Domäne. Eine kleine, präzise Ontologie ist besser als eine umfangreiche, vage.

- **Fachsprache verwenden** -- Nutzen Sie die Begriffe, die in Ihren Dokumenten tatsächlich vorkommen. Wenn Ihre Verträge von "Auftragnehmer" sprechen, definieren Sie eine Klasse "Auftragnehmer" statt "ServiceProvider".

- **Beziehungstypen explizit machen** -- Definieren Sie die wichtigsten Beziehungen zwischen Klassen:
  ```
  Vertrag --hatVertragspartner--> Organisation
  Vertrag --hatLaufzeit--> Zeitraum
  Projekt --wirdGeleitetVon--> Person
  ```

- **Unterstützte Formate** -- GraphMesh akzeptiert Ontologien im Turtle-Format (`.ttl`) und RDF/XML-Format. Turtle ist lesbarer und wird empfohlen.

- **Iterativ verbessern** -- Laden Sie erste Dokumente ohne Ontologie hoch, analysieren Sie die extrahierten Beziehungen im Graph-Explorer (`/graph`), und leiten Sie daraus Ihre Ontologie-Klassen ab. Dann importieren Sie die Ontologie und laden weitere Dokumente hoch.

### Abfrage-Strategien

Die Wahl des richtigen Abfragetyps bestimmt die Qualität der Antworten.

| Anwendungsfall | Empfohlener Abfragetyp | Begründung |
|---------------|----------------------|------------|
| Beziehungen zwischen Entitäten | Graph RAG | Navigiert den Wissensgraphen entlang von Kanten |
| Konkrete Textstellen / Zitate | Document RAG | Findet die relevantesten Textabschnitte |
| Allgemeine Fragen | NLP | Erkennt automatisch den besten Abfragetyp |
| Überblick über ein Thema | Graph RAG mit hohem `max-edges` | Erfasst mehr Zusammenhänge |
| Detailsuche in einem Dokument | Document RAG mit niedrigem `threshold` | Findet auch entferntere Treffer |
| Fehleranalyse | Explain-Funktionen | Zeigt die Abfragekette und Entscheidungslogik |

**Weitere Tipps:**

- **NLP als Einstieg nutzen** -- Wenn Sie unsicher sind, welcher Abfragetyp passt, verwenden Sie die NLP-Abfrage. Sie zeigt den erkannten Intent und die Konfidenz an. Bei niedriger Konfidenz können Sie manuell den passenden Typ wählen.

- **Explain für Optimierung** -- Nutzen Sie `explain trace`, um zu verstehen, warum eine Abfrage ein bestimmtes Ergebnis liefert. Die Erklärungskette zeigt, welche Kanten ausgewählt und warum sie als relevant eingestuft wurden.

- **Schwellenwerte anpassen** -- Bei Document RAG bestimmt der `threshold`-Wert die Mindest-Ähnlichkeit. Erhöhen Sie ihn (z.B. 0.7), wenn Sie nur hochrelevante Treffer wollen. Senken Sie ihn (z.B. 0.3), wenn Sie breitere Ergebnisse benötigen.

- **Graph-Tiefe steuern** -- Bei Graph RAG bestimmt `max-depth`, wie viele Beziehungsschritte verfolgt werden. Tiefe 1 findet nur direkte Beziehungen, Tiefe 3 auch indirekte Verbindungen über Zwischenentitäten.
