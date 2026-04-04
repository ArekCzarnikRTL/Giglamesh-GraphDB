# Feature 20: Ontology System

## Problem

Ohne ein formales Ontologie-Modell gibt es keine Moeglichkeit, die Struktur des Knowledge Graphs zu definieren und zu
validieren. Extrahierte Triples koennen beliebige Praedikate und Typen enthalten, was zu inkonsistenten und schwer
abfragbaren Graphen fuehrt. Ein OWL-inspiriertes Ontologie-System ermoeglicht die Definition von Klassen, Properties und
deren Constraints, wodurch nachgelagerte Extraktoren (Feature 21) gezielt und validiert arbeiten koennen.

## Ziel

Implementierung eines OWL-inspirierten Ontologie-Systems, das Klassen-Hierarchien, Object Properties, Datatype
Properties und Validierungsregeln verwaltet. Ontologien werden als Config-Items ueber den ConfigService (Feature 06)
gespeichert und koennen in Standard-Formaten importiert/exportiert werden.

1. **Ontologie-Datenmodell** -- OWL-inspirierte Klassen mit Vererbung, Properties mit Domain/Range, Kardinalitaeten
2. **Persistenz via ConfigService** -- Ontologien als Config-Items vom Typ `ontology` gespeichert (Feature 06)
3. **Validierung** -- Zirkulaere Vererbung erkennen, Domain/Range pruefen, Kardinalitaets-Constraints validieren
4. **Import/Export** -- Unterstuetzung fuer Turtle (.ttl) und RDF/XML (.rdf) Formate
5. **Multi-Language Labels** -- BCP-47-konforme Sprach-Tags fuer Labels und Kommentare
6. **Cassandra-Abfrage** -- Ontologie-Elemente ueber CassandraClient (Feature 02) abfragbar

## Voraussetzungen

| Abhaengigkeit                                                    | Status     | Blocker? |
|------------------------------------------------------------------|------------|----------|
| Feature 02: Cassandra Storage Layer (CassandraClient)            | Geplant    | Ja       |
| Feature 06: Configuration Service (ConfigService, ConfigHandler) | Geplant    | Ja       |
| Feature 07: RDF Graph Model (RdfTerm, Uri, Literal)              | Geplant    | Ja       |
| Spring Boot 3.x                                                  | Verfuegbar | Nein     |

## Architektur

### Datenmodell

```kotlin
package com.graphmesh.ontology

import kotlinx.serialization.Serializable

/**
 * Sprach-getaggtes Label nach BCP-47.
 */
@Serializable
data class LangLabel(
    val value: String,
    val lang: String = "en"
)

/**
 * Kardinalitaets-Constraint fuer Properties.
 */
@Serializable
data class Cardinality(
    val min: Int? = null,
    val max: Int? = null,
    val exact: Int? = null
) {
    init {
        require(min == null || max == null || min <= max) {
            "minCardinality ($min) darf nicht groesser als maxCardinality ($max) sein"
        }
        require(exact == null || (min == null && max == null)) {
            "exact und min/max koennen nicht gleichzeitig gesetzt werden"
        }
    }
}

/**
 * OWL-inspirierte Ontologie-Klasse.
 */
@Serializable
data class OntologyClass(
    val id: String,
    val uri: String,
    val labels: List<LangLabel> = emptyList(),
    val comment: String? = null,
    val subClassOf: List<String> = emptyList(),
    val equivalentClasses: List<String> = emptyList(),
    val disjointWith: List<String> = emptyList(),
    val identifier: String? = null
)

/**
 * Object Property: Beziehung zwischen zwei Instanzen.
 */
@Serializable
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

/**
 * Datatype Property: Attribut mit XSD-Typ.
 */
@Serializable
data class DatatypeProperty(
    val id: String,
    val uri: String,
    val labels: List<LangLabel> = emptyList(),
    val comment: String? = null,
    val domain: String? = null,
    val range: XsdType = XsdType.STRING,
    val functional: Boolean = false,
    val cardinality: Cardinality? = null
)

/**
 * Unterstuetzte XSD-Datentypen fuer Datatype Properties.
 */
enum class XsdType(val uri: String) {
    STRING("xsd:string"),
    INTEGER("xsd:integer"),
    NON_NEGATIVE_INTEGER("xsd:nonNegativeInteger"),
    FLOAT("xsd:float"),
    DOUBLE("xsd:double"),
    BOOLEAN("xsd:boolean"),
    DATE_TIME("xsd:dateTime"),
    DATE("xsd:date"),
    ANY_URI("xsd:anyURI");

    companion object {
        fun fromUri(uri: String): XsdType =
            entries.firstOrNull { it.uri == uri }
                ?: throw IllegalArgumentException("Unbekannter XSD-Typ: $uri")
    }
}

/**
 * Ontologie-Metadaten.
 */
@Serializable
data class OntologyMetadata(
    val name: String,
    val description: String? = null,
    val version: String = "1.0.0",
    val created: String? = null,
    val modified: String? = null,
    val creator: String? = null,
    val namespace: String,
    val imports: List<String> = emptyList()
)

/**
 * Vollstaendige Ontologie mit allen Elementen.
 */
@Serializable
data class Ontology(
    val metadata: OntologyMetadata,
    val classes: Map<String, OntologyClass> = emptyMap(),
    val objectProperties: Map<String, ObjectProperty> = emptyMap(),
    val datatypeProperties: Map<String, DatatypeProperty> = emptyMap()
) {
    /**
     * Gibt alle Klassen-IDs zurueck, die von der gegebenen Klasse erben.
     */
    fun getSubClasses(classId: String): List<String> =
        classes.filter { (_, cls) -> classId in cls.subClassOf }.map { it.key }

    /**
     * Gibt die vollstaendige Vererbungshierarchie einer Klasse zurueck (aufsteigend).
     */
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

### OntologyValidator

```kotlin
package com.graphmesh.ontology

/**
 * Validiert Ontologien auf strukturelle und semantische Korrektheit.
 */
interface OntologyValidator {

    /**
     * Fuehrt alle Validierungen durch und gibt Fehler zurueck.
     */
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
    DUPLICATE_IDENTIFIER,
    DISJOINT_SUBCLASS_CONFLICT,
    FUNCTIONAL_CARDINALITY_CONFLICT,
    INVALID_LANGUAGE_TAG,
    MISSING_URI,
    INVALID_XSD_TYPE
}

class DefaultOntologyValidator : OntologyValidator {

    override fun validate(ontology: Ontology): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        errors.addAll(checkCircularInheritance(ontology))
        errors.addAll(checkDomainRangeReferences(ontology))
        errors.addAll(checkCardinalityConstraints(ontology))
        errors.addAll(checkDisjointSubclassConflicts(ontology))
        errors.addAll(checkFunctionalCardinality(ontology))
        return errors
    }

    /**
     * Erkennt zirkulaere Vererbungsketten in der Klassenhierarchie.
     */
    private fun checkCircularInheritance(ontology: Ontology): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        for ((classId, cls) in ontology.classes) {
            val visited = mutableSetOf<String>()
            var current = cls.subClassOf
            while (current.isNotEmpty()) {
                val next = mutableListOf<String>()
                for (parentId in current) {
                    if (parentId == classId) {
                        errors.add(
                            ValidationError(
                                element = classId,
                                rule = ValidationRule.CIRCULAR_INHERITANCE,
                                message = "Zirkulaere Vererbung erkannt: $classId -> ... -> $classId"
                            )
                        )
                        break
                    }
                    if (parentId !in visited) {
                        visited.add(parentId)
                        ontology.classes[parentId]?.subClassOf?.let { next.addAll(it) }
                    }
                }
                current = next
            }
        }
        return errors
    }

    /**
     * Prueft, ob Domain/Range-Referenzen auf existierende Klassen zeigen.
     */
    private fun checkDomainRangeReferences(ontology: Ontology): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val classIds = ontology.classes.keys

        for ((propId, prop) in ontology.objectProperties) {
            prop.domain?.let { domain ->
                if (domain !in classIds) {
                    errors.add(ValidationError(propId, ValidationRule.MISSING_DOMAIN_CLASS,
                        "Domain-Klasse '$domain' existiert nicht"))
                }
            }
            prop.range?.let { range ->
                if (range !in classIds) {
                    errors.add(ValidationError(propId, ValidationRule.MISSING_RANGE_CLASS,
                        "Range-Klasse '$range' existiert nicht"))
                }
            }
        }

        for ((propId, prop) in ontology.datatypeProperties) {
            prop.domain?.let { domain ->
                if (domain !in classIds) {
                    errors.add(ValidationError(propId, ValidationRule.MISSING_DOMAIN_CLASS,
                        "Domain-Klasse '$domain' existiert nicht"))
                }
            }
        }

        return errors
    }

    private fun checkCardinalityConstraints(ontology: Ontology): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        for ((propId, prop) in ontology.datatypeProperties) {
            prop.cardinality?.let { card ->
                if (card.min != null && card.max != null && card.min > card.max) {
                    errors.add(ValidationError(propId, ValidationRule.INVALID_CARDINALITY,
                        "minCardinality (${card.min}) > maxCardinality (${card.max})"))
                }
            }
        }
        return errors
    }

    private fun checkDisjointSubclassConflicts(ontology: Ontology): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        for ((classId, cls) in ontology.classes) {
            for (disjoint in cls.disjointWith) {
                if (disjoint in cls.subClassOf) {
                    errors.add(ValidationError(classId, ValidationRule.DISJOINT_SUBCLASS_CONFLICT,
                        "Klasse '$classId' ist disjunkt mit '$disjoint' und gleichzeitig Subklasse"))
                }
            }
        }
        return errors
    }

    private fun checkFunctionalCardinality(ontology: Ontology): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        for ((propId, prop) in ontology.datatypeProperties) {
            if (prop.functional && prop.cardinality?.max != null && prop.cardinality.max > 1) {
                errors.add(ValidationError(propId, ValidationRule.FUNCTIONAL_CARDINALITY_CONFLICT,
                    "Functional Property '$propId' darf maxCardinality > 1 nicht haben"))
            }
        }
        return errors
    }
}
```

### OntologyStore

```kotlin
package com.graphmesh.ontology

/**
 * Persistenz-Schicht fuer Ontologien.
 * Nutzt den ConfigService (Feature 06) fuer die Speicherung als Config-Items.
 */
interface OntologyStore {

    /**
     * Speichert oder aktualisiert eine Ontologie.
     */
    suspend fun save(key: String, ontology: Ontology)

    /**
     * Laedt eine Ontologie anhand ihres Keys.
     */
    suspend fun load(key: String): Ontology?

    /**
     * Listet alle verfuegbaren Ontologie-Keys.
     */
    suspend fun listKeys(): List<String>

    /**
     * Loescht eine Ontologie.
     */
    suspend fun delete(key: String)
}
```

### OntologyService

```kotlin
package com.graphmesh.ontology

/**
 * Hauptservice fuer Ontologie-Operationen.
 * Koordiniert Laden, Speichern, Validieren und Import/Export.
 */
interface OntologyService {

    /**
     * Speichert eine Ontologie nach Validierung.
     * Wirft eine Exception, wenn die Validierung fehlschlaegt.
     */
    suspend fun saveOntology(key: String, ontology: Ontology): List<ValidationError>

    /**
     * Laedt eine Ontologie anhand ihres Keys.
     */
    suspend fun getOntology(key: String): Ontology?

    /**
     * Listet alle verfuegbaren Ontologie-Keys.
     */
    suspend fun listOntologies(): List<String>

    /**
     * Loescht eine Ontologie.
     */
    suspend fun deleteOntology(key: String)

    /**
     * Importiert eine Ontologie aus Turtle-Format.
     */
    suspend fun importTurtle(key: String, turtleContent: String): Ontology

    /**
     * Exportiert eine Ontologie im Turtle-Format.
     */
    suspend fun exportTurtle(key: String): String

    /**
     * Importiert eine Ontologie aus RDF/XML-Format.
     */
    suspend fun importRdfXml(key: String, rdfXmlContent: String): Ontology

    /**
     * Exportiert eine Ontologie im RDF/XML-Format.
     */
    suspend fun exportRdfXml(key: String): String

    /**
     * Validiert eine Ontologie und gibt alle Fehler zurueck.
     */
    fun validate(ontology: Ontology): List<ValidationError>
}
```

### ConfigHandler-Integration

```kotlin
package com.graphmesh.ontology

import com.graphmesh.config.ConfigHandler
import com.graphmesh.config.ConfigItem

/**
 * ConfigHandler fuer Ontologie-Items.
 * Reagiert auf Config-Aenderungen vom Typ "ontology" und aktualisiert
 * den In-Memory-Cache der geladenen Ontologien.
 */
class OntologyConfigHandler(
    private val ontologyCache: MutableMap<String, Ontology>
) : ConfigHandler {

    override val configType: String = "ontology"

    override fun onConfigUpdated(item: ConfigItem) {
        val ontology = OntologyJsonParser.parse(item.value)
        ontologyCache[item.key] = ontology
    }

    override fun onConfigDeleted(key: String) {
        ontologyCache.remove(key)
    }
}
```

## Betroffene Dateien

### Backend

| Datei                                                                      | Aenderung                                                                           |
|----------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| `ontology/src/main/kotlin/com/graphmesh/ontology/Ontology.kt`              | NEU - Haupt-Datenmodell (Ontology, OntologyClass, ObjectProperty, DatatypeProperty) |
| `ontology/src/main/kotlin/com/graphmesh/ontology/OntologyMetadata.kt`      | NEU - Metadaten-Datenklasse                                                         |
| `ontology/src/main/kotlin/com/graphmesh/ontology/LangLabel.kt`             | NEU - Sprach-getaggtes Label                                                        |
| `ontology/src/main/kotlin/com/graphmesh/ontology/Cardinality.kt`           | NEU - Kardinalitaets-Constraints                                                    |
| `ontology/src/main/kotlin/com/graphmesh/ontology/XsdType.kt`               | NEU - Unterstuetzte XSD-Datentypen (Enum)                                           |
| `ontology/src/main/kotlin/com/graphmesh/ontology/OntologyService.kt`       | NEU - Haupt-Service-Interface                                                       |
| `ontology/src/main/kotlin/com/graphmesh/ontology/OntologyStore.kt`         | NEU - Persistenz-Interface via ConfigService                                        |
| `ontology/src/main/kotlin/com/graphmesh/ontology/OntologyValidator.kt`     | NEU - Validierungs-Interface und Default-Implementierung                            |
| `ontology/src/main/kotlin/com/graphmesh/ontology/OntologyConfigHandler.kt` | NEU - ConfigHandler fuer Event-basierte Updates                                     |
| `ontology/src/main/kotlin/com/graphmesh/ontology/OntologyJsonParser.kt`    | NEU - JSON-Parsing der Ontologie-Struktur                                           |
| `ontology/src/main/kotlin/com/graphmesh/ontology/TurtleExporter.kt`        | NEU - Export im Turtle-Format                                                       |
| `ontology/src/main/kotlin/com/graphmesh/ontology/RdfXmlExporter.kt`        | NEU - Export im RDF/XML-Format                                                      |

### Frontend

Nicht betroffen.

### Tests

| Datei                                                                          | Aenderung                                                                   |
|--------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| `ontology/src/test/kotlin/com/graphmesh/ontology/OntologyValidatorTest.kt`     | NEU - Validierungstests (zirkulaere Vererbung, Domain/Range, Kardinalitaet) |
| `ontology/src/test/kotlin/com/graphmesh/ontology/OntologyTest.kt`              | NEU - Datenmodell-Tests (Hierarchie, SubClasses)                            |
| `ontology/src/test/kotlin/com/graphmesh/ontology/OntologyJsonParserTest.kt`    | NEU - JSON-Parsing mit Beispiel-Ontologien                                  |
| `ontology/src/test/kotlin/com/graphmesh/ontology/TurtleExporterTest.kt`        | NEU - Turtle Import/Export Round-Trip                                       |
| `ontology/src/test/kotlin/com/graphmesh/ontology/OntologyConfigHandlerTest.kt` | NEU - Config-Event-Verarbeitung                                             |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                              |
|-------------------|-------------|----------------------------------------------------|
| Spring Boot (JVM) | Ja          | ConfigService, Cassandra-Client                    |
| KMP Library       | Nein        | Abhaengigkeit zu ConfigService und CassandraClient |
| Ktor/Wasm         | Nein        | ConfigService ist JVM-spezifisch                   |

## Akzeptanzkriterien

- [ ] Ontologie-Datenmodell bildet OWL-Klassen, Object Properties und Datatype Properties ab
- [ ] Klassen unterstuetzen einfache und mehrfache Vererbung via `subClassOf`
- [ ] Object Properties definieren Domain/Range als Referenzen auf Ontologie-Klassen
- [ ] Datatype Properties definieren Range als XSD-Typ mit optionaler Kardinalitaet
- [ ] Multi-Language Labels (BCP-47) werden fuer Klassen und Properties unterstuetzt
- [ ] Zirkulaere Vererbungsketten werden erkannt und als ValidationError gemeldet
- [ ] Domain/Range-Referenzen auf nicht-existierende Klassen werden erkannt
- [ ] Kardinalitaets-Constraints werden validiert (min <= max, functional => max <= 1)
- [ ] Disjunkte Klassen koennen nicht gleichzeitig in einer Vererbungsbeziehung stehen
- [ ] Ontologien werden als Config-Items vom Typ `ontology` ueber ConfigService gespeichert
- [ ] Import aus Turtle-Format erzeugt valides Ontologie-Objekt
- [ ] Export nach Turtle-Format erzeugt gueltiges TTL
- [ ] Import aus RDF/XML-Format erzeugt valides Ontologie-Objekt
- [ ] Export nach RDF/XML-Format erzeugt gueltiges RDF/XML
- [ ] OntologyConfigHandler reagiert auf Config-Updates und aktualisiert den Cache
