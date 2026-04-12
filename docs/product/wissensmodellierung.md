# Wissensmodellierung

GraphMesh ermoeglicht die Strukturierung von Wissen durch Ontologien, kontrollierte Vokabulare (Taxonomien) und den Import bestehender Wissensdaten. Diese Werkzeuge definieren, wie Wissen organisiert, klassifiziert und miteinander verknuepft wird.

---

## Ontology System

**Problem:** Ohne definierte Struktur extrahiert ein LLM Wissen in beliebigen Formaten -- "Mitarbeiter arbeitet-bei Firma" und "Person ist-angestellt-bei Unternehmen" beschreiben dasselbe, werden aber als unterschiedlich behandelt.

**Loesung:** Ontologien definieren die erlaubten Klassen (z.B. Person, Organisation, Projekt) und Beziehungstypen (z.B. arbeitetBei, gehoertZu). GraphMesh nutzt diese Definitionen, um Wissen konsistent zu extrahieren und zu speichern.

### Anwendung

**Ontologien auflisten:**

```graphql
query {
  listOntologies {
    key
    name
    namespace
    version
    classCount
    objectPropertyCount
    datatypePropertyCount
  }
}
```

**Einzelne Ontologie abrufen:**

```graphql
query {
  ontology(key: "unternehmensmodell") {
    key
    name
    namespace
    version
    classCount
    objectPropertyCount
    datatypePropertyCount
  }
}
```

**Ontologie loeschen:**

```graphql
mutation {
  deleteOntology(key: "unternehmensmodell")
}
```

**Frontend:** Seite `/admin/ontologies` -- Uebersicht aller Ontologien mit Detailansicht und Verwaltung.

### Beispiel

> **Szenario:** Ein Wissensmanager moechte die IT-Landschaft seines Unternehmens modellieren.

1. Er erstellt eine Ontologie mit den Klassen `Anwendung`, `Server`, `Datenbank`, `Team`.
2. Er definiert Beziehungstypen: `laeuftAuf`, `nutztDatenbank`, `verantwortetDurch`.
3. Er definiert Datentyp-Eigenschaften: `goLiveDatum`, `kritikalitaet`, `version`.
4. Beim Hochladen von Architekturdokumenten extrahiert GraphMesh nun konsistent strukturiertes Wissen gemaess dieser Ontologie -- z.B. `CRM-System -- laeuftAuf -- Server-EU-01`.

---

## Ontology Import API

**Problem:** Ontologien existieren oft bereits in Standardformaten (Turtle, RDF/XML) und sollen nicht manuell neu erstellt werden.

**Loesung:** Die Import-API nimmt bestehende Ontologie-Dateien entgegen und macht sie in GraphMesh verfuegbar. Unterstuetzte Formate sind Turtle und RDF/XML.

### Anwendung

**GraphQL API:**

```graphql
mutation {
  importOntology(input: {
    key: "unternehmensmodell"
    name: "Unternehmensmodell"
    namespace: "https://example.org/ontology/unternehmen#"
    format: TURTLE
    version: "1.0"
    content: """
      @prefix owl: <http://www.w3.org/2002/07/owl#> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix unt: <https://example.org/ontology/unternehmen#> .

      unt:Person a owl:Class .
      unt:Organisation a owl:Class .
      unt:arbeitetBei a owl:ObjectProperty ;
        rdfs:domain unt:Person ;
        rdfs:range unt:Organisation .
    """
  }) {
    key
    name
    classCount
    objectPropertyCount
    datatypePropertyCount
  }
}
```

Parameter:
- `key` -- Eindeutiger Schluessel der Ontologie
- `name` -- Anzeigename
- `namespace` -- URI-Namespace der Ontologie
- `format` -- `TURTLE` oder `RDFXML`
- `version` -- Versionsnummer (Standard: "1.0")
- `content` -- Der Ontologie-Inhalt im angegebenen Format

**Frontend:** Seite `/admin/ontologies` -- Upload-Dialog fuer Ontologie-Dateien.

### Beispiel

> **Szenario:** Ein Unternehmen hat eine bestehende Ontologie im Turtle-Format aus einem anderen System.

1. Der Administrator oeffnet die Ontologie-Verwaltung und waehlt "Ontologie importieren".
2. Er laedt die `.ttl`-Datei hoch und vergibt den Schluessel `it-architektur` sowie den Namen "IT-Architektur-Ontologie".
3. GraphMesh parst die Datei und meldet: 12 Klassen, 8 Objekt-Eigenschaften, 15 Datentyp-Eigenschaften importiert.
4. Die Ontologie steht sofort fuer die Wissensextraktion aus neuen Dokumenten zur Verfuegung.

---

## SKOS Taxonomy

**Problem:** In grossen Organisationen verwenden verschiedene Abteilungen unterschiedliche Begriffe fuer dieselben Konzepte. Ohne kontrolliertes Vokabular entstehen Synonyme, Mehrdeutigkeiten und lueckenhafte Suche.

**Loesung:** SKOS-Taxonomien (Simple Knowledge Organization System) verwalten kontrollierte Vokabulare mit Konzept-Schemata, Hierarchien (breiter/enger), Synonymen und Beziehungen zwischen Begriffen.

### Anwendung

**Konzept-Schemata auflisten:**

```graphql
query {
  skosConceptSchemes(collectionId: "meine-sammlung") {
    uri
    prefLabels { value lang }
    topConcepts {
      uri
      prefLabels { value lang }
    }
    conceptCount
  }
}
```

**Konzepte eines Schemas abrufen:**

```graphql
query {
  skosConcepts(
    collectionId: "meine-sammlung"
    schemeUri: "https://example.org/scheme/technologien"
  ) {
    uri
    prefLabels { value lang }
    altLabels { value lang }
    broader { uri prefLabels { value lang } }
    narrower { uri prefLabels { value lang } }
    related { uri prefLabels { value lang } }
    scopeNote
    definition
  }
}
```

**Einzelnes Konzept abrufen:**

```graphql
query {
  skosConcept(
    collectionId: "meine-sammlung"
    conceptUri: "https://example.org/concept/kubernetes"
  ) {
    uri
    prefLabels { value lang }
    altLabels { value lang }
    broader { uri prefLabels { value lang } }
    narrower { uri prefLabels { value lang } }
    definition
  }
}
```

**Konzepte suchen (nach Label):**

```graphql
query {
  skosSearch(
    collectionId: "meine-sammlung"
    label: "Container"
  ) {
    uri
    prefLabels { value lang }
    altLabels { value lang }
    definition
  }
}
```

**Frontend:** Seite `/admin/ontologies` -- Bereich fuer Taxonomie-Verwaltung mit Baumansicht der Konzept-Hierarchien.

### Beispiel

> **Szenario:** Eine Organisation moechte ihre Technologie-Begriffe vereinheitlichen.

1. Das Taxonomie-Schema "Technologien" wird angelegt mit den Top-Konzepten "Programmiersprachen", "Infrastruktur", "Datenbanken".
2. Unter "Infrastruktur" werden Konzepte eingeordnet: "Containerisierung" (mit engeren Begriffen "Docker", "Kubernetes", "Podman").
3. "Kubernetes" erhaelt alternative Labels: "K8s", "k8s" -- so werden auch Abkuerzungen bei der Suche gefunden.
4. "Docker" und "Podman" werden als verwandte Begriffe (`related`) verknuepft.
5. Wenn nun ein Nutzer nach "K8s" sucht, findet GraphMesh alle Inhalte, die mit "Kubernetes" verknuepft sind.

---

## RDF Data Import

**Problem:** Strukturiertes Wissen existiert oft schon in RDF-Formaten aus anderen Systemen, Linked-Data-Quellen oder manuell gepflegten Wissensbasen. Dieses Wissen soll in GraphMesh nutzbar gemacht werden.

**Loesung:** Der RDF-Import nimmt Daten in Turtle, RDF/XML oder N-Triples entgegen und fuegt sie direkt in den Knowledge Graph ein. Optional koennen Embeddings fuer die importierten Tripel generiert werden, um sie per semantischer Suche auffindbar zu machen.

### Anwendung

**GraphQL API:**

```graphql
mutation {
  importRdf(input: {
    collectionId: "it-landschaft"
    format: TURTLE
    dataset: "cmdb-export"
    generateEmbeddings: true
    content: """
      @prefix ex: <https://example.org/> .

      ex:CRM-System a ex:Anwendung ;
        ex:laeuftAuf ex:Server-EU-01 ;
        ex:version "4.2" ;
        ex:verantwortetDurch ex:Team-Vertrieb .

      ex:Server-EU-01 a ex:Server ;
        ex:standort "Frankfurt" ;
        ex:betriebssystem "RHEL 9" .
    """
  }) {
    tripleCount
    skippedCount
    durationMs
    embeddingsGenerated
  }
}
```

Parameter:
- `collectionId` -- Ziel-Wissenssammlung
- `format` -- `TURTLE`, `RDFXML` oder `NTRIPLES`
- `dataset` -- Optionaler Name fuer die Datenquelle (zur Nachverfolgbarkeit)
- `generateEmbeddings` -- Embeddings fuer semantische Suche erzeugen (Standard: nein)
- `content` -- Die RDF-Daten im angegebenen Format

**Frontend:** Seite `/admin/ontologies` -- Upload-Dialog fuer RDF-Daten.

### Beispiel

> **Szenario:** Ein Unternehmen exportiert seine CMDB (Configuration Management Database) als RDF und moechte sie in GraphMesh integrieren.

1. Der Administrator exportiert die CMDB-Daten im Turtle-Format (ca. 5.000 Tripel).
2. Er importiert die Datei ueber die API mit `dataset: "cmdb-q1-2026"` und `generateEmbeddings: true`.
3. GraphMesh meldet: 4.987 Tripel importiert, 13 uebersprungen (ungueltige URIs), 4.987 Embeddings generiert, Dauer 12.400 ms.
4. Die CMDB-Daten sind sofort im Knowledge Graph verfuegbar und koennen per Graph RAG, Document RAG oder NLP Query abgefragt werden.
5. Durch den Dataset-Namen "cmdb-q1-2026" ist die Herkunft der Daten jederzeit nachvollziehbar.
