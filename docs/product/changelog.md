---
title: Aenderungshistorie
nav_order: 13
---

# Aenderungshistorie

Uebersicht der nutzerrelevanten Aenderungen in GraphMesh, nach Monat gruppiert.

---

## April 2026

### Neue Features
- Context Cores: Wissensstaende koennen nun als versionierte, portable ZIP-Bundles exportiert und importiert werden. Ein Context Core enthaelt alle Wissensgraph-Tripel, Vektor-Embeddings, die zugehoerige Ontologie und Retrieval-Konfigurationen. Drei Konfliktstrategien beim Import (FAIL, MERGE, REPLACE), Tag-basierte Versionierung (z.B. "stage", "prod") und Namespace-Rewriting werden unterstuetzt.
- Automatische Themenextraktion aus Dokumenten hinzugefuegt: Beim Verarbeiten von Dokumenten werden nun Themen und Schlagwoerter per LLM erkannt und als strukturierte Metadaten gespeichert.
- SKOS-Taxonomie-Unterstuetzung: Hierarchische Begriffssysteme (Taxonomien) koennen nun verwaltet und in der GraphQL-API abgefragt werden. Konzepte und Konzeptschemen nach SKOS-Standard werden unterstuetzt.

### Verbesserungen
- Context Core Build-Dialog: Ontologien koennen nun beim Export direkt ausgewaehlt werden, damit die Ontologie-Datei korrekt ins Bundle geschrieben wird.

### Fehlerbehebungen
- Context Cores: Fehler beim Ontologie-Export werden nun protokolliert statt stillschweigend ignoriert.
- Themenextraktion: Korrekte Verarbeitung der Konfigurationsschluessel und Vermeidung doppelter Eintraege.
- SKOS-Abfragen: Collection-ID wird nun korrekt an untergeordnete Abfragen weitergegeben.

---

## Maerz 2026

### Neue Features
- RDF-Datenimport: Bestehende RDF-Daten in den Formaten Turtle, RDF/XML und N-Triples koennen nun direkt importiert werden - inklusive optionaler Embedding-Erzeugung fuer semantische Suche.
- Admin-Dashboard: Neue Verwaltungsoberflaeche mit Uebersicht ueber Collections, Systemmetriken, Konfigurationseditor und Pipeline-Status (Verarbeitungsfortschritt und Fehlerlisten).
- Graph-Explorer mit Sigma.js: Der Graph-Explorer wurde auf Sigma.js umgestellt und bietet nun fluessigere Darstellung, bessere Navigation und Entity-Suche.

### Verbesserungen
- Abfrage-Performance deutlich verbessert: Parallele Verarbeitung von Intent-Erkennung und Embedding-Berechnung, Caching fuer Embeddings, und zusammengefasste LLM-Aufrufe bei Graph-RAG-Abfragen.
- Neues dunkles Design-System fuer die gesamte Weboberflaeche mit verbesserter Typografie.

### Fehlerbehebungen
- Streaming-Antworten: Textbasierte Tool-Aufrufe werden nun korrekt erkannt und die gepufferte Antwort vollstaendig weitergeleitet.
- Admin-UI: Kompatibilitaetsprobleme mit Apollo v4 behoben.
- Graph-Explorer: Entity-Deep-Links, verwaiste Knoten und doppelte Verbindungen korrigiert.

---

## Februar 2026

### Neue Features
- Graph-Explorer UI: Interaktive Visualisierung des Wissensgraphen mit Entitaetssuche, Knotendetails und Filterfunktionen.
- Subgraph-Abfragen: Zusammenhaengende Teilgraphen koennen nun ausgehend von Dokumenten und Entitaeten abgerufen werden.
- Entity-Suche und Graph-Metadaten ueber die GraphQL-API verfuegbar.

### Verbesserungen
- Triples-Abfragen unterstuetzen nun ein Limit-Feld zur Begrenzung der Ergebnismenge.

### Fehlerbehebungen
- Graph-RAG: Subgraph-Traversierung von Chunks ueber Subgraphen zu Entitaeten korrigiert.
- API: Redundante Schema-Mappings entfernt und Triple-Limit-Begrenzung validiert.
- S3-Speicher: Verbindungsleck beim Abrufen von Dateien behoben.
- Kafka-Messaging: Korrekte Konfiguration fuer Avro-Deserialisierung und verbesserte Fehlerprotokollierung.
