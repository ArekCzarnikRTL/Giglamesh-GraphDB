# Feature 07: RDF Graph Model — Done

## Implementierung

### Backend

- **`src/main/kotlin/com/agentwork/graphmesh/rdf/RdfTerm.kt`** — Sealed Class `RdfTerm` mit Varianten `Uri`, `Literal`, `BlankNode`, `QuotedTriple`. `Literal`-Konstruktor erzwingt per `require`, dass `language` nur mit `xsd:string` oder `rdf:langString` kombiniert wird. `toNTriples()` je Variante. Datei enthaelt zusaetzlich die Objekte `XsdTypes`, `RdfTypes` und `SkosTypes` (siehe Feature 46).
- **`src/main/kotlin/com/agentwork/graphmesh/rdf/Quad.kt`** — Enthaelt `Triple`, `Quad` und das `NamedGraph`-Objekt (Konstanten `DEFAULT=""`, `SOURCE="urn:graph:source"`, `RETRIEVAL="urn:graph:retrieval"`, Helper `isStandardGraph`). `Quad.toNQuads()` serialisiert mit oder ohne Graph-Namen.
- **`src/main/kotlin/com/agentwork/graphmesh/rdf/NamespaceRegistry.kt`** — Enthaelt `Namespace`-Datenklasse und `NamespaceRegistry` mit vorregistrierten Prefixes `rdf`, `rdfs`, `xsd`, `owl`, `gm`, `gms`. `expand(prefixedName)` und `compact(uri)` arbeiten ueber die Map. `allNamespaces()` listet alle Registrierungen.
- **`src/main/kotlin/com/agentwork/graphmesh/rdf/EntityIdGenerator.kt`** — Deterministische URI-Generierung via SHA-256 (erste 16 Hex-Zeichen) ueber normalisiertes Label (trim, lowercase, collapse whitespace). Zweite Ueberladung `generate(vararg fields)` mit `|`-Join.
- **`src/main/kotlin/com/agentwork/graphmesh/rdf/QuadConverter.kt`** — `toStoredQuad(quad)` und `fromStoredQuad(stored)`. Blank Nodes werden als URI kodiert (`_:id`). QuotedTriples im Format `<<s|p|o>>`. Zusaetzlich `unpackQuotedTriple(stored)`, das eine `tg:contains <<s|p|o>>`-Provenance-Zeile in ein `StoredQuad`-Tupel zerlegt (dokumentiert als lossy; innerer Objekttyp wird immer als `URI` zurueckgegeben).

### Tests

- **`RdfTermTest`** — Tests fuer alle vier `RdfTerm`-Varianten inkl. `toNTriples()`.
- **`QuadTest`** — `Quad`/`Triple`, Named-Graph-Serialisierung via `toNQuads()`.
- **`NamespaceRegistryTest`** — `expand`/`compact` sowie Standard-Prefixes.
- **`EntityIdGeneratorTest`** — Determinismus, Normalisierung (trim/lowercase/whitespace-collapse), Multi-Field-Variante.
- **`QuadConverterTest`** — Round-Trip RDF-Modell <-> `StoredQuad`, Quoted-Triple-Serialisierung, `unpackQuotedTriple`.

## Abweichungen vom Feature-Dokument

- **Package**: `com.agentwork.graphmesh.rdf` statt `com.graphmesh.rdf`. Kein separates Gradle-Modul.
- **Datei-Layout**: Spec listet 10 einzelne Dateien (`Triple.kt`, `Quad.kt`, `NamedGraph.kt`, `XsdTypes.kt`, `RdfTypes.kt`, `Namespace.kt`, `NamespaceRegistry.kt`, ...). Implementierung fasst zusammen: `Quad.kt` enthaelt `Triple`, `Quad`, `NamedGraph`; `RdfTerm.kt` enthaelt `XsdTypes`, `RdfTypes`, `SkosTypes`; `NamespaceRegistry.kt` enthaelt auch `Namespace`.
- **Kein separater Test `LiteralValidationTest` und `TripleTest`**: Literal-Validation wird innerhalb `RdfTermTest` geprueft, Triple-Serialisierung innerhalb `QuadTest`.
- **`StoredQuad`-Package**: Liegt unter `com.agentwork.graphmesh.storage` (nicht `com.graphmesh.storage.cassandra`).
- **Zusatz `fromStoredQuad` und `unpackQuotedTriple`**: Im Spec nur `toStoredQuad`; Rueckrichtung und Provenance-Unpacking wurden ergaenzt.
- **`SkosTypes`**: Nicht im Spec, aber im gleichen File — siehe Feature 46.

## Akzeptanzkriterien

- [x] `RdfTerm` deckt alle vier Varianten ab — `Uri`, `Literal`, `BlankNode`, `QuotedTriple`.
- [x] `Literal` erzwingt Datentyp-XOR-Sprachtag — `init { require(...) }` in `RdfTerm.Literal`.
- [x] `Quad` unterstuetzt DEFAULT, SOURCE, RETRIEVAL — `NamedGraph`-Objekt.
- [x] `QuotedTriple` ermoeglicht Reification — eigene sealed-Variante plus Round-Trip in `QuadConverter`.
- [x] `toNTriples()`/`toNQuads()` korrekt — je Variante implementiert und getestet.
- [x] `NamespaceRegistry.expand`/`compact` — implementiert und getestet.
- [x] Standard-Prefixes vorregistriert — `rdf`, `rdfs`, `xsd`, `owl`, `gm`, `gms` (siehe `init`-Block).
- [x] `EntityIdGenerator` deterministisch — SHA-256 ueber normalisiertes Label, getestet.
- [x] Normalisierung Input — trim/lowercase/whitespace-collapse, getestet.
- [x] `QuadConverter` — bidirektionale Konvertierung, getestet.
- [x] Quoted Triples serialisiert/deserialisiert fuer Cassandra — `<<s|p|o>>`-Format, Round-Trip-Test.
- [x] Bestehende Funktionalitaet unberuehrt.

## Offene Punkte

- Keine.
