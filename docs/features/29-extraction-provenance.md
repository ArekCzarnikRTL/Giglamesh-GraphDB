# Feature 29: Extraction-Time Provenance

## Problem

Aktuell werden extrahierte Fakten im Knowledge Graph lediglich mit dem Top-Level-Dokument verknuepft (flache `subjectOf`
-Beziehung). Es gibt keine Sichtbarkeit in die Transformationskette -- von welcher Seite, welchem Chunk und durch welche
Extraktionsmethode ein Fakt entstanden ist. Zudem wird Dokument-Metadaten redundant mit jedem Triple-Batch mitgeliefert,
statt einmal zentral gespeichert zu werden. Pro Chunk mit 20 extrahierten Triples entstehen ~260 Provenance-Triples (
per-Triple-Reification), obwohl alle Triples aus einem einzelnen LLM-Aufruf stammen.

## Ziel

Implementierung eines PROV-O-basierten Provenance-Modells mit Subgraph-Kompression, das die vollstaendige
Transformationskette von Dokumenten ueber Seiten und Chunks bis zu extrahierten Triples aufzeichnet.

1. **PROV-O Modell** -- W3C-konformes Provenance-Modell mit Entity, Activity und Agent
2. **DAG-Struktur** -- Vollstaendiger Provenance-DAG: Document -> Pages -> Chunks -> Triples
3. **Subgraph-Kompression** -- Ein Provenance-Record pro Chunk-Extraktion statt pro Triple (10:1 Reduktion)
4. **RDF-Star** -- Verwendung von Quoted Triples (`tg:contains <<s p o>>`) fuer Triple-Referenzierung
5. **Named Graph** -- Speicherung aller Provenance-Daten in `urn:graph:source`
6. **Einmalige Metadaten** -- Dokument-Metadaten werden einmal beim Verarbeitungsstart emittiert

## Voraussetzungen

| Abhaengigkeit                                                                | Status     | Blocker? |
|------------------------------------------------------------------------------|------------|----------|
| Feature 07: RDF Graph Model (Quad, Triple, RdfTerm, NamedGraph)              | Geplant    | Ja       |
| Feature 10: PDF Decoder (extrahiert Seiten aus PDFs)                         | Geplant    | Ja       |
| Feature 11: Document Chunker (teilt Seiten in Chunks)                        | Geplant    | Ja       |
| Feature 12: Relationship Extractor (extrahiert Triples aus Chunks)           | Geplant    | Ja       |
| Feature 09: Document Management / Librarian (Document, parent-child Linking) | Geplant    | Ja       |
| W3C PROV-O Ontologie                                                         | Verfuegbar | Nein     |

## Architektur

### PROV-O Datenmodell

```kotlin
package com.graphmesh.provenance.extraction

import com.graphmesh.rdf.Triple
import com.graphmesh.rdf.RdfTerm
import java.time.Instant

/**
 * PROV-O Activity: Repraesentiert eine Verarbeitungsoperation
 * (z.B. PDF-Extraktion, Chunking, Triple-Extraktion).
 */
data class ProvenanceActivity(
    /** URI der Activity (z.B. "urn:graphmesh:activity:{uuid}"). */
    val uri: String,
    /** Menschenlesbares Label (z.B. "RelationshipExtractor extraction"). */
    val label: String,
    /** URI der Input-Entity (prov:used). */
    val usedEntity: String,
    /** URI des ausfuehrenden Agents (prov:wasAssociatedWith). */
    val agentUri: String,
    /** Startzeitpunkt. */
    val startedAt: Instant = Instant.now(),
    /** Version der Komponente. */
    val componentVersion: String,
    /** LLM-Modell (falls zutreffend). */
    val llmModel: String? = null,
    /** Verwendete Ontologie-URI (falls zutreffend). */
    val ontologyUri: String? = null
)

/**
 * PROV-O Entity: Repraesentiert ein Artefakt im Provenance-DAG
 * (Dokument, Seite, Chunk, Subgraph).
 */
data class ProvenanceEntity(
    /** URI der Entity. */
    val uri: String,
    /** Typ der Entity (z.B. "prov:Entity"). */
    val type: String = "prov:Entity",
    /** Von welcher Entity diese abgeleitet ist (prov:wasDerivedFrom). */
    val derivedFrom: String? = null,
    /** Durch welche Activity diese erzeugt wurde (prov:wasGeneratedBy). */
    val generatedBy: String? = null
)

/**
 * PROV-O Agent: Repraesentiert eine ausfuehrende Komponente
 * (z.B. PDFDecoder, Chunker, RelationshipExtractor).
 */
data class ProvenanceAgent(
    /** URI des Agents (z.B. "urn:graphmesh:agent:RelationshipExtractor"). */
    val uri: String,
    /** Menschenlesbares Label. */
    val label: String
)
```

### SubgraphProvenance (Komprimiertes Modell)

```kotlin
package com.graphmesh.provenance.extraction

import com.graphmesh.rdf.Triple
import com.graphmesh.rdf.Quad

/**
 * Provenance-Record fuer einen Subgraphen extrahierter Triples.
 * Ein Record pro Chunk-Extraktion, statt pro Triple.
 *
 * Zielstruktur in urn:graph:source:
 *
 *   <subgraph> tg:contains <<s1 p1 o1>> .
 *   <subgraph> tg:contains <<s2 p2 o2>> .
 *   <subgraph> prov:wasDerivedFrom <chunk_uri> .
 *   <subgraph> prov:wasGeneratedBy <activity> .
 *   <activity> rdf:type prov:Activity .
 *   <activity> prov:used <chunk_uri> .
 *   <activity> prov:wasAssociatedWith <agent> .
 */
data class SubgraphProvenance(
    /** URI des Subgraphen ("urn:graphmesh:subgraph:{uuid}"). */
    val subgraphUri: String,
    /** Extrahierte Triples, die diesem Subgraph angehoeren. */
    val extractedTriples: List<Triple>,
    /** URI des Quell-Chunks. */
    val chunkUri: String,
    /** Activity-Metadaten. */
    val activity: ProvenanceActivity,
    /** Agent-Metadaten. */
    val agent: ProvenanceAgent
)
```

### ProvenanceService

```kotlin
package com.graphmesh.provenance.extraction

import com.graphmesh.rdf.Quad
import com.graphmesh.rdf.Triple

/**
 * Service fuer die Erzeugung und Speicherung von Extraction-Time-Provenance.
 */
interface ProvenanceService {

    /**
     * Erzeugt Provenance-Quads fuer ein Quelldokument.
     * Wird einmal beim Start der Verarbeitung aufgerufen.
     *
     * @param documentUri URI des Quelldokuments.
     * @param title Dokumenttitel.
     * @param mimeType MIME-Typ.
     * @param pageCount Seitenzahl (fuer PDFs).
     * @return Quads im Named Graph urn:graph:source.
     */
    fun documentProvenance(
        documentUri: String,
        title: String,
        mimeType: String,
        pageCount: Int? = null
    ): List<Quad>

    /**
     * Erzeugt Provenance-Quads fuer eine extrahierte Seite.
     *
     * @param pageUri URI der Seite.
     * @param documentUri URI des Eltern-Dokuments.
     * @param pageNumber Seitennummer.
     * @param componentVersion Version des PDF-Decoders.
     * @return Quads im Named Graph urn:graph:source.
     */
    fun pageProvenance(
        pageUri: String,
        documentUri: String,
        pageNumber: Int,
        componentVersion: String
    ): List<Quad>

    /**
     * Erzeugt Provenance-Quads fuer einen Chunk.
     *
     * @param chunkUri URI des Chunks.
     * @param parentUri URI der Eltern-Entity (Seite oder Dokument).
     * @param chunkIndex Index innerhalb des Eltern-Elements.
     * @param charOffset Zeichenposition im Elterntext.
     * @param charLength Laenge des Chunks in Zeichen.
     * @param componentVersion Version des Chunkers.
     * @return Quads im Named Graph urn:graph:source.
     */
    fun chunkProvenance(
        chunkUri: String,
        parentUri: String,
        chunkIndex: Int,
        charOffset: Int,
        charLength: Int,
        componentVersion: String
    ): List<Quad>

    /**
     * Erzeugt Subgraph-Provenance fuer eine Menge extrahierter Triples.
     * Komprimiertes Modell: 1 Record pro Chunk-Extraktion.
     *
     * @param subgraphProvenance Der Subgraph-Provenance-Record.
     * @return Quads im Named Graph urn:graph:source.
     */
    fun subgraphProvenance(subgraphProvenance: SubgraphProvenance): List<Quad>
}
```

### ProvenanceRecorder (Pipeline-Integration)

```kotlin
package com.graphmesh.provenance.extraction

import com.graphmesh.rdf.Quad

/**
 * Recorder, der in jeden Pipeline-Prozessor eingebunden wird.
 * Sammelt Provenance-Quads und sendet sie an den QuadStore.
 */
interface ProvenanceRecorder {

    /**
     * Zeichnet Provenance-Quads auf und schreibt sie in den Store.
     *
     * @param quads Die zu speichernden Provenance-Quads.
     */
    suspend fun record(quads: List<Quad>)

    /**
     * Erzeugt eine eindeutige URI fuer einen Subgraphen.
     */
    fun generateSubgraphUri(): String

    /**
     * Erzeugt eine eindeutige URI fuer eine Activity.
     */
    fun generateActivityUri(): String
}
```

### Provenance DAG Visualisierung

```
PDF-Dokument (prov:Entity)
  |-- prov:wasDerivedFrom --> (nichts, Root)
  |-- dc:title "Research Paper"
  |-- tg:mimeType "application/pdf"
  |-- tg:pageCount 42
  |
  +-- Seite 1 (prov:Entity)
  |   |-- prov:wasDerivedFrom --> PDF-Dokument
  |   |-- prov:wasGeneratedBy --> Activity:PDFDecode
  |   |-- tg:pageNumber 1
  |   |
  |   +-- Chunk 1 (prov:Entity)
  |   |   |-- prov:wasDerivedFrom --> Seite 1
  |   |   |-- prov:wasGeneratedBy --> Activity:Chunking
  |   |   |-- tg:chunkIndex 0
  |   |   |-- tg:charOffset 0
  |   |   |-- tg:charLength 2048
  |   |   |
  |   |   +-- Subgraph (tg:contains)
  |   |       |-- tg:contains <<John worksAt Acme>>
  |   |       |-- tg:contains <<Acme locatedIn Berlin>>
  |   |       |-- prov:wasDerivedFrom --> Chunk 1
  |   |       |-- prov:wasGeneratedBy --> Activity:Extraction
  |   |
  |   +-- Chunk 2 (prov:Entity)
  |       ...
  +-- Seite 2 (prov:Entity)
      ...
```

### Volumenvergleich

```
Fuer einen Chunk mit N extrahierten Triples:

Alt (per-Triple Reification):
  tg:reifies      = N
  Activity Triples = ~9 x N
  Agent Triples    = 2 x N
  Statement Meta   = 2 x N
  Total            = ~13N    (bei N=20: ~260 Provenance-Triples)

Neu (Subgraph-Modell):
  tg:contains      = N
  Activity Triples = ~9
  Agent Triples    = 2
  Subgraph Meta    = 2
  Total            = N + 13  (bei N=20: 33 Provenance-Triples)
```

## Betroffene Dateien

### Backend

| Datei                                                                                        | Aenderung                                    |
|----------------------------------------------------------------------------------------------|----------------------------------------------|
| `provenance/src/main/kotlin/com/graphmesh/provenance/extraction/ProvenanceActivity.kt`       | PROV-O Activity Datenklasse                  |
| `provenance/src/main/kotlin/com/graphmesh/provenance/extraction/ProvenanceEntity.kt`         | PROV-O Entity Datenklasse                    |
| `provenance/src/main/kotlin/com/graphmesh/provenance/extraction/ProvenanceAgent.kt`          | PROV-O Agent Datenklasse                     |
| `provenance/src/main/kotlin/com/graphmesh/provenance/extraction/SubgraphProvenance.kt`       | Subgraph-Provenance Datenklasse              |
| `provenance/src/main/kotlin/com/graphmesh/provenance/extraction/ProvenanceService.kt`        | Service-Interface                            |
| `provenance/src/main/kotlin/com/graphmesh/provenance/extraction/DefaultProvenanceService.kt` | Implementierung mit Quad-Erzeugung           |
| `provenance/src/main/kotlin/com/graphmesh/provenance/extraction/ProvenanceRecorder.kt`       | Pipeline-Integration                         |
| `provenance/src/main/kotlin/com/graphmesh/provenance/extraction/ProvenanceNamespaces.kt`     | PROV-O und TG Namespace-Konstanten           |
| `librarian/src/main/kotlin/com/graphmesh/librarian/LibrarianService.kt`                      | Emittiert Dokument-Metadaten einmal in Graph |
| `decoder/src/main/kotlin/com/graphmesh/decoder/PdfDecoderService.kt`                         | Erzeugt Seiten-Provenance                    |
| `chunker/src/main/kotlin/com/graphmesh/chunker/ChunkerService.kt`                            | Erzeugt Chunk-Provenance                     |
| `extraction/src/main/kotlin/com/graphmesh/extraction/RelationshipExtractorService.kt`        | Subgraph-Provenance statt per-Triple         |
| `extraction/src/main/kotlin/com/graphmesh/extraction/DefinitionExtractorService.kt`          | Subgraph-Provenance statt per-Triple         |

### Frontend

Nicht direkt betroffen. Provenance-Daten werden ueber GraphQL abgefragt (Feature 30).

### Tests

| Datei                                                                                      | Aenderung                         |
|--------------------------------------------------------------------------------------------|-----------------------------------|
| `provenance/src/test/kotlin/com/graphmesh/provenance/extraction/ProvenanceServiceTest.kt`  | Tests fuer Quad-Erzeugung         |
| `provenance/src/test/kotlin/com/graphmesh/provenance/extraction/SubgraphProvenanceTest.kt` | Tests fuer Kompressionsmodell     |
| `provenance/src/test/kotlin/com/graphmesh/provenance/extraction/ProvenanceRecorderTest.kt` | Integration-Tests Pipeline        |
| `provenance/src/test/kotlin/com/graphmesh/provenance/extraction/VolumeComparisonTest.kt`   | Verifikation der Volumenreduktion |

## Platform-Einschraenkungen

| Plattform         | Unterstuetzt | Anmerkung                           |
|-------------------|--------------|-------------------------------------|
| Spring Boot (JVM) | Ja           | Primaere Zielplattform              |
| KMP Library       | Nein         | Abhaengig von Cassandra-QuadStore   |
| Ktor/Wasm         | Nein         | Server-seitige Pipeline-Integration |

## Akzeptanzkriterien

- [ ] Dokument-Metadaten werden genau einmal in `urn:graph:source` emittiert (nicht pro Triple-Batch)
- [ ] PDF-Seiten werden als prov:Entity mit `prov:wasDerivedFrom` zum Eltern-Dokument gespeichert
- [ ] Chunks werden als prov:Entity mit `prov:wasDerivedFrom` zur Eltern-Seite gespeichert
- [ ] Pro Chunk-Extraktion wird genau ein Subgraph-Provenance-Record erzeugt
- [ ] Jeder extrahierte Triple wird ueber `tg:contains` mit Quoted Triple referenziert
- [ ] Activity-Metadaten (Komponente, Version, LLM-Modell) werden pro Subgraph einmal gespeichert
- [ ] Agent-Metadaten (Komponentenname) sind stabil ueber Ausfuehrungen
- [ ] Provenance-Volumen fuer N Triples liegt bei N+13 statt ~13N
- [ ] Der vollstaendige DAG ist von jedem Triple zurueck zum Quelldokument traversierbar
- [ ] Alle Provenance-Quads verwenden den Named Graph `urn:graph:source`
