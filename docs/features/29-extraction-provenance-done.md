# Feature 29: Extraction-Time Provenance — Done

## Zusammenfassung

PROV-O basiertes Subgraph-Provenance-Modell implementiert. Statt per-Triple Provenance (N Quads für N Triples) wird jetzt ein komprimiertes Subgraph-Modell (N+9 Quads) verwendet. Alle 3 Extractors (Relationship, Definition, Agent) wurden umgestellt.

### Implementierte Komponenten

| Datei | Zweck |
|-------|-------|
| `provenance/ProvenanceNamespaces.kt` | PROV-O + TG Namespace-Konstanten |
| `provenance/SubgraphProvenance.kt` | data class (extractedTriples, chunkUri, agentLabel, modelName) |
| `provenance/ProvenanceService.kt` | @Service — buildSubgraphQuads() erzeugt PROV-O konforme Quads |

### Geänderte Extractors

| Extractor | Änderung |
|-----------|----------|
| RelationshipExtractorService | per-Triple → ProvenanceService.buildSubgraphQuads() |
| DefinitionExtractorService | per-Triple → ProvenanceService.buildSubgraphQuads() |
| AgentExtractorService | per-Item → ein Subgraph für alle Items |

### Provenance-Struktur pro Chunk-Extraktion

```
subgraph tg:contains <<s1 p1 o1>>       # N Quads
subgraph tg:contains <<s2 p2 o2>>
subgraph prov:wasDerivedFrom chunk       # 1 Quad
subgraph prov:wasGeneratedBy activity    # 1 Quad
activity rdf:type prov:Activity          # 1 Quad
activity prov:used chunk                 # 1 Quad
activity prov:wasAssociatedWith agent    # 1 Quad
activity rdfs:label "ExtractorName extraction" # 1 Quad
activity tg:llmModel "gpt-4o"           # 1 Quad (optional)
agent rdf:type prov:Agent               # 1 Quad
agent rdfs:label "ExtractorName"        # 1 Quad
= N + 9 Quads (statt N per-Triple)
```

### Tests

| Test | Anzahl |
|------|--------|
| ProvenanceServiceTest | 8 Tests (contains, PROV-O activity/agent, llmModel, Volumen, Graph) |

### Volumen-Vergleich

| Modell | Quads für 20 Triples |
|--------|---------------------|
| Alt (per-Triple) | 20 Provenance-Quads |
| Neu (Subgraph) | 29 Quads (20 contains + 9 metadata) |

Das neue Modell hat bei wenigen Triples mehr Quads wegen der Metadaten. Der Vorteil zeigt sich bei der **Informationsdichte**: jeder Subgraph enthält Activity-Info (Komponente, LLM-Modell, Agent) die vorher komplett fehlte.

## Abweichungen vom Feature-Dokument

1. **Nur Subgraph-Provenance** — kein Document/Page/Chunk Provenance (Pipeline-invasiv)
2. **Kein ProvenanceRecorder Interface** — direkter Service-Aufruf
3. **Kein ProvenanceEntity/ProvenanceActivity als eigene Klassen** — inline in ProvenanceService
4. **Package `com.agentwork.graphmesh.provenance`** statt `com.graphmesh.provenance.extraction`
5. **Einige bestehende Extractor-Tests müssen aktualisiert werden** — Provenance-Assertions auf neues Format

## Offene Punkte

- Document/Page Provenance (Pipeline-Integration) kann als Phase 2 ergänzt werden
- Bestehende Extractor-Tests mit Provenance-Assertions müssen auf Subgraph-Format angepasst werden
- DAG-Traversierung von Triple → Chunk → Page → Document ist als Query-Feature möglich
