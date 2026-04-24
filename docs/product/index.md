# GraphMesh Produktdokumentation

## Was ist GraphMesh?

GraphMesh ist eine KI-Knowledge-Graph-Plattform zur automatischen Wissensextraktion aus Dokumenten. Die Plattform analysiert Dokumente mithilfe von KI-Sprachmodellen, extrahiert strukturiertes Wissen als Wissensgraph (Subjekt-Praedikat-Objekt-Tripel) und ermoeglicht semantische Suche sowie intelligente Abfragen ueber den gesamten Wissensbestand.

Der zentrale Gedanke: Unstrukturiertes Wissen in Dokumenten wird automatisch in einen durchsuchbaren, verknuepften Wissensgraphen ueberfuehrt -- ohne manuelle Verschlagwortung oder Modellierung.

## Welche Probleme loest GraphMesh?

- **Wissen in Dokumenten ist unzugaenglich** -- Informationen stecken in PDFs, Reports und Texten, die nur durch manuelles Lesen erschlossen werden koennen.
- **Manuelle Wissensextraktion skaliert nicht** -- Das haendische Erfassen von Zusammenhaengen aus Hunderten von Dokumenten ist zeitaufwaendig und fehleranfaellig.
- **Stichwortsuche reicht nicht** -- Klassische Volltextsuche findet keine semantischen Zusammenhaenge und versteht keine natuerlichsprachlichen Fragen.
- **Wissen ist nicht verknuepft** -- Zusammenhaenge zwischen Konzepten aus verschiedenen Dokumenten bleiben unsichtbar.
- **Keine Nachvollziehbarkeit** -- Bei klassischen KI-Antworten ist unklar, woher die Information stammt. GraphMesh liefert Quellenangaben und Erklaerungen.

## Themengebiete

| Thema | Beschreibung | Status |
|---|---|---|
| [Dokumentenverwaltung](dokumentenverwaltung.md) | Upload, Verwaltung und Verarbeitung von Dokumenten | implementiert |
| [Wissensextraktion](wissensextraktion.md) | Automatische Extraktion von Wissen aus Dokumenten mittels KI | implementiert |
| [Suche & Abfragen](suche-und-abfragen.md) | Semantische Suche, RAG-Abfragen und natuerlichsprachliche Fragen | implementiert |
| [Wissensmodellierung](wissensmodellierung.md) | Ontologien, SKOS-Taxonomien, RDF-Datenimport und dynamische GraphQL-APIs | implementiert |
| [Graph-Visualisierung](graph-visualisierung.md) | Interaktive Graphdarstellung und Exploration | implementiert |
| [Agent & Streaming](agent-und-streaming.md) | KI-Agenten mit Werkzeugen und Echtzeit-Streaming | implementiert |
| [Context Cores](context-cores.md) | Versionierte Wissens-Bundles: Export, Import, Tagging | implementiert |
| [Administration](administration.md) | Konfiguration, Mandantenfaehigkeit und Kommandozeilen-Tools | implementiert |

## Erste Schritte

Eine Schritt-fuer-Schritt-Anleitung zur Installation und zum ersten Workflow finden Sie unter [Erste Schritte](erste-schritte.md).
