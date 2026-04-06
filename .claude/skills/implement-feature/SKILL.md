---
name: feature-implementieren
description: Verwenden, wenn der Nutzer ein Feature aus docs/features/00-feature-set-overview.md implementieren möchte. Stellt die Feature-Liste zur Auswahl dar, recherchiert den Tech-Stack über context7 und führt anschließend den vollständigen Superpowers-Workflow aus (Brainstorming, Planung, subagentengesteuerte Implementierung).
---

# Feature implementieren

Wähle ein Feature aus diesem Projekt-Feature-Set, recherchiere die Technik mit context7 und implementiere es anschließend Ende-zu-Ende mit Superpowers.

## Prozess

```dot
digraph implement_feature {
    rankdir=TB;
    "Feature-Tabelle aus\n00-feature-set-overview.md anzeigen" [shape=box];
    "Nutzer wählt Feature-Nummer" [shape=diamond];
    "Feature-Dokument lesen:\ndocs/features/NN-feature-name.md" [shape=box];
    "Tech-Stack über context7 recherchieren\n(resolve-library-id + query-docs)" [shape=box];
    "superpowers:brainstorming aufrufen\n(Feature-Dokument + context7-Erkenntnisse übergeben)" [shape=box];
    "Brainstorming erzeugt Spezifikation\n-> ruft superpowers:writing-plans auf" [shape=box];
    "Plan erstellt\n-> superpowers:subagent-driven-development aufrufen" [shape=box];
    "Implementierung abgeschlossen\n-> superpowers:finishing-a-development-branch aufrufen" [shape=doublecircle];

    "Feature-Tabelle aus\n00-feature-set-overview.md anzeigen" -> "Nutzer wählt Feature-Nummer";
    "Nutzer wählt Feature-Nummer" -> "Feature-Dokument lesen:\ndocs/features/NN-feature-name.md";
    "Feature-Dokument lesen:\ndocs/features/NN-feature-name.md" -> "Tech-Stack über context7 recherchieren\n(resolve-library-id + query-docs)";
    "Tech-Stack über context7 recherchieren\n(resolve-library-id + query-docs)" -> "superpowers:brainstorming aufrufen\n(Feature-Dokument + context7-Erkenntnisse übergeben)";
    "superpowers:brainstorming aufrufen\n(Feature-Dokument + context7-Erkenntnisse übergeben)" -> "Brainstorming erzeugt Spezifikation\n-> ruft superpowers:writing-plans auf";
    "Brainstorming erzeugt Spezifikation\n-> ruft superpowers:writing-plans auf" -> "Plan erstellt\n-> superpowers:subagent-driven-development aufrufen";
    "Plan erstellt\n-> superpowers:subagent-driven-development aufrufen" -> "Implementierung abgeschlossen\n-> superpowers:finishing-a-development-branch aufrufen";
}
```

## Schritt 1: Feature-Auswahl

Lies `docs/features/00-feature-set-overview.md` und präsentiere dem Nutzer die Feature-Tabelle. Zeige Phase, Nummer, Name, Abhängigkeiten und Aufwand. Der Nutzer soll per Nummer auswählen.

Falls der Nutzer bereits eine Feature-Nummer oder einen Namen angegeben hat, überspringe die Auswahl.

## Schritt 2: Feature-Dokument lesen

Lies das vollständige Feature-Dokument unter `docs/features/NN-feature-name.md`. Extrahiere:
- Problem und Ziel
- Architektur und Datenmodelle
- Service-Schnittstellen und Implementierungen
- Änderungen am GraphQL-Schema
- Betroffene Dateien
- Akzeptanzkriterien
- Abhängigkeiten zu anderen Features

Prüfe, welche Abhängigkeiten bereits implementiert sind, indem du die Codebasis durchsuchst (suche nach bestehenden Klassen, Interfaces und Services aus der Abhängigkeitsliste).

## Schritt 3: Context7-Recherche

Recherchiere auf Basis der technischen Anforderungen des Features die aktuelle Dokumentation über context7:

1. **Bibliotheken identifizieren**, die das Feature verwendet (aus dem Feature-Dokument und dem Projekt-Tech-Stack)
2. **Library-IDs auflösen** über `mcp__plugin_context7_context7__resolve-library-id`
3. **Relevante Dokumentation abfragen** über `mcp__plugin_context7_context7__query-docs`

Beispiel Typische Bibliotheken je nach Feature-Typ:

| Feature-Typ | Zu recherchierende Bibliotheken |
|---|---|
| Storage (Cassandra, Qdrant, S3) | Spring Data Cassandra, Qdrant Java Client, AWS S3 SDK |
| LLM/KI | Spring AI (ChatClient, strukturiertes Output, Embeddings) |
| GraphQL API | Spring Boot GraphQL (@QueryMapping, Coroutines) |
| Kafka Messaging | Spring Kafka |
| Frontend | Next.js, React |

Zusätzlich prüfen: Koog-Framework (die LLM-Abstraktion des Projekts), falls das Feature LLM-Aufrufe enthält.

Fasse die Erkenntnisse knapp zusammen — konzentriere dich auf API-Muster, Kotlin-spezifische Aspekte und alles, was von den Annahmen im Feature-Dokument abweicht.

## Schritt 4: Brainstorming

**ERFORDERLICHE SUB-SKILL:** `superpowers:brainstorming` aufrufen

Übergib dem Brainstorming-Skill:
- Den vollständigen Inhalt des Feature-Dokuments
- Die Ergebnisse der Context7-Recherche (relevante API-Muster, Stolperfallen)
- Den aktuellen Zustand der Codebasis (was bereits implementiert ist und was fehlt)
- Alle Abweichungen zwischen Feature-Dokument und tatsächlichem Code (z. B. Paketnamen, Framework-Entscheidungen)

Der Brainstorming-Skill übernimmt: Rückfragen, Vorschläge zur Herangehensweise, Darstellung des Designs, Spezifikationserstellung und Übergabe an writing-plans.

## Schritt 5: Planung

Der Brainstorming-Skill ruft automatisch `superpowers:writing-plans` auf.

## Schritt 6: Implementierung

Sobald der Plan geschrieben ist, biete die Ausführungswahl an:
- **Subagentengesteuert** `superpowers:subagent-driven-development`

## Schritt 7: Abschluss

**ERFORDERLICHE SUB-SKILL:** `superpowers:finishing-a-development-branch` aufrufen
**Feature done datei:** schreibe eine `features/NN-feature-name-done.md` mit:
- Kurze Zusammenfassung der Implementierung
- Alle Abweichungen vom ursprünglichen Plan oder Feature-Dokument
- Alle offenen Fragen oder technischen Schulden, die nach der Implementierung bestehen

## Wichtige Regeln

- **Immer context7 verwenden**, bevor Brainstorming startet — verlasse dich bei Bibliotheks-APIs nicht auf Trainingsdaten
- **Bestehenden Code prüfen**, bevor du annimmst, dass etwas von Grund auf neu gebaut werden muss
- **Bestehenden Mustern folgen** Folge Best Practices, Architektur- und Designentscheidungen, die in der Codebasis bereits etabliert sind.
- **Feature die bereits fertiggestellt wurde** sollte nicht erneut implementiert die bereits mit `features/NN-feature-name-done.md` existiert, so sollte der Nutzer darauf hingewiesen werden, dass dieses Feature bereits implementiert wurde und die entsprechende Done-Datei lesen, anstatt eine neue Implementierung zu starten.