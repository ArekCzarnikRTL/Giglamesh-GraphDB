# Feature 37: Context Cores — Done

## Zusammenfassung

Context Cores sind versionierte, portable Wissens-Bundles (.zip) die den vollstaendigen Stand einer Collection exportieren (Quads, Embeddings, Ontologie, Retrieval-Policies) und in andere Collections importieren koennen.

## Implementierte Komponenten

### Backend (`com.agentwork.graphmesh.contextcore`)
- **CoreManifest.kt** — Data classes: CoreManifest, CoreStats, BuildRequest, ImportRequest, ImportResult, ConflictStrategy, NamespaceRewrite, RetrievalPolicies
- **NQuadsSerializer.kt** — StoredQuad ↔ N-Quads Konvertierung via Apache Jena 6
- **NamespaceRewriter.kt** — URI-Praefix-Umschreibung beim Import
- **BundleWriter.kt** — ZIP-Bundle-Erzeugung mit Manifest, Checksums
- **BundleReader.kt** — ZIP-Bundle-Lesen mit Checksum-Verifikation
- **ContextCoreRegistry.kt** — Cassandra-Persistenz fuer Core-Metadaten
- **ContextCoreService.kt** — Build, Import, List, Delete, Tag, ResolveByTag

### Storage-Erweiterungen
- **QuadStore** — + scrollAll(), isEmpty()
- **VectorStore** — + scroll()
- **CassandraSchemaInitializer** — + context_cores Tabelle

### GraphQL API
- **context-core.graphqls** — ContextCore, ContextCoreStats, ImportResultDto Typen + ConflictStrategy Enum
- **ContextCoreController.kt** — Queries (contextCores, contextCore, contextCoreByTag) + Mutations (build, import, tag, delete)

### Frontend
- **/cores** Seite — Liste aller Cores mit Stats, Tags, Build/Import/Delete Actions
- **BuildCoreDialog** — Formular mit Collection-Auswahl, Core-ID, Version, Tags
- **ImportCoreDialog** — Formular mit Ziel-Collection, Konfliktstrategie, Namespace-Rewrite

### Tests
- NQuadsSerializerTest (5 Tests) — Roundtrip URI/Literal/Language, Empty-Cases
- NamespaceRewriterTest (4 Tests) — Rewrite, Non-Matching, Literal, Null
- BundleWriterReaderTest (2 Tests) — Roundtrip, Checksum-Tampering

## Abweichungen vom Feature-Dokument

1. **ZIP statt tar.gz** — JDK-Bordmittel (ZipOutputStream), keine externe Dependency
2. **Synchrone API** — Kein async REST mit Job-Tracking (Build/Import laufen im Request-Thread)
3. **OntologyService statt OntologyStore** — exportTurtle/importTurtle leben in OntologyService
4. **Spring GraphQL statt Netflix DGS** — @Controller + @QueryMapping/@MutationMapping
5. **jackson-datatype-jsr310** als neue Dependency fuer Instant-Serialisierung im Bundle-Manifest

## Offene Punkte

- Async REST-Endpunkte mit Job-Tracking fehlen (fuer grosse Collections relevant)
- Ontology-Axiom-Count wird aktuell immer als 0 gesetzt (muesste aus Jena-Model gezaehlt werden)
- MERGE-Strategie ueberspringt keine Duplikate (fuegt alle Quads hinzu, da Quad-Hash-Check fehlt)
