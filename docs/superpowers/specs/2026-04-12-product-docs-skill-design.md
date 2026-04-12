# Product Documentation Skill — Design Spec

## Ziel

Ein Claude Code Skill (`/product-docs`), der fachliche Produktdokumentation aus dem bestehenden GraphMesh-Projekt generiert. Der Fokus liegt auf der Nutzerperspektive: welche Probleme loest GraphMesh, welche Features gibt es, wie wendet man sie ueber die verschiedenen Interfaces an.

## Skill-Datei

Pfad: `.claude/skills/product-docs/SKILL.md`

Aufruf: `/product-docs` (interaktive Themenauswahl) oder `/product-docs <thema>` (direkt)

## Ablauf

```
1. Themen-Katalog laden (im Skill definiert)
2. docs/product/ scannen → welche Dateien existieren bereits
3. Tabelle anzeigen: Thema | Status (dokumentiert/fehlt) | Dateiname
4. User waehlt ein Thema (oder hat es als Argument uebergeben)
5. Zugehoerige Quellen lesen (Feature-Docs, Done-Files, GraphQL-Schemas, Code)
6. Markdown-Datei nach Template generieren
7. Datei in docs/product/ schreiben und committen
```

## Themen-Katalog

| Thema | Dateiname | Feature-Nummern |
|---|---|---|
| Ueberblick | `index.md` | Gesamtuebersicht, Was ist GraphMesh |
| Dokumentenverwaltung | `dokumentenverwaltung.md` | 01, 02, 03, 04, 08, 09, 10, 11 |
| Wissensextraktion | `wissensextraktion.md` | 05, 12, 19, 21, 23, 24, 29, 38 |
| Abfragen & RAG | `abfragen-und-rag.md` | 13, 14, 15, 30, 45 |
| Ontologien & Taxonomien | `ontologien-und-taxonomien.md` | 20, 43, 44, 46 |
| Agenten & Tools | `agenten-und-tools.md` | 25, 26, 27 |
| Administration | `administration.md` | 06, 16, 28, 31, 35 |
| Benutzeroberflaeche | `benutzeroberflaeche.md` | 32, 33, 34, 42 |

## Erkennung bestehender Dokumentation

Der Skill prueft ob die jeweilige Datei in `docs/product/` existiert:
- Existiert → Status "dokumentiert" in der Tabelle
- Existiert nicht → Status "fehlt"

Bei bereits dokumentierten Themen fragt der Skill ob der User die Doku aktualisieren oder ein anderes Thema waehlen moechte.

## Quellen pro Thema

Der Skill weist Claude an, folgende Quellen zu lesen bevor die Doku generiert wird:

| Quelle | Pfad | Zweck |
|---|---|---|
| Feature-Docs | `docs/features/NN-feature-name.md` | Problem, Ziel, Architektur |
| Done-Files | `docs/features/NN-feature-name-done.md` | Abweichungen, tatsaechlicher Stand |
| GraphQL-Schemas | `src/main/resources/graphql/*.graphqls` | API-Queries/Mutations fuer Beispiele |
| MCP-Tools | `src/main/kotlin/.../api/mcp/GraphMeshMcpTools.kt` | MCP-Tool-Beschreibungen |
| CLI-Queries | `src/main/kotlin/.../cli/queries/*.graphql` | CLI-Befehle |
| Frontend-Pages | `frontend/src/app/*/page.tsx` | UI-Beschreibungen |
| Feature-Overview | `docs/features/00-feature-set-overview.md` | Abhaengigkeiten, Phasen |

## Dokumentations-Template

Jede generierte Datei folgt dieser Struktur:

```markdown
# <Themenname>

## Ueberblick

Was dieses Themengebiet abdeckt und welche Probleme es loest.
Kurze Einordnung in den Gesamtkontext von GraphMesh.

## Features

### <Feature-Name>

**Problem:** Welches konkrete Problem wird geloest?

**Loesung:** Was bietet GraphMesh dafuer?

#### Anwendung

Nur die Interfaces beschreiben, die fuer dieses Feature tatsaechlich existieren:

- **Frontend:** Wie nutzt man es in der UI
- **GraphQL API:** Queries/Mutations mit Beispiel-Aufrufen
- **CLI:** Befehle mit Beispiel-Aufrufen
- **MCP:** Tool-Aufrufe mit Parametern

#### Beispiel

Konkretes Anwendungsszenario mit Schritten:
1. Schritt eins...
2. Schritt zwei...
3. Ergebnis...

---

(naechstes Feature im Themengebiet)
```

### Regeln fuer die Generierung

- **Sprache:** Deutsch
- **Perspektive:** Nutzersicht, nicht Entwicklersicht
- **Keine interne Architektur:** Keine Package-Namen, keine Klassennamen, keine Kafka-Topics
- **Konkrete Beispiele:** Jedes Feature bekommt mindestens ein Anwendungsszenario
- **Nur implementierte Features:** Nur Features mit Done-File dokumentieren. Fehlende Features erwaehnen als "geplant" wenn sie zum Thema gehoeren.
- **Interface-Abschnitte nur wenn vorhanden:** Kein leeres "CLI:" wenn das Feature kein CLI hat
- **GraphQL-Beispiele:** Echte Queries/Mutations aus den Schema-Dateien verwenden, keine erfundenen

## Output

- Dateien werden nach `docs/product/<dateiname>.md` geschrieben
- Nach dem Schreiben wird die Datei committed mit Message: `docs(product): add <thema> documentation`
