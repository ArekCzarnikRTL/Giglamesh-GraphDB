# Erste Schritte

## Voraussetzungen

Folgende Software muss installiert sein:

- **Docker & Docker Compose** -- fuer die Infrastruktur (Datenbanken, Message Broker, Objektspeicher)
- **JDK 21** -- fuer das Backend (z.B. Eclipse Temurin oder GraalVM)
- **Node.js 20+** und **pnpm** -- fuer das Frontend
- **Ein LLM-API-Schluessel** -- OpenAI (empfohlen: `gpt-4o-mini`) oder Anthropic

## Installation & Setup

### 1. Repository klonen

```bash
git clone <repository-url>
cd GraphMesh
```

### 2. Infrastruktur starten

Die gesamte Infrastruktur (Cassandra, Qdrant, MinIO, Kafka) wird ueber Docker Compose bereitgestellt:

```bash
docker compose up -d
```

Warten Sie, bis alle Dienste gestartet sind. Insbesondere Cassandra benoetigt beim ersten Start ca. 30-60 Sekunden.

Die Dienste sind danach erreichbar unter:

| Dienst | Adresse | Beschreibung |
|---|---|---|
| Cassandra | localhost:9042 | Graphspeicher (RDF-Tripel) |
| Qdrant | localhost:6333 | Vektordatenbank (Embeddings) |
| MinIO | localhost:9000 | Objektspeicher (Dokumente) |
| MinIO Console | localhost:9001 | Web-Oberflaeche fuer MinIO |
| Kafka | localhost:9092 | Message Broker |

### 3. LLM-Provider konfigurieren

Setzen Sie Ihren API-Schluessel als Umgebungsvariable:

```bash
# Fuer OpenAI (Standard)
export OPENAI_API_KEY=sk-...

# Oder fuer Anthropic
export ANTHROPIC_ENABLED=true
export OPENAI_ENABLED=false
export ANTHROPIC_API_KEY=sk-ant-...
```

Optional koennen Sie das Extraktionsmodell und das Embedding-Modell anpassen:

```bash
export LLM_EXTRACTION_MODEL=gpt-4o-mini
export EMBEDDING_MODEL=text-embedding-3-small
```

### 4. Backend starten

```bash
./gradlew bootRun
```

Das Backend startet auf **http://localhost:8083**. Die GraphQL-Oberflaeche (GraphiQL) ist unter **http://localhost:8083/graphiql** erreichbar.

### 5. Frontend starten

```bash
cd frontend
pnpm install
pnpm dev
```

Das Frontend startet auf **http://localhost:3002**.

## Ihr erster Workflow

### 1. Collection anlegen

Oeffnen Sie das Frontend unter **http://localhost:3002** und navigieren Sie zu **Administration > Collections** (`/admin/collections`). Erstellen Sie eine neue Collection -- diese dient als Container fuer zusammengehoerige Dokumente.

### 2. Dokument hochladen

Wechseln Sie zu **Dokumente > Upload** (`/documents/upload`). Waehlen Sie Ihre Collection aus und laden Sie ein PDF-Dokument hoch.

### 3. Extraktion abwarten

Nach dem Upload wird das Dokument automatisch verarbeitet:
1. Das PDF wird dekodiert und in Textabschnitte zerlegt
2. Ein KI-Modell extrahiert strukturiertes Wissen (Subjekt-Praedikat-Objekt-Tripel) aus jedem Abschnitt
3. Themen werden automatisch erkannt
4. Die Tripel werden im Wissensgraphen gespeichert
5. Vektoreinbettungen werden fuer die semantische Suche erzeugt

Den Verarbeitungsstatus koennen Sie auf der Dokumentdetailseite (`/documents/<id>`) verfolgen.

### 4. Wissensgraph abfragen

Navigieren Sie zur **Abfrage-Seite** (`/query`) und stellen Sie eine natuerlichsprachliche Frage zu Ihrem Dokument, z.B.:

> "Welche Personen werden im Dokument erwaehnt und in welchem Zusammenhang stehen sie?"

GraphMesh durchsucht den Wissensgraphen semantisch und liefert eine KI-generierte Antwort mit Quellenangaben.

### 5. Graph erkunden

Wechseln Sie zum **Graph Explorer** (`/graph`), um den Wissensgraphen visuell zu erkunden. Knoten repraesentieren Entitaeten (Personen, Organisationen, Konzepte), Kanten die Beziehungen dazwischen. Sie koennen:

- Knoten anklicken fuer Details
- Den Graphen zoomen und verschieben
- Nach bestimmten Entitaeten suchen

## Naechste Schritte

- [Dokumentenverwaltung](dokumentenverwaltung.md) -- Mehr ueber Upload-Optionen und Dokumentverwaltung
- [Wissensextraktion](wissensextraktion.md) -- Ontologie-gesteuerte und agentenbasierte Extraktion konfigurieren
- [Suche & Abfragen](suche-und-abfragen.md) -- Semantische Suche, RAG und Erklaerbarkeit nutzen
- [Wissensmodellierung](wissensmodellierung.md) -- Eigene Ontologien und Taxonomien importieren
- [Administration](administration.md) -- Mandantenfaehigkeit, Pipeline-Konfiguration und CLI-Tools
