# Agent-System und Streaming

GraphMesh bietet KI-Agenten, die komplexe Fragen ueber den Wissensgraphen beantworten. Die Agenten nutzen Werkzeuge (Tools) in einer Denk-Handlungs-Schleife und liefern Antworten in Echtzeit per Streaming.

---

## Uebersicht

| Funktion | Beschreibung |
|---|---|
| Agent-System | KI-Agenten beantworten komplexe Fragen durch schrittweises Denken und Werkzeug-Einsatz |
| Tool-Gruppen | Werkzeuge sind in logische Gruppen organisiert und koennen gezielt aktiviert werden |
| Streaming | Echtzeit-Uebertragung der Agenten-Antwort mit sichtbarem Denkprozess |

---

## Features

### Agent-System

**Problem:** Einfache Suchanfragen genuegen nicht, wenn die Antwort aus mehreren Wissensquellen zusammengesetzt werden muss. Ein einzelner Datenbankzugriff kann komplexe Fragen nicht beantworten.

**Loesung:** GraphMesh-Agenten arbeiten in einer ReAct-Schleife (Reasoning + Acting): Sie analysieren die Frage, waehlen passende Werkzeuge, fuehren Aktionen aus, beobachten die Ergebnisse und wiederholen diesen Zyklus bis die Frage beantwortet ist. Die maximale Anzahl an Iterationen ist konfigurierbar.

#### Anwendung

- **Frontend:** Auf der Query-Seite den Modus "Agent Stream" waehlen und eine Frage stellen
- **GraphQL API:** Mutation `askAgent` fuer eine vollstaendige Antwort, Subscription `agentStream` fuer Echtzeit-Streaming

#### Verfuegbare Werkzeuge abfragen

Ueber die API laesst sich jederzeit pruefen, welche Werkzeuge dem Agenten zur Verfuegung stehen:

```graphql
query {
  agentTools {
    name
    description
    groups
  }
}
```

#### Beispiel

> **Szenario:** Ein Produktmanager moechte wissen, welche Kunden sowohl Produkt A als auch Produkt B nutzen und welche Vertraege dazu bestehen.

1. Er oeffnet die Query-Seite und waehlt den Modus "Agent Stream"
2. Er stellt die Frage: "Welche Kunden nutzen sowohl Produkt A als auch Produkt B? Zeige die zugehoerigen Vertraege."
3. Der Agent denkt nach (THOUGHT): "Ich muss zuerst Kunden von Produkt A finden, dann Kunden von Produkt B, und die Schnittmenge bilden."
4. Der Agent fuehrt eine Aktion aus (ACTION): Suche im Wissensgraphen nach Kunden mit Beziehung zu Produkt A
5. Der Agent beobachtet das Ergebnis (OBSERVATION): 12 Kunden gefunden
6. Der Agent fuehrt eine weitere Aktion aus: Suche nach Kunden von Produkt B
7. Der Agent liefert die finale Antwort (ANSWER): "3 Kunden nutzen beide Produkte: ..."
8. Der gesamte Denkprozess ist in der Timeline sichtbar

---

### Tool-Gruppen

**Problem:** Nicht jede Frage erfordert alle verfuegbaren Werkzeuge. Irrelevante Tools erhoehen die Antwortzeit und koennen den Agenten ablenken.

**Loesung:** Werkzeuge sind in logische Gruppen organisiert (z.B. "Graph-Suche", "Dokumenten-Suche", "Vektor-Suche"). Bei jeder Anfrage kann festgelegt werden, welche Tool-Gruppen der Agent nutzen darf.

#### Anwendung

- **GraphQL API:** Parameter `allowedGroups` bei `askAgent` und `agentStream` -- beschraenkt die verfuegbaren Werkzeuge auf bestimmte Gruppen

#### Tool-Gruppen abfragen

```graphql
query {
  toolGroups {
    name
    description
    toolNames
  }
}
```

#### Gruppen bei einer Anfrage einschraenken

```graphql
mutation {
  askAgent(input: {
    question: "Finde alle Vertraege mit Siemens"
    collectionId: "col-123"
    allowedGroups: ["graph-suche"]
    maxIterations: 5
  }) {
    sessionId
    answer
    durationMs
  }
}
```

#### Beispiel

> **Szenario:** Ein Analyst moechte sicherstellen, dass der Agent nur im Wissensgraphen sucht und keine Volltextsuche in Dokumenten durchfuehrt.

1. Er ruft `toolGroups` ab und sieht die verfuegbaren Gruppen mit ihren Werkzeugen
2. Er stellt seine Frage mit `allowedGroups: ["graph-suche"]`
3. Der Agent nutzt ausschliesslich Graph-basierte Werkzeuge
4. Die Antwort basiert nur auf strukturiertem Wissen aus dem Graphen

---

### Streaming

**Problem:** Agent-Anfragen koennen mehrere Sekunden dauern. Ohne Streaming sieht der Nutzer nur einen Ladebalken und erhaelt die Antwort erst am Ende.

**Loesung:** GraphMesh streamt den gesamten Agenten-Prozess in Echtzeit. Jedes Token traegt einen Typ, der den aktuellen Schritt des Agenten kennzeichnet. Im Frontend wird eine farbcodierte Timeline dargestellt.

#### Token-Typen

| Typ | Bedeutung | Farbe im Frontend |
|---|---|---|
| TEXT | Allgemeiner Text | Grau |
| THOUGHT | Denkschritt des Agenten | Blau |
| ACTION | Werkzeug-Aufruf | Gelb/Orange |
| OBSERVATION | Ergebnis eines Werkzeugs | Violett |
| ANSWER | Finale Antwort | Gruen |
| ERROR | Fehlermeldung | Rot |

Jedes Token enthaelt zusaetzlich:
- `endOfMessage` -- markiert das Ende eines einzelnen Schritts
- `endOfStream` -- markiert das Ende der gesamten Verarbeitung

#### Anwendung

- **Frontend:** Automatisch aktiv im Modus "Agent Stream" -- die Timeline zeigt jeden Schritt farbcodiert an
- **GraphQL API:** Subscription `agentStream` liefert Token fuer Token in Echtzeit

#### GraphQL Subscription

```graphql
subscription {
  agentStream(input: {
    question: "Was sind die Hauptrisiken im Jahresbericht 2025?"
    collectionId: "col-123"
    maxIterations: 10
  }) {
    content
    type
    endOfMessage
    endOfStream
  }
}
```

#### Beispiel

> **Szenario:** Eine Analystin stellt eine komplexe Frage und beobachtet den Denkprozess des Agenten live.

1. Sie oeffnet die Query-Seite und waehlt "Agent Stream"
2. Sie fragt: "Was sind die drei groessten Risiken im Jahresbericht 2025 und welche Massnahmen werden genannt?"
3. Sofort erscheint ein blauer THOUGHT-Block: "Ich suche zuerst nach Risiko-bezogenen Entitaeten..."
4. Ein gelber ACTION-Block zeigt den Werkzeug-Aufruf
5. Ein violetter OBSERVATION-Block zeigt die gefundenen Ergebnisse
6. Nach 2 weiteren Iterationen erscheint ein gruener ANSWER-Block mit der zusammengefassten Antwort
7. Die gesamte Timeline bleibt sichtbar und nachvollziehbar

---

## GraphQL-Referenz

### Agent-Anfrage (blockierend)

```graphql
mutation {
  askAgent(input: {
    question: "Welche Personen sind im Vorstand von Siemens?"
    collectionId: "col-123"
    maxIterations: 10
    allowedGroups: null
  }) {
    sessionId
    answer
    durationMs
  }
}
```

### Agent-Stream (Echtzeit)

```graphql
subscription {
  agentStream(input: {
    question: "Welche Personen sind im Vorstand von Siemens?"
    collectionId: "col-123"
    maxIterations: 10
  }) {
    content
    type
    endOfMessage
    endOfStream
  }
}
```

### Werkzeuge auflisten

```graphql
query {
  agentTools {
    name
    description
    groups
  }
}
```

### Tool-Gruppen auflisten

```graphql
query {
  toolGroups {
    name
    description
    toolNames
  }
}
```
