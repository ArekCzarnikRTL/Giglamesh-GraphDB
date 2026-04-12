# Feature 37: Context Cores

## Problem

Wissen, das aus Dokumenten und RDF extrahiert wurde -- Ontologien, Entitaeten, Relationen, Embeddings,
Provenance -- existiert in GraphMesh derzeit nur als verteilter Live-Zustand in Cassandra,
Qdrant und MinIO. Es gibt keinen Mechanismus, einen kuratierten Wissensstand als
versionierbares, portables Artefakt zu paketieren, in einem anderen Environment zu
deployen, zu pruefen, einzufrieren oder zurueckzurollen.

In der Praxis fehlen damit drei Dinge:

1. **Reproduzierbarkeit** -- Warum lieferte ein RAG-Lauf in Stage Antwort A, in Prod aber B?
2. **Portabilitaet** -- Eine Domain (z.B. "Pharma-Ontologie + 12k Studien-Triples") laesst
   sich nicht zwischen Projekten/Tenants teilen, ohne die Pipeline neu laufen zu lassen.
3. **Promotion-Workflow** -- Es gibt kein "build / test / pin / promote / rollback" fuer
   Wissen, wie man es von Code-Artefakten kennt.

XGraph bezeichnet das Konzept als *Context Core*: ein versioniertes Bundle aus
Ontologie + Entity-Triples + Embeddings + Evidence + Retrieval-Policies, das wie ein
Build-Artefakt behandelt wird.

## Ziel

Implementierung eines `ContextCoreService`, der den vollstaendigen Wissensstand einer
Collection (oder eines Subgraphen) als signiertes, versioniertes Bundle exportieren,
importieren und verwalten kann.

1. **Export** -- Eine Collection wird in ein Tar-/Zip-Bundle serialisiert: Manifest,
   Ontologie (TTL), Quads (N-Quads), Embeddings (Parquet/JSONL), Provenance, Policies.
2. **Import** -- Ein Bundle wird in eine Ziel-Collection geladen, mit optionaler
   Namespace-Umschreibung und Konfliktstrategie (`fail`, `merge`, `replace`).
3. **Versionierung** -- Bundles tragen `coreId`, `version` (SemVer), `parentVersion`,
   `createdAt`, `checksum` (SHA-256 ueber Manifest).
4. **Registry** -- Cassandra-Tabelle `context_cores` als Index ueber alle bekannten
   Cores; Bundles selbst liegen im Blob-Store (S3/MinIO).
5. **REST/GraphQL** -- Endpunkte zum Auflisten, Bauen, Importieren, Pruefen, Loeschen.
6. **Promotion** -- Tag-basiertes Markieren (`stage`, `prod`); Default-Lookup waehlt den
   `prod`-Tag.

## Voraussetzungen

| Abhaengigkeit                                                    | Status     | Blocker? |
|------------------------------------------------------------------|------------|----------|
| Feature 02: Cassandra Storage Layer (`QuadStore`)                | Vorhanden  | Ja       |
| Feature 03: S3/MinIO Blob Storage                                | Vorhanden  | Ja       |
| Feature 04: Qdrant Vector Store (`VectorStore`)                  | Vorhanden  | Ja       |
| Feature 07: RDF Graph Model (`Quad`, `RdfTerm`, `NamedGraph`)    | Vorhanden  | Ja       |
| Feature 08: Collection Management                                | Vorhanden  | Ja       |
| Feature 14: GraphQL API                                          | Vorhanden  | Ja       |
| Feature 20: Ontology System (Jena `OntologyStore`)               | Vorhanden  | Empfohlen|
| Feature 29: Extraction-Time Provenance                           | Vorhanden  | Empfohlen|
| Apache Jena Riot (N-Quads-Serialisierung)                        | Vorhanden  | Ja       |

## Architektur

### Bundle-Layout

```
core-<coreId>-<version>.tar.gz
├── manifest.json                # CoreManifest
├── ontology/
│   └── ontology.ttl             # Jena-TTL aus OntologyStore
├── graph/
│   ├── default.nq               # NamedGraph.DEFAULT als N-Quads
│   ├── source.nq                # NamedGraph.SOURCE (Provenance)
│   └── ontology.nq              # NamedGraph.ONTOLOGY (falls vorhanden)
├── embeddings/
│   ├── chunks.jsonl             # {id, vector, payload} pro Chunk
│   └── entities.jsonl           # optional: Entity-Embeddings
├── policies/
│   └── retrieval.json           # GraphRAG/DocRAG-Defaults (Top-K, Threshold, Hop-Tiefe)
└── checksums.sha256             # SHA-256 pro Datei
```

### CoreManifest

```kotlin
package com.agentwork.graphmesh.contextcore

import java.time.Instant

/**
 * Manifest eines Context-Core-Bundles. Wird als manifest.json in das Bundle
 * geschrieben und in der Cassandra-Registry gespiegelt.
 */
data class CoreManifest(
    val coreId: String,                 // logischer Name, z.B. "pharma-base"
    val version: String,                // SemVer, z.B. "1.4.0"
    val parentVersion: String? = null,  // Version, von der dieser Core abgeleitet ist
    val sourceCollection: String,       // Collection, aus der exportiert wurde
    val createdAt: Instant,
    val createdBy: String,              // User oder Service-Identitaet
    val description: String? = null,
    val tags: Set<String> = emptySet(), // z.B. "stage", "prod"
    val stats: CoreStats,
    val embeddingModel: String,         // z.B. "text-embedding-3-small"
    val embeddingDimension: Int,
    val checksum: String                // SHA-256 ueber alle Dateien (ausser checksums.sha256)
)

data class CoreStats(
    val quadCount: Long,
    val entityCount: Long,
    val chunkEmbeddingCount: Long,
    val ontologyAxiomCount: Long
)
```

### ContextCoreService

```kotlin
package com.agentwork.graphmesh.contextcore

import com.agentwork.graphmesh.ontology.OntologyStore
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.blob.BlobStore
import com.agentwork.graphmesh.storage.vector.CollectionNaming
import com.agentwork.graphmesh.storage.vector.VectorStore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPOutputStream

/**
 * Verwaltet Context Cores: Bauen, Importieren, Auflisten, Loeschen.
 *
 * Bundles werden in MinIO unter dem Bucket `graphmesh-context-cores`
 * abgelegt; die Registry-Metadaten leben in Cassandra (siehe ContextCoreRegistry).
 */
@Service
class ContextCoreService(
    private val quadStore: QuadStore,
    private val vectorStore: VectorStore,
    private val ontologyStore: OntologyStore,
    private val blobStore: BlobStore,
    private val registry: ContextCoreRegistry
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    /**
     * Exportiert eine Collection in ein Context-Core-Bundle und legt es im Blob-Store ab.
     *
     * Schritte:
     * 1. Quads aller NamedGraphs der Collection auslesen, als N-Quads serialisieren.
     * 2. Ontologie aus OntologyStore als Turtle exportieren.
     * 3. Embeddings aus Qdrant scrollen (Chunk- und ggf. Entity-Embeddings).
     * 4. Retrieval-Policies sammeln (Defaults aus Configuration Service).
     * 5. Manifest mit Stats + Checksum erzeugen.
     * 6. Tar.gz packen, in Blob-Store hochladen, in Registry eintragen.
     */
    fun build(request: BuildRequest): CoreManifest {
        val collection = request.sourceCollection
        logger.info("Building context core: coreId={}, version={}, source={}",
            request.coreId, request.version, collection)

        val quads = quadStore.scrollAll(collection)
        val ontology = ontologyStore.exportTurtle(collection)
        val chunkEmbeddings = scrollEmbeddings(collection, request.embeddingDimension)
        val policies = request.retrievalPolicies

        val bundle = BundleWriter().apply {
            writeManifestPlaceholder()
            writeOntology(ontology)
            writeQuads(quads)
            writeEmbeddings(chunkEmbeddings)
            writePolicies(policies)
        }

        val stats = CoreStats(
            quadCount = quads.size.toLong(),
            entityCount = quads.map { it.subject }.distinct().size.toLong(),
            chunkEmbeddingCount = chunkEmbeddings.size.toLong(),
            ontologyAxiomCount = ontology.axiomCount
        )

        val manifest = CoreManifest(
            coreId = request.coreId,
            version = request.version,
            parentVersion = request.parentVersion,
            sourceCollection = collection,
            createdAt = Instant.now(),
            createdBy = request.createdBy,
            description = request.description,
            tags = request.tags,
            stats = stats,
            embeddingModel = request.embeddingModel,
            embeddingDimension = request.embeddingDimension,
            checksum = bundle.computeChecksum()
        )
        bundle.replaceManifest(manifest)

        val bytes = bundle.toTarGz()
        val blobKey = "cores/${request.coreId}/${request.version}.tar.gz"
        blobStore.put("graphmesh-context-cores", blobKey, bytes)

        registry.register(manifest, blobKey)
        logger.info("Context core built: {} bytes, checksum={}", bytes.size, manifest.checksum)
        return manifest
    }

    /**
     * Importiert ein Bundle aus dem Blob-Store in eine (ggf. neue) Collection.
     *
     * Konfliktstrategien:
     *  - FAIL    : Bricht ab, wenn die Ziel-Collection nicht leer ist.
     *  - MERGE   : Fuegt Quads hinzu, ueberspringt vorhandene (per Quad-Hash).
     *  - REPLACE : Loescht vorhandene Quads/Embeddings vor dem Import.
     */
    fun import(request: ImportRequest): ImportResult {
        val record = registry.find(request.coreId, request.version)
            ?: error("Unknown context core: ${request.coreId}@${request.version}")

        val bytes = blobStore.get("graphmesh-context-cores", record.blobKey)
        val bundle = BundleReader(bytes)

        val manifest = bundle.readManifest()
        require(bundle.verifyChecksum(manifest.checksum)) { "Bundle checksum mismatch" }

        when (request.strategy) {
            ConflictStrategy.FAIL -> require(quadStore.isEmpty(request.targetCollection)) {
                "Target collection ${request.targetCollection} is not empty"
            }
            ConflictStrategy.REPLACE -> {
                quadStore.deleteCollection(request.targetCollection)
                vectorStore.deleteCollection(
                    CollectionNaming.physicalName(request.targetCollection, manifest.embeddingDimension)
                )
            }
            ConflictStrategy.MERGE -> Unit
        }

        val quads = bundle.readQuads().map { rewriteNamespace(it, request.namespaceRewrite) }
        quadStore.insertBatch(request.targetCollection, quads)

        val points = bundle.readEmbeddings()
        vectorStore.upsert(
            CollectionNaming.physicalName(request.targetCollection, manifest.embeddingDimension),
            points
        )

        ontologyStore.importTurtle(request.targetCollection, bundle.readOntology())
        return ImportResult(
            coreId = manifest.coreId,
            version = manifest.version,
            quadsImported = quads.size,
            embeddingsImported = points.size
        )
    }

    fun list(): List<CoreManifest> = registry.listAll()

    fun delete(coreId: String, version: String) {
        val record = registry.find(coreId, version) ?: return
        blobStore.delete("graphmesh-context-cores", record.blobKey)
        registry.unregister(coreId, version)
    }

    fun tag(coreId: String, version: String, tag: String) {
        registry.addTag(coreId, version, tag)
    }

    /** Findet die Version mit dem gegebenen Tag (z.B. "prod"). */
    fun resolveByTag(coreId: String, tag: String): CoreManifest? =
        registry.findByTag(coreId, tag)

    private fun scrollEmbeddings(collection: String, dim: Int) =
        vectorStore.scroll(CollectionNaming.physicalName(collection, dim))

    private fun rewriteNamespace(quad: com.agentwork.graphmesh.rdf.Quad, rewrite: NamespaceRewrite?) =
        if (rewrite == null) quad else NamespaceRewriter.apply(quad, rewrite)
}
```

### Datenklassen

```kotlin
package com.agentwork.graphmesh.contextcore

data class BuildRequest(
    val coreId: String,
    val version: String,
    val sourceCollection: String,
    val embeddingModel: String,
    val embeddingDimension: Int,
    val createdBy: String,
    val description: String? = null,
    val parentVersion: String? = null,
    val tags: Set<String> = emptySet(),
    val retrievalPolicies: RetrievalPolicies = RetrievalPolicies()
)

data class ImportRequest(
    val coreId: String,
    val version: String,
    val targetCollection: String,
    val strategy: ConflictStrategy = ConflictStrategy.FAIL,
    val namespaceRewrite: NamespaceRewrite? = null
)

data class ImportResult(
    val coreId: String,
    val version: String,
    val quadsImported: Int,
    val embeddingsImported: Int
)

enum class ConflictStrategy { FAIL, MERGE, REPLACE }

data class NamespaceRewrite(val from: String, val to: String)

data class RetrievalPolicies(
    val graphRag: GraphRagDefaults = GraphRagDefaults(),
    val docRag: DocRagDefaults = DocRagDefaults()
)
data class GraphRagDefaults(val maxHops: Int = 2, val topK: Int = 20)
data class DocRagDefaults(val topK: Int = 8, val similarityThreshold: Float = 0.65f)
```

### ContextCoreRegistry (Cassandra)

```kotlin
package com.agentwork.graphmesh.contextcore

import com.datastax.oss.driver.api.core.CqlSession
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Cassandra-Tabelle:
 *
 *   CREATE TABLE IF NOT EXISTS graphmesh.context_cores (
 *     core_id        text,
 *     version        text,
 *     parent_version text,
 *     source_collection text,
 *     created_at     timestamp,
 *     created_by     text,
 *     description    text,
 *     tags           set<text>,
 *     embedding_model text,
 *     embedding_dim  int,
 *     stats          text,        -- JSON
 *     checksum       text,
 *     blob_key       text,
 *     PRIMARY KEY (core_id, version)
 *   );
 *
 *   CREATE INDEX IF NOT EXISTS context_cores_tags_idx
 *     ON graphmesh.context_cores (tags);
 */
@Component
class ContextCoreRegistry(private val session: CqlSession) {
    fun register(manifest: CoreManifest, blobKey: String) { /* INSERT */ }
    fun unregister(coreId: String, version: String) { /* DELETE */ }
    fun find(coreId: String, version: String): CoreRecord? = TODO()
    fun findByTag(coreId: String, tag: String): CoreManifest? = TODO()
    fun listAll(): List<CoreManifest> = TODO()
    fun addTag(coreId: String, version: String, tag: String) { /* UPDATE */ }
}

data class CoreRecord(val manifest: CoreManifest, val blobKey: String)
```

### GraphQL-Schema (Erweiterung)

```graphql
type ContextCore {
  coreId: String!
  version: String!
  parentVersion: String
  sourceCollection: String!
  createdAt: String!
  createdBy: String!
  description: String
  tags: [String!]!
  stats: ContextCoreStats!
  embeddingModel: String!
  embeddingDimension: Int!
  checksum: String!
}

type ContextCoreStats {
  quadCount: Int!
  entityCount: Int!
  chunkEmbeddingCount: Int!
  ontologyAxiomCount: Int!
}

extend type Query {
  contextCores: [ContextCore!]!
  contextCore(coreId: String!, version: String!): ContextCore
  contextCoreByTag(coreId: String!, tag: String!): ContextCore
}

extend type Mutation {
  buildContextCore(
    coreId: String!,
    version: String!,
    sourceCollection: String!,
    description: String,
    tags: [String!]
  ): ContextCore!

  importContextCore(
    coreId: String!,
    version: String!,
    targetCollection: String!,
    strategy: ConflictStrategy! = FAIL
  ): ImportResultDto!

  tagContextCore(coreId: String!, version: String!, tag: String!): ContextCore!
  deleteContextCore(coreId: String!, version: String!): Boolean!
}

enum ConflictStrategy { FAIL, MERGE, REPLACE }
```

### REST/Streaming

Die Build- und Import-Operationen koennen lange dauern. Sie werden synchron via GraphQL
angeboten und zusaetzlich als asynchrone REST-Endpunkte mit Job-IDs:

```
POST /api/cores/build      -> { jobId }
POST /api/cores/import     -> { jobId }
GET  /api/cores/jobs/{id}  -> { status, progress, error? }
```

## Betroffene Dateien

### Backend

| Datei                                                                                           | Aenderung                                              |
|-------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/contextcore/ContextCoreService.kt`                     | NEU - Service fuer Build/Import/List/Delete            |
| `src/main/kotlin/com/agentwork/graphmesh/contextcore/ContextCoreRegistry.kt`                    | NEU - Cassandra-Index ueber alle Cores                 |
| `src/main/kotlin/com/agentwork/graphmesh/contextcore/CoreManifest.kt`                           | NEU - Manifest + Stats + Datenklassen                  |
| `src/main/kotlin/com/agentwork/graphmesh/contextcore/BundleWriter.kt`                           | NEU - Tar.gz-Schreiber, SHA-256-Checksums              |
| `src/main/kotlin/com/agentwork/graphmesh/contextcore/BundleReader.kt`                           | NEU - Tar.gz-Leser, Checksum-Verifikation              |
| `src/main/kotlin/com/agentwork/graphmesh/contextcore/NQuadsSerializer.kt`                       | NEU - Wrapper um Jena Riot fuer N-Quads I/O            |
| `src/main/kotlin/com/agentwork/graphmesh/contextcore/NamespaceRewriter.kt`                      | NEU - URI-Praefix-Umschreibung beim Import             |
| `src/main/kotlin/com/agentwork/graphmesh/contextcore/ContextCoreJobService.kt`                  | NEU - Async-Jobs (Build/Import) mit In-Memory-State    |
| `src/main/kotlin/com/agentwork/graphmesh/api/ContextCoreController.kt`                          | NEU - REST-Endpoints (Build/Import/Status)             |
| `src/main/kotlin/com/agentwork/graphmesh/api/graphql/ContextCoreDataFetcher.kt`                 | NEU - DGS-DataFetcher fuer Queries/Mutations           |
| `src/main/resources/schema/context-core.graphqls`                                               | NEU - GraphQL-Typen                                    |
| `src/main/kotlin/com/agentwork/graphmesh/storage/CassandraSchemaInitializer.kt`                 | UPDATE - `context_cores`-Tabelle erzeugen              |
| `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt`                                  | UPDATE - `scrollAll`, `isEmpty`, `deleteCollection`    |
| `src/main/kotlin/com/agentwork/graphmesh/storage/vector/VectorStore.kt`                         | UPDATE - `scroll(collection)`-Methode fuer Export      |
| `src/main/resources/application.yml`                                                            | UPDATE - `graphmesh.context-cores.bucket`              |

### Frontend

| Datei                                                                       | Aenderung                                                  |
|-----------------------------------------------------------------------------|------------------------------------------------------------|
| `frontend/src/app/cores/page.tsx`                                           | NEU - Liste aller Cores, Build-Dialog                      |
| `frontend/src/app/cores/[coreId]/[version]/page.tsx`                        | NEU - Detailseite (Stats, Tags, Import-Aktion)             |
| `frontend/src/components/cores/BuildCoreDialog.tsx`                         | NEU - Formular: coreId, version, sourceCollection, tags    |
| `frontend/src/components/cores/ImportCoreDialog.tsx`                        | NEU - Formular: targetCollection, ConflictStrategy         |
| `frontend/src/lib/graphql/cores.ts`                                         | NEU - Apollo-Queries/-Mutations                            |

### Tests

| Datei                                                                                                  | Aenderung                                              |
|--------------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/contextcore/ContextCoreServiceTest.kt`                        | NEU - Build/Import Roundtrip mit Fakes                 |
| `src/test/kotlin/com/agentwork/graphmesh/contextcore/BundleWriterReaderTest.kt`                        | NEU - Tar.gz Roundtrip + Checksum-Verifikation         |
| `src/test/kotlin/com/agentwork/graphmesh/contextcore/NamespaceRewriterTest.kt`                         | NEU - URI-Rewrite Edge Cases                           |
| `src/test/kotlin/com/agentwork/graphmesh/contextcore/ConflictStrategyTest.kt`                          | NEU - FAIL/MERGE/REPLACE Verhalten                     |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                                          |
|-------------------|-------------|----------------------------------------------------------------|
| Spring Boot (JVM) | Ja          | Cassandra, MinIO, Jena, Tar/Gzip                               |
| KMP Library       | Nein        | Jena ist JVM-only                                              |

## Akzeptanzkriterien

- [ ] `buildContextCore`-Mutation erzeugt ein Tar.gz-Bundle in MinIO und einen Eintrag in `context_cores`.
- [ ] `manifest.json` enthaelt `coreId`, `version`, `checksum`, `stats`, `embeddingModel`, `embeddingDimension`.
- [ ] N-Quads-Dateien sind via Jena Riot lesbar und round-trip-stabil.
- [ ] `embeddings/chunks.jsonl` enthaelt fuer jeden Chunk-Vektor `{id, vector, payload}`.
- [ ] `importContextCore` mit Strategy `FAIL` bricht ab, wenn die Ziel-Collection nicht leer ist.
- [ ] Strategy `REPLACE` loescht Quads + Vector-Collection vor dem Import.
- [ ] Strategy `MERGE` ueberspringt bereits vorhandene Quads (per Quad-Hash).
- [ ] Checksum-Mismatch beim Import fuehrt zu einem klaren Fehler, kein Teil-Import.
- [ ] `namespaceRewrite` wendet das Praefix-Mapping auf alle URIs an (Subjects, Predicates, Objects, Quoted Triples).
- [ ] `tagContextCore` markiert eine Version (z.B. `prod`); `contextCoreByTag` liefert sie zurueck.
- [ ] Async-Build/Import liefert eine `jobId`; `/api/cores/jobs/{id}` zeigt `pending`/`running`/`done`/`failed` plus Progress.
- [ ] Frontend listet alle Cores mit Stats und ermoeglicht Build und Import per Dialog.
- [ ] Roundtrip-Test (Build -> Import in zweite Collection -> identische Quad-Anzahl + identische Embeddings).
