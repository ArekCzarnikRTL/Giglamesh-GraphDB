# Feature 20: Ontology System — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement an OWL-inspired ontology system that manages class hierarchies, object/datatype properties, and validation rules, persisted via ConfigService and supporting Turtle/RDF-XML import/export via Apache Jena.

**Architecture:** Own Kotlin data model as the core (Jackson-serializable), with Apache Jena isolated in an adapter layer for Turtle/RDF-XML I/O only. Ontologies are stored as JSON ConfigItems (type=ONTOLOGY) via the existing ConfigService. A ConcurrentHashMap cache with Spring @EventListener invalidation provides fast reads.

**Tech Stack:** Kotlin, Spring Boot 4.0.5, Jackson, Apache Jena 5.3.0, MockK, JUnit 5

---

## File Structure

### Production Code (`src/main/kotlin/com/agentwork/graphmesh/ontology/`)

| File | Responsibility |
|---|---|
| `Ontology.kt` | All data classes: Ontology, OntologyClass, ObjectProperty, DatatypeProperty, OntologyMetadata, LangLabel, Cardinality |
| `DefaultOntologyValidator.kt` | Validation logic + ValidationError data class + ValidationRule enum |
| `OntologyStore.kt` | JSON persistence via ConfigService + Jackson ObjectMapper |
| `OntologyCache.kt` | ConcurrentHashMap cache + @EventListener for ConfigChangedEvent invalidation |
| `OntologyService.kt` | Orchestration: CRUD, validation, import/export delegation |
| `JenaAdapter.kt` | Kotlin ↔ Jena Model conversion, Turtle/RDF-XML parsing and serialization |

### Test Code (`src/test/kotlin/com/agentwork/graphmesh/ontology/`)

| File | Responsibility |
|---|---|
| `OntologyTest.kt` | Data model tests: getSubClasses, getClassHierarchy, Cardinality init validation |
| `DefaultOntologyValidatorTest.kt` | All 5 validation rules |
| `OntologyStoreTest.kt` | JSON round-trip, ConfigService interaction via MockK |
| `OntologyCacheTest.kt` | Cache hit/miss, EventListener invalidation |
| `OntologyServiceTest.kt` | Save with validation, CRUD, import/export delegation via MockK |
| `JenaAdapterTest.kt` | Kotlin→Jena→Kotlin round-trip, Turtle/RDF-XML round-trip |

---

### Task 1: Add Jena Dependency

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add Apache Jena to build.gradle.kts**

In `build.gradle.kts`, add the Jena dependency in the `dependencies` block, after the existing `implementation` entries:

```kotlin
implementation("org.apache.jena:apache-jena-libs:5.3.0")
```

Add it after line 46 (the `implementation("ai.koog:koog-spring-ai-starter-model-embedding:$koogVersion")` line), before the Kafka starter.

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "feat(ontology): add Apache Jena dependency for RDF import/export"
```

---

### Task 2: Ontology Data Model + Tests

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/ontology/Ontology.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/ontology/OntologyTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/agentwork/graphmesh/ontology/OntologyTest.kt`:

```kotlin
package com.agentwork.graphmesh.ontology

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OntologyTest {

    @Test
    fun `Cardinality allows valid min max`() {
        val card = Cardinality(min = 1, max = 5)
        assertEquals(1, card.min)
        assertEquals(5, card.max)
    }

    @Test
    fun `Cardinality rejects min greater than max`() {
        assertThrows<IllegalArgumentException> {
            Cardinality(min = 5, max = 1)
        }
    }

    @Test
    fun `Cardinality rejects exact with min`() {
        assertThrows<IllegalArgumentException> {
            Cardinality(exact = 3, min = 1)
        }
    }

    @Test
    fun `Cardinality rejects exact with max`() {
        assertThrows<IllegalArgumentException> {
            Cardinality(exact = 3, max = 5)
        }
    }

    @Test
    fun `Cardinality allows exact alone`() {
        val card = Cardinality(exact = 3)
        assertEquals(3, card.exact)
    }

    @Test
    fun `getSubClasses returns direct subclasses`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Animal" to OntologyClass(id = "Animal", uri = "http://test.org/Animal"),
                "Dog" to OntologyClass(id = "Dog", uri = "http://test.org/Dog", subClassOf = listOf("Animal")),
                "Cat" to OntologyClass(id = "Cat", uri = "http://test.org/Cat", subClassOf = listOf("Animal")),
                "Poodle" to OntologyClass(id = "Poodle", uri = "http://test.org/Poodle", subClassOf = listOf("Dog"))
            )
        )

        val subClasses = ontology.getSubClasses("Animal")
        assertEquals(2, subClasses.size)
        assertTrue(subClasses.contains("Dog"))
        assertTrue(subClasses.contains("Cat"))
    }

    @Test
    fun `getSubClasses returns empty for leaf class`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Animal" to OntologyClass(id = "Animal", uri = "http://test.org/Animal"),
                "Dog" to OntologyClass(id = "Dog", uri = "http://test.org/Dog", subClassOf = listOf("Animal"))
            )
        )

        assertTrue(ontology.getSubClasses("Dog").isEmpty())
    }

    @Test
    fun `getClassHierarchy returns full upward chain`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Thing" to OntologyClass(id = "Thing", uri = "http://test.org/Thing"),
                "Animal" to OntologyClass(id = "Animal", uri = "http://test.org/Animal", subClassOf = listOf("Thing")),
                "Dog" to OntologyClass(id = "Dog", uri = "http://test.org/Dog", subClassOf = listOf("Animal"))
            )
        )

        val hierarchy = ontology.getClassHierarchy("Dog")
        assertEquals(listOf("Dog", "Animal", "Thing"), hierarchy)
    }

    @Test
    fun `getClassHierarchy handles multiple inheritance`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "A" to OntologyClass(id = "A", uri = "http://test.org/A"),
                "B" to OntologyClass(id = "B", uri = "http://test.org/B"),
                "C" to OntologyClass(id = "C", uri = "http://test.org/C", subClassOf = listOf("A", "B"))
            )
        )

        val hierarchy = ontology.getClassHierarchy("C")
        assertEquals(3, hierarchy.size)
        assertEquals("C", hierarchy[0])
        assertTrue(hierarchy.contains("A"))
        assertTrue(hierarchy.contains("B"))
    }

    @Test
    fun `LangLabel defaults to english`() {
        val label = LangLabel("hello")
        assertEquals("en", label.lang)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.OntologyTest"`
Expected: FAIL — classes not found

- [ ] **Step 3: Implement the data model**

Create `src/main/kotlin/com/agentwork/graphmesh/ontology/Ontology.kt`:

```kotlin
package com.agentwork.graphmesh.ontology

import com.agentwork.graphmesh.rdf.XsdTypes

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
    val range: String = XsdTypes.STRING,
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

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.OntologyTest"`
Expected: All 9 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/ontology/Ontology.kt src/test/kotlin/com/agentwork/graphmesh/ontology/OntologyTest.kt
git commit -m "feat(ontology): add data model with class hierarchy and cardinality validation"
```

---

### Task 3: Ontology Validator + Tests

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/ontology/DefaultOntologyValidator.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/ontology/DefaultOntologyValidatorTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/agentwork/graphmesh/ontology/DefaultOntologyValidatorTest.kt`:

```kotlin
package com.agentwork.graphmesh.ontology

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultOntologyValidatorTest {

    private val validator = DefaultOntologyValidator()

    private fun ontologyWithClasses(vararg classes: Pair<String, OntologyClass>) = Ontology(
        metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
        classes = classes.toMap()
    )

    @Test
    fun `valid ontology produces no errors`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Animal" to OntologyClass(id = "Animal", uri = "http://test.org/Animal"),
                "Dog" to OntologyClass(id = "Dog", uri = "http://test.org/Dog", subClassOf = listOf("Animal"))
            ),
            objectProperties = mapOf(
                "owns" to ObjectProperty(
                    id = "owns", uri = "http://test.org/owns",
                    domain = "Animal", range = "Animal"
                )
            )
        )

        val errors = validator.validate(ontology)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    @Test
    fun `detects circular inheritance - direct cycle`() {
        val ontology = ontologyWithClasses(
            "A" to OntologyClass(id = "A", uri = "http://test.org/A", subClassOf = listOf("B")),
            "B" to OntologyClass(id = "B", uri = "http://test.org/B", subClassOf = listOf("A"))
        )

        val errors = validator.validate(ontology)
        assertTrue(errors.any { it.rule == ValidationRule.CIRCULAR_INHERITANCE })
    }

    @Test
    fun `detects circular inheritance - transitive cycle`() {
        val ontology = ontologyWithClasses(
            "A" to OntologyClass(id = "A", uri = "http://test.org/A", subClassOf = listOf("B")),
            "B" to OntologyClass(id = "B", uri = "http://test.org/B", subClassOf = listOf("C")),
            "C" to OntologyClass(id = "C", uri = "http://test.org/C", subClassOf = listOf("A"))
        )

        val errors = validator.validate(ontology)
        assertTrue(errors.any { it.rule == ValidationRule.CIRCULAR_INHERITANCE })
    }

    @Test
    fun `detects missing domain class on object property`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Animal" to OntologyClass(id = "Animal", uri = "http://test.org/Animal")
            ),
            objectProperties = mapOf(
                "owns" to ObjectProperty(
                    id = "owns", uri = "http://test.org/owns",
                    domain = "NonExistent", range = "Animal"
                )
            )
        )

        val errors = validator.validate(ontology)
        assertTrue(errors.any { it.rule == ValidationRule.MISSING_DOMAIN_CLASS && it.element == "owns" })
    }

    @Test
    fun `detects missing range class on object property`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Animal" to OntologyClass(id = "Animal", uri = "http://test.org/Animal")
            ),
            objectProperties = mapOf(
                "owns" to ObjectProperty(
                    id = "owns", uri = "http://test.org/owns",
                    domain = "Animal", range = "NonExistent"
                )
            )
        )

        val errors = validator.validate(ontology)
        assertTrue(errors.any { it.rule == ValidationRule.MISSING_RANGE_CLASS && it.element == "owns" })
    }

    @Test
    fun `detects missing domain class on datatype property`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            datatypeProperties = mapOf(
                "name" to DatatypeProperty(
                    id = "name", uri = "http://test.org/name",
                    domain = "NonExistent"
                )
            )
        )

        val errors = validator.validate(ontology)
        assertTrue(errors.any { it.rule == ValidationRule.MISSING_DOMAIN_CLASS && it.element == "name" })
    }

    @Test
    fun `detects disjoint subclass conflict`() {
        val ontology = ontologyWithClasses(
            "A" to OntologyClass(id = "A", uri = "http://test.org/A"),
            "B" to OntologyClass(
                id = "B", uri = "http://test.org/B",
                subClassOf = listOf("A"),
                disjointWith = listOf("A")
            )
        )

        val errors = validator.validate(ontology)
        assertTrue(errors.any { it.rule == ValidationRule.DISJOINT_SUBCLASS_CONFLICT && it.element == "B" })
    }

    @Test
    fun `detects functional property with max cardinality greater than 1`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Person" to OntologyClass(id = "Person", uri = "http://test.org/Person")
            ),
            datatypeProperties = mapOf(
                "name" to DatatypeProperty(
                    id = "name", uri = "http://test.org/name",
                    domain = "Person",
                    functional = true,
                    cardinality = Cardinality(max = 3)
                )
            )
        )

        val errors = validator.validate(ontology)
        assertTrue(errors.any { it.rule == ValidationRule.FUNCTIONAL_CARDINALITY_CONFLICT && it.element == "name" })
    }

    @Test
    fun `functional property with max 1 is valid`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(name = "test", namespace = "http://test.org/"),
            classes = mapOf(
                "Person" to OntologyClass(id = "Person", uri = "http://test.org/Person")
            ),
            datatypeProperties = mapOf(
                "name" to DatatypeProperty(
                    id = "name", uri = "http://test.org/name",
                    domain = "Person",
                    functional = true,
                    cardinality = Cardinality(max = 1)
                )
            )
        )

        val errors = validator.validate(ontology)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.DefaultOntologyValidatorTest"`
Expected: FAIL — DefaultOntologyValidator not found

- [ ] **Step 3: Implement the validator**

Create `src/main/kotlin/com/agentwork/graphmesh/ontology/DefaultOntologyValidator.kt`:

```kotlin
package com.agentwork.graphmesh.ontology

import org.springframework.stereotype.Component

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

@Component
class DefaultOntologyValidator {

    fun validate(ontology: Ontology): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        errors.addAll(checkCircularInheritance(ontology))
        errors.addAll(checkDomainRangeReferences(ontology))
        errors.addAll(checkDisjointSubclassConflicts(ontology))
        errors.addAll(checkFunctionalCardinality(ontology))
        return errors
    }

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
                                message = "Zirkuläre Vererbung erkannt: $classId -> ... -> $classId"
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

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.DefaultOntologyValidatorTest"`
Expected: All 9 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/ontology/DefaultOntologyValidator.kt src/test/kotlin/com/agentwork/graphmesh/ontology/DefaultOntologyValidatorTest.kt
git commit -m "feat(ontology): add validator with circular inheritance, domain/range, and cardinality checks"
```

---

### Task 4: OntologyStore + Tests

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/ontology/OntologyStore.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/ontology/OntologyStoreTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/agentwork/graphmesh/ontology/OntologyStoreTest.kt`:

```kotlin
package com.agentwork.graphmesh.ontology

import com.agentwork.graphmesh.config.ConfigItem
import com.agentwork.graphmesh.config.ConfigService
import com.agentwork.graphmesh.config.ConfigType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OntologyStoreTest {

    private val objectMapper = jacksonObjectMapper()
    private val configService = mockk<ConfigService>()
    private val store = OntologyStore(configService, objectMapper)

    private val sampleOntology = Ontology(
        metadata = OntologyMetadata(name = "Animals", namespace = "http://example.org/animals/"),
        classes = mapOf(
            "Animal" to OntologyClass(
                id = "Animal",
                uri = "http://example.org/animals/Animal",
                labels = listOf(LangLabel("Animal", "en"), LangLabel("Tier", "de"))
            ),
            "Dog" to OntologyClass(
                id = "Dog",
                uri = "http://example.org/animals/Dog",
                subClassOf = listOf("Animal")
            )
        ),
        objectProperties = mapOf(
            "eats" to ObjectProperty(
                id = "eats",
                uri = "http://example.org/animals/eats",
                domain = "Animal",
                range = "Animal"
            )
        )
    )

    @Test
    fun `save serializes ontology to JSON and stores as ConfigItem`() {
        val itemSlot = slot<ConfigItem>()
        every { configService.findByTypeAndKey(ConfigType.ONTOLOGY, "animals") } returns null
        every { configService.save(capture(itemSlot)) } answers { itemSlot.captured }

        store.save("animals", sampleOntology)

        val saved = itemSlot.captured
        assertEquals(ConfigType.ONTOLOGY, saved.type)
        assertEquals("animals", saved.key)

        val deserialized = objectMapper.readValue(saved.value, Ontology::class.java)
        assertEquals("Animals", deserialized.metadata.name)
        assertEquals(2, deserialized.classes.size)
        assertEquals(1, deserialized.objectProperties.size)
    }

    @Test
    fun `save reuses existing ConfigItem id on update`() {
        val existingItem = ConfigItem(
            id = "existing-id",
            type = ConfigType.ONTOLOGY,
            key = "animals",
            value = "{}"
        )
        val itemSlot = slot<ConfigItem>()
        every { configService.findByTypeAndKey(ConfigType.ONTOLOGY, "animals") } returns existingItem
        every { configService.save(capture(itemSlot)) } answers { itemSlot.captured }

        store.save("animals", sampleOntology)

        assertEquals("existing-id", itemSlot.captured.id)
    }

    @Test
    fun `load returns ontology when ConfigItem exists`() {
        val json = objectMapper.writeValueAsString(sampleOntology)
        val item = ConfigItem(
            id = "id-1",
            type = ConfigType.ONTOLOGY,
            key = "animals",
            value = json
        )
        every { configService.findByTypeAndKey(ConfigType.ONTOLOGY, "animals") } returns item

        val loaded = store.load("animals")

        assertNotNull(loaded)
        assertEquals("Animals", loaded.metadata.name)
        assertEquals(2, loaded.classes.size)
        assertTrue(loaded.classes.containsKey("Dog"))
    }

    @Test
    fun `load returns null when ConfigItem does not exist`() {
        every { configService.findByTypeAndKey(ConfigType.ONTOLOGY, "nonexistent") } returns null

        assertNull(store.load("nonexistent"))
    }

    @Test
    fun `listKeys returns all ontology keys`() {
        every { configService.findByType(ConfigType.ONTOLOGY) } returns listOf(
            ConfigItem(id = "1", type = ConfigType.ONTOLOGY, key = "animals", value = "{}"),
            ConfigItem(id = "2", type = ConfigType.ONTOLOGY, key = "geography", value = "{}")
        )

        val keys = store.listKeys()
        assertEquals(listOf("animals", "geography"), keys)
    }

    @Test
    fun `delete finds item by type and key then deletes by id`() {
        val item = ConfigItem(id = "id-1", type = ConfigType.ONTOLOGY, key = "animals", value = "{}")
        every { configService.findByTypeAndKey(ConfigType.ONTOLOGY, "animals") } returns item
        every { configService.delete("id-1") } returns Unit

        store.delete("animals")

        verify { configService.delete("id-1") }
    }

    @Test
    fun `delete does nothing when key does not exist`() {
        every { configService.findByTypeAndKey(ConfigType.ONTOLOGY, "nonexistent") } returns null

        store.delete("nonexistent")

        verify(exactly = 0) { configService.delete(any()) }
    }

    @Test
    fun `JSON round-trip preserves all fields including labels and cardinality`() {
        val ontology = Ontology(
            metadata = OntologyMetadata(
                name = "Test",
                description = "A test ontology",
                version = "2.0.0",
                namespace = "http://test.org/",
                imports = listOf("http://other.org/")
            ),
            classes = mapOf(
                "Person" to OntologyClass(
                    id = "Person",
                    uri = "http://test.org/Person",
                    labels = listOf(LangLabel("Person", "en"), LangLabel("Personne", "fr")),
                    comment = "A human being",
                    equivalentClasses = listOf("Human"),
                    disjointWith = listOf("Robot")
                )
            ),
            datatypeProperties = mapOf(
                "age" to DatatypeProperty(
                    id = "age",
                    uri = "http://test.org/age",
                    domain = "Person",
                    range = "http://www.w3.org/2001/XMLSchema#integer",
                    functional = true,
                    cardinality = Cardinality(exact = 1)
                )
            )
        )

        val json = objectMapper.writeValueAsString(ontology)
        val deserialized = objectMapper.readValue(json, Ontology::class.java)

        assertEquals(ontology, deserialized)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.OntologyStoreTest"`
Expected: FAIL — OntologyStore not found

- [ ] **Step 3: Implement OntologyStore**

Create `src/main/kotlin/com/agentwork/graphmesh/ontology/OntologyStore.kt`:

```kotlin
package com.agentwork.graphmesh.ontology

import com.agentwork.graphmesh.config.ConfigItem
import com.agentwork.graphmesh.config.ConfigService
import com.agentwork.graphmesh.config.ConfigType
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OntologyStore(
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper
) {

    fun save(key: String, ontology: Ontology): ConfigItem {
        val json = objectMapper.writeValueAsString(ontology)
        val existing = configService.findByTypeAndKey(ConfigType.ONTOLOGY, key)
        val item = ConfigItem(
            id = existing?.id ?: UUID.randomUUID().toString(),
            type = ConfigType.ONTOLOGY,
            key = key,
            value = json,
            description = ontology.metadata.description ?: ""
        )
        return configService.save(item)
    }

    fun load(key: String): Ontology? {
        val item = configService.findByTypeAndKey(ConfigType.ONTOLOGY, key) ?: return null
        return objectMapper.readValue(item.value, Ontology::class.java)
    }

    fun listKeys(): List<String> =
        configService.findByType(ConfigType.ONTOLOGY).map { it.key }

    fun delete(key: String) {
        val item = configService.findByTypeAndKey(ConfigType.ONTOLOGY, key) ?: return
        configService.delete(item.id)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.OntologyStoreTest"`
Expected: All 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/ontology/OntologyStore.kt src/test/kotlin/com/agentwork/graphmesh/ontology/OntologyStoreTest.kt
git commit -m "feat(ontology): add OntologyStore for JSON persistence via ConfigService"
```

---

### Task 5: OntologyCache + Tests

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/ontology/OntologyCache.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/ontology/OntologyCacheTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/agentwork/graphmesh/ontology/OntologyCacheTest.kt`:

```kotlin
package com.agentwork.graphmesh.ontology

import com.agentwork.graphmesh.config.ConfigAction
import com.agentwork.graphmesh.config.ConfigChangedEvent
import com.agentwork.graphmesh.config.ConfigType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OntologyCacheTest {

    private val store = mockk<OntologyStore>()
    private val cache = OntologyCache(store)

    private val sampleOntology = Ontology(
        metadata = OntologyMetadata(name = "Animals", namespace = "http://example.org/animals/"),
        classes = mapOf(
            "Animal" to OntologyClass(id = "Animal", uri = "http://example.org/animals/Animal")
        )
    )

    @Test
    fun `get loads from store on cache miss`() {
        every { store.load("animals") } returns sampleOntology

        val result = cache.get("animals")

        assertNotNull(result)
        assertEquals("Animals", result.metadata.name)
        verify(exactly = 1) { store.load("animals") }
    }

    @Test
    fun `get returns cached value on second call without hitting store`() {
        every { store.load("animals") } returns sampleOntology

        cache.get("animals")
        val result = cache.get("animals")

        assertNotNull(result)
        assertEquals("Animals", result.metadata.name)
        verify(exactly = 1) { store.load("animals") }
    }

    @Test
    fun `get returns null when store returns null`() {
        every { store.load("nonexistent") } returns null

        assertNull(cache.get("nonexistent"))
    }

    @Test
    fun `onConfigChanged invalidates cache on UPDATED event`() {
        every { store.load("animals") } returns sampleOntology

        cache.get("animals")

        cache.onConfigChanged(ConfigChangedEvent(
            configId = "id-1",
            configType = ConfigType.ONTOLOGY,
            key = "animals",
            action = ConfigAction.UPDATED,
            version = 2
        ))

        cache.get("animals")

        verify(exactly = 2) { store.load("animals") }
    }

    @Test
    fun `onConfigChanged invalidates cache on DELETED event`() {
        every { store.load("animals") } returns sampleOntology

        cache.get("animals")

        cache.onConfigChanged(ConfigChangedEvent(
            configId = "id-1",
            configType = ConfigType.ONTOLOGY,
            key = "animals",
            action = ConfigAction.DELETED,
            version = 1
        ))

        every { store.load("animals") } returns null

        assertNull(cache.get("animals"))
    }

    @Test
    fun `onConfigChanged ignores non-ONTOLOGY events`() {
        every { store.load("animals") } returns sampleOntology

        cache.get("animals")

        cache.onConfigChanged(ConfigChangedEvent(
            configId = "id-1",
            configType = ConfigType.PARAMETER,
            key = "animals",
            action = ConfigAction.UPDATED,
            version = 2
        ))

        cache.get("animals")

        verify(exactly = 1) { store.load("animals") }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.OntologyCacheTest"`
Expected: FAIL — OntologyCache not found

- [ ] **Step 3: Implement OntologyCache**

Create `src/main/kotlin/com/agentwork/graphmesh/ontology/OntologyCache.kt`:

```kotlin
package com.agentwork.graphmesh.ontology

import com.agentwork.graphmesh.config.ConfigChangedEvent
import com.agentwork.graphmesh.config.ConfigType
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class OntologyCache(private val store: OntologyStore) {

    private val cache = ConcurrentHashMap<String, Ontology>()

    fun get(key: String): Ontology? =
        cache[key] ?: store.load(key)?.also { cache[key] = it }

    @EventListener
    fun onConfigChanged(event: ConfigChangedEvent) {
        if (event.configType != ConfigType.ONTOLOGY) return
        cache.remove(event.key)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.OntologyCacheTest"`
Expected: All 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/ontology/OntologyCache.kt src/test/kotlin/com/agentwork/graphmesh/ontology/OntologyCacheTest.kt
git commit -m "feat(ontology): add in-memory cache with Spring EventListener invalidation"
```

---

### Task 6: JenaAdapter + Tests

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/ontology/JenaAdapter.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/ontology/JenaAdapterTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/agentwork/graphmesh/ontology/JenaAdapterTest.kt`:

```kotlin
package com.agentwork.graphmesh.ontology

import com.agentwork.graphmesh.rdf.XsdTypes
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JenaAdapterTest {

    private val adapter = JenaAdapter()

    private val sampleOntology = Ontology(
        metadata = OntologyMetadata(
            name = "Animals",
            namespace = "http://example.org/animals/"
        ),
        classes = mapOf(
            "Animal" to OntologyClass(
                id = "Animal",
                uri = "http://example.org/animals/Animal",
                labels = listOf(LangLabel("Animal", "en"), LangLabel("Tier", "de")),
                comment = "A living organism"
            ),
            "Dog" to OntologyClass(
                id = "Dog",
                uri = "http://example.org/animals/Dog",
                subClassOf = listOf("Animal"),
                labels = listOf(LangLabel("Dog", "en"))
            )
        ),
        objectProperties = mapOf(
            "eats" to ObjectProperty(
                id = "eats",
                uri = "http://example.org/animals/eats",
                domain = "Animal",
                range = "Animal",
                labels = listOf(LangLabel("eats", "en")),
                functional = true
            )
        ),
        datatypeProperties = mapOf(
            "name" to DatatypeProperty(
                id = "name",
                uri = "http://example.org/animals/name",
                domain = "Animal",
                range = XsdTypes.STRING,
                labels = listOf(LangLabel("name", "en")),
                functional = true,
                cardinality = Cardinality(exact = 1)
            )
        )
    )

    @Test
    fun `toJenaModel creates OWL classes with correct types`() {
        val model = adapter.toJenaModel(sampleOntology)

        val animalResource = model.getResource("http://example.org/animals/Animal")
        assertNotNull(animalResource)

        val owlClass = model.getResource("http://www.w3.org/2002/07/owl#Class")
        assertTrue(model.contains(animalResource, model.getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), owlClass))
    }

    @Test
    fun `toJenaModel creates subClassOf relationships`() {
        val model = adapter.toJenaModel(sampleOntology)

        val dogResource = model.getResource("http://example.org/animals/Dog")
        val animalResource = model.getResource("http://example.org/animals/Animal")
        val subClassOf = model.getProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf")

        assertTrue(model.contains(dogResource, subClassOf, animalResource))
    }

    @Test
    fun `toJenaModel creates labels with language tags`() {
        val model = adapter.toJenaModel(sampleOntology)

        val animalResource = model.getResource("http://example.org/animals/Animal")
        val label = model.getProperty("http://www.w3.org/2000/01/rdf-schema#label")

        val labels = model.listStatements(animalResource, label, null as org.apache.jena.rdf.model.RDFNode?).toList()
        assertEquals(2, labels.size)
    }

    @Test
    fun `round-trip Kotlin to Jena to Kotlin preserves classes`() {
        val model = adapter.toJenaModel(sampleOntology)
        val result = adapter.fromJenaModel(model, sampleOntology.metadata)

        assertEquals(sampleOntology.classes.size, result.classes.size)
        assertTrue(result.classes.containsKey("Animal"))
        assertTrue(result.classes.containsKey("Dog"))
        assertEquals(listOf("Animal"), result.classes["Dog"]?.subClassOf)
    }

    @Test
    fun `round-trip preserves object properties`() {
        val model = adapter.toJenaModel(sampleOntology)
        val result = adapter.fromJenaModel(model, sampleOntology.metadata)

        assertEquals(1, result.objectProperties.size)
        val eats = result.objectProperties["eats"]
        assertNotNull(eats)
        assertEquals("Animal", eats.domain)
        assertEquals("Animal", eats.range)
        assertTrue(eats.functional)
    }

    @Test
    fun `round-trip preserves datatype properties`() {
        val model = adapter.toJenaModel(sampleOntology)
        val result = adapter.fromJenaModel(model, sampleOntology.metadata)

        assertEquals(1, result.datatypeProperties.size)
        val name = result.datatypeProperties["name"]
        assertNotNull(name)
        assertEquals("Animal", name.domain)
        assertEquals(XsdTypes.STRING, name.range)
        assertTrue(name.functional)
    }

    @Test
    fun `Turtle serialization round-trip`() {
        val model = adapter.toJenaModel(sampleOntology)
        val turtle = adapter.serializeTurtle(model)

        assertTrue(turtle.contains("owl:Class"))
        assertTrue(turtle.contains("Animal"))

        val parsed = adapter.parseTurtle(turtle)
        val result = adapter.fromJenaModel(parsed, sampleOntology.metadata)

        assertEquals(sampleOntology.classes.size, result.classes.size)
        assertTrue(result.classes.containsKey("Animal"))
        assertTrue(result.classes.containsKey("Dog"))
    }

    @Test
    fun `RDF-XML serialization round-trip`() {
        val model = adapter.toJenaModel(sampleOntology)
        val rdfXml = adapter.serializeRdfXml(model)

        assertTrue(rdfXml.contains("rdf:RDF"))

        val parsed = adapter.parseRdfXml(rdfXml)
        val result = adapter.fromJenaModel(parsed, sampleOntology.metadata)

        assertEquals(sampleOntology.classes.size, result.classes.size)
        assertTrue(result.classes.containsKey("Animal"))
        assertTrue(result.classes.containsKey("Dog"))
    }

    @Test
    fun `parseTurtle parses external Turtle content`() {
        val turtle = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix ex: <http://example.org/> .

            ex:Person a owl:Class ;
                rdfs:label "Person"@en .

            ex:knows a owl:ObjectProperty ;
                rdfs:domain ex:Person ;
                rdfs:range ex:Person .
        """.trimIndent()

        val model = adapter.parseTurtle(turtle)
        val metadata = OntologyMetadata(name = "Test", namespace = "http://example.org/")
        val result = adapter.fromJenaModel(model, metadata)

        assertEquals(1, result.classes.size)
        assertTrue(result.classes.containsKey("Person"))
        assertEquals(1, result.objectProperties.size)
        assertTrue(result.objectProperties.containsKey("knows"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.JenaAdapterTest"`
Expected: FAIL — JenaAdapter not found

- [ ] **Step 3: Implement JenaAdapter**

Create `src/main/kotlin/com/agentwork/graphmesh/ontology/JenaAdapter.kt`:

```kotlin
package com.agentwork.graphmesh.ontology

import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.Resource
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.vocabulary.OWL2
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.RDFS
import org.apache.jena.vocabulary.XSD
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.StringWriter

@Component
class JenaAdapter {

    fun toJenaModel(ontology: Ontology): Model {
        val model = ModelFactory.createDefaultModel()
        model.setNsPrefix("owl", OWL2.NS)
        model.setNsPrefix("rdfs", RDFS.uri)
        model.setNsPrefix("rdf", RDF.uri)
        model.setNsPrefix("xsd", XSD.NS)

        val ns = ontology.metadata.namespace
        if (ns.isNotBlank()) {
            model.setNsPrefix("", ns)
        }

        for ((_, cls) in ontology.classes) {
            val resource = model.createResource(cls.uri)
            resource.addProperty(RDF.type, OWL2.Class)

            for (parentId in cls.subClassOf) {
                ontology.classes[parentId]?.let { parent ->
                    resource.addProperty(RDFS.subClassOf, model.createResource(parent.uri))
                }
            }

            for (eqId in cls.equivalentClasses) {
                ontology.classes[eqId]?.let { eq ->
                    resource.addProperty(OWL2.equivalentClass, model.createResource(eq.uri))
                }
            }

            for (disjId in cls.disjointWith) {
                ontology.classes[disjId]?.let { disj ->
                    resource.addProperty(OWL2.disjointWith, model.createResource(disj.uri))
                }
            }

            addLabelsAndComment(model, resource, cls.labels, cls.comment)
        }

        for ((_, prop) in ontology.objectProperties) {
            val resource = model.createResource(prop.uri)
            resource.addProperty(RDF.type, OWL2.ObjectProperty)

            if (prop.functional) {
                resource.addProperty(RDF.type, OWL2.FunctionalProperty)
            }
            if (prop.inverseFunctional) {
                resource.addProperty(RDF.type, OWL2.InverseFunctionalProperty)
            }

            prop.domain?.let { domainId ->
                ontology.classes[domainId]?.let { domainCls ->
                    resource.addProperty(RDFS.domain, model.createResource(domainCls.uri))
                }
            }
            prop.range?.let { rangeId ->
                ontology.classes[rangeId]?.let { rangeCls ->
                    resource.addProperty(RDFS.range, model.createResource(rangeCls.uri))
                }
            }
            prop.inverseOf?.let { inverseId ->
                ontology.objectProperties[inverseId]?.let { inverseProp ->
                    resource.addProperty(OWL2.inverseOf, model.createResource(inverseProp.uri))
                }
            }

            addLabelsAndComment(model, resource, prop.labels, prop.comment)
        }

        for ((_, prop) in ontology.datatypeProperties) {
            val resource = model.createResource(prop.uri)
            resource.addProperty(RDF.type, OWL2.DatatypeProperty)

            if (prop.functional) {
                resource.addProperty(RDF.type, OWL2.FunctionalProperty)
            }

            prop.domain?.let { domainId ->
                ontology.classes[domainId]?.let { domainCls ->
                    resource.addProperty(RDFS.domain, model.createResource(domainCls.uri))
                }
            }

            resource.addProperty(RDFS.range, model.createResource(prop.range))

            addLabelsAndComment(model, resource, prop.labels, prop.comment)
        }

        return model
    }

    fun fromJenaModel(model: Model, metadata: OntologyMetadata): Ontology {
        val classes = mutableMapOf<String, OntologyClass>()
        val objectProperties = mutableMapOf<String, ObjectProperty>()
        val datatypeProperties = mutableMapOf<String, DatatypeProperty>()

        val classResources = model.listResourcesWithProperty(RDF.type, OWL2.Class)
        while (classResources.hasNext()) {
            val resource = classResources.next()
            val uri = resource.uri ?: continue
            val id = extractId(uri)

            val subClassOf = model.listStatements(resource, RDFS.subClassOf, null as Resource?)
                .toList().mapNotNull { it.`object`.asResource().uri?.let { u -> extractId(u) } }

            val equivalentClasses = model.listStatements(resource, OWL2.equivalentClass, null as Resource?)
                .toList().mapNotNull { it.`object`.asResource().uri?.let { u -> extractId(u) } }

            val disjointWith = model.listStatements(resource, OWL2.disjointWith, null as Resource?)
                .toList().mapNotNull { it.`object`.asResource().uri?.let { u -> extractId(u) } }

            val labels = extractLabels(model, resource)
            val comment = model.listStatements(resource, RDFS.comment, null as org.apache.jena.rdf.model.RDFNode?)
                .toList().firstOrNull()?.`object`?.asLiteral()?.string

            classes[id] = OntologyClass(
                id = id, uri = uri, labels = labels, comment = comment,
                subClassOf = subClassOf, equivalentClasses = equivalentClasses, disjointWith = disjointWith
            )
        }

        val objPropResources = model.listResourcesWithProperty(RDF.type, OWL2.ObjectProperty)
        while (objPropResources.hasNext()) {
            val resource = objPropResources.next()
            val uri = resource.uri ?: continue
            val id = extractId(uri)

            val domain = model.listStatements(resource, RDFS.domain, null as Resource?)
                .toList().firstOrNull()?.`object`?.asResource()?.uri?.let { extractId(it) }
            val range = model.listStatements(resource, RDFS.range, null as Resource?)
                .toList().firstOrNull()?.`object`?.asResource()?.uri?.let { extractId(it) }
            val inverseOf = model.listStatements(resource, OWL2.inverseOf, null as Resource?)
                .toList().firstOrNull()?.`object`?.asResource()?.uri?.let { extractId(it) }
            val functional = model.contains(resource, RDF.type, OWL2.FunctionalProperty)
            val inverseFunctional = model.contains(resource, RDF.type, OWL2.InverseFunctionalProperty)
            val labels = extractLabels(model, resource)
            val comment = model.listStatements(resource, RDFS.comment, null as org.apache.jena.rdf.model.RDFNode?)
                .toList().firstOrNull()?.`object`?.asLiteral()?.string

            objectProperties[id] = ObjectProperty(
                id = id, uri = uri, labels = labels, comment = comment,
                domain = domain, range = range, inverseOf = inverseOf,
                functional = functional, inverseFunctional = inverseFunctional
            )
        }

        val dtPropResources = model.listResourcesWithProperty(RDF.type, OWL2.DatatypeProperty)
        while (dtPropResources.hasNext()) {
            val resource = dtPropResources.next()
            val uri = resource.uri ?: continue
            val id = extractId(uri)

            val domain = model.listStatements(resource, RDFS.domain, null as Resource?)
                .toList().firstOrNull()?.`object`?.asResource()?.uri?.let { extractId(it) }
            val range = model.listStatements(resource, RDFS.range, null as Resource?)
                .toList().firstOrNull()?.`object`?.asResource()?.uri
                ?: "http://www.w3.org/2001/XMLSchema#string"
            val functional = model.contains(resource, RDF.type, OWL2.FunctionalProperty)
            val labels = extractLabels(model, resource)
            val comment = model.listStatements(resource, RDFS.comment, null as org.apache.jena.rdf.model.RDFNode?)
                .toList().firstOrNull()?.`object`?.asLiteral()?.string

            datatypeProperties[id] = DatatypeProperty(
                id = id, uri = uri, labels = labels, comment = comment,
                domain = domain, range = range, functional = functional
            )
        }

        return Ontology(
            metadata = metadata,
            classes = classes,
            objectProperties = objectProperties,
            datatypeProperties = datatypeProperties
        )
    }

    fun parseTurtle(content: String): Model {
        val model = ModelFactory.createDefaultModel()
        ByteArrayInputStream(content.toByteArray()).use { stream ->
            RDFDataMgr.read(model, stream, Lang.TURTLE)
        }
        return model
    }

    fun serializeTurtle(model: Model): String {
        val writer = StringWriter()
        RDFDataMgr.write(writer, model, Lang.TURTLE)
        return writer.toString()
    }

    fun parseRdfXml(content: String): Model {
        val model = ModelFactory.createDefaultModel()
        ByteArrayInputStream(content.toByteArray()).use { stream ->
            RDFDataMgr.read(model, stream, Lang.RDFXML)
        }
        return model
    }

    fun serializeRdfXml(model: Model): String {
        val writer = StringWriter()
        RDFDataMgr.write(writer, model, Lang.RDFXML)
        return writer.toString()
    }

    private fun extractId(uri: String): String {
        val fragment = uri.substringAfterLast('#', "")
        if (fragment.isNotEmpty()) return fragment
        return uri.substringAfterLast('/')
    }

    private fun extractLabels(model: Model, resource: Resource): List<LangLabel> =
        model.listStatements(resource, RDFS.label, null as org.apache.jena.rdf.model.RDFNode?)
            .toList()
            .map { stmt ->
                val literal = stmt.`object`.asLiteral()
                LangLabel(
                    value = literal.string,
                    lang = literal.language.ifEmpty { "en" }
                )
            }

    private fun addLabelsAndComment(model: Model, resource: Resource, labels: List<LangLabel>, comment: String?) {
        for (label in labels) {
            resource.addProperty(RDFS.label, model.createLiteral(label.value, label.lang))
        }
        comment?.let {
            resource.addProperty(RDFS.comment, model.createLiteral(it, "en"))
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.JenaAdapterTest"`
Expected: All 9 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/ontology/JenaAdapter.kt src/test/kotlin/com/agentwork/graphmesh/ontology/JenaAdapterTest.kt
git commit -m "feat(ontology): add JenaAdapter for Turtle and RDF/XML import/export"
```

---

### Task 7: OntologyService + Tests

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/ontology/OntologyService.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/ontology/OntologyServiceTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/agentwork/graphmesh/ontology/OntologyServiceTest.kt`:

```kotlin
package com.agentwork.graphmesh.ontology

import com.agentwork.graphmesh.config.ConfigItem
import com.agentwork.graphmesh.config.ConfigType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.jena.rdf.model.ModelFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OntologyServiceTest {

    private val store = mockk<OntologyStore>(relaxed = true)
    private val cache = mockk<OntologyCache>()
    private val validator = DefaultOntologyValidator()
    private val jenaAdapter = mockk<JenaAdapter>()
    private val service = OntologyService(store, cache, validator, jenaAdapter)

    private val validOntology = Ontology(
        metadata = OntologyMetadata(name = "Test", namespace = "http://test.org/"),
        classes = mapOf(
            "Animal" to OntologyClass(id = "Animal", uri = "http://test.org/Animal"),
            "Dog" to OntologyClass(id = "Dog", uri = "http://test.org/Dog", subClassOf = listOf("Animal"))
        )
    )

    private val invalidOntology = Ontology(
        metadata = OntologyMetadata(name = "Test", namespace = "http://test.org/"),
        classes = mapOf(
            "A" to OntologyClass(id = "A", uri = "http://test.org/A", subClassOf = listOf("B")),
            "B" to OntologyClass(id = "B", uri = "http://test.org/B", subClassOf = listOf("A"))
        )
    )

    @Test
    fun `save validates and stores when no errors`() {
        val configItem = ConfigItem(id = "id-1", type = ConfigType.ONTOLOGY, key = "test", value = "{}")
        every { store.save("test", validOntology) } returns configItem

        val errors = service.save("test", validOntology)

        assertTrue(errors.isEmpty())
        verify { store.save("test", validOntology) }
    }

    @Test
    fun `save returns errors and does not store when validation fails`() {
        val errors = service.save("test", invalidOntology)

        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.rule == ValidationRule.CIRCULAR_INHERITANCE })
        verify(exactly = 0) { store.save(any(), any()) }
    }

    @Test
    fun `get delegates to cache`() {
        every { cache.get("test") } returns validOntology

        val result = service.get("test")

        assertNotNull(result)
        assertEquals("Test", result.metadata.name)
        verify { cache.get("test") }
    }

    @Test
    fun `get returns null when not found`() {
        every { cache.get("nonexistent") } returns null

        assertNull(service.get("nonexistent"))
    }

    @Test
    fun `list delegates to store`() {
        every { store.listKeys() } returns listOf("a", "b", "c")

        assertEquals(listOf("a", "b", "c"), service.list())
    }

    @Test
    fun `delete delegates to store`() {
        service.delete("test")

        verify { store.delete("test") }
    }

    @Test
    fun `importTurtle parses, converts, validates, and saves`() {
        val turtleContent = "@prefix owl: <http://www.w3.org/2002/07/owl#> ."
        val model = ModelFactory.createDefaultModel()

        every { jenaAdapter.parseTurtle(turtleContent) } returns model
        every { jenaAdapter.fromJenaModel(model, any()) } returns validOntology
        val configItem = ConfigItem(id = "id-1", type = ConfigType.ONTOLOGY, key = "test", value = "{}")
        every { store.save("test", validOntology) } returns configItem

        val result = service.importTurtle("test", turtleContent, OntologyMetadata(name = "Test", namespace = "http://test.org/"))

        assertEquals("Test", result.metadata.name)
        verify { store.save("test", validOntology) }
    }

    @Test
    fun `exportTurtle loads, converts, and serializes`() {
        val model = ModelFactory.createDefaultModel()
        val expectedTurtle = "@prefix owl: <http://www.w3.org/2002/07/owl#> ."

        every { cache.get("test") } returns validOntology
        every { jenaAdapter.toJenaModel(validOntology) } returns model
        every { jenaAdapter.serializeTurtle(model) } returns expectedTurtle

        val result = service.exportTurtle("test")

        assertEquals(expectedTurtle, result)
    }

    @Test
    fun `exportTurtle throws when ontology not found`() {
        every { cache.get("nonexistent") } returns null

        assertThrows<IllegalArgumentException> {
            service.exportTurtle("nonexistent")
        }
    }

    @Test
    fun `importRdfXml parses, converts, validates, and saves`() {
        val rdfXmlContent = "<rdf:RDF />"
        val model = ModelFactory.createDefaultModel()

        every { jenaAdapter.parseRdfXml(rdfXmlContent) } returns model
        every { jenaAdapter.fromJenaModel(model, any()) } returns validOntology
        val configItem = ConfigItem(id = "id-1", type = ConfigType.ONTOLOGY, key = "test", value = "{}")
        every { store.save("test", validOntology) } returns configItem

        val result = service.importRdfXml("test", rdfXmlContent, OntologyMetadata(name = "Test", namespace = "http://test.org/"))

        assertEquals("Test", result.metadata.name)
    }

    @Test
    fun `exportRdfXml loads, converts, and serializes`() {
        val model = ModelFactory.createDefaultModel()
        val expectedXml = "<rdf:RDF />"

        every { cache.get("test") } returns validOntology
        every { jenaAdapter.toJenaModel(validOntology) } returns model
        every { jenaAdapter.serializeRdfXml(model) } returns expectedXml

        val result = service.exportRdfXml("test")

        assertEquals(expectedXml, result)
    }

    @Test
    fun `validate delegates to validator`() {
        val errors = service.validate(invalidOntology)

        assertTrue(errors.any { it.rule == ValidationRule.CIRCULAR_INHERITANCE })
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.OntologyServiceTest"`
Expected: FAIL — OntologyService not found

- [ ] **Step 3: Implement OntologyService**

Create `src/main/kotlin/com/agentwork/graphmesh/ontology/OntologyService.kt`:

```kotlin
package com.agentwork.graphmesh.ontology

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class OntologyService(
    private val store: OntologyStore,
    private val cache: OntologyCache,
    private val validator: DefaultOntologyValidator,
    private val jenaAdapter: JenaAdapter
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun save(key: String, ontology: Ontology): List<ValidationError> {
        val errors = validator.validate(ontology)
        if (errors.isNotEmpty()) {
            logger.warn("Ontology '{}' has {} validation errors, not saving", key, errors.size)
            return errors
        }
        store.save(key, ontology)
        logger.info("Ontology '{}' saved (version {})", key, ontology.metadata.version)
        return emptyList()
    }

    fun get(key: String): Ontology? = cache.get(key)

    fun list(): List<String> = store.listKeys()

    fun delete(key: String) {
        store.delete(key)
        logger.info("Ontology '{}' deleted", key)
    }

    fun importTurtle(key: String, content: String, metadata: OntologyMetadata): Ontology {
        val model = jenaAdapter.parseTurtle(content)
        val ontology = jenaAdapter.fromJenaModel(model, metadata)
        val errors = validator.validate(ontology)
        require(errors.isEmpty()) { "Imported Turtle ontology has validation errors: $errors" }
        store.save(key, ontology)
        logger.info("Ontology '{}' imported from Turtle", key)
        return ontology
    }

    fun exportTurtle(key: String): String {
        val ontology = cache.get(key)
            ?: throw IllegalArgumentException("Ontology '$key' not found")
        val model = jenaAdapter.toJenaModel(ontology)
        return jenaAdapter.serializeTurtle(model)
    }

    fun importRdfXml(key: String, content: String, metadata: OntologyMetadata): Ontology {
        val model = jenaAdapter.parseRdfXml(content)
        val ontology = jenaAdapter.fromJenaModel(model, metadata)
        val errors = validator.validate(ontology)
        require(errors.isEmpty()) { "Imported RDF/XML ontology has validation errors: $errors" }
        store.save(key, ontology)
        logger.info("Ontology '{}' imported from RDF/XML", key)
        return ontology
    }

    fun exportRdfXml(key: String): String {
        val ontology = cache.get(key)
            ?: throw IllegalArgumentException("Ontology '$key' not found")
        val model = jenaAdapter.toJenaModel(ontology)
        return jenaAdapter.serializeRdfXml(model)
    }

    fun validate(ontology: Ontology): List<ValidationError> = validator.validate(ontology)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.OntologyServiceTest"`
Expected: All 12 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/ontology/OntologyService.kt src/test/kotlin/com/agentwork/graphmesh/ontology/OntologyServiceTest.kt
git commit -m "feat(ontology): add OntologyService with CRUD, validation, and import/export"
```

---

### Task 8: Full Build Verification

**Files:** None (verification only)

- [ ] **Step 1: Run all ontology tests**

Run: `./gradlew test --tests "com.agentwork.graphmesh.ontology.*"`
Expected: All tests PASS (approximately 53 tests across 6 test classes)

- [ ] **Step 2: Run full project build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit any fixes if needed**

If any tests failed, fix and commit. Otherwise, no commit needed.

---

### Design Notes for Implementers

**Important: OntologyService.importTurtle/importRdfXml signature.**
The spec shows `importTurtle(key: String, content: String): Ontology` but the implementation needs `OntologyMetadata` as a third parameter because metadata (name, namespace, version) cannot be reliably extracted from Turtle/RDF-XML content. The tests and implementation use `importTurtle(key: String, content: String, metadata: OntologyMetadata): Ontology`.

**Jena dependency conflict risk.**
Apache Jena brings in many transitive dependencies. If the build fails with dependency conflicts, add exclusions in `build.gradle.kts`:
```kotlin
implementation("org.apache.jena:apache-jena-libs:5.3.0") {
    exclude(group = "org.slf4j", module = "slf4j-api") // Spring Boot manages SLF4J
}
```

**Jackson and Kotlin data classes.**
Kotlin data classes work with Jackson out of the box via `jackson-module-kotlin` (already in `build.gradle.kts`). No `@JsonProperty` or `@JsonCreator` annotations needed.
