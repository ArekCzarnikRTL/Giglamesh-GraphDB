# Feature 21: Ontology-guided Extractor — Done

## Zusammenfassung

Ontologie-gesteuerter Extractor mit Zwei-Pass-Verfahren implementiert:
1. Pass 1: Entitaeten nach Ontologie-Klassen klassifizieren
2. Pass 2: Typisierte Beziehungen und Attribute extrahieren
3. Validierung gegen Domain/Range-Constraints mit Vererbungshierarchie
4. Quad-Generierung: rdf:type, rdfs:label, Relationship-Triples, Attribute-Triples, Provenance (RDF-Star)

## Implementierte Dateien

| Datei | Beschreibung |
|---|---|
| `extraction/ontology/Models.kt` | ExtractedEntity, ExtractionItem (sealed), ExtractionMode, OntologyExtractionResult, OntologySubset |
| `extraction/ontology/OntologyPromptBuilder.kt` | Schema-Section + Classification/Relationship-Prompt-Generierung |
| `extraction/ontology/OntologyValidationFilter.kt` | Domain/Range-Validierung mit Vererbungshierarchie |
| `extraction/ontology/OntologyGuidedExtractorService.kt` | Zwei-Pass-Extraktion, JSONL-Parsing, Quad-Generierung |
| `extraction/ontology/OntologyGuidedExtractorConsumer.kt` | Kafka Consumer fuer chunk.created Events |

## Abweichungen vom Feature-Dokument

1. **Paket:** `com.agentwork.graphmesh.extraction.ontology` statt `com.graphmesh.extraction.ontology`
2. **LLM-Integration:** Koog PromptExecutor statt ChatCompletionService
3. **JSON-Parsing:** Jackson ObjectMapper statt kotlinx.serialization
4. **OntologyService:** `get()` statt `getOntology()`
5. **QuadStore:** `insertBatch()` + `QuadConverter.toStoredQuad()` statt `saveAll()`
6. **DatatypeProperty.range:** String (XSD-URI) statt Objekt mit `.uri`
7. **Ontologie-Auswahl:** `ontologyKey` aus `Collection.metadata["ontologyKey"]` statt Parameter
8. **Ontologie-Subset:** Ganze Ontologie wird verwendet (Embedding-basierte Selektion als Future Work)
9. **Fallback:** Gibt ExtractionMode.FREE mit 0 Ergebnissen zurueck (kein Aufruf von RelationshipExtractor)
10. **Sync:** `runBlocking { promptExecutor.execute() }` konsistent mit bestehenden Extractors

## Offene Punkte / Technische Schulden

- Embedding-basierte Ontologie-Subset-Selektion nicht implementiert (ganze Ontologie wird verwendet)
- Kein Retry-Mechanismus bei LLM-Ausfaellen
- Model-Name konfigurierbar aber nicht per Collection individuell setzbar
