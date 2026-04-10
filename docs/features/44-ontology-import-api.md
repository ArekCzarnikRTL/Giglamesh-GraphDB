# Feature 44: Ontology Import API

## Problem

`OntologyService.importTurtle()` und `importRdfXml()` sind im Backend implementiert, aber weder
per GraphQL noch per Frontend erreichbar. Ontologien koennen aktuell nur programmatisch
(z.B. in Tests oder direkt per Kafka-Event) importiert werden. Nutzer ohne Codezugriff haben
keine Moeglichkeit, eigene OWL/RDFS-Ontologien hochzuladen und fuer die ontologiegesteuerte
Extraktion (Feature 21) zu aktivieren.

## Ziel

GraphQL-API und Frontend-UI fuer den Ontologie-Import, damit Nutzer Turtle- und RDF/XML-Dateien
direkt hochladen koennen.

1. **GraphQL-Mutationen** — `importOntology`, `deleteOntology`, Query `listOntologies`
2. **DGS DataFetcher** — delegiert an bestehenden `OntologyService`
3. **Frontend-Seite** — Upload-Formular im Admin-UI (`/admin/ontologies`) mit Datei-Picker, Key/Name/Namespace-Eingabe und Ergebnisanzeige

## Voraussetzungen

| Abhaengigkeit                                              | Status        | Blocker? |
|------------------------------------------------------------|---------------|----------|
| Feature 20: Ontology System (OntologyService, OntologyStore) | Implementiert | Ja       |
| Feature 14: GraphQL API (DGS Setup)                        | Implementiert | Ja       |
| Feature 35: Admin UI (Routing, Layout)                     | Implementiert | Nein     |

## Architektur

### GraphQL Schema

```graphql
# src/main/resources/graphql/ontology.graphqls

enum OntologyFormat {
    TURTLE
    RDFXML
}

input ImportOntologyInput {
    key:       String!
    content:   String!        # base64-kodierter Dateiinhalt
    format:    OntologyFormat!
    name:      String!
    namespace: String!
    version:   String = "1.0"
}

type OntologyInfo {
    key:                  String!
    name:                 String!
    namespace:            String!
    version:              String!
    classCount:           Int!
    objectPropertyCount:  Int!
    datatypePropertyCount: Int!
}

# Erweiterungen in type Query / type Mutation:
# type Query    { listOntologies: [OntologyInfo!]! }
# type Mutation { importOntology(input: ImportOntologyInput!): OntologyInfo!
#                 deleteOntology(key: String!): Boolean! }
```

Da GraphQL in Spring DGS type extensions unterstuetzt, kommen Query/Mutation-Erweiterungen
in dieselbe Datei:

```graphql
extend type Query {
    listOntologies: [OntologyInfo!]!
    ontology(key: String!): OntologyInfo
}

extend type Mutation {
    importOntology(input: ImportOntologyInput!): OntologyInfo!
    deleteOntology(key: String!): Boolean!
}
```

### DGS DataFetcher

```kotlin
package com.agentwork.graphmesh.ontology

import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import java.util.Base64

@DgsComponent
class OntologyDataFetcher(
    private val ontologyService: OntologyService,
) {

    @DgsQuery
    fun listOntologies(): List<OntologyInfo> =
        ontologyService.list().mapNotNull { key ->
            ontologyService.get(key)?.toInfo(key)
        }

    @DgsQuery
    fun ontology(@InputArgument key: String): OntologyInfo? =
        ontologyService.get(key)?.toInfo(key)

    @DgsMutation
    fun importOntology(@InputArgument input: ImportOntologyInput): OntologyInfo {
        val content = String(Base64.getDecoder().decode(input.content))
        val metadata = OntologyMetadata(
            name      = input.name,
            namespace = input.namespace,
            version   = input.version,
        )
        val ontology = when (input.format) {
            OntologyFormat.TURTLE -> ontologyService.importTurtle(input.key, content, metadata)
            OntologyFormat.RDFXML -> ontologyService.importRdfXml(input.key, content, metadata)
        }
        return ontology.toInfo(input.key)
    }

    @DgsMutation
    fun deleteOntology(@InputArgument key: String): Boolean {
        ontologyService.delete(key)
        return true
    }

    private fun Ontology.toInfo(key: String) = OntologyInfo(
        key                  = key,
        name                 = metadata.name,
        namespace            = metadata.namespace,
        version              = metadata.version,
        classCount           = classes.size,
        objectPropertyCount  = objectProperties.size,
        datatypePropertyCount = datatypeProperties.size,
    )
}
```

### OntologyInfo DTO

```kotlin
package com.agentwork.graphmesh.ontology

data class OntologyInfo(
    val key: String,
    val name: String,
    val namespace: String,
    val version: String,
    val classCount: Int,
    val objectPropertyCount: Int,
    val datatypePropertyCount: Int,
)
```

### Frontend — Seite `/admin/ontologies`

Upload-Flow analog zum bestehenden Dokument-Upload:
1. Nutzer waehlt `.ttl` oder `.rdf`-Datei via Datei-Picker
2. `FileReader.readAsText()` liest den Inhalt, `btoa()` kodiert ihn als base64
3. GraphQL-Mutation `importOntology` wird abgesetzt
4. Ergebnis (Klassen-/Property-Anzahl) wird als Toast angezeigt

```tsx
// frontend/src/app/admin/ontologies/page.tsx  (vereinfacht)
"use client";
import { useMutation, useQuery } from "@apollo/client";
import { IMPORT_ONTOLOGY, LIST_ONTOLOGIES } from "@/lib/ontology-queries";

export default function OntologiesPage() {
  const { data, refetch } = useQuery(LIST_ONTOLOGIES);
  const [importOntology, { loading }] = useMutation(IMPORT_ONTOLOGY, {
    onCompleted: () => refetch(),
  });

  async function handleFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    const text = await file.text();
    const content = btoa(unescape(encodeURIComponent(text)));
    const format = file.name.endsWith(".rdf") ? "RDFXML" : "TURTLE";
    await importOntology({
      variables: {
        input: {
          key: file.name.replace(/\.[^.]+$/, ""),
          content,
          format,
          name: file.name,
          namespace: "http://example.org/ontology/",
        },
      },
    });
  }

  return (
    <div>
      <h1>Ontologien</h1>
      <input type="file" accept=".ttl,.rdf" onChange={handleFile} disabled={loading} />
      <ul>
        {data?.listOntologies.map((o: OntologyInfo) => (
          <li key={o.key}>
            {o.key} — {o.classCount} Klassen, {o.objectPropertyCount} Object Properties
          </li>
        ))}
      </ul>
    </div>
  );
}
```

```ts
// frontend/src/lib/ontology-queries.ts
import { gql } from "@apollo/client";

export const LIST_ONTOLOGIES = gql`
  query ListOntologies {
    listOntologies { key name namespace version classCount objectPropertyCount datatypePropertyCount }
  }
`;

export const IMPORT_ONTOLOGY = gql`
  mutation ImportOntology($input: ImportOntologyInput!) {
    importOntology(input: $input) { key classCount objectPropertyCount datatypePropertyCount }
  }
`;

export const DELETE_ONTOLOGY = gql`
  mutation DeleteOntology($key: String!) {
    deleteOntology(key: $key)
  }
`;
```

## Betroffene Dateien

### Backend

| Datei                                                                              | Aenderung                               |
|------------------------------------------------------------------------------------|-----------------------------------------|
| `src/main/resources/graphql/ontology.graphqls`                                     | NEU — Schema mit Typen, Query, Mutation |
| `src/main/kotlin/com/agentwork/graphmesh/ontology/OntologyDataFetcher.kt`          | NEU — DGS DataFetcher                   |
| `src/main/kotlin/com/agentwork/graphmesh/ontology/OntologyInfo.kt`                 | NEU — Response DTO                      |
| `src/main/kotlin/com/agentwork/graphmesh/ontology/OntologyService.kt`              | Keine Aenderung noetig                  |

### Frontend

| Datei                                                          | Aenderung                                      |
|----------------------------------------------------------------|------------------------------------------------|
| `frontend/src/app/admin/ontologies/page.tsx`                   | NEU — Upload-Seite                             |
| `frontend/src/lib/ontology-queries.ts`                         | NEU — GraphQL-Operationen                      |
| `frontend/src/app/admin/page.tsx` oder Navigation              | Link zu `/admin/ontologies` hinzufuegen        |

### Tests

| Datei                                                                              | Aenderung                                        |
|------------------------------------------------------------------------------------|--------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/ontology/OntologyDataFetcherTest.kt`      | NEU — Unit-Test mit Mock-OntologyService         |

## Akzeptanzkriterien

- [ ] `importOntology`-Mutation akzeptiert Turtle und RDF/XML (base64-kodiert)
- [ ] `listOntologies` gibt alle gespeicherten Ontologien mit Metadaten zurueck
- [ ] `deleteOntology` entfernt Ontologie aus dem Store
- [ ] Validierungsfehler aus `OntologyService` werden als GraphQL-Fehler propagiert
- [ ] Frontend zeigt Upload-Formular unter `/admin/ontologies`
- [ ] Nach erfolgreichem Import wird die Ontologie-Liste aktualisiert
- [ ] Bestehende Funktionalitaet (`OntologyGuidedExtractorService`, `OntologyService`) bleibt unberuehrt
