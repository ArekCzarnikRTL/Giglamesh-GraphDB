# Feature 37: Context Cores — Design Spec

## Zusammenfassung

Context Cores sind versionierte, portable Wissens-Bundles (.zip) die den vollstaendigen Stand einer Collection exportieren. Sie ermoeglichen Reproduzierbarkeit, Portabilitaet und Promotion-Workflows (stage/prod) fuer Wissen.

## Scope

- Backend: Service, Registry, BundleWriter/Reader, NQuads-Serialisierung, Namespace-Rewrite
- GraphQL API: Queries + Mutations
- Frontend: Core-Liste, Detail, Build-Dialog, Import-Dialog
- Tests: Unit-Tests fuer alle Kernkomponenten

Ausserhalb des Scope: Async REST-Endpunkte mit Job-Tracking.

## Bundle-Format

```
core-<coreId>-<version>.zip
├── manifest.json
├── ontology/
│   └── ontology.ttl
├── graph/
│   └── default.nq
├── embeddings/
│   └── chunks.jsonl
├── policies/
│   └── retrieval.json
└── checksums.sha256
```

Format: .zip (JDK ZipOutputStream/ZipInputStream, keine externe Dependency).

## Architektur

```
Collection (Cassandra + Qdrant + OntologyService)
        │
        ▼  build()
┌─────────────────────┐
│ ContextCoreService  │──── BundleWriter (ZipOutputStream) ──→ .zip in MinIO
│                     │──── ContextCoreRegistry (Cassandra)
│                     │◄─── BundleReader (ZipInputStream) ◄── .zip aus MinIO
└─────────────────────┘
        │  import()
        ▼
Target Collection (Cassandra + Qdrant + OntologyService)
```

## Komponenten

### Neues Package: `com.agentwork.graphmesh.contextcore`

| Datei | Verantwortung |
|---|---|
| `CoreManifest.kt` | Data classes: CoreManifest, CoreStats, BuildRequest, ImportRequest, ImportResult, ConflictStrategy, NamespaceRewrite, RetrievalPolicies |
| `ContextCoreService.kt` | Build, Import, List, Delete, Tag, ResolveByTag |
| `ContextCoreRegistry.kt` | Cassandra CRUD fuer `context_cores` Tabelle |
| `BundleWriter.kt` | ZipOutputStream: schreibt alle Bundle-Bestandteile + SHA-256 |
| `BundleReader.kt` | ZipInputStream: liest Bundle + Checksum-Verifikation |
| `NQuadsSerializer.kt` | StoredQuad ↔ N-Quads String Konvertierung via Jena |
| `NamespaceRewriter.kt` | URI-Praefix-Umschreibung auf StoredQuad-Feldern |

### Aenderungen an bestehenden Dateien

| Datei | Aenderung |
|---|---|
| `storage/QuadStore.kt` | + `scrollAll(collection): List<StoredQuad>` + `isEmpty(collection): Boolean` |
| `storage/CassandraQuadStore.kt` | Implementierung der neuen Methoden |
| `storage/vector/VectorStore.kt` | + `scroll(collection): List<VectorPoint>` |
| `storage/vector/QdrantVectorStore.kt` | Implementierung von scroll() |
| `storage/CassandraSchemaInitializer.kt` | + `context_cores` Tabelle anlegen |

### GraphQL

Neue Datei: `src/main/resources/graphql/context-core.graphqls`

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

type ImportResultDto {
  coreId: String!
  version: String!
  quadsImported: Int!
  embeddingsImported: Int!
}

enum ConflictStrategy { FAIL, MERGE, REPLACE }

extend type Query {
  contextCores: [ContextCore!]!
  contextCore(coreId: String!, version: String!): ContextCore
  contextCoreByTag(coreId: String!, tag: String!): ContextCore
}

extend type Mutation {
  buildContextCore(
    coreId: String!
    version: String!
    sourceCollection: String!
    description: String
    tags: [String!]
    embeddingModel: String
    embeddingDimension: Int
  ): ContextCore!

  importContextCore(
    coreId: String!
    version: String!
    targetCollection: String!
    strategy: ConflictStrategy! = FAIL
    namespaceFrom: String
    namespaceTo: String
  ): ImportResultDto!

  tagContextCore(coreId: String!, version: String!, tag: String!): ContextCore!
  deleteContextCore(coreId: String!, version: String!): Boolean!
}
```

Controller: `api/ContextCoreController.kt` mit Spring GraphQL Pattern (@Controller, @QueryMapping, @MutationMapping).

### Frontend

| Datei | Inhalt |
|---|---|
| `frontend/src/app/cores/page.tsx` | Liste aller Cores mit Stats, Tags, Build-Button |
| `frontend/src/app/cores/[coreId]/[version]/page.tsx` | Detailseite mit Import-Aktion |
| `frontend/src/components/cores/BuildCoreDialog.tsx` | Formular: coreId, version, sourceCollection, tags |
| `frontend/src/components/cores/ImportCoreDialog.tsx` | Formular: targetCollection, ConflictStrategy |
| `frontend/src/lib/graphql/cores.ts` | Apollo Queries + Mutations |

### Tests

| Datei | Inhalt |
|---|---|
| `ContextCoreServiceTest.kt` | Build + Import Roundtrip mit Fakes/Mocks |
| `BundleWriterReaderTest.kt` | Zip Roundtrip + Checksum-Verifikation |
| `NQuadsSerializerTest.kt` | StoredQuad ↔ N-Quads Konvertierung |
| `NamespaceRewriterTest.kt` | URI-Rewrite Edge Cases |

## Datenfluss

### Build

1. `QuadStore.scrollAll(collection)` → alle StoredQuads
2. `NQuadsSerializer.serialize(quads)` → N-Quads String → `graph/default.nq`
3. `OntologyService.exportTurtle(ontologyKey)` → Turtle String → `ontology/ontology.ttl`
4. `VectorStore.scroll(physicalCollection)` → alle VectorPoints → JSONL → `embeddings/chunks.jsonl`
5. `RetrievalPolicies` → JSON → `policies/retrieval.json`
6. SHA-256 ueber alle Dateien → `checksums.sha256`
7. CoreManifest mit Stats + Gesamtchecksum → `manifest.json`
8. `BundleWriter` packt alles in .zip
9. `BlobStore.put("graphmesh-context-cores", "cores/{coreId}/{version}.zip", bytes)`
10. `ContextCoreRegistry.register(manifest, blobKey)`

### Import

1. `ContextCoreRegistry.find(coreId, version)` → CoreRecord mit blobKey
2. `BlobStore.get("graphmesh-context-cores", blobKey)` → BlobData
3. `BundleReader` entpackt .zip, verifiziert Checksum
4. ConflictStrategy anwenden:
   - FAIL: `QuadStore.isEmpty(target)` muss true sein
   - REPLACE: `QuadStore.deleteCollection(target)` + `VectorStore.deleteCollection(physicalTarget)`
   - MERGE: kein Vorab-Loeschen
5. N-Quads lesen → `NQuadsSerializer.deserialize()` → optional `NamespaceRewriter.apply()` → `QuadStore.insertBatch(target, quads)`
6. Embeddings JSONL lesen → `VectorStore.upsert(physicalTarget, points)`
7. Ontologie TTL lesen → `OntologyService.importTurtle()`

## Cassandra Schema

```cql
CREATE TABLE IF NOT EXISTS graphmesh.context_cores (
  core_id          text,
  version          text,
  parent_version   text,
  source_collection text,
  created_at       timestamp,
  created_by       text,
  description      text,
  tags             set<text>,
  embedding_model  text,
  embedding_dim    int,
  quad_count       bigint,
  entity_count     bigint,
  chunk_embedding_count bigint,
  ontology_axiom_count bigint,
  checksum         text,
  blob_key         text,
  PRIMARY KEY (core_id, version)
) WITH CLUSTERING ORDER BY (version DESC);

CREATE INDEX IF NOT EXISTS context_cores_tags_idx
  ON graphmesh.context_cores (tags);
```

## Technische Entscheidungen

1. **Zip statt tar.gz** — JDK-Bordmittel, keine externe Dependency
2. **Synchrone API** — Kein async Job-Tracking; Build/Import laufen im Request-Thread (fuer kleine/mittlere Collections ausreichend)
3. **OntologyService statt OntologyStore** — exportTurtle/importTurtle leben in OntologyService, nicht im Store-Interface
4. **StoredQuad** — Der tatsaechliche Typ aus QuadStore, nicht das RDF-Model Quad
5. **Spring GraphQL** — @Controller + @QueryMapping/@MutationMapping (nicht Netflix DGS)
6. **BlobStore.get() → BlobData** — muss .bytes oder .inputStream daraus extrahieren
7. **Embedding-Dimension aus Config** — BuildRequest nimmt embeddingModel + embeddingDimension; Default aus application.yml

## Akzeptanzkriterien

- buildContextCore erzeugt ein .zip-Bundle in MinIO und einen Eintrag in context_cores
- manifest.json enthaelt coreId, version, checksum, stats, embeddingModel, embeddingDimension
- N-Quads-Dateien sind round-trip-stabil (serialize → deserialize → identische StoredQuads)
- importContextCore mit FAIL bricht ab wenn Ziel-Collection nicht leer
- REPLACE loescht Quads + Vectors vor Import
- MERGE fuegt hinzu ohne vorhandene zu loeschen
- Checksum-Mismatch beim Import → klarer Fehler, kein Teil-Import
- namespaceRewrite wendet Praefix-Mapping auf alle URIs an
- tagContextCore markiert Version; contextCoreByTag liefert sie zurueck
- Frontend zeigt Core-Liste, Build-Dialog und Import-Dialog
- Unit-Tests fuer BundleWriter/Reader Roundtrip, NQuads-Serialisierung, NamespaceRewriter
