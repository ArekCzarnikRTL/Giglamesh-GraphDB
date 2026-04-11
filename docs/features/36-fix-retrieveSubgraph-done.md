# Feature 36: Fix `GraphRagService.retrieveSubgraph` — Done

## Zusammenfassung

Der ID-Mismatch zwischen Vector-Payload (`chunk_id`), Provenance-Schema
(`urn:chunk:...`) und Knowledge-Triple-Subjekten
(`http://graphmesh.io/entity/...`) wurde behoben. `GraphRagService.retrieveSubgraph`
folgt jetzt dem Pfad **Vector-Hit → Chunk-URN → Provenance-Subgraph →
Quoted-Triples → Entity-URIs → Knowledge-Edges** wie im Feature-Doc beschrieben.

## Relevante Commits

| Commit | Nachricht |
|---|---|
| `9bbc7ed` | `feat(storage): add findSubgraphsForChunks and findQuotedTriplesForSubgraphs` |
| `7b72300` | `fix(graphrag): rewrite retrieveSubgraph to walk chunk→subgraph→entities` |
| `5a45c17` | `docs(spec): add design for Feature 36 retrieveSubgraph fix` |

## Implementierte Aenderungen

### QuadStore (`src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt`)

Als `interface`-Default-Methoden implementiert (nicht als abstrakte Methoden
wie im urspruenglichen Spec-Vorschlag — weniger Boilerplate, funktioniert fuer
beide Implementierungen `CassandraQuadStore` und `InMemoryQuadStore` gleich):

- `findSubgraphsForChunks(collection, chunkUrns)` — nutzt
  `QuadQuery(predicate = prov:wasDerivedFrom, objectValue = chunkUrn)` um
  Subgraph-URIs zu finden.
- `findQuotedTriplesForSubgraphs(collection, subgraphUris)` — nutzt
  `QuadQuery(subject = subgraphUri, predicate = tg:contains)` und entpackt die
  gefundenen Quoted-Triples via `QuadConverter.unpackQuotedTriple()`.

### GraphRagService (`src/main/kotlin/com/agentwork/graphmesh/query/graphrag/GraphRagService.kt`)

`retrieveSubgraph()` trennt Vector-Hits jetzt in zwei Pfade:

1. **Chunk-Pfad** (Dokument-Ingestion): `chunk_id` → `urn:chunk:...` →
   Subgraphen → Quoted-Triples → 1-Hop-Entity-Expansion
2. **Entity-Pfad** (RDF-Import): direktes `entity_uri` aus Vector-Payload →
   `findByEntities()`

Ergebnisse beider Pfade werden dedupliziert zu `maxEdges` zusammengefasst.

**Zusaetzlich (nach urspruenglichem Feature-36-Scope):** Bei Feature 45
(Query Performance) wurde noch ein Fallback auf vollen QuadStore-Scan ergaenzt,
falls die Vector-Suche gar keine Treffer liefert.

## Abweichungen vom urspruenglichen Plan

- **QuadStore-Methoden sind Interface-Default-Methoden**, nicht abstrakt in
  jeder Implementierung. Das ist sauberer und wurde fuer alle Tests gruen.
- **Keine `CassandraQuadStoreTest`-Erweiterung** — die Default-Methoden werden
  indirekt ueber den bestehenden `query(...)`-Pfad getestet, separate Tests
  waeren nur Duplikate.
- **smoke-test.sh graphRag-Assertion** wurde nicht verschaerft wie im Spec
  vorgeschlagen — stattdessen gibt es den neueren `query-smoke-test.sh` und
  `ontology-smoke-test.sh` die explizit Substantives pruefen.

## Warum Done-Datei erst jetzt

Das Feature wurde vor der Einfuehrung der `NN-feature-name-done.md`-Konvention
implementiert. Die Done-Datei wird nachgetragen, damit die Feature-Uebersicht
konsistent ist.
