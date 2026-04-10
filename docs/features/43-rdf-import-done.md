# Feature 43: RDF Data Import — Done

## Implementierung

### Backend

- **`src/main/resources/graphql/rdf-import.graphqls`** — GraphQL-Schema mit `RdfFormat`-Enum (TURTLE, RDFXML, NTRIPLES), `ImportRdfInput`, `ImportRdfResult` und `extend type Mutation { importRdf }`
- **`src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt`** — Service, der RDF-Content per JenaAdapter parst, Statements in `StoredQuad`-Objekte konvertiert und per `QuadStore.insertBatch()` speichert. Blank Nodes werden übersprungen. Enthält auch das `RdfFormat`-Enum.
- **`src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportController.kt`** — Spring GraphQL `@Controller` mit `@MutationMapping` für `importRdf`. Base64-Dekodierung des Content vor Weitergabe an den Service. Enthält auch `ImportRdfInput` und `ImportRdfResultPayload`.
- **`src/main/kotlin/com/agentwork/graphmesh/ontology/JenaAdapter.kt`** — `parseNTriples()` hinzugefügt (analog zu `parseTurtle()`/`parseRdfXml()`)

### Tests

- **`src/test/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportServiceTest.kt`** — 8 Unit-Tests mit InMemoryQuadStore: alle 3 Formate, Blank-Node-Skipping, Dataset-Handling, Literal-Datatype/Language, Duration
- **`src/test/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportControllerTest.kt`** — 2 Unit-Tests mit MockK: Base64-Dekodierung, Dataset-Durchreichung
- **`src/test/kotlin/com/agentwork/graphmesh/ontology/JenaAdapterTest.kt`** — Test für `parseNTriples()` hinzugefügt

### Beispieldaten & Smoke-Test

- **`examples/sample-data.ttl`** — Beispiel-RDF-Datendatei mit Personen, Organisationen, Projekten und Beziehungen
- **`ontology-smoke-test.sh`** — Um RDF-Daten-Import-Abschnitt erweitert (Schritte 8-11: Collection erstellen, importRdf, Triples verifizieren, Cleanup)

## Abweichungen vom Feature-Dokument

- **Spring GraphQL statt DGS**: Feature-Dokument beschreibt `@DgsComponent`/`@DgsMutation`, implementiert als Spring GraphQL `@Controller`/`@MutationMapping` (Projekt-Konvention)
- **Base64-Dekodierung im Controller**: Der Content wird base64-kodiert über GraphQL gesendet und im Controller dekodiert, bevor er an den Service geht. Der Service arbeitet mit Klartext-RDF.
- **`generateEmbeddings` noch nicht aktiv**: Das Feld ist im GraphQL-Schema vorhanden, aber die Kafka-Event-Publikation ist noch nicht implementiert (Embedding-Generierung für importierte Entities als zukünftige Erweiterung)
- **Kein eigenes Package für Input/Format-Typen**: `RdfFormat`, `ImportRdfInput` und `ImportRdfResultPayload` leben direkt in den jeweiligen Service/Controller-Dateien statt in separaten Dateien

## Offene Punkte

- `generateEmbeddings`-Flag wird akzeptiert, aber noch nicht verarbeitet (kein Kafka-Event-Publishing). Kann in einem Follow-up implementiert werden.
- Große RDF-Dateien könnten Cassandra-Batch-Limits überschreiten — für Produktionseinsatz sollte Batching in Chunks erfolgen.
