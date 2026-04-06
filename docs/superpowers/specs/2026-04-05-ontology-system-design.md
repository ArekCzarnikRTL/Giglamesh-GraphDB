# Feature 20: Ontology System — Design Spec

## Zusammenfassung

OWL-inspiriertes Ontologie-System für GraphMesh. Ermöglicht die Definition von Klassen-Hierarchien, Object Properties, Datatype Properties und Validierungsregeln. Ontologien werden als JSON über den ConfigService persistiert. Import/Export in Turtle und RDF/XML via Apache Jena.

## Entscheidungen

| Entscheidung | Wahl | Begründung |
|---|---|---|
| Turtle/RDF-XML | Apache Jena | Robuste, bewährte RDF-Bibliothek |
| Sync vs. Async | Synchron | Konsistent mit bestehendem ConfigService |
| Config-Events | Spring @EventListener | ConfigService publiziert bereits ConfigChangedEvent |
| Architektur | Eigenes Kotlin-Modell + Jena nur für I/O | Saubere Kotlin-API, Jena isoliert |
| Serialisierung | Jackson (bestehendes Setup) | Kein kotlinx.serialization im Projekt |
| XSD-Typen | Bestehende XsdTypes aus rdf/RdfTerm.kt | Keine Duplikation |

## Paket

`com.agentwork.graphmesh.ontology`

## Datenmodell

```kotlin
data class LangLabel(val value: String, val lang: String = "en")

data class Cardinality(val min: Int? = null, val max: Int? = null, val exact: Int? = null) {
    init {
        require(min == null || max == null || min <= max) {
            "minCardinality ($min) darf nicht größer als maxCardinality ($max) sein"
        }
        require(exact == null || (min == null && max == null)) {
            "exact und min/max können nicht gleichzeitig gesetzt werden"
        }
    }
}

data class OntologyClass(
    val id: String,
    val uri: String,
    val labels: List<LangLabel> = emptyList(),
    val comment: String? = null,
    val subClassOf: List<String> = emptyList(),
    val equivalentClasses: List<String> = emptyList(),
    val disjointWith: List<String> = emptyList()
)

data class ObjectProperty(
    val id: String,
    val uri: String,
    val labels: List<LangLabel> = emptyList(),
    val comment: String? = null,
    val domain: String? = null,
    val range: String? = null,
    val inverseOf: String? = null,
    val functional: Boolean = false,
    val inverseFunctional: Boolean = false
)

data class DatatypeProperty(
    val id: String,
    val uri: String,
    val labels: List<LangLabel> = emptyList(),
    val comment: String? = null,
    val domain: String? = null,
    val range: String = "http://www.w3.org/2001/XMLSchema#string",
    val functional: Boolean = false,
    val cardinality: Cardinality? = null
)

data class OntologyMetadata(
    val name: String,
    val description: String? = null,
    val version: String = "1.0.0",
    val namespace: String,
    val imports: List<String> = emptyList()
)

data class Ontology(
    val metadata: OntologyMetadata,
    val classes: Map<String, OntologyClass> = emptyMap(),
    val objectProperties: Map<String, ObjectProperty> = emptyMap(),
    val datatypeProperties: Map<String, DatatypeProperty> = emptyMap()
) {
    fun getSubClasses(classId: String): List<String> =
        classes.filter { (_, cls) -> classId in cls.subClassOf }.map { it.key }

    fun getClassHierarchy(classId: String): List<String> {
        val hierarchy = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var current = listOf(classId)
        while (current.isNotEmpty()) {
            val next = mutableListOf<String>()
            for (id in current) {
                if (id in visited) continue
                visited.add(id)
                hierarchy.add(id)
                classes[id]?.subClassOf?.let { next.addAll(it) }
            }
            current = next
        }
        return hierarchy
    }
}
```

## Validierung

```kotlin
@Component
class DefaultOntologyValidator {
    fun validate(ontology: Ontology): List<ValidationError>
}

data class ValidationError(
    val element: String,
    val rule: ValidationRule,
    val message: String
)

enum class ValidationRule {
    CIRCULAR_INHERITANCE,
    MISSING_DOMAIN_CLASS,
    MISSING_RANGE_CLASS,
    INVALID_CARDINALITY,
    DISJOINT_SUBCLASS_CONFLICT,
    FUNCTIONAL_CARDINALITY_CONFLICT
}
```

**Regeln:**
1. Zirkuläre Vererbung — BFS über subClassOf-Ketten
2. Domain/Range-Referenzen — Prüft ob Class-IDs existieren
3. Kardinalität — min <= max, exact nicht mit min/max (via init-Block)
4. Disjoint-Subclass — Keine gleichzeitige disjointWith + subClassOf
5. Functional-Cardinality — Functional Property: max <= 1

## Persistenz

```kotlin
@Component
class OntologyStore(
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper
) {
    fun save(key: String, ontology: Ontology): ConfigItem
    fun load(key: String): Ontology?
    fun listKeys(): List<String>
    fun delete(key: String)
}
```

- Serialisiert Ontology zu JSON via Jackson ObjectMapper
- Speichert als ConfigItem mit type=ONTOLOGY
- Nutzt `configService.findByTypeAndKey(ONTOLOGY, key)` für Lookups

## Cache

```kotlin
@Component
class OntologyCache(private val store: OntologyStore) {
    private val cache = ConcurrentHashMap<String, Ontology>()

    fun get(key: String): Ontology?  // cache-hit oder lazy-load aus store

    @EventListener
    fun onConfigChanged(event: ConfigChangedEvent)  // invalidiert bei ONTOLOGY-Events
}
```

Einfache Invalidierung (remove aus Cache), lazy reload beim nächsten Zugriff.

## OntologyService

```kotlin
@Service
class OntologyService(
    private val store: OntologyStore,
    private val cache: OntologyCache,
    private val validator: DefaultOntologyValidator,
    private val jenaAdapter: JenaAdapter
) {
    fun save(key: String, ontology: Ontology): List<ValidationError>
    fun get(key: String): Ontology?
    fun list(): List<String>
    fun delete(key: String)
    fun importTurtle(key: String, content: String): Ontology
    fun exportTurtle(key: String): String
    fun importRdfXml(key: String, content: String): Ontology
    fun exportRdfXml(key: String): String
    fun validate(ontology: Ontology): List<ValidationError>
}
```

- `save()` validiert erst, speichert nur bei 0 Fehlern
- `get()` liest über OntologyCache
- Import: Jena parst → fromJenaModel → save
- Export: load → toJenaModel → Jena serialisiert

## Jena-Adapter

```kotlin
@Component
class JenaAdapter {
    fun toJenaModel(ontology: Ontology): Model
    fun fromJenaModel(model: Model, metadata: OntologyMetadata): Ontology
    fun parseTurtle(content: String): Model
    fun serializeTurtle(model: Model): String
    fun parseRdfXml(content: String): Model
    fun serializeRdfXml(model: Model): String
}
```

**Konvertierung Kotlin → Jena:**
- OntologyClass → owl:Class mit rdfs:subClassOf, rdfs:label, rdfs:comment, owl:disjointWith, owl:equivalentClass
- ObjectProperty → owl:ObjectProperty mit rdfs:domain, rdfs:range, owl:inverseOf, owl:FunctionalProperty
- DatatypeProperty → owl:DatatypeProperty mit rdfs:domain, rdfs:range (XSD-URI), owl:FunctionalProperty
- LangLabel → rdfs:label Literals mit @lang

**Konvertierung Jena → Kotlin:**
- Iteriert über owl:Class, owl:ObjectProperty, owl:DatatypeProperty Resources
- Class-ID aus URI-Fragment oder lokalem Namen
- OntologyMetadata wird separat übergeben

## Gradle-Dependency

```kotlin
implementation("org.apache.jena:apache-jena-libs:5.3.0")
```

## Dateien

| Datei | Beschreibung |
|---|---|
| `ontology/Ontology.kt` | Alle Data Classes: Ontology, OntologyClass, ObjectProperty, DatatypeProperty, OntologyMetadata, LangLabel, Cardinality |
| `ontology/DefaultOntologyValidator.kt` | Validierungslogik + ValidationError + ValidationRule |
| `ontology/OntologyStore.kt` | JSON-Persistenz via ConfigService |
| `ontology/OntologyCache.kt` | In-Memory-Cache mit EventListener |
| `ontology/OntologyService.kt` | Orchestrierung: CRUD, Validierung, Import/Export |
| `ontology/JenaAdapter.kt` | Konvertierung Kotlin ↔ Jena, Turtle/RDF-XML I/O |

## Tests

| Testklasse | Fokus |
|---|---|
| `OntologyTest` | Datenmodell: getSubClasses, getClassHierarchy, Cardinality-init |
| `DefaultOntologyValidatorTest` | Alle 5 Validierungsregeln |
| `OntologyStoreTest` | JSON Round-Trip, ConfigService-Interaktion (MockK) |
| `OntologyCacheTest` | Cache-Hit/Miss, EventListener-Invalidierung |
| `OntologyServiceTest` | Save mit Validierung, CRUD, Import/Export-Delegation (MockK) |
| `JenaAdapterTest` | Kotlin→Jena→Kotlin Round-Trip, Turtle/RDF-XML Round-Trip |
