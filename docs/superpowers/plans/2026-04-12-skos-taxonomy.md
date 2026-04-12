# SKOS Taxonomy Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add semantic SKOS taxonomy support — constants, data model, QuadStore-based navigation service, JenaAdapter SKOS extraction, validation, and GraphQL API.

**Architecture:** New `com.agentwork.graphmesh.skos` package with `SkosService` reading SKOS quads from `QuadStore`, `SkosValidator` for consistency checks, `JenaAdapter` extension for SKOS extraction, and `SkosController` exposing GraphQL queries. `LangLabel` from `ontology` package is reused.

**Tech Stack:** Kotlin, Spring Boot 4, Spring GraphQL (`@QueryMapping`/`@SchemaMapping`), Apache Jena (`org.apache.jena.vocabulary.SKOS`), Cassandra QuadStore, JUnit 5, MockK

---

## File Map

### New Files

| File | Responsibility |
|------|---------------|
| `src/main/kotlin/.../skos/Models.kt` | `SkosConcept`, `SkosConceptScheme` data classes |
| `src/main/kotlin/.../skos/SkosService.kt` | QuadStore-based SKOS navigation |
| `src/main/kotlin/.../skos/SkosValidator.kt` | SKOS consistency validation |
| `src/main/kotlin/.../skos/SkosController.kt` | GraphQL controller |
| `src/main/resources/graphql/skos.graphqls` | GraphQL schema |
| `src/test/kotlin/.../skos/SkosServiceTest.kt` | Service unit tests |
| `src/test/kotlin/.../skos/SkosValidatorTest.kt` | Validator unit tests |
| `src/test/kotlin/.../skos/SkosControllerTest.kt` | Controller unit tests |
| `src/test/kotlin/.../ontology/JenaAdapterSkosTest.kt` | JenaAdapter SKOS extraction tests |

### Modified Files

| File | Change |
|------|--------|
| `src/main/kotlin/.../rdf/RdfTerm.kt` | Add `SkosTypes` object |
| `src/main/kotlin/.../ontology/JenaAdapter.kt` | Add `extractSkosSchemes()`, `extractSkosConcepts()` |

**Base package:** `com.agentwork.graphmesh` (abbreviated as `...` above)

---

### Task 1: SkosTypes Constants

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/rdf/RdfTerm.kt`

- [ ] **Step 1: Add SkosTypes object to RdfTerm.kt**

Add after the `RdfTypes` object at the end of the file:

```kotlin
object SkosTypes {
    private const val NS = "http://www.w3.org/2004/02/skos/core#"

    const val CONCEPT = "${NS}Concept"
    const val CONCEPT_SCHEME = "${NS}ConceptScheme"
    const val IN_SCHEME = "${NS}inScheme"
    const val HAS_TOP_CONCEPT = "${NS}hasTopConcept"
    const val TOP_CONCEPT_OF = "${NS}topConceptOf"
    const val PREF_LABEL = "${NS}prefLabel"
    const val ALT_LABEL = "${NS}altLabel"
    const val HIDDEN_LABEL = "${NS}hiddenLabel"
    const val BROADER = "${NS}broader"
    const val NARROWER = "${NS}narrower"
    const val RELATED = "${NS}related"
    const val NOTE = "${NS}note"
    const val SCOPE_NOTE = "${NS}scopeNote"
    const val DEFINITION = "${NS}definition"
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/rdf/RdfTerm.kt
git commit -m "feat(skos): add SkosTypes vocabulary constants to RdfTerm.kt"
```

---

### Task 2: Data Classes

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/skos/Models.kt`

- [ ] **Step 1: Create Models.kt**

```kotlin
package com.agentwork.graphmesh.skos

import com.agentwork.graphmesh.ontology.LangLabel

data class SkosConcept(
    val uri: String,
    val prefLabels: List<LangLabel>,
    val altLabels: List<LangLabel> = emptyList(),
    val broader: List<String> = emptyList(),
    val narrower: List<String> = emptyList(),
    val related: List<String> = emptyList(),
    val inScheme: String? = null,
    val scopeNote: String? = null,
    val definition: String? = null
)

data class SkosConceptScheme(
    val uri: String,
    val prefLabels: List<LangLabel>,
    val topConcepts: List<String> = emptyList()
)
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/skos/Models.kt
git commit -m "feat(skos): add SkosConcept and SkosConceptScheme data classes"
```

---

### Task 3: SkosService — Tests First

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/skos/SkosServiceTest.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/skos/SkosService.kt`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/agentwork/graphmesh/skos/SkosServiceTest.kt`:

```kotlin
package com.agentwork.graphmesh.skos

import com.agentwork.graphmesh.ontology.LangLabel
import com.agentwork.graphmesh.rdf.SkosTypes
import com.agentwork.graphmesh.storage.InMemoryQuadStore
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.RDF_TYPE_URI
import com.agentwork.graphmesh.storage.StoredQuad
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkosServiceTest {

    private val quadStore = InMemoryQuadStore()
    private val service = SkosService(quadStore)
    private val collectionId = "test-col"

    private val schemeUri = "http://example.org/scheme/animals"
    private val catUri = "http://example.org/concept/cat"
    private val dogUri = "http://example.org/concept/dog"
    private val mammalUri = "http://example.org/concept/mammal"
    private val animalUri = "http://example.org/concept/animal"

    @BeforeEach
    fun setUp() {
        // ConceptScheme
        quadStore.insert(collectionId, StoredQuad(schemeUri, RDF_TYPE_URI, SkosTypes.CONCEPT_SCHEME, ""))
        quadStore.insert(collectionId, StoredQuad(schemeUri, SkosTypes.PREF_LABEL, "Animals", "", ObjectType.LITERAL, language = "en"))
        quadStore.insert(collectionId, StoredQuad(schemeUri, SkosTypes.PREF_LABEL, "Tiere", "", ObjectType.LITERAL, language = "de"))
        quadStore.insert(collectionId, StoredQuad(schemeUri, SkosTypes.HAS_TOP_CONCEPT, animalUri, ""))

        // animal (top concept)
        quadStore.insert(collectionId, StoredQuad(animalUri, RDF_TYPE_URI, SkosTypes.CONCEPT, ""))
        quadStore.insert(collectionId, StoredQuad(animalUri, SkosTypes.PREF_LABEL, "Animal", "", ObjectType.LITERAL, language = "en"))
        quadStore.insert(collectionId, StoredQuad(animalUri, SkosTypes.TOP_CONCEPT_OF, schemeUri, ""))
        quadStore.insert(collectionId, StoredQuad(animalUri, SkosTypes.IN_SCHEME, schemeUri, ""))
        quadStore.insert(collectionId, StoredQuad(animalUri, SkosTypes.NARROWER, mammalUri, ""))

        // mammal
        quadStore.insert(collectionId, StoredQuad(mammalUri, RDF_TYPE_URI, SkosTypes.CONCEPT, ""))
        quadStore.insert(collectionId, StoredQuad(mammalUri, SkosTypes.PREF_LABEL, "Mammal", "", ObjectType.LITERAL, language = "en"))
        quadStore.insert(collectionId, StoredQuad(mammalUri, SkosTypes.ALT_LABEL, "Saeugetier", "", ObjectType.LITERAL, language = "de"))
        quadStore.insert(collectionId, StoredQuad(mammalUri, SkosTypes.IN_SCHEME, schemeUri, ""))
        quadStore.insert(collectionId, StoredQuad(mammalUri, SkosTypes.BROADER, animalUri, ""))
        quadStore.insert(collectionId, StoredQuad(mammalUri, SkosTypes.NARROWER, catUri, ""))
        quadStore.insert(collectionId, StoredQuad(mammalUri, SkosTypes.NARROWER, dogUri, ""))

        // cat
        quadStore.insert(collectionId, StoredQuad(catUri, RDF_TYPE_URI, SkosTypes.CONCEPT, ""))
        quadStore.insert(collectionId, StoredQuad(catUri, SkosTypes.PREF_LABEL, "Cat", "", ObjectType.LITERAL, language = "en"))
        quadStore.insert(collectionId, StoredQuad(catUri, SkosTypes.IN_SCHEME, schemeUri, ""))
        quadStore.insert(collectionId, StoredQuad(catUri, SkosTypes.BROADER, mammalUri, ""))
        quadStore.insert(collectionId, StoredQuad(catUri, SkosTypes.RELATED, dogUri, ""))

        // dog
        quadStore.insert(collectionId, StoredQuad(dogUri, RDF_TYPE_URI, SkosTypes.CONCEPT, ""))
        quadStore.insert(collectionId, StoredQuad(dogUri, SkosTypes.PREF_LABEL, "Dog", "", ObjectType.LITERAL, language = "en"))
        quadStore.insert(collectionId, StoredQuad(dogUri, SkosTypes.SCOPE_NOTE, "A domesticated canine", "", ObjectType.LITERAL))
        quadStore.insert(collectionId, StoredQuad(dogUri, SkosTypes.IN_SCHEME, schemeUri, ""))
        quadStore.insert(collectionId, StoredQuad(dogUri, SkosTypes.BROADER, mammalUri, ""))
        quadStore.insert(collectionId, StoredQuad(dogUri, SkosTypes.RELATED, catUri, ""))
    }

    @Test
    fun `getConceptSchemes returns all schemes`() {
        val schemes = service.getConceptSchemes(collectionId)
        assertEquals(1, schemes.size)
        assertEquals(schemeUri, schemes[0].uri)
        assertEquals(2, schemes[0].prefLabels.size)
        assertTrue(schemes[0].prefLabels.any { it.value == "Animals" && it.lang == "en" })
        assertTrue(schemes[0].prefLabels.any { it.value == "Tiere" && it.lang == "de" })
        assertEquals(listOf(animalUri), schemes[0].topConcepts)
    }

    @Test
    fun `getConceptSchemes returns empty for unknown collection`() {
        val schemes = service.getConceptSchemes("unknown")
        assertTrue(schemes.isEmpty())
    }

    @Test
    fun `getConcepts returns all concepts in scheme`() {
        val concepts = service.getConcepts(collectionId, schemeUri)
        assertEquals(4, concepts.size)
        val uris = concepts.map { it.uri }.toSet()
        assertTrue(animalUri in uris)
        assertTrue(mammalUri in uris)
        assertTrue(catUri in uris)
        assertTrue(dogUri in uris)
    }

    @Test
    fun `getTopConcepts returns top concepts of scheme`() {
        val tops = service.getTopConcepts(collectionId, schemeUri)
        assertEquals(1, tops.size)
        assertEquals(animalUri, tops[0].uri)
    }

    @Test
    fun `getNarrower returns direct children`() {
        val narrower = service.getNarrower(collectionId, mammalUri)
        assertEquals(2, narrower.size)
        val uris = narrower.map { it.uri }.toSet()
        assertTrue(catUri in uris)
        assertTrue(dogUri in uris)
    }

    @Test
    fun `getBroader returns direct parents`() {
        val broader = service.getBroader(collectionId, catUri)
        assertEquals(1, broader.size)
        assertEquals(mammalUri, broader[0].uri)
    }

    @Test
    fun `getRelated returns related concepts`() {
        val related = service.getRelated(collectionId, catUri)
        assertEquals(1, related.size)
        assertEquals(dogUri, related[0].uri)
    }

    @Test
    fun `getConcept returns full concept with all fields`() {
        val concept = service.getConcept(collectionId, dogUri)
        assertNotNull(concept)
        assertEquals(dogUri, concept.uri)
        assertEquals(1, concept.prefLabels.size)
        assertEquals("Dog", concept.prefLabels[0].value)
        assertEquals(listOf(mammalUri), concept.broader)
        assertEquals(listOf(catUri), concept.related)
        assertEquals("A domesticated canine", concept.scopeNote)
        assertEquals(schemeUri, concept.inScheme)
    }

    @Test
    fun `getConcept returns null for unknown URI`() {
        assertNull(service.getConcept(collectionId, "http://example.org/nonexistent"))
    }

    @Test
    fun `findByLabel matches case-insensitive substring on prefLabel`() {
        val results = service.findByLabel(collectionId, "mam")
        assertEquals(1, results.size)
        assertEquals(mammalUri, results[0].uri)
    }

    @Test
    fun `findByLabel matches on altLabel`() {
        val results = service.findByLabel(collectionId, "saeugetier")
        assertEquals(1, results.size)
        assertEquals(mammalUri, results[0].uri)
    }

    @Test
    fun `findByLabel returns empty for no match`() {
        val results = service.findByLabel(collectionId, "zzzzz")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `countConcepts returns number of concepts in scheme`() {
        val count = service.countConcepts(collectionId, schemeUri)
        assertEquals(4, count)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.skos.SkosServiceTest"`
Expected: FAIL — `SkosService` class does not exist yet

- [ ] **Step 3: Implement SkosService**

Create `src/main/kotlin/com/agentwork/graphmesh/skos/SkosService.kt`:

```kotlin
package com.agentwork.graphmesh.skos

import com.agentwork.graphmesh.ontology.LangLabel
import com.agentwork.graphmesh.rdf.SkosTypes
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.RDF_TYPE_URI
import com.agentwork.graphmesh.storage.StoredQuad
import org.springframework.stereotype.Service

@Service
class SkosService(private val quadStore: QuadStore) {

    fun getConceptSchemes(collectionId: String): List<SkosConceptScheme> {
        val schemeSubjects = quadStore.query(
            collectionId,
            QuadQuery(predicate = RDF_TYPE_URI, objectValue = SkosTypes.CONCEPT_SCHEME)
        ).map { it.subject }.distinct()

        return schemeSubjects.map { uri -> buildScheme(collectionId, uri) }
    }

    fun getConcepts(collectionId: String, schemeUri: String): List<SkosConcept> {
        val conceptUris = quadStore.query(
            collectionId,
            QuadQuery(predicate = SkosTypes.IN_SCHEME, objectValue = schemeUri)
        ).map { it.subject }.distinct()

        return conceptUris.mapNotNull { uri -> buildConcept(collectionId, uri) }
    }

    fun getTopConcepts(collectionId: String, schemeUri: String): List<SkosConcept> {
        val fromScheme = quadStore.query(
            collectionId,
            QuadQuery(subject = schemeUri, predicate = SkosTypes.HAS_TOP_CONCEPT)
        ).map { it.objectValue }

        val fromConcept = quadStore.query(
            collectionId,
            QuadQuery(predicate = SkosTypes.TOP_CONCEPT_OF, objectValue = schemeUri)
        ).map { it.subject }

        val topUris = (fromScheme + fromConcept).distinct()
        return topUris.mapNotNull { uri -> buildConcept(collectionId, uri) }
    }

    fun getNarrower(collectionId: String, conceptUri: String): List<SkosConcept> {
        val uris = quadStore.query(
            collectionId,
            QuadQuery(subject = conceptUri, predicate = SkosTypes.NARROWER)
        ).map { it.objectValue }.distinct()

        return uris.mapNotNull { uri -> buildConcept(collectionId, uri) }
    }

    fun getBroader(collectionId: String, conceptUri: String): List<SkosConcept> {
        val uris = quadStore.query(
            collectionId,
            QuadQuery(subject = conceptUri, predicate = SkosTypes.BROADER)
        ).map { it.objectValue }.distinct()

        return uris.mapNotNull { uri -> buildConcept(collectionId, uri) }
    }

    fun getRelated(collectionId: String, conceptUri: String): List<SkosConcept> {
        val uris = quadStore.query(
            collectionId,
            QuadQuery(subject = conceptUri, predicate = SkosTypes.RELATED)
        ).map { it.objectValue }.distinct()

        return uris.mapNotNull { uri -> buildConcept(collectionId, uri) }
    }

    fun findByLabel(collectionId: String, label: String): List<SkosConcept> {
        val needle = label.lowercase()

        val prefLabelQuads = quadStore.query(
            collectionId,
            QuadQuery(predicate = SkosTypes.PREF_LABEL)
        )
        val altLabelQuads = quadStore.query(
            collectionId,
            QuadQuery(predicate = SkosTypes.ALT_LABEL)
        )

        val matchingUris = (prefLabelQuads + altLabelQuads)
            .filter { it.objectValue.lowercase().contains(needle) }
            .map { it.subject }
            .distinct()

        return matchingUris.mapNotNull { uri -> buildConcept(collectionId, uri) }
    }

    fun getConcept(collectionId: String, conceptUri: String): SkosConcept? {
        return buildConcept(collectionId, conceptUri)
    }

    fun countConcepts(collectionId: String, schemeUri: String): Int {
        return quadStore.query(
            collectionId,
            QuadQuery(predicate = SkosTypes.IN_SCHEME, objectValue = schemeUri)
        ).map { it.subject }.distinct().size
    }

    private fun buildScheme(collectionId: String, uri: String): SkosConceptScheme {
        val quads = quadStore.query(collectionId, QuadQuery(subject = uri))
        val prefLabels = quads.filter { it.predicate == SkosTypes.PREF_LABEL }
            .map { LangLabel(value = it.objectValue, lang = it.language.ifEmpty { "en" }) }
        val topConcepts = quads.filter { it.predicate == SkosTypes.HAS_TOP_CONCEPT }
            .map { it.objectValue }

        return SkosConceptScheme(uri = uri, prefLabels = prefLabels, topConcepts = topConcepts)
    }

    private fun buildConcept(collectionId: String, uri: String): SkosConcept? {
        val quads = quadStore.query(collectionId, QuadQuery(subject = uri))
        if (quads.isEmpty()) return null

        val isConcept = quads.any { it.predicate == RDF_TYPE_URI && it.objectValue == SkosTypes.CONCEPT }
        if (!isConcept) return null

        return SkosConcept(
            uri = uri,
            prefLabels = quads.extractLabels(SkosTypes.PREF_LABEL),
            altLabels = quads.extractLabels(SkosTypes.ALT_LABEL),
            broader = quads.extractUris(SkosTypes.BROADER),
            narrower = quads.extractUris(SkosTypes.NARROWER),
            related = quads.extractUris(SkosTypes.RELATED),
            inScheme = quads.firstOrNull { it.predicate == SkosTypes.IN_SCHEME }?.objectValue,
            scopeNote = quads.firstOrNull { it.predicate == SkosTypes.SCOPE_NOTE }?.objectValue,
            definition = quads.firstOrNull { it.predicate == SkosTypes.DEFINITION }?.objectValue
        )
    }

    private fun List<StoredQuad>.extractLabels(predicate: String): List<LangLabel> =
        filter { it.predicate == predicate }
            .map { LangLabel(value = it.objectValue, lang = it.language.ifEmpty { "en" }) }

    private fun List<StoredQuad>.extractUris(predicate: String): List<String> =
        filter { it.predicate == predicate }.map { it.objectValue }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.skos.SkosServiceTest"`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/skos/SkosService.kt src/test/kotlin/com/agentwork/graphmesh/skos/SkosServiceTest.kt
git commit -m "feat(skos): add SkosService with QuadStore-based taxonomy navigation"
```

---

### Task 4: SkosValidator — Tests First

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/skos/SkosValidatorTest.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/skos/SkosValidator.kt`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/agentwork/graphmesh/skos/SkosValidatorTest.kt`:

```kotlin
package com.agentwork.graphmesh.skos

import com.agentwork.graphmesh.ontology.LangLabel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkosValidatorTest {

    private val validator = SkosValidator()

    private fun concept(
        uri: String,
        prefLabels: List<LangLabel> = listOf(LangLabel("Label", "en")),
        altLabels: List<LangLabel> = emptyList(),
        broader: List<String> = emptyList(),
        narrower: List<String> = emptyList(),
        related: List<String> = emptyList(),
        inScheme: String? = null
    ) = SkosConcept(uri, prefLabels, altLabels, broader, narrower, related, inScheme)

    private fun scheme(
        uri: String,
        prefLabels: List<LangLabel> = listOf(LangLabel("Scheme", "en")),
        topConcepts: List<String> = emptyList()
    ) = SkosConceptScheme(uri, prefLabels, topConcepts)

    @Test
    fun `valid taxonomy produces no errors`() {
        val schemes = listOf(scheme("http://ex.org/scheme", topConcepts = listOf("http://ex.org/A")))
        val concepts = listOf(
            concept("http://ex.org/A", inScheme = "http://ex.org/scheme", narrower = listOf("http://ex.org/B")),
            concept("http://ex.org/B", inScheme = "http://ex.org/scheme", broader = listOf("http://ex.org/A"))
        )
        val errors = validator.validate(schemes, concepts)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `concept without prefLabel is an error`() {
        val concepts = listOf(concept("http://ex.org/A", prefLabels = emptyList()))
        val errors = validator.validate(emptyList(), concepts)
        assertEquals(1, errors.size)
        assertEquals(SkosValidationRule.MISSING_PREF_LABEL, errors[0].rule)
        assertEquals("http://ex.org/A", errors[0].uri)
    }

    @Test
    fun `duplicate prefLabel per language is an error`() {
        val concepts = listOf(
            concept("http://ex.org/A", prefLabels = listOf(
                LangLabel("Label1", "en"),
                LangLabel("Label2", "en")
            ))
        )
        val errors = validator.validate(emptyList(), concepts)
        assertEquals(1, errors.size)
        assertEquals(SkosValidationRule.DUPLICATE_PREF_LABEL_PER_LANG, errors[0].rule)
    }

    @Test
    fun `circular broader chain is an error`() {
        val concepts = listOf(
            concept("http://ex.org/A", broader = listOf("http://ex.org/B")),
            concept("http://ex.org/B", broader = listOf("http://ex.org/C")),
            concept("http://ex.org/C", broader = listOf("http://ex.org/A"))
        )
        val errors = validator.validate(emptyList(), concepts)
        assertTrue(errors.any { it.rule == SkosValidationRule.CIRCULAR_BROADER })
    }

    @Test
    fun `inScheme pointing to non-existent scheme is an error`() {
        val concepts = listOf(
            concept("http://ex.org/A", inScheme = "http://ex.org/missing-scheme")
        )
        val errors = validator.validate(emptyList(), concepts)
        assertEquals(1, errors.size)
        assertEquals(SkosValidationRule.UNKNOWN_SCHEME, errors[0].rule)
    }

    @Test
    fun `broader-narrower asymmetry is a warning`() {
        val concepts = listOf(
            concept("http://ex.org/A", narrower = listOf("http://ex.org/B")),
            concept("http://ex.org/B") // missing broader pointing to A
        )
        val errors = validator.validate(emptyList(), concepts)
        assertTrue(errors.any { it.rule == SkosValidationRule.BROADER_NARROWER_ASYMMETRY })
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.skos.SkosValidatorTest"`
Expected: FAIL — `SkosValidator` class does not exist yet

- [ ] **Step 3: Implement SkosValidator**

Create `src/main/kotlin/com/agentwork/graphmesh/skos/SkosValidator.kt`:

```kotlin
package com.agentwork.graphmesh.skos

import org.springframework.stereotype.Component

enum class SkosValidationRule {
    MISSING_PREF_LABEL,
    DUPLICATE_PREF_LABEL_PER_LANG,
    CIRCULAR_BROADER,
    UNKNOWN_SCHEME,
    BROADER_NARROWER_ASYMMETRY
}

data class SkosValidationError(
    val uri: String,
    val rule: SkosValidationRule,
    val message: String
)

@Component
class SkosValidator {

    fun validate(
        schemes: List<SkosConceptScheme>,
        concepts: List<SkosConcept>
    ): List<SkosValidationError> {
        val errors = mutableListOf<SkosValidationError>()
        errors.addAll(checkMissingPrefLabel(concepts))
        errors.addAll(checkDuplicatePrefLabelPerLang(concepts))
        errors.addAll(checkCircularBroader(concepts))
        errors.addAll(checkUnknownScheme(schemes, concepts))
        errors.addAll(checkBroaderNarrowerAsymmetry(concepts))
        return errors
    }

    private fun checkMissingPrefLabel(concepts: List<SkosConcept>): List<SkosValidationError> =
        concepts.filter { it.prefLabels.isEmpty() }.map { c ->
            SkosValidationError(c.uri, SkosValidationRule.MISSING_PREF_LABEL,
                "Concept '${c.uri}' has no skos:prefLabel")
        }

    private fun checkDuplicatePrefLabelPerLang(concepts: List<SkosConcept>): List<SkosValidationError> =
        concepts.flatMap { c ->
            c.prefLabels.groupBy { it.lang }
                .filter { (_, labels) -> labels.size > 1 }
                .map { (lang, _) ->
                    SkosValidationError(c.uri, SkosValidationRule.DUPLICATE_PREF_LABEL_PER_LANG,
                        "Concept '${c.uri}' has ${c.prefLabels.count { it.lang == lang }} prefLabels for language '$lang'")
                }
        }

    private fun checkCircularBroader(concepts: List<SkosConcept>): List<SkosValidationError> {
        val errors = mutableListOf<SkosValidationError>()
        val broaderMap = concepts.associate { it.uri to it.broader }

        for (concept in concepts) {
            val visited = mutableSetOf<String>()
            var current = concept.broader
            while (current.isNotEmpty()) {
                val next = mutableListOf<String>()
                for (parentUri in current) {
                    if (parentUri == concept.uri) {
                        errors.add(SkosValidationError(concept.uri, SkosValidationRule.CIRCULAR_BROADER,
                            "Circular broader chain detected for '${concept.uri}'"))
                        break
                    }
                    if (parentUri !in visited) {
                        visited.add(parentUri)
                        broaderMap[parentUri]?.let { next.addAll(it) }
                    }
                }
                if (errors.any { it.uri == concept.uri && it.rule == SkosValidationRule.CIRCULAR_BROADER }) break
                current = next
            }
        }
        return errors
    }

    private fun checkUnknownScheme(
        schemes: List<SkosConceptScheme>,
        concepts: List<SkosConcept>
    ): List<SkosValidationError> {
        val schemeUris = schemes.map { it.uri }.toSet()
        return concepts.filter { it.inScheme != null && it.inScheme !in schemeUris }.map { c ->
            SkosValidationError(c.uri, SkosValidationRule.UNKNOWN_SCHEME,
                "Concept '${c.uri}' references unknown scheme '${c.inScheme}'")
        }
    }

    private fun checkBroaderNarrowerAsymmetry(concepts: List<SkosConcept>): List<SkosValidationError> {
        val conceptMap = concepts.associateBy { it.uri }
        val errors = mutableListOf<SkosValidationError>()

        for (concept in concepts) {
            for (narrowerUri in concept.narrower) {
                val narrowerConcept = conceptMap[narrowerUri]
                if (narrowerConcept != null && concept.uri !in narrowerConcept.broader) {
                    errors.add(SkosValidationError(concept.uri, SkosValidationRule.BROADER_NARROWER_ASYMMETRY,
                        "'${concept.uri}' has narrower '${narrowerUri}' but '${narrowerUri}' does not have broader '${concept.uri}'"))
                }
            }
        }
        return errors
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.skos.SkosValidatorTest"`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/skos/SkosValidator.kt src/test/kotlin/com/agentwork/graphmesh/skos/SkosValidatorTest.kt
git commit -m "feat(skos): add SkosValidator with consistency checks"
```

---

### Task 5: JenaAdapter SKOS Extraction — Tests First

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/ontology/JenaAdapterSkosTest.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/ontology/JenaAdapter.kt`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/agentwork/graphmesh/ontology/JenaAdapterSkosTest.kt`:

```kotlin
package com.agentwork.graphmesh.ontology

import com.agentwork.graphmesh.skos.SkosConcept
import com.agentwork.graphmesh.skos.SkosConceptScheme
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JenaAdapterSkosTest {

    private val adapter = JenaAdapter()

    private val skosTurtle = """
        @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
        @prefix ex: <http://example.org/> .

        ex:animals a skos:ConceptScheme ;
            skos:prefLabel "Animals"@en ;
            skos:prefLabel "Tiere"@de ;
            skos:hasTopConcept ex:animal .

        ex:animal a skos:Concept ;
            skos:prefLabel "Animal"@en ;
            skos:inScheme ex:animals ;
            skos:topConceptOf ex:animals ;
            skos:narrower ex:mammal .

        ex:mammal a skos:Concept ;
            skos:prefLabel "Mammal"@en ;
            skos:altLabel "Saeugetier"@de ;
            skos:inScheme ex:animals ;
            skos:broader ex:animal ;
            skos:narrower ex:cat ;
            skos:scopeNote "Warm-blooded vertebrates"@en .

        ex:cat a skos:Concept ;
            skos:prefLabel "Cat"@en ;
            skos:inScheme ex:animals ;
            skos:broader ex:mammal ;
            skos:definition "A small domesticated feline"@en ;
            skos:related ex:dog .

        ex:dog a skos:Concept ;
            skos:prefLabel "Dog"@en ;
            skos:inScheme ex:animals ;
            skos:broader ex:mammal ;
            skos:related ex:cat .
    """.trimIndent()

    @Test
    fun `extractSkosSchemes parses ConceptScheme with labels and topConcepts`() {
        val model = adapter.parseTurtle(skosTurtle)
        val schemes = adapter.extractSkosSchemes(model)

        assertEquals(1, schemes.size)
        val scheme = schemes[0]
        assertEquals("http://example.org/animals", scheme.uri)
        assertEquals(2, scheme.prefLabels.size)
        assertTrue(scheme.prefLabels.any { it.value == "Animals" && it.lang == "en" })
        assertTrue(scheme.prefLabels.any { it.value == "Tiere" && it.lang == "de" })
        assertEquals(listOf("http://example.org/animal"), scheme.topConcepts)
    }

    @Test
    fun `extractSkosConcepts parses all concepts with properties`() {
        val model = adapter.parseTurtle(skosTurtle)
        val concepts = adapter.extractSkosConcepts(model)

        assertEquals(4, concepts.size)
        val byUri = concepts.associateBy { it.uri }

        val animal = byUri["http://example.org/animal"]!!
        assertEquals("Animal", animal.prefLabels[0].value)
        assertEquals(listOf("http://example.org/mammal"), animal.narrower)
        assertEquals("http://example.org/animals", animal.inScheme)

        val mammal = byUri["http://example.org/mammal"]!!
        assertEquals("Mammal", mammal.prefLabels[0].value)
        assertEquals(1, mammal.altLabels.size)
        assertEquals("Saeugetier", mammal.altLabels[0].value)
        assertEquals(listOf("http://example.org/animal"), mammal.broader)
        assertEquals("Warm-blooded vertebrates", mammal.scopeNote)

        val cat = byUri["http://example.org/cat"]!!
        assertEquals(listOf("http://example.org/mammal"), cat.broader)
        assertEquals(listOf("http://example.org/dog"), cat.related)
        assertEquals("A small domesticated feline", cat.definition)
    }

    @Test
    fun `extractSkosSchemes returns empty for model without SKOS`() {
        val turtle = """
            @prefix ex: <http://example.org/> .
            ex:Alice ex:knows ex:Bob .
        """.trimIndent()
        val model = adapter.parseTurtle(turtle)
        val schemes = adapter.extractSkosSchemes(model)
        assertTrue(schemes.isEmpty())
    }

    @Test
    fun `extractSkosConcepts returns empty for model without SKOS`() {
        val turtle = """
            @prefix ex: <http://example.org/> .
            ex:Alice ex:knows ex:Bob .
        """.trimIndent()
        val model = adapter.parseTurtle(turtle)
        val concepts = adapter.extractSkosConcepts(model)
        assertTrue(concepts.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.JenaAdapterSkosTest"`
Expected: FAIL — `extractSkosSchemes`/`extractSkosConcepts` methods don't exist yet

- [ ] **Step 3: Extend JenaAdapter with SKOS extraction**

Add the following methods to `src/main/kotlin/com/agentwork/graphmesh/ontology/JenaAdapter.kt`.

First, add the SKOS import at the top of the file alongside existing imports:

```kotlin
import org.apache.jena.vocabulary.SKOS
```

Then add imports for the skos models:

```kotlin
import com.agentwork.graphmesh.skos.SkosConcept
import com.agentwork.graphmesh.skos.SkosConceptScheme
```

Add these methods inside the `JenaAdapter` class, before the `private fun extractId` method:

```kotlin
    fun extractSkosSchemes(model: Model): List<SkosConceptScheme> {
        val schemes = mutableListOf<SkosConceptScheme>()
        val schemeResources = model.listResourcesWithProperty(RDF.type, SKOS.ConceptScheme)
        while (schemeResources.hasNext()) {
            val resource = schemeResources.next()
            val uri = resource.uri ?: continue

            val prefLabels = extractSkosLabels(model, resource, SKOS.prefLabel)
            val topConcepts = model.listStatements(resource, SKOS.hasTopConcept, null as Resource?)
                .toList().mapNotNull { it.`object`.asResource().uri }

            schemes.add(SkosConceptScheme(uri = uri, prefLabels = prefLabels, topConcepts = topConcepts))
        }
        return schemes
    }

    fun extractSkosConcepts(model: Model): List<SkosConcept> {
        val concepts = mutableListOf<SkosConcept>()
        val conceptResources = model.listResourcesWithProperty(RDF.type, SKOS.Concept)
        while (conceptResources.hasNext()) {
            val resource = conceptResources.next()
            val uri = resource.uri ?: continue

            val broader = model.listStatements(resource, SKOS.broader, null as Resource?)
                .toList().mapNotNull { it.`object`.asResource().uri }
            val narrower = model.listStatements(resource, SKOS.narrower, null as Resource?)
                .toList().mapNotNull { it.`object`.asResource().uri }
            val related = model.listStatements(resource, SKOS.related, null as Resource?)
                .toList().mapNotNull { it.`object`.asResource().uri }
            val inScheme = model.listStatements(resource, SKOS.inScheme, null as Resource?)
                .toList().firstOrNull()?.`object`?.asResource()?.uri
            val scopeNote = model.listStatements(resource, SKOS.scopeNote, null as org.apache.jena.rdf.model.RDFNode?)
                .toList().firstOrNull()?.`object`?.asLiteral()?.string
            val definition = model.listStatements(resource, SKOS.definition, null as org.apache.jena.rdf.model.RDFNode?)
                .toList().firstOrNull()?.`object`?.asLiteral()?.string

            concepts.add(SkosConcept(
                uri = uri,
                prefLabels = extractSkosLabels(model, resource, SKOS.prefLabel),
                altLabels = extractSkosLabels(model, resource, SKOS.altLabel),
                broader = broader,
                narrower = narrower,
                related = related,
                inScheme = inScheme,
                scopeNote = scopeNote,
                definition = definition
            ))
        }
        return concepts
    }

    private fun extractSkosLabels(model: Model, resource: Resource, property: org.apache.jena.rdf.model.Property): List<LangLabel> =
        model.listStatements(resource, property, null as org.apache.jena.rdf.model.RDFNode?)
            .toList()
            .map { stmt ->
                val literal = stmt.`object`.asLiteral()
                LangLabel(value = literal.string, lang = literal.language.ifEmpty { "en" })
            }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.JenaAdapterSkosTest"`
Expected: ALL PASS

- [ ] **Step 5: Run existing JenaAdapter tests to verify no regression**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.JenaAdapterTest"`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/ontology/JenaAdapter.kt src/test/kotlin/com/agentwork/graphmesh/ontology/JenaAdapterSkosTest.kt
git commit -m "feat(skos): add SKOS extraction to JenaAdapter"
```

---

### Task 6: GraphQL Schema

**Files:**
- Create: `src/main/resources/graphql/skos.graphqls`

- [ ] **Step 1: Create the GraphQL schema**

Create `src/main/resources/graphql/skos.graphqls`:

```graphql
type LangLabel {
    value: String!
    lang: String!
}

type SkosConcept {
    uri: String!
    prefLabels: [LangLabel!]!
    altLabels: [LangLabel!]!
    broader: [SkosConcept!]!
    narrower: [SkosConcept!]!
    related: [SkosConcept!]!
    inScheme: String
    scopeNote: String
    definition: String
}

type SkosConceptScheme {
    uri: String!
    prefLabels: [LangLabel!]!
    topConcepts: [SkosConcept!]!
    conceptCount: Int!
}

extend type Query {
    skosConceptSchemes(collectionId: ID!): [SkosConceptScheme!]!
    skosConcepts(collectionId: ID!, schemeUri: String!): [SkosConcept!]!
    skosConcept(collectionId: ID!, conceptUri: String!): SkosConcept
    skosSearch(collectionId: ID!, label: String!): [SkosConcept!]!
}
```

- [ ] **Step 2: Verify schema compiles with the build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL (schema is only validated at runtime, but no syntax errors)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/graphql/skos.graphqls
git commit -m "feat(skos): add GraphQL schema for SKOS types and queries"
```

---

### Task 7: SkosController — Tests First

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/skos/SkosControllerTest.kt`
- Create: `src/main/kotlin/com/agentwork/graphmesh/skos/SkosController.kt`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/agentwork/graphmesh/skos/SkosControllerTest.kt`:

```kotlin
package com.agentwork.graphmesh.skos

import com.agentwork.graphmesh.ontology.LangLabel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkosControllerTest {

    private val service = mockk<SkosService>()
    private val controller = SkosController(service)

    private val sampleScheme = SkosConceptScheme(
        uri = "http://ex.org/scheme",
        prefLabels = listOf(LangLabel("Test Scheme", "en")),
        topConcepts = listOf("http://ex.org/A")
    )

    private val sampleConcept = SkosConcept(
        uri = "http://ex.org/A",
        prefLabels = listOf(LangLabel("Concept A", "en")),
        broader = listOf("http://ex.org/B"),
        narrower = listOf("http://ex.org/C"),
        related = listOf("http://ex.org/D"),
        inScheme = "http://ex.org/scheme"
    )

    @Test
    fun `skosConceptSchemes delegates to service`() {
        every { service.getConceptSchemes("col-1") } returns listOf(sampleScheme)

        val result = controller.skosConceptSchemes("col-1")

        assertEquals(1, result.size)
        assertEquals("http://ex.org/scheme", result[0].uri)
        verify { service.getConceptSchemes("col-1") }
    }

    @Test
    fun `skosConcepts delegates to service`() {
        every { service.getConcepts("col-1", "http://ex.org/scheme") } returns listOf(sampleConcept)

        val result = controller.skosConcepts("col-1", "http://ex.org/scheme")

        assertEquals(1, result.size)
        assertEquals("http://ex.org/A", result[0].uri)
    }

    @Test
    fun `skosConcept returns concept for known URI`() {
        every { service.getConcept("col-1", "http://ex.org/A") } returns sampleConcept

        val result = controller.skosConcept("col-1", "http://ex.org/A")

        assertEquals("http://ex.org/A", result?.uri)
    }

    @Test
    fun `skosConcept returns null for unknown URI`() {
        every { service.getConcept("col-1", "http://ex.org/unknown") } returns null

        val result = controller.skosConcept("col-1", "http://ex.org/unknown")

        assertNull(result)
    }

    @Test
    fun `skosSearch delegates to service`() {
        every { service.findByLabel("col-1", "concept") } returns listOf(sampleConcept)

        val result = controller.skosSearch("col-1", "concept")

        assertEquals(1, result.size)
        verify { service.findByLabel("col-1", "concept") }
    }

    @Test
    fun `topConcepts SchemaMapping delegates to service`() {
        every { service.getTopConcepts("col-1", "http://ex.org/scheme") } returns listOf(sampleConcept)

        val result = controller.topConcepts("col-1", sampleScheme)

        assertEquals(1, result.size)
        assertEquals("http://ex.org/A", result[0].uri)
    }

    @Test
    fun `conceptCount SchemaMapping delegates to service`() {
        every { service.countConcepts("col-1", "http://ex.org/scheme") } returns 5

        val result = controller.conceptCount("col-1", sampleScheme)

        assertEquals(5, result)
    }

    @Test
    fun `broader SchemaMapping delegates to service`() {
        val broaderConcept = sampleConcept.copy(uri = "http://ex.org/B")
        every { service.getBroader("col-1", "http://ex.org/A") } returns listOf(broaderConcept)

        val result = controller.broader("col-1", sampleConcept)

        assertEquals(1, result.size)
        assertEquals("http://ex.org/B", result[0].uri)
    }

    @Test
    fun `narrower SchemaMapping delegates to service`() {
        val narrowerConcept = sampleConcept.copy(uri = "http://ex.org/C")
        every { service.getNarrower("col-1", "http://ex.org/A") } returns listOf(narrowerConcept)

        val result = controller.narrower("col-1", sampleConcept)

        assertEquals(1, result.size)
        assertEquals("http://ex.org/C", result[0].uri)
    }

    @Test
    fun `related SchemaMapping delegates to service`() {
        val relatedConcept = sampleConcept.copy(uri = "http://ex.org/D")
        every { service.getRelated("col-1", "http://ex.org/A") } returns listOf(relatedConcept)

        val result = controller.related("col-1", sampleConcept)

        assertEquals(1, result.size)
        assertEquals("http://ex.org/D", result[0].uri)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.skos.SkosControllerTest"`
Expected: FAIL — `SkosController` class does not exist yet

- [ ] **Step 3: Implement SkosController**

Create `src/main/kotlin/com/agentwork/graphmesh/skos/SkosController.kt`:

```kotlin
package com.agentwork.graphmesh.skos

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

@Controller
class SkosController(private val skosService: SkosService) {

    @QueryMapping
    fun skosConceptSchemes(@Argument collectionId: String): List<SkosConceptScheme> =
        skosService.getConceptSchemes(collectionId)

    @QueryMapping
    fun skosConcepts(@Argument collectionId: String, @Argument schemeUri: String): List<SkosConcept> =
        skosService.getConcepts(collectionId, schemeUri)

    @QueryMapping
    fun skosConcept(@Argument collectionId: String, @Argument conceptUri: String): SkosConcept? =
        skosService.getConcept(collectionId, conceptUri)

    @QueryMapping
    fun skosSearch(@Argument collectionId: String, @Argument label: String): List<SkosConcept> =
        skosService.findByLabel(collectionId, label)

    @SchemaMapping(typeName = "SkosConceptScheme", field = "topConcepts")
    fun topConcepts(@Argument collectionId: String, scheme: SkosConceptScheme): List<SkosConcept> =
        skosService.getTopConcepts(collectionId, scheme.uri)

    @SchemaMapping(typeName = "SkosConceptScheme", field = "conceptCount")
    fun conceptCount(@Argument collectionId: String, scheme: SkosConceptScheme): Int =
        skosService.countConcepts(collectionId, scheme.uri)

    @SchemaMapping(typeName = "SkosConcept", field = "broader")
    fun broader(@Argument collectionId: String, concept: SkosConcept): List<SkosConcept> =
        skosService.getBroader(collectionId, concept.uri)

    @SchemaMapping(typeName = "SkosConcept", field = "narrower")
    fun narrower(@Argument collectionId: String, concept: SkosConcept): List<SkosConcept> =
        skosService.getNarrower(collectionId, concept.uri)

    @SchemaMapping(typeName = "SkosConcept", field = "related")
    fun related(@Argument collectionId: String, concept: SkosConcept): List<SkosConcept> =
        skosService.getRelated(collectionId, concept.uri)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.skos.SkosControllerTest"`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/skos/SkosController.kt src/test/kotlin/com/agentwork/graphmesh/skos/SkosControllerTest.kt
git commit -m "feat(skos): add SkosController with GraphQL queries and schema mappings"
```

---

### Task 8: Full Build Verification

**Files:** None (verification only)

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: ALL PASS — no regressions in existing tests

- [ ] **Step 2: Run full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify all new SKOS tests pass in isolation**

Run: `./gradlew test --tests "com.agentwork.graphmesh.skos.*" --tests "com.agentwork.graphmesh.ontology.JenaAdapterSkosTest"`
Expected: ALL PASS

---
