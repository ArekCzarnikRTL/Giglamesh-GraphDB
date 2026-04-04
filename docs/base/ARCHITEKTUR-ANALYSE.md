# TrustGraph Base -- Architektur, Features und Funktionsanalyse

> **Paket:** `trustgraph-base`
> **Sprache:** Python (>=3.8, asyncio-basiert)
> **Lizenz:** Apache 2.0
> **Stand:** Maerz 2026

---

## Inhaltsverzeichnis

1. [Projektuebersicht](#1-projektuebersicht)
2. [Verzeichnisstruktur](#2-verzeichnisstruktur)
3. [Architektur-Ueberblick](#3-architektur-ueberblick)
4. [Kernmodule im Detail](#4-kernmodule-im-detail)
    - 4.1 [base/ -- Async-Prozessor-Framework](#41-base----async-prozessor-framework)
    - 4.2 [api/ -- REST- und WebSocket-Client](#42-api----rest--und-websocket-client)
    - 4.3 [schema/ -- Datenmodelle und Nachrichtentypen](#43-schema----datenmodelle-und-nachrichtentypen)
    - 4.4 [clients/ -- Pulsar-basierte Service-Clients](#44-clients----pulsar-basierte-service-clients)
    - 4.5 [knowledge/ -- Wissensgraph-Utilities](#45-knowledge----wissensgraph-utilities)
    - 4.6 [provenance/ -- Herkunftsnachverfolgung (PROV-O)](#46-provenance----herkunftsnachverfolgung-prov-o)
    - 4.7 [messaging/ -- Nachrichten-Uebersetzer](#47-messaging----nachrichten-uebersetzer)
    - 4.8 [objects/ -- Objektspeicher](#48-objects----objektspeicher)
5. [Design-Patterns und Architekturmuster](#5-design-patterns-und-architekturmuster)
6. [Nachrichtenfluss und Kommunikation](#6-nachrichtenfluss-und-kommunikation)
7. [API-Schnittstellen](#7-api-schnittstellen)
8. [Metriken und Observability](#8-metriken-und-observability)
9. [Abhaengigkeiten und Tech Stack](#9-abhaengigkeiten-und-tech-stack)
10. [Datenmodell und RDF-Unterstuetzung](#10-datenmodell-und-rdf-unterstuetzung)
11. [Deployment und Konfiguration](#11-deployment-und-konfiguration)
12. [Klassendiagramme](#12-klassendiagramme)
13. [Erweiterbarkeit](#13-erweiterbarkeit)

---

## 1. Projektuebersicht

**TrustGraph** ist eine *Context Development Platform* -- eine graphbasierte Infrastruktur zum Speichern, Anreichern und
Abrufen von strukturiertem Wissen in beliebigem Massstab. Das Konzept laesst sich mit Supabase vergleichen, jedoch
aufgebaut um *Context Graphs* statt traditioneller Datenbanken.

Das Paket `trustgraph-base` bildet das **Fundament** der gesamten Plattform. Es stellt bereit:

- Das **asynchrone Prozessor-Framework** fuer alle Services
- Die **Pub/Sub-Messaging-Schicht** ueber Apache Pulsar
- Alle **Schema-Definitionen** (Datenmodelle) fuer die Kommunikation zwischen Services
- Die **Python-API-Client-Bibliothek** fuer REST- und WebSocket-Zugriff
- **Metriken** (Prometheus) und **Logging** (Loki)
- **Provenance-Tracking** ueber RDF/PROV-O-Triples
- **Knowledge-Graph-Utilities** fuer RDF-Datentypen

### Kernfaehigkeiten der Plattform

| Feature                | Beschreibung                                                             |
|------------------------|--------------------------------------------------------------------------|
| Multi-Modell-Datenbank | Tabellarisch, Key-Value, Dokument, Graph, Vektoren, Bilder, Video, Audio |
| RAG-Pipelines          | DocumentRAG, GraphRAG, OntologyRAG -- sofort einsatzbereit               |
| Agentensystem          | Single Agent, Multi Agent, MCP-Integration                               |
| LLM-Unterstuetzung     | Anthropic, AWS Bedrock, Azure, Cohere, Google, Mistral, OpenAI           |
| Lokale Inferenz        | vLLM, Ollama, TGI, LM Studio, Llamafiles                                 |
| Deployment             | Docker (lokal), Kubernetes (Cloud)                                       |
| Observability          | Prometheus, Grafana, Loki                                                |

---

## 2. Verzeichnisstruktur

```
trustgraph-base/trustgraph/
|
|-- base/                  # Async-Prozessor-Framework (47 Dateien)
|   |-- async_processor.py     # Basisklasse fuer alle Prozessoren
|   |-- flow_processor.py      # Erweitert AsyncProcessor mit Flow-Management
|   |-- flow.py                # Flow-Container (Producer, Consumer, Parameter)
|   |-- consumer.py            # Nachrichten-Empfaenger mit Retry-Logik
|   |-- producer.py            # Nachrichten-Sender
|   |-- publisher.py           # Broadcast-Publisher
|   |-- subscriber.py          # Broadcast-Subscriber mit Backpressure
|   |-- pubsub.py              # Pub/Sub Factory
|   |-- pulsar_backend.py      # Apache Pulsar Backend-Implementierung
|   |-- backend.py             # Backend-Abstraktion
|   |-- metrics.py             # Prometheus-Metriken
|   |-- logging.py             # Logging-Konfiguration (Loki)
|   |-- llm_service.py         # Basis fuer LLM-Text-Completion
|   |-- embeddings_service.py  # Basis fuer Embedding-Services
|   |-- agent_service.py       # Basis fuer Agenten-Services
|   |-- chunking_service.py    # Basis fuer Chunking-Services
|   |-- tool_service.py        # Basis fuer Tool-Services
|   |-- dynamic_tool_service.py# Dynamisches Tool-Service
|   |-- *_client.py            # Diverse Service-Clients
|   |-- *_spec.py              # Spezifikationsklassen
|   `-- ...
|
|-- api/                   # REST/WebSocket API-Client (19 Dateien)
|   |-- api.py                 # Haupt-API-Client-Klasse
|   |-- flow.py                # Flow-Management und FlowInstance
|   |-- config.py              # Konfigurationsmanagement
|   |-- knowledge.py           # Knowledge-Core-Operationen
|   |-- library.py             # Dokumentenbibliothek
|   |-- collection.py          # Collection-Management
|   |-- socket_client.py       # WebSocket-Client
|   |-- async_socket_client.py # Async WebSocket-Client
|   |-- bulk_client.py         # Bulk Import/Export
|   |-- async_bulk_client.py   # Async Bulk-Client
|   |-- metrics.py             # API-Metriken
|   |-- async_metrics.py       # Async Metriken
|   |-- async_flow.py          # Async Flow-Management
|   |-- types.py               # API-Typdefinitionen
|   |-- exceptions.py          # API-Ausnahmen
|   `-- explainability.py      # Erklaerbarkeits-Support
|
|-- schema/                # Datenmodelle (7 Unterverzeichnisse)
|   |-- core/                  # Basis-Typen und Primitive (Term, Triple, IRI, Literal)
|   |-- knowledge/             # Wissens-Schemata (Triples, Embeddings)
|   |-- services/              # Service-Schemata
|   |   |-- llm/               # TextCompletion Request/Response
|   |   |-- retrieval/         # GraphRAG, DocumentRAG, Embeddings
|   |   |-- query/             # Abfrage-Schemata
|   |   |-- agent/             # Agenten-Schemata
|   |   |-- flow/              # Flow-Schemata
|   |   |-- config/            # Konfiguration
|   |   |-- prompt/            # Prompt-Management
|   |   |-- library/           # Bibliotheks-Schemata
|   |   |-- storage/           # Speicher-Schemata
|   |   |-- tool_service/      # Tool-Service-Schemata
|   |   `-- ...
|   `-- __init__.py
|
|-- clients/               # Pulsar-basierte Service-Clients (15 Dateien)
|
|-- knowledge/             # Knowledge-Graph-Utilities (6 Dateien)
|   |-- rdf.py                 # RDF-Datentypen (Uri, Literal, QuotedTriple)
|   |-- hash.py                # Hash-Funktionen fuer Knowledge-Entitaeten
|   `-- ...
|
|-- provenance/            # PROV-O Herkunftsnachverfolgung (5 Dateien)
|   |-- triples.py             # PROV-O Triple-Generierung
|   |-- namespaces.py          # RDF-Namensraeume (PROV, DC, TG)
|   |-- uris.py                # URI-Generierung
|   |-- vocabulary.py          # Vokabular-Definitionen
|   `-- agent.py               # Agenten-Provenance
|
|-- messaging/             # Nachrichten-Uebersetzer (3 Dateien)
|
|-- objects/               # Objekt-/Dateispeicher (3 Dateien)
|
|-- exceptions.py          # Globale Ausnahmen (TooManyRequests)
|-- log_level.py           # Log-Level-Konfiguration
`-- rdf.py                 # RDF-Hilfsfunktionen
```

---

## 3. Architektur-Ueberblick

TrustGraph folgt einer **Event-Driven Microservice-Architektur** mit folgenden Kernprinzipien:

```
                          +------------------+
                          |  Config Service  |
                          |  (ConfigPush)    |
                          +--------+---------+
                                   |
                          ConfigPush-Broadcast
                                   |
             +---------------------+---------------------+
             |                     |                     |
    +--------v--------+  +--------v--------+  +---------v-------+
    |  LLM Service    |  | Embeddings Svc  |  | GraphRAG Svc    |
    |  (FlowProc.)    |  | (FlowProc.)     |  | (FlowProc.)     |
    +--------+--------+  +--------+--------+  +---------+-------+
             |                     |                     |
             +----------+----------+----------+----------+
                        |                     |
                   Apache Pulsar         Apache Pulsar
                  (Request Topics)      (Response Topics)
                        |                     |
             +----------v----------+----------v----------+
             |                                           |
    +--------v--------+                        +---------v-------+
    |  API Gateway    |                        |  Workbench UI   |
    |  (REST/WS)      |                        |  (Port 8888)    |
    +-----------------+                        +-----------------+
```

### Architekturprinzipien

1. **Async-First**: Alle I/O-Operationen nutzen `asyncio` mit `TaskGroups` fuer strukturierte Nebenlaeufigkeit
2. **Konfigurationsgetrieben**: Services reagieren dynamisch auf `ConfigPush`-Nachrichten
3. **Schema-First**: Alle Nachrichten sind `dataclass`-basiert mit automatischer JSON-Serialisierung
4. **Streaming-First**: Unterstuetzung fuer inkrementelle Antworten mit End-of-Stream-Markierungen
5. **Erklaerbarkeit**: Eingebautes Provenance-Tracking ueber RDF-Triples
6. **Multi-Tenancy**: Benutzer- und Collection-Kontext in jeder Anfrage
7. **Fehlertoleranz**: Strukturierte Fehlerantworten, Rate-Limiting, Retry-Logik
8. **Graceful Shutdown**: Drain-Modus fuer Subscriber mit Timeout-Handling

---

## 4. Kernmodule im Detail

### 4.1 base/ -- Async-Prozessor-Framework

Das `base/`-Modul ist das Herzstrueck der gesamten Plattform. Es implementiert das asynchrone Verarbeitungs-Framework,
auf dem alle TrustGraph-Services aufbauen.

#### AsyncProcessor (`async_processor.py`)

Die **Basisklasse fuer alle TrustGraph-Services**. Sie implementiert:

- **Pulsar-Client-Verwaltung**: Erstellt und verwaltet die Verbindung zum Message-Broker
- **Async-Startup-Logik**: Verwendet `asyncio.TaskGroup` fuer strukturierte Nebenlaeufigkeit
- **Metriken-Initialisierung**: Registriert Prometheus-Metriken beim Start
- **Konfigurationsabonnement**: Abonniert `config_push_queue` fuer dynamische Konfiguration

```python
class AsyncProcessor:
    def __init__(self, **params):
        self.id = params.get("id")
        self.pubsub_backend = get_pubsub(**params)
        self.taskgroup = params.get("taskgroup")  # Pflicht!
        self.config_handlers = []
        # Abonniert config_push_queue automatisch
```

**Wichtige Mechanismen:**

- Jeder Prozessor hat eine eindeutige `id`
- Die `taskgroup` ist zwingend erforderlich -- alle Aktivitaeten laufen darin
- Konfigurationsaenderungen werden ueber registrierte Handler verarbeitet
- Ein zufaelliger `config_subscriber_id` stellt sicher, dass jede Instanz eigene Nachrichten erhaelt

#### FlowProcessor (`flow_processor.py`)

Erweitert `AsyncProcessor` um **dynamisches Flow-Management**:

```python
class FlowProcessor(AsyncProcessor):
    def __init__(self, **params):
        super().__init__(**params)
        self.register_config_handler(self.on_configure_flows)
        self.flows = {}           # Aktive Flows
        self.specifications = []  # ConsumerSpec, ProducerSpec, ParameterSpec
```

**Flow-Lifecycle:**

1. `ConfigPush` kommt an -> `on_configure_flows()` wird aufgerufen
2. Neue Flows werden per `start_flow()` gestartet
3. Entfernte Flows werden per `stop_flow()` gestoppt
4. Jeder Flow instanziiert Consumer/Producer gemaess den Spezifikationen

Dies erlaubt es, **zur Laufzeit Flows hinzuzufuegen, zu aendern oder zu entfernen**, ohne den Service neu zu starten.

#### Flow (`flow.py`)

Ein **Flow** ist der Container, der Producer, Consumer und Parameter zusammenfasst:

```python
class Flow:
    def __init__(self, id, flow, processor, defn):
        self.producer = {}    # Name -> Producer
        self.consumer = {}    # Name -> Consumer
        self.parameter = {}   # Name -> Parameter

        # Jede Spezifikation fuegt sich selbst hinzu
        for spec in processor.specifications:
            spec.add(self, processor, defn)
```

Der Flow bietet Zugriff ueber `flow("name")` -- er sucht zuerst in Producern, dann Consumern, dann Parametern.

#### Consumer (`consumer.py`)

Der **Consumer** ist die zentrale Empfangsschleife:

```python
class Consumer:
    def __init__(self, taskgroup, flow, backend, topic, subscriber,
                 schema, handler, metrics=None,
                 rate_limit_retry_time=10,
                 rate_limit_timeout=7200,
                 concurrency=1):
```

**Funktionen:**

- Empfaengt Nachrichten von einer Pulsar-Queue
- Ruft den registrierten `handler` auf
- **Rate-Limiting**: Bei `TooManyRequests`-Ausnahme wird nach `rate_limit_retry_time` Sekunden erneut versucht (max.
  `rate_limit_timeout`)
- **Reconnect**: Bei Verbindungsabbruch automatische Wiederverbindung nach `reconnect_time`
- **Concurrency**: Konfigurierbare Anzahl paralleler Request-Verarbeitungen
- **Metriken**: Zaehlt Verarbeitungen nach Status, misst Latenz

#### Producer (`producer.py`)

Sendet Nachrichten an Pulsar-Topics:

```python
class Producer:
    def __init__(self, backend, topic, schema, metrics=None,
                 chunking_enabled=True):
```

- **Lazy Connection**: Verbindung wird erst beim ersten `send()` hergestellt
- **Retry-Logik**: Bei Verbindungsfehlern wird automatisch reconnected
- **Chunking**: Grosse Nachrichten koennen aufgeteilt werden

#### Spezifikationsklassen

Die Spec-Klassen definieren **deklarativ**, welche Consumer, Producer und Parameter ein Service benoetigt:

| Klasse                | Datei                      | Zweck                                     |
|-----------------------|----------------------------|-------------------------------------------|
| `ConsumerSpec`        | `consumer_spec.py`         | Definiert Input-Queue, Schema und Handler |
| `ProducerSpec`        | `producer_spec.py`         | Definiert Output-Queue und Schema         |
| `ParameterSpec`       | `parameter_spec.py`        | Definiert konfigurierbare Parameter       |
| `RequestResponseSpec` | `request_response_spec.py` | Bidirektionales Request/Response-Pattern  |
| `SubscriberSpec`      | `subscriber_spec.py`       | Broadcast-Abonnement                      |

Beispiel einer Service-Definition:

```python
class LlmService(FlowProcessor):
    def __init__(self, **params):
        super().__init__(**params)
        self.register_specification(
            ConsumerSpec(
                name="request",
                schema=TextCompletionRequest,
                handler=self.on_request,
            )
        )
        self.register_specification(
            ProducerSpec(
                name="response",
                schema=TextCompletionResponse,
            )
        )
```

#### Service-Basisklassen

| Klasse                           | Datei                                  | Zweck                              |
|----------------------------------|----------------------------------------|------------------------------------|
| `LlmService`                     | `llm_service.py`                       | Text-Completion mit Token-Tracking |
| `EmbeddingsService`              | `embeddings_service.py`                | Vektor-Embeddings                  |
| `ChunkingService`                | `chunking_service.py`                  | Dokumenten-Chunking                |
| `AgentService`                   | `agent_service.py`                     | Agenten-Verarbeitung               |
| `ToolService`                    | `tool_service.py`                      | Tool-Ausfuehrung                   |
| `DynamicToolService`             | `dynamic_tool_service.py`              | Dynamische Tool-Registrierung      |
| `TriplesQueryService`            | `triples_query_service.py`             | Triple-Abfragen                    |
| `TriplesStoreService`            | `triples_store_service.py`             | Triple-Speicherung                 |
| `GraphEmbeddingsQueryService`    | `graph_embeddings_query_service.py`    | Graph-Embedding-Abfragen           |
| `GraphEmbeddingsStoreService`    | `graph_embeddings_store_service.py`    | Graph-Embedding-Speicherung        |
| `DocumentEmbeddingsQueryService` | `document_embeddings_query_service.py` | Dokument-Embedding-Abfragen        |
| `DocumentEmbeddingsStoreService` | `document_embeddings_store_service.py` | Dokument-Embedding-Speicherung     |

#### LlmService im Detail

```python
class LlmResult:
    """Ergebnis einer LLM-Anfrage"""
    __slots__ = ["text", "in_token", "out_token", "model"]

class LlmChunk:
    """Streaming-Chunk vom LLM"""
    __slots__ = ["text", "in_token", "out_token", "model", "is_final"]

class LlmService(FlowProcessor):
    # Registriert Consumer fuer TextCompletionRequest
    # Registriert Producer fuer TextCompletionResponse
    # Trackt Token-Nutzung und Modell-Info via Prometheus
```

---

### 4.2 api/ -- REST- und WebSocket-Client

Das `api/`-Modul stellt die **Python-Client-Bibliothek** bereit, mit der Entwickler auf TrustGraph-Services zugreifen
koennen.

#### Api-Klasse (`api.py`)

```python
class Api:
    def __init__(self, url="http://localhost:8088/", timeout=60, token=None):
        self.url = url
        self.timeout = timeout
        self.token = token

    # Zugriff auf Sub-Clients:
    def flow(self) -> Flow         # Flow-Management
    def knowledge(self) -> Knowledge  # Knowledge-Core
    def library(self) -> Library     # Dokumentenbibliothek
    def config(self) -> Config       # Konfiguration
    def collection(self) -> Collection  # Collection-Management
```

**Nutzungsbeispiel:**

```python
with Api(url="http://localhost:8088/") as api:
    # GraphRAG-Abfrage
    result = api.flow().id("default").graph_rag(
        query="Was ist TrustGraph?",
        collection="meine-sammlung",
        user="benutzer-1"
    )

    # Text-Completion
    result = api.flow().id("default").text_completion(
        system="Du bist ein hilfreicher Assistent",
        prompt="Erklaere mir Wissensgraphen"
    )
```

#### FlowInstance -- Service-Operationen

`FlowInstance` wird durch `api.flow().id("flow-name")` erstellt und bietet Zugriff auf:

| Methode                                   | Beschreibung                |
|-------------------------------------------|-----------------------------|
| `text_completion(system, prompt)`         | LLM-Text-Completion         |
| `graph_rag(query, collection, user)`      | GraphRAG-Abfrage            |
| `document_rag(query, collection, user)`   | DocumentRAG-Abfrage         |
| `agent(question, collection, user)`       | Agenten-Anfrage             |
| `embeddings(text)`                        | Vektor-Embeddings berechnen |
| `triples_query(s, p, o, limit)`           | Triple-Store abfragen       |
| `graph_embeddings_query(query, limit)`    | Graph-Embedding-Suche       |
| `document_embeddings_query(query, limit)` | Dokument-Embedding-Suche    |
| `rows_query(query, collection, limit)`    | Zeilen-Abfrage              |
| `librarian(operation, ...)`               | Bibliotheks-Operationen     |

#### Flow-Management -- Blueprints und Instanzen

```python
# Blueprints (Vorlagen)
api.flow().list_blueprints()              # Alle Blueprints auflisten
api.flow().get_blueprint("name")          # Blueprint abrufen
api.flow().put_blueprint("name", defn)    # Blueprint erstellen/aktualisieren
api.flow().delete_blueprint("name")       # Blueprint loeschen

# Flow-Instanzen
api.flow().list()                         # Aktive Flows auflisten
api.flow().get("id")                      # Flow-Definition abrufen
api.flow().create("id", "blueprint")      # Flow aus Blueprint erstellen
api.flow().delete("id")                   # Flow loeschen
```

#### WebSocket-Client (`socket_client.py`, `async_socket_client.py`)

Fuer **Echtzeit-Streaming** von Agenten-Antworten, RAG-Abfragen und Text-Completion:

- Chunk-basiertes Streaming (Thought, Action, Observation, Answer)
- Erklaerbarkeits-Events mit Provenance-URIs
- Synchrone und asynchrone Varianten verfuegbar

#### Bulk-Client (`bulk_client.py`, `async_bulk_client.py`)

Fuer **Massenimport/-export** von Daten:

- Bulk-Import von Triples, Embeddings, Dokumenten
- Bulk-Export fuer Backups und Migration
- Synchrone und asynchrone Varianten

---

### 4.3 schema/ -- Datenmodelle und Nachrichtentypen

Das Schema-Modul definiert **alle Datenstrukturen**, die zwischen Services ausgetauscht werden. Organisiert in drei
Ebenen:

#### core/ -- Basis-Primitive

| Typ       | Beschreibung                                            |
|-----------|---------------------------------------------------------|
| `Term`    | Basis-Datentyp mit Typ-Tag (`IRI`, `LITERAL`, `TRIPLE`) |
| `Triple`  | Subject-Predicate-Object mit optionalem Graph           |
| `IRI`     | Internationalized Resource Identifier                   |
| `LITERAL` | Literaler Wert (String)                                 |
| `TRIPLE`  | Quoted Triple (verschachtelt)                           |

#### knowledge/ -- Wissens-Schemata

Definitionen fuer Triples, Embeddings, und Knowledge-Graph-Operationen.

#### services/ -- Service-Schemata

Alle Service-spezifischen Request/Response-Typen:

| Service                 | Request                     | Response                     |
|-------------------------|-----------------------------|------------------------------|
| **LLM**                 | `TextCompletionRequest`     | `TextCompletionResponse`     |
| **Agent**               | `AgentRequest`              | `AgentResponse`              |
| **GraphRAG**            | `GraphRagQuery`             | `GraphRagResponse`           |
| **DocumentRAG**         | `DocumentRagQuery`          | `DocumentRagResponse`        |
| **Embeddings**          | `EmbeddingsRequest`         | `EmbeddingsResponse`         |
| **Graph-Embeddings**    | `GraphEmbeddingsRequest`    | `GraphEmbeddingsResponse`    |
| **Document-Embeddings** | `DocumentEmbeddingsRequest` | `DocumentEmbeddingsResponse` |
| **Row-Embeddings**      | `RowEmbeddingsRequest`      | `RowEmbeddingsResponse`      |
| **Triples-Query**       | `TriplesQueryRequest`       | `TriplesQueryResponse`       |
| **Structured-Query**    | `StructuredQueryRequest`    | `StructuredQueryResponse`    |
| **NLP-Query**           | `NlpQueryRequest`           | `NlpQueryResponse`           |
| **Librarian**           | `LibrarianRequest`          | `LibrarianResponse`          |
| **Config**              | `ConfigRequest`             | `ConfigPush`                 |
| **Flow**                | Flow-Schemata               | --                           |
| **Prompt**              | Prompt-Schemata             | --                           |
| **Collection**          | Collection-Schemata         | --                           |
| **Storage**             | Storage-Schemata            | --                           |
| **Tool-Service**        | Tool-Service-Schemata       | --                           |
| **Diagnosis**           | Diagnose-Schemata           | --                           |

---

### 4.4 clients/ -- Pulsar-basierte Service-Clients

Spezialisierte Clients, die ueber Apache Pulsar direkt mit den Services kommunizieren (ohne REST-API):

| Client                     | Beschreibung                   |
|----------------------------|--------------------------------|
| `TextCompletionClient`     | LLM-Anfragen senden            |
| `EmbeddingsClient`         | Embedding-Berechnung           |
| `GraphRagClient`           | GraphRAG-Abfragen              |
| `GraphEmbeddingsClient`    | Graph-Embedding-Operationen    |
| `DocumentEmbeddingsClient` | Dokument-Embedding-Operationen |
| `RowEmbeddingsQueryClient` | Zeilen-Embedding-Abfragen      |
| `TriplesClient`            | Triple-Store-Operationen       |
| `StructuredQueryClient`    | Strukturierte Abfragen         |
| `PromptClient`             | Prompt-Management              |
| `AgentClient`              | Agenten-Kommunikation          |
| `ToolClient`               | Tool-Ausfuehrung               |
| `ToolServiceClient`        | Tool-Service-Verwaltung        |

**Request/Response-Pattern:**

```
Client sendet Request -> input_queue (mit UUID als Property)
                                    |
                                 Service
                                    |
Client empfaengt Response <- output_queue (UUID-Match)
```

- Timeout-Handling mit konfigurierbarem Retry und Backoff
- Jede Anfrage erhaelt eine eindeutige UUID fuer die Zuordnung

---

### 4.5 knowledge/ -- Wissensgraph-Utilities

Stellt RDF-kompatible Datentypen bereit:

```python
class Uri:
    """Internationalized Resource Identifier"""
    pass

class Literal:
    """RDF-Literal (String-Wert)"""
    pass

class QuotedTriple:
    """Verschachteltes Triple (RDF-Star)"""
    def __init__(self, s, p, o):
        self.s = s  # Subject
        self.p = p  # Predicate
        self.o = o  # Object
```

Zusaetzlich:

- `hash.py` -- Hash-Funktionen fuer deterministische Identifikation von Entitaeten
- Konvertierungsfunktionen zwischen internem Format und Wire-Format

---

### 4.6 provenance/ -- Herkunftsnachverfolgung (PROV-O)

Implementiert das **W3C PROV-O-Modell** fuer lueckenlose Nachverfolgbarkeit:

#### Namensraeume (`namespaces.py`)

```
RDF:   rdf:type, rdfs:label
PROV:  prov:Entity, prov:Activity, prov:Agent,
       prov:wasDerivedFrom, prov:wasGeneratedBy,
       prov:used, prov:wasAssociatedWith, prov:startedAtTime
DC:    dc:title, dc:source, dc:date, dc:creator
TG:    tg:pageCount, tg:mimeType, tg:pageNumber,
       tg:chunkIndex, tg:charOffset, tg:charLength,
       tg:chunkSize, tg:chunkOverlap, tg:componentVersion,
       tg:llmModel, tg:ontology, tg:contains, ...
```

#### Provenance-Entitaetstypen

| Typ                | Beschreibung          |
|--------------------|-----------------------|
| `TG_DOCUMENT_TYPE` | Quell-Dokument        |
| `TG_PAGE_TYPE`     | Dokumentenseite       |
| `TG_SECTION_TYPE`  | Dokumentenabschnitt   |
| `TG_CHUNK_TYPE`    | Text-Chunk            |
| `TG_IMAGE_TYPE`    | Bild                  |
| `TG_SUBGRAPH_TYPE` | Extrahierter Subgraph |

#### Query-Time-Provenance

| Praedikat         | Kontext                 |
|-------------------|-------------------------|
| `tg:query`        | Die gestellte Frage     |
| `tg:concept`      | Erkanntes Konzept       |
| `tg:entity`       | Zugeordnete Entitaet    |
| `tg:edgeCount`    | Anzahl der Kanten       |
| `tg:selectedEdge` | Ausgewaehlte Kante      |
| `tg:reasoning`    | Begruendung             |
| `tg:document`     | Referenziertes Dokument |

#### Erklaerbarkeits-Entitaetstypen

| Typ              | Beschreibung                |
|------------------|-----------------------------|
| `TG_QUESTION`    | Die gestellte Frage         |
| `TG_GROUNDING`   | Verankerung/Kontextbasis    |
| `TG_EXPLORATION` | Erkundungsphase             |
| `TG_FOCUS`       | Fokussierungsphase          |
| `TG_SYNTHESIS`   | Synthese/Antwortgenerierung |
| `TG_ANSWER_TYPE` | Antwort-Typ                 |

---

### 4.7 messaging/ -- Nachrichten-Uebersetzer

Stellt Adapter bereit, um Nachrichten zwischen verschiedenen Service-Formaten zu uebersetzen.

---

### 4.8 objects/ -- Objektspeicher

Schnittstelle zum S3-kompatiblen Objektspeicher (Garage) fuer Dateien, Bilder und andere Binaerdaten.

---

## 5. Design-Patterns und Architekturmuster

### 5.1 AsyncProcessor-Pattern

```
                AsyncProcessor (Basis)
                       |
              FlowProcessor (+ Flow-Mgmt)
              /        |        \
     LlmService  EmbeddingsService  AgentService  ...
```

Jeder Service:

1. Erbt von `FlowProcessor`
2. Registriert seine Spezifikationen (Consumer, Producer, Parameter)
3. Implementiert Handler-Methoden
4. Wird durch `ConfigPush` dynamisch konfiguriert

### 5.2 Specification-Pattern

Services definieren ihre Schnittstellren **deklarativ** ueber Spec-Objekte:

```python
# Deklarative Service-Definition
self.register_specification(ConsumerSpec(
    name="request",
    schema=TextCompletionRequest,
    handler=self.on_request,
))
self.register_specification(ProducerSpec(
    name="response",
    schema=TextCompletionResponse,
))
self.register_specification(ParameterSpec(
    name="model",
    default="default-model",
))
```

### 5.3 Configuration-Push-Pattern

```
Config Service ---ConfigPush---> Alle Prozessoren
                                       |
                              on_configure_flows()
                                       |
                            +----------+----------+
                            |                     |
                      start_flow()          stop_flow()
```

### 5.4 Request/Response-Pattern

```
Client                          Service
  |                                |
  |--- Request (UUID) ----------->|
  |         [input_queue]          |
  |                                |-- Processing
  |<---------- Response (UUID) ---|
  |         [output_queue]         |
```

### 5.5 Streaming-Pattern

Fuer grosse Antworten (RAG, Agenten):

```
Service sendet:
  Chunk 1 (text="Erster Teil...")
  Chunk 2 (text="Zweiter Teil...")
  ...
  Chunk N (end_of_stream=True)
```

---

## 6. Nachrichtenfluss und Kommunikation

### Topic-Namenskonvention

```
{qos}/{tenant}/{namespace}/{queue_name}

Beispiele:
  q0/tg/flow/text-completion-request    # Best-effort
  q1/tg/flow/graph-rag-response         # At-least-once
  q2/tg/config/config                   # Exactly-once
```

**Quality-of-Service-Level:**

| QoS  | Garantie      | Einsatz                        |
|------|---------------|--------------------------------|
| `q0` | Best-effort   | Monitoring, Metriken           |
| `q1` | At-least-once | Standard-Nachrichten           |
| `q2` | Exactly-once  | Konfiguration, kritische Daten |

### Beispiel: GraphRAG-Abfragefluss

```
1. Benutzer -> API Gateway (REST/WebSocket)
2. API Gateway -> Pulsar: GraphRagQuery auf graph-rag-request
3. GraphRAG-Service empfaengt Nachricht
   a. Berechnet Embeddings der Anfrage
   b. Durchsucht den Wissensgraphen
   c. Selektiert relevante Entitaeten und Beziehungen
   d. Ruft LLM zur Synthese auf
4. GraphRAG-Service -> Pulsar: GraphRagResponse (Streaming-Chunks)
5. API Gateway -> Benutzer: Streaming-Antwort mit Provenance
```

---

## 7. API-Schnittstellen

### REST API (Standard-Port 8088)

| Endpunkt              | Beschreibung                     |
|-----------------------|----------------------------------|
| `/api/v1/flow/`       | Flow-Management und -Ausfuehrung |
| `/api/v1/config/`     | Konfigurationsverwaltung         |
| `/api/v1/knowledge/`  | Knowledge-Core-Management        |
| `/api/v1/library/`    | Dokumentenbibliothek             |
| `/api/v1/collection/` | Collection-Management            |
| `/api/v1/bulk/`       | Bulk-Import/Export               |

### WebSocket API

- **Echtzeit-Streaming** fuer Agenten, RAG-Abfragen, Text-Completion
- **Chunk-basiert**: Thought -> Action -> Observation -> Answer
- **Erklaerbarkeit**: Provenance-URIs in jedem Event

### Python API

```python
from trustgraph.api import Api

api = Api(url="http://localhost:8088/", token="mein-token")

# Flow-Operationen
flow = api.flow().id("default")
antwort = flow.graph_rag(query="Frage", collection="sammlung")
completion = flow.text_completion(system="System", prompt="Prompt")
embeddings = flow.embeddings(text="Text zum Einbetten")

# Knowledge-Management
api.knowledge().export_core("core-name")
api.knowledge().import_core("core-name", data)

# Bibliothek
api.library().upload("dateiname.pdf", pdf_bytes)
api.library().list()
```

---

## 8. Metriken und Observability

### Prometheus-Metriken (Standard-Port 8000)

#### ConsumerMetrics

| Metrik             | Typ       | Labels                        | Beschreibung                     |
|--------------------|-----------|-------------------------------|----------------------------------|
| `consumer_state`   | Enum      | processor, flow, name         | Status: stopped/running          |
| `request_latency`  | Histogram | processor, flow, name         | Verarbeitungslatenz (Sekunden)   |
| `processing_count` | Counter   | processor, flow, name, status | Verarbeitungszaehler nach Status |
| `rate_limit_count` | Counter   | processor, flow, name         | Rate-Limit-Events                |

#### ProducerMetrics

| Metrik            | Typ     | Labels                | Beschreibung                |
|-------------------|---------|-----------------------|-----------------------------|
| `producer_output` | Counter | processor, flow, name | Ausgangs-Nachrichtenzaehler |

#### ProcessorMetrics

| Metrik           | Typ  | Labels    | Beschreibung                          |
|------------------|------|-----------|---------------------------------------|
| `processor_info` | Info | processor | Prozessor-Konfigurationsinformationen |

### Grafana-Dashboard (Standard-Port 3000)

Vorinstallierte Metriken:

- LLM-Latenz
- Fehlerrate
- Service-Request-Raten
- Queue-Rueckstaende
- Chunking-Histogramm
- Fehlerquelle nach Service
- Rate-Limit-Events
- CPU-/Speichernutzung pro Service
- Deployed Models
- Token-Durchsatz (Tokens/Sekunde)
- Kosten-Durchsatz (Kosten/Sekunde)

### Logging (Loki)

- Zentralisierte Log-Aggregation ueber Grafana Loki
- Optionale Basic-Auth-Authentifizierung
- Processor-ID in allen Log-Nachrichten
- Konfigurierbar ueber `add_logging_args()` und `setup_logging()`

---

## 9. Abhaengigkeiten und Tech Stack

### Direkte Python-Abhaengigkeiten (trustgraph-base)

| Paket                 | Zweck                               |
|-----------------------|-------------------------------------|
| `pulsar-client`       | Apache Pulsar Message-Broker-Client |
| `prometheus-client`   | Prometheus-Metriken-Export          |
| `requests`            | HTTP-Client fuer REST-API           |
| `python-logging-loki` | Loki-Log-Aggregation                |

### Infrastruktur-Komponenten

| Komponente           | Rolle                 | Beschreibung                             |
|----------------------|-----------------------|------------------------------------------|
| **Apache Pulsar**    | Message Broker        | Pub/Sub-Messaging-Fabric mit QoS-Stufen  |
| **Apache Cassandra** | Multi-Modell-Speicher | Tabellarisch, Key-Value, Graph, Dokument |
| **Qdrant**           | VectorDB              | Vektor-Embedding-Speicher und -Suche     |
| **Garage**           | Objektspeicher        | S3-kompatibler Datei-/Objektspeicher     |
| **Prometheus**       | Metriken              | Sammlung und Speicherung von Metriken    |
| **Grafana**          | Dashboards            | Visualisierung und Alerting              |
| **Loki**             | Logs                  | Log-Aggregation und -Suche               |

### Unterstuetzte LLM-Anbieter

| API-Anbieter            | Lokale Inferenz                 |
|-------------------------|---------------------------------|
| Anthropic               | vLLM                            |
| AWS Bedrock             | Ollama                          |
| Azure AI / Azure OpenAI | TGI (Text Generation Inference) |
| Google AI Studio        | LM Studio                       |
| Google Vertex AI        | Llamafiles                      |
| Mistral                 |                                 |
| Cohere                  |                                 |
| OpenAI                  |                                 |

### Companion-Pakete

| Paket                      | Beschreibung                      |
|----------------------------|-----------------------------------|
| `trustgraph`               | Haupt-API-Client-Bibliothek       |
| `trustgraph-cli`           | Kommandozeilen-Interface          |
| `trustgraph-flow`          | Flow-Orchestrierung               |
| `trustgraph-vertexai`      | Google Vertex AI Integration      |
| `trustgraph-bedrock`       | AWS Bedrock Integration           |
| `trustgraph-embeddings-hf` | HuggingFace Embeddings            |
| `trustgraph-ocr`           | OCR-Verarbeitung                  |
| `trustgraph-mcp`           | Model Context Protocol            |
| `trustgraph-unstructured`  | Unstrukturierte Datenverarbeitung |

---

## 10. Datenmodell und RDF-Unterstuetzung

### Triple-Modell

TrustGraph verwendet ein RDF-basiertes Datenmodell:

```
Triple {
    s: Term    # Subject (IRI, Literal oder QuotedTriple)
    p: Term    # Predicate (IRI)
    o: Term    # Object (IRI, Literal oder QuotedTriple)
    g: String  # Optional: Named Graph URI
}

Term {
    t: String  # Typ: "i" (IRI), "l" (Literal), "t" (Triple)
    i: String  # IRI-Wert (wenn t="i")
    v: String  # Literal-Wert (wenn t="l")
    tr: Triple # Verschachteltes Triple (wenn t="t")
}
```

### Wire-Format (JSON)

```json
{
    "s": {"t": "i", "i": "urn:entity:person:alice"},
    "p": {"t": "i", "i": "urn:rel:knows"},
    "o": {"t": "i", "i": "urn:entity:person:bob"}
}
```

### RDF-Star-Unterstuetzung

TrustGraph unterstuetzt **Quoted Triples** (RDF-Star), was es ermoeglicht, Aussagen ueber Aussagen zu machen:

```python
QuotedTriple(
    s=Uri("urn:entity:alice"),
    p=Uri("urn:rel:knows"),
    o=Uri("urn:entity:bob")
)
# Kann als Subject oder Object in einem anderen Triple verwendet werden
```

---

## 11. Deployment und Konfiguration

### Quickstart

```bash
npx @trustgraph/config
```

Generiert:

- `deploy.zip` mit `docker-compose.yaml` (Docker) oder `resources.yaml` (Kubernetes)
- `INSTALLATION.md` mit Deployment-Anweisungen

### Standard-Ports

| Port | Service                      |
|------|------------------------------|
| 8088 | API Gateway (REST/WebSocket) |
| 8888 | Workbench UI                 |
| 8000 | Prometheus Metriken          |
| 3000 | Grafana Dashboard            |
| 6650 | Apache Pulsar                |

### Workbench-Features (Port 8888)

| Feature                   | Beschreibung                                             |
|---------------------------|----------------------------------------------------------|
| Vector Search             | Semantische Suche in Wissensbasen                        |
| Chat (Agent/GraphRAG/LLM) | Chat-Interface fuer verschiedene Modi                    |
| Relationships             | Tiefe Beziehungsanalyse                                  |
| Graph Visualizer          | 3D-GraphViz der Wissensbasen                             |
| Library                   | Staging-Bereich fuer Wissensbasen                        |
| Flow Classes              | Workflow-Presets                                         |
| Flows                     | Benutzerdefinierte Workflows, LLM-Parameter zur Laufzeit |
| Knowledge Cores           | Verwaltung wiederverwendbarer Wissensbasen               |
| Prompts                   | Prompt-Verwaltung zur Laufzeit                           |
| Schemas                   | Schema-Definition fuer strukturierte Daten               |
| Ontologies                | Ontologie-Definition fuer unstrukturierte Daten          |
| Agent Tools               | Tool-Definition mit Collections, MCP, Tool-Gruppen       |
| MCP Tools                 | MCP-Server-Verbindungen                                  |

---

## 12. Klassendiagramme

### Prozessor-Hierarchie

```
AsyncProcessor
|-- id: str
|-- pubsub_backend: PulsarClient
|-- taskgroup: TaskGroup
|-- config_handlers: list
|-- register_config_handler(handler)
|
+-- FlowProcessor
    |-- flows: dict[str, Flow]
    |-- specifications: list[Spec]
    |-- register_specification(spec)
    |-- start_flow(flow, defn)
    |-- stop_flow(flow)
    |-- on_configure_flows(config, version)
    |
    +-- LlmService
    |   |-- on_request(handler)
    |   +-- Prometheus: token count, model info, latency
    |
    +-- EmbeddingsService
    +-- ChunkingService
    +-- AgentService
    +-- ToolService
    +-- DynamicToolService
    +-- TriplesQueryService
    +-- TriplesStoreService
    +-- GraphEmbeddingsQueryService
    +-- GraphEmbeddingsStoreService
    +-- DocumentEmbeddingsQueryService
    +-- DocumentEmbeddingsStoreService
```

### Flow und Spezifikationen

```
Flow
|-- id: str
|-- name: str
|-- producer: dict[str, Producer]
|-- consumer: dict[str, Consumer]
|-- parameter: dict[str, Parameter]
|-- start()
|-- stop()
|-- __call__(key) -> Producer | Consumer | value

Spec (Abstract)
+-- ConsumerSpec(name, schema, handler)
+-- ProducerSpec(name, schema)
+-- ParameterSpec(name, default)
+-- RequestResponseSpec(name, request_schema, response_schema)
+-- SubscriberSpec(name, schema, handler)
```

### API-Client-Hierarchie

```
Api
|-- url: str
|-- timeout: int
|-- token: str
|-- flow() -> Flow
|-- knowledge() -> Knowledge
|-- library() -> Library
|-- config() -> Config
|-- collection() -> Collection

Flow
|-- id(id) -> FlowInstance
|-- list_blueprints() -> list[str]
|-- get_blueprint(name) -> dict
|-- put_blueprint(name, defn)
|-- delete_blueprint(name)
|-- list() -> list[str]
|-- create(id, blueprint)
|-- delete(id)

FlowInstance
|-- text_completion(system, prompt)
|-- graph_rag(query, collection, user)
|-- document_rag(query, collection, user)
|-- agent(question, collection, user)
|-- embeddings(text)
|-- triples_query(s, p, o, limit)
|-- graph_embeddings_query(query, limit)
|-- document_embeddings_query(query, limit)
|-- rows_query(query, collection, limit)
```

---

## 13. Erweiterbarkeit

### Eigenen Service erstellen

Um einen neuen TrustGraph-Service zu erstellen:

```python
from trustgraph.base import FlowProcessor, ConsumerSpec, ProducerSpec

class MeinService(FlowProcessor):
    def __init__(self, **params):
        super().__init__(**params | {"id": "mein-service"})

        self.register_specification(ConsumerSpec(
            name="input",
            schema=MeineAnfrageSchema,
            handler=self.verarbeiten,
        ))
        self.register_specification(ProducerSpec(
            name="output",
            schema=MeineAntwortSchema,
        ))

    async def verarbeiten(self, msg, flow):
        # Nachricht verarbeiten
        ergebnis = await meine_logik(msg)

        # Antwort senden
        await flow("output").send(ergebnis)
```

### Neuen LLM-Anbieter integrieren

```python
from trustgraph.base import LlmService, LlmResult

class MeinLlmService(LlmService):
    async def complete(self, system_prompt, user_prompt, **kwargs):
        # API des LLM-Anbieters aufrufen
        response = await mein_llm_api.complete(system_prompt, user_prompt)
        return LlmResult(
            text=response.text,
            in_token=response.input_tokens,
            out_token=response.output_tokens,
            model=response.model_name,
        )
```

### Neues Schema definieren

```python
from dataclasses import dataclass

@dataclass
class MeineAnfrage:
    frage: str
    kontext: str = ""
    max_ergebnisse: int = 10

@dataclass
class MeineAntwort:
    antwort: str
    quellen: list = None
    fehler: str = None
```

---

> **Hinweis:** Diese Dokumentation bezieht sich auf den Stand des `trustgraph-base`-Pakets im Repository
`trustgraph-ai/trustgraph`. Fuer die offizielle Dokumentation siehe [docs.trustgraph.ai](https://docs.trustgraph.ai).
