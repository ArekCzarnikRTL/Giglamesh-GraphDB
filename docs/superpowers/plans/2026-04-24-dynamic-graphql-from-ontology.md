# Feature 61: Dynamic GraphQL from Ontology — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically generate a per-collection GraphQL endpoint (`/graphql/{collectionName}`) from the collection's assigned ontology, resolving data from the QuadStore.

**Architecture:** A `DynamicGraphQlSchemaBuilder` reads `Ontology` (classes, object/datatype properties) via `OntologyCache` and builds a `GraphQLSchema` programmatically using graphql-java. The schema is cached in a `DynamicGraphQlRegistry` (ConcurrentHashMap). A `DynamicGraphQlController` serves requests per collection name. Schema generation is triggered at the end of `RdfImportService.importRdf()`.

**Tech Stack:** graphql-java (transitive via spring-boot-starter-graphql), graphql-java-extended-scalars, Kotlin, Spring Boot 4.0.5

---

## File Structure

| File | Responsibility |
|---|---|
| **Create:** `src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/XsdScalarMapping.kt` | XSD URI → GraphQL scalar type mapping incl. custom Date/DateTime/Long scalars |
| **Create:** `src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlRegistry.kt` | Thread-safe schema cache, register/get/remove, event listener for collection delete |
| **Create:** `src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/QuadDataFetcher.kt` | Generic DataFetcher implementations: top-level list, by-id, datatype property, object property |
| **Create:** `src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlSchemaBuilder.kt` | Ontology → GraphQLSchema transformation, schema lifecycle |
| **Create:** `src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlController.kt` | REST controller for `/graphql/{collectionName}` |
| **Create:** `src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/XsdScalarMappingTest.kt` | Unit tests for scalar mapping |
| **Create:** `src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlRegistryTest.kt` | Unit tests for registry |
| **Create:** `src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/QuadDataFetcherTest.kt` | Unit tests for resolver logic |
| **Create:** `src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlSchemaBuilderTest.kt` | Unit tests for schema generation |
| **Create:** `src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlControllerTest.kt` | Unit tests for controller |
| **Modify:** `build.gradle.kts` | Add graphql-java-extended-scalars dependency |
| **Modify:** `src/main/kotlin/com/agentwork/graphmesh/api/CorsConfig.kt:10` | Expand CORS mapping to `/graphql/**` |
| **Modify:** `src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt:20-96` | Inject `DynamicGraphQlSchemaBuilder`, call `rebuildIfOntologyAssigned()` after import |

---

### Task 1: Add dependency and create XsdScalarMapping

**Files:**
- Modify: `build.gradle.kts:39-73`
- Create: `src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/XsdScalarMapping.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/XsdScalarMappingTest.kt`

- [ ] **Step 1: Add graphql-java-extended-scalars to build.gradle.kts**

In the `dependencies` block, after the `apache-jena-libs` line (line 57), add:

```kotlin
implementation("com.graphql-java:graphql-java-extended-scalars:22.0")
```

- [ ] **Step 2: Write the failing test for XsdScalarMapping**

```kotlin
package com.agentwork.graphmesh.dynamicgraphql

import graphql.Scalars
import graphql.scalars.ExtendedScalars
import kotlin.test.Test
import kotlin.test.assertEquals

class XsdScalarMappingTest {

    @Test
    fun `maps xsd string to GraphQL String`() {
        val result = XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#string")
        assertEquals(Scalars.GraphQLString, result)
    }

    @Test
    fun `maps xsd integer to GraphQL Int`() {
        val result = XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#integer")
        assertEquals(Scalars.GraphQLInt, result)
    }

    @Test
    fun `maps xsd long to ExtendedScalars GraphQLLong`() {
        val result = XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#long")
        assertEquals(ExtendedScalars.GraphQLLong, result)
    }

    @Test
    fun `maps xsd boolean to GraphQL Boolean`() {
        val result = XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#boolean")
        assertEquals(Scalars.GraphQLBoolean, result)
    }

    @Test
    fun `maps xsd float to GraphQL Float`() {
        val result = XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#float")
        assertEquals(Scalars.GraphQLFloat, result)
    }

    @Test
    fun `maps xsd double to GraphQL Float`() {
        val result = XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#double")
        assertEquals(Scalars.GraphQLFloat, result)
    }

    @Test
    fun `maps xsd anyURI to GraphQL ID`() {
        val result = XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#anyURI")
        assertEquals(Scalars.GraphQLID, result)
    }

    @Test
    fun `maps xsd date to ExtendedScalars Date`() {
        val result = XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#date")
        assertEquals(ExtendedScalars.Date, result)
    }

    @Test
    fun `maps xsd dateTime to ExtendedScalars DateTime`() {
        val result = XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#dateTime")
        assertEquals(ExtendedScalars.DateTime, result)
    }

    @Test
    fun `maps unknown XSD type to GraphQL String`() {
        val result = XsdScalarMapping.resolve("http://www.w3.org/2001/XMLSchema#hexBinary")
        assertEquals(Scalars.GraphQLString, result)
    }

    @Test
    fun `maps rdfs Literal to GraphQL String`() {
        val result = XsdScalarMapping.resolve("http://www.w3.org/2000/01/rdf-schema#Literal")
        assertEquals(Scalars.GraphQLString, result)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.dynamicgraphql.XsdScalarMappingTest"`
Expected: Compilation error — `XsdScalarMapping` does not exist.

- [ ] **Step 4: Implement XsdScalarMapping**

```kotlin
package com.agentwork.graphmesh.dynamicgraphql

import graphql.Scalars
import graphql.scalars.ExtendedScalars
import graphql.schema.GraphQLScalarType

object XsdScalarMapping {

    private val mapping: Map<String, GraphQLScalarType> = mapOf(
        "http://www.w3.org/2001/XMLSchema#string" to Scalars.GraphQLString,
        "http://www.w3.org/2001/XMLSchema#integer" to Scalars.GraphQLInt,
        "http://www.w3.org/2001/XMLSchema#int" to Scalars.GraphQLInt,
        "http://www.w3.org/2001/XMLSchema#short" to Scalars.GraphQLInt,
        "http://www.w3.org/2001/XMLSchema#byte" to Scalars.GraphQLInt,
        "http://www.w3.org/2001/XMLSchema#long" to ExtendedScalars.GraphQLLong,
        "http://www.w3.org/2001/XMLSchema#float" to Scalars.GraphQLFloat,
        "http://www.w3.org/2001/XMLSchema#double" to Scalars.GraphQLFloat,
        "http://www.w3.org/2001/XMLSchema#decimal" to Scalars.GraphQLFloat,
        "http://www.w3.org/2001/XMLSchema#boolean" to Scalars.GraphQLBoolean,
        "http://www.w3.org/2001/XMLSchema#anyURI" to Scalars.GraphQLID,
        "http://www.w3.org/2001/XMLSchema#date" to ExtendedScalars.Date,
        "http://www.w3.org/2001/XMLSchema#dateTime" to ExtendedScalars.DateTime,
        "http://www.w3.org/2000/01/rdf-schema#Literal" to Scalars.GraphQLString,
    )

    fun resolve(xsdUri: String): GraphQLScalarType = mapping[xsdUri] ?: Scalars.GraphQLString

    fun isGraphQLInputType(xsdUri: String): Boolean = mapping.containsKey(xsdUri)
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.dynamicgraphql.XsdScalarMappingTest"`
Expected: All 11 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts \
  src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/XsdScalarMapping.kt \
  src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/XsdScalarMappingTest.kt
git commit -m "feat(dynamicgraphql): add XsdScalarMapping with extended-scalars dependency"
```

---

### Task 2: Create DynamicGraphQlRegistry

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlRegistry.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlRegistryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.agentwork.graphmesh.dynamicgraphql

import graphql.GraphQL
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicGraphQlRegistryTest {

    private val registry = DynamicGraphQlRegistry()

    private fun dummySchema(): GraphQLSchema {
        val queryType = GraphQLObjectType.newObject()
            .name("Query")
            .field { it.name("dummy").type(graphql.Scalars.GraphQLString) }
            .build()
        return GraphQLSchema.newSchema().query(queryType).build()
    }

    @Test
    fun `register and get returns GraphQL instance`() {
        registry.register("col-1", dummySchema())
        val result = registry.get("col-1")
        assertNotNull(result)
    }

    @Test
    fun `get returns null for unknown collection`() {
        assertNull(registry.get("unknown"))
    }

    @Test
    fun `has returns true after register`() {
        registry.register("col-1", dummySchema())
        assertTrue(registry.has("col-1"))
    }

    @Test
    fun `has returns false for unknown collection`() {
        assertFalse(registry.has("unknown"))
    }

    @Test
    fun `remove clears entry`() {
        registry.register("col-1", dummySchema())
        registry.remove("col-1")
        assertNull(registry.get("col-1"))
        assertFalse(registry.has("col-1"))
    }

    @Test
    fun `register replaces existing schema`() {
        registry.register("col-1", dummySchema())
        val first = registry.get("col-1")
        registry.register("col-1", dummySchema())
        val second = registry.get("col-1")
        assertNotNull(first)
        assertNotNull(second)
        // Different instances because dummySchema() creates new schema each time
        assertTrue(first !== second)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.dynamicgraphql.DynamicGraphQlRegistryTest"`
Expected: Compilation error — `DynamicGraphQlRegistry` does not exist.

- [ ] **Step 3: Implement DynamicGraphQlRegistry**

```kotlin
package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.collection.CollectionEvent
import com.agentwork.graphmesh.collection.CollectionEventType
import graphql.GraphQL
import graphql.schema.GraphQLSchema
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class DynamicGraphQlRegistry {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val schemas = ConcurrentHashMap<String, GraphQL>()

    fun register(collectionId: String, schema: GraphQLSchema) {
        schemas[collectionId] = GraphQL.newGraphQL(schema).build()
        logger.info("Dynamic GraphQL schema registered for collection '{}'", collectionId)
    }

    fun get(collectionId: String): GraphQL? = schemas[collectionId]

    fun remove(collectionId: String) {
        if (schemas.remove(collectionId) != null) {
            logger.info("Dynamic GraphQL schema removed for collection '{}'", collectionId)
        }
    }

    fun has(collectionId: String): Boolean = schemas.containsKey(collectionId)

    @EventListener
    fun onCollectionDeleted(event: CollectionEvent) {
        if (event.type == CollectionEventType.DELETED) {
            remove(event.collectionId)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.dynamicgraphql.DynamicGraphQlRegistryTest"`
Expected: All 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlRegistry.kt \
  src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlRegistryTest.kt
git commit -m "feat(dynamicgraphql): add DynamicGraphQlRegistry with collection-delete listener"
```

---

### Task 3: Create QuadDataFetcher

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/QuadDataFetcher.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/QuadDataFetcherTest.kt`

- [ ] **Step 1: Write the failing test for literal value conversion**

```kotlin
package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.rdf.XsdTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuadDataFetcherTest {

    @Test
    fun `convertLiteral parses xsd string`() {
        val result = QuadDataFetcher.convertLiteral("hello", XsdTypes.STRING)
        assertEquals("hello", result)
    }

    @Test
    fun `convertLiteral parses xsd integer`() {
        val result = QuadDataFetcher.convertLiteral("42", XsdTypes.INTEGER)
        assertEquals(42, result)
    }

    @Test
    fun `convertLiteral parses xsd long`() {
        val result = QuadDataFetcher.convertLiteral("9999999999", XsdTypes.LONG)
        assertEquals(9999999999L, result)
    }

    @Test
    fun `convertLiteral parses xsd boolean true`() {
        val result = QuadDataFetcher.convertLiteral("true", XsdTypes.BOOLEAN)
        assertEquals(true, result)
    }

    @Test
    fun `convertLiteral parses xsd boolean false`() {
        val result = QuadDataFetcher.convertLiteral("false", XsdTypes.BOOLEAN)
        assertEquals(false, result)
    }

    @Test
    fun `convertLiteral parses xsd double`() {
        val result = QuadDataFetcher.convertLiteral("3.14", XsdTypes.DOUBLE)
        assertEquals(3.14, result)
    }

    @Test
    fun `convertLiteral parses xsd float`() {
        val result = QuadDataFetcher.convertLiteral("2.5", XsdTypes.FLOAT)
        assertEquals(2.5, result as Double, 0.01)
    }

    @Test
    fun `convertLiteral returns string for unknown datatype`() {
        val result = QuadDataFetcher.convertLiteral("abc", "http://example.org/custom")
        assertEquals("abc", result)
    }

    @Test
    fun `convertLiteral returns null for invalid integer`() {
        val result = QuadDataFetcher.convertLiteral("notanumber", XsdTypes.INTEGER)
        assertNull(result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.dynamicgraphql.QuadDataFetcherTest"`
Expected: Compilation error — `QuadDataFetcher` does not exist.

- [ ] **Step 3: Implement QuadDataFetcher**

```kotlin
package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.rdf.XsdTypes
import com.agentwork.graphmesh.storage.ObjectType
import com.agentwork.graphmesh.storage.QuadQuery
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.RDF_TYPE_URI
import com.agentwork.graphmesh.storage.StoredQuad
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment

object QuadDataFetcher {

    fun convertLiteral(value: String, datatype: String): Any? = when (datatype) {
        XsdTypes.STRING, "" -> value
        XsdTypes.INTEGER -> value.toIntOrNull()
        XsdTypes.LONG -> value.toLongOrNull()
        XsdTypes.FLOAT, XsdTypes.DOUBLE -> value.toDoubleOrNull()
        XsdTypes.BOOLEAN -> value.toBooleanStrictOrNull()
        XsdTypes.DATE, XsdTypes.DATE_TIME, XsdTypes.ANY_URI -> value
        else -> value
    }

    fun topLevelListFetcher(
        quadStore: QuadStore,
        collectionId: String,
        classUri: String,
        datatypeProperties: Map<String, String>,
    ): DataFetcher<List<Map<String, Any>>> = DataFetcher { env ->
        val limit = env.getArgumentOrDefault("limit", 20)
        val offset = env.getArgumentOrDefault("offset", 0)
        val filter: Map<String, Any>? = env.getArgument("filter")

        val typeQuads = quadStore.query(
            collectionId,
            QuadQuery(predicate = RDF_TYPE_URI, objectValue = classUri)
        )
        var subjectUris = typeQuads.map { it.subject }.distinct()

        if (filter != null && filter.isNotEmpty()) {
            subjectUris = applyFilter(quadStore, collectionId, subjectUris, filter, datatypeProperties)
        }

        subjectUris
            .drop(offset)
            .take(limit)
            .map { uri -> mapOf<String, Any>("id" to uri, "_collectionId" to collectionId) }
    }

    fun topLevelByIdFetcher(
        quadStore: QuadStore,
        collectionId: String,
        classUri: String,
    ): DataFetcher<Map<String, Any>?> = DataFetcher { env ->
        val id: String = env.getArgument("id")
        val typeQuads = quadStore.query(
            collectionId,
            QuadQuery(subject = id, predicate = RDF_TYPE_URI, objectValue = classUri)
        )
        if (typeQuads.isNotEmpty()) {
            mapOf<String, Any>("id" to id, "_collectionId" to collectionId)
        } else {
            null
        }
    }

    fun datatypePropertyFetcher(
        quadStore: QuadStore,
        propertyUri: String,
        datatype: String,
        functional: Boolean,
    ): DataFetcher<Any?> = DataFetcher { env ->
        val parent = env.getSource<Map<String, Any>>()
        val subjectUri = parent["id"] as String
        val collectionId = parent["_collectionId"] as String

        val quads = quadStore.query(
            collectionId,
            QuadQuery(subject = subjectUri, predicate = propertyUri)
        )

        if (functional) {
            quads.firstOrNull()?.let { convertLiteral(it.objectValue, datatype) }
        } else {
            quads.mapNotNull { convertLiteral(it.objectValue, datatype) }
        }
    }

    fun objectPropertyFetcher(
        quadStore: QuadStore,
        propertyUri: String,
        functional: Boolean,
    ): DataFetcher<Any?> = DataFetcher { env ->
        val parent = env.getSource<Map<String, Any>>()
        val subjectUri = parent["id"] as String
        val collectionId = parent["_collectionId"] as String

        val quads = quadStore.query(
            collectionId,
            QuadQuery(subject = subjectUri, predicate = propertyUri)
        ).filter { it.objectType == ObjectType.URI }

        val objectUris = quads.map { it.objectValue }.distinct()

        if (functional) {
            objectUris.firstOrNull()?.let { uri ->
                mapOf<String, Any>("id" to uri, "_collectionId" to collectionId)
            }
        } else {
            val limit = env.getArgumentOrDefault("limit", 10)
            val offset = env.getArgumentOrDefault("offset", 0)
            objectUris
                .drop(offset)
                .take(limit)
                .map { uri -> mapOf<String, Any>("id" to uri, "_collectionId" to collectionId) }
        }
    }

    private fun applyFilter(
        quadStore: QuadStore,
        collectionId: String,
        subjectUris: List<String>,
        filter: Map<String, Any>,
        datatypeProperties: Map<String, String>,
    ): List<String> {
        var filtered = subjectUris
        for ((fieldName, filterValue) in filter) {
            val propertyUri = datatypeProperties[fieldName] ?: continue
            filtered = filtered.filter { uri ->
                val quads = quadStore.query(
                    collectionId,
                    QuadQuery(subject = uri, predicate = propertyUri, objectValue = filterValue.toString())
                )
                quads.isNotEmpty()
            }
        }
        return filtered
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.dynamicgraphql.QuadDataFetcherTest"`
Expected: All 9 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/QuadDataFetcher.kt \
  src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/QuadDataFetcherTest.kt
git commit -m "feat(dynamicgraphql): add QuadDataFetcher with literal conversion and resolver factories"
```

---

### Task 4: Create DynamicGraphQlSchemaBuilder

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlSchemaBuilder.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlSchemaBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.collection.CollectionOntologyRecord
import com.agentwork.graphmesh.collection.CollectionOntologyService
import com.agentwork.graphmesh.ontology.DatatypeProperty
import com.agentwork.graphmesh.ontology.ObjectProperty
import com.agentwork.graphmesh.ontology.Ontology
import com.agentwork.graphmesh.ontology.OntologyCache
import com.agentwork.graphmesh.ontology.OntologyClass
import com.agentwork.graphmesh.ontology.OntologyMetadata
import com.agentwork.graphmesh.rdf.XsdTypes
import com.agentwork.graphmesh.storage.QuadStore
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicGraphQlSchemaBuilderTest {

    private val quadStore = mockk<QuadStore>()
    private val ontologyCache = mockk<OntologyCache>()
    private val collectionOntologyService = mockk<CollectionOntologyService>()
    private val registry = DynamicGraphQlRegistry()

    private val builder = DynamicGraphQlSchemaBuilder(
        quadStore, ontologyCache, collectionOntologyService, registry
    )

    private val personOntology = Ontology(
        metadata = OntologyMetadata(
            name = "test", namespace = "http://example.org/", version = "1.0"
        ),
        classes = mapOf(
            "Person" to OntologyClass(
                id = "Person", uri = "http://example.org/Person"
            ),
            "Company" to OntologyClass(
                id = "Company", uri = "http://example.org/Company"
            ),
        ),
        objectProperties = mapOf(
            "worksAt" to ObjectProperty(
                id = "worksAt",
                uri = "http://example.org/worksAt",
                domain = "Person",
                range = "Company",
                functional = false,
            ),
        ),
        datatypeProperties = mapOf(
            "name" to DatatypeProperty(
                id = "name",
                uri = "http://example.org/name",
                domain = "Person",
                range = XsdTypes.STRING,
                functional = true,
            ),
            "age" to DatatypeProperty(
                id = "age",
                uri = "http://example.org/age",
                domain = "Person",
                range = XsdTypes.INTEGER,
                functional = true,
            ),
            "companyName" to DatatypeProperty(
                id = "companyName",
                uri = "http://example.org/companyName",
                domain = "Company",
                range = XsdTypes.STRING,
                functional = true,
            ),
        ),
    )

    @Test
    fun `rebuildIfOntologyAssigned builds schema when ontology is assigned`() {
        every { collectionOntologyService.listForCollection("col-1") } returns listOf(
            CollectionOntologyRecord("col-1", "test", "primary", Instant.now(), "user")
        )
        every { ontologyCache.get("test") } returns personOntology

        builder.rebuildIfOntologyAssigned("col-1")

        assertTrue(registry.has("col-1"))
    }

    @Test
    fun `rebuildIfOntologyAssigned skips when no ontology assigned`() {
        every { collectionOntologyService.listForCollection("col-1") } returns emptyList()

        builder.rebuildIfOntologyAssigned("col-1")

        assertTrue(!registry.has("col-1"))
    }

    @Test
    fun `generated schema has top-level query per class`() {
        every { collectionOntologyService.listForCollection("col-1") } returns listOf(
            CollectionOntologyRecord("col-1", "test", "primary", Instant.now(), "user")
        )
        every { ontologyCache.get("test") } returns personOntology

        builder.rebuildIfOntologyAssigned("col-1")

        val graphql = registry.get("col-1")!!
        val schema = graphql.graphQLSchema
        val queryType = schema.queryType

        assertNotNull(queryType.getFieldDefinition("Person"))
        assertNotNull(queryType.getFieldDefinition("PersonById"))
        assertNotNull(queryType.getFieldDefinition("Company"))
        assertNotNull(queryType.getFieldDefinition("CompanyById"))
    }

    @Test
    fun `Person type has expected fields`() {
        every { collectionOntologyService.listForCollection("col-1") } returns listOf(
            CollectionOntologyRecord("col-1", "test", "primary", Instant.now(), "user")
        )
        every { ontologyCache.get("test") } returns personOntology

        builder.rebuildIfOntologyAssigned("col-1")

        val schema = registry.get("col-1")!!.graphQLSchema
        val personType = schema.getObjectType("Person")
        assertNotNull(personType)

        val idField = personType.getFieldDefinition("id")
        assertNotNull(idField)

        val nameField = personType.getFieldDefinition("name")
        assertNotNull(nameField)

        val ageField = personType.getFieldDefinition("age")
        assertNotNull(ageField)

        val worksAtField = personType.getFieldDefinition("worksAt")
        assertNotNull(worksAtField)
    }

    @Test
    fun `rebuildIfOntologyAssigned replaces existing schema`() {
        every { collectionOntologyService.listForCollection("col-1") } returns listOf(
            CollectionOntologyRecord("col-1", "test", "primary", Instant.now(), "user")
        )
        every { ontologyCache.get("test") } returns personOntology

        builder.rebuildIfOntologyAssigned("col-1")
        val first = registry.get("col-1")

        builder.rebuildIfOntologyAssigned("col-1")
        val second = registry.get("col-1")

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first !== second)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.dynamicgraphql.DynamicGraphQlSchemaBuilderTest"`
Expected: Compilation error — `DynamicGraphQlSchemaBuilder` does not exist.

- [ ] **Step 3: Implement DynamicGraphQlSchemaBuilder**

```kotlin
package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.collection.CollectionOntologyService
import com.agentwork.graphmesh.ontology.DatatypeProperty
import com.agentwork.graphmesh.ontology.ObjectProperty
import com.agentwork.graphmesh.ontology.Ontology
import com.agentwork.graphmesh.ontology.OntologyCache
import com.agentwork.graphmesh.ontology.OntologyClass
import com.agentwork.graphmesh.storage.QuadStore
import graphql.Scalars
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeReference
import graphql.schema.FieldCoordinates
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class DynamicGraphQlSchemaBuilder(
    private val quadStore: QuadStore,
    private val ontologyCache: OntologyCache,
    private val collectionOntologyService: CollectionOntologyService,
    private val registry: DynamicGraphQlRegistry,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun rebuildIfOntologyAssigned(collectionId: String) {
        val assignments = collectionOntologyService.listForCollection(collectionId)
        if (assignments.isEmpty()) return

        val ontologyKey = assignments.first().ontologyKey
        val ontology = ontologyCache.get(ontologyKey)
        if (ontology == null) {
            logger.warn("Ontology '{}' not found for collection '{}'", ontologyKey, collectionId)
            return
        }

        val schema = buildSchema(collectionId, ontology)
        registry.register(collectionId, schema)
    }

    private fun buildSchema(collectionId: String, ontology: Ontology): GraphQLSchema {
        val objectTypes = mutableMapOf<String, GraphQLObjectType>()
        val filterTypes = mutableMapOf<String, GraphQLInputObjectType>()
        val codeRegistry = GraphQLCodeRegistry.newCodeRegistry()

        val propertiesByDomain = groupPropertiesByDomain(ontology)

        for ((classId, ontologyClass) in ontology.classes) {
            val className = classId
            val props = propertiesByDomain[classId]

            val filterType = buildFilterType(className, props?.datatypeProperties ?: emptyList())
            if (filterType != null) filterTypes[className] = filterType

            val objectType = buildObjectType(className, props, objectTypes)
            objectTypes[className] = objectType

            registerFieldFetchers(
                codeRegistry, collectionId, className, ontologyClass.uri,
                props, filterType
            )
        }

        val queryType = buildQueryType(ontology, objectTypes, filterTypes)
        registerQueryFetchers(codeRegistry, collectionId, ontology, filterTypes)

        return GraphQLSchema.newSchema()
            .query(queryType)
            .codeRegistry(codeRegistry.build())
            .additionalTypes(objectTypes.values.toSet())
            .build()
    }

    private data class ClassProperties(
        val datatypeProperties: List<DatatypeProperty>,
        val objectProperties: List<ObjectProperty>,
    )

    private fun groupPropertiesByDomain(ontology: Ontology): Map<String, ClassProperties> {
        val result = mutableMapOf<String, ClassProperties>()
        val dtByDomain = ontology.datatypeProperties.values.groupBy { it.domain }
        val opByDomain = ontology.objectProperties.values.groupBy { it.domain }

        for (classId in ontology.classes.keys) {
            result[classId] = ClassProperties(
                datatypeProperties = dtByDomain[classId] ?: emptyList(),
                objectProperties = opByDomain[classId] ?: emptyList(),
            )
        }
        return result
    }

    private fun buildObjectType(
        className: String,
        props: ClassProperties?,
        existingTypes: Map<String, GraphQLObjectType>,
    ): GraphQLObjectType {
        val builder = GraphQLObjectType.newObject().name(className)
        builder.field(
            GraphQLFieldDefinition.newFieldDefinition()
                .name("id")
                .type(GraphQLNonNull.nonNull(Scalars.GraphQLID))
        )

        props?.datatypeProperties?.forEach { dp ->
            val scalarType = XsdScalarMapping.resolve(dp.range)
            builder.field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name(dp.id)
                    .type(if (dp.functional) scalarType else GraphQLList.list(scalarType))
            )
        }

        props?.objectProperties?.forEach { op ->
            val targetTypeName = op.range ?: return@forEach
            val fieldBuilder = GraphQLFieldDefinition.newFieldDefinition().name(op.id)
            if (op.functional) {
                fieldBuilder.type(GraphQLTypeReference(targetTypeName))
            } else {
                fieldBuilder.type(GraphQLList.list(GraphQLTypeReference(targetTypeName)))
                fieldBuilder.argument(GraphQLArgument.newArgument().name("limit").type(Scalars.GraphQLInt).defaultValueLiteral(graphql.language.IntValue.of(10)).build())
                fieldBuilder.argument(GraphQLArgument.newArgument().name("offset").type(Scalars.GraphQLInt).defaultValueLiteral(graphql.language.IntValue.of(0)).build())
            }
            builder.field(fieldBuilder)
        }

        return builder.build()
    }

    private fun buildFilterType(
        className: String,
        datatypeProperties: List<DatatypeProperty>,
    ): GraphQLInputObjectType? {
        if (datatypeProperties.isEmpty()) return null
        val builder = GraphQLInputObjectType.newInputObject().name("${className}Filter")
        datatypeProperties.forEach { dp ->
            builder.field(
                GraphQLInputObjectField.newInputObjectField()
                    .name(dp.id)
                    .type(XsdScalarMapping.resolve(dp.range))
            )
        }
        return builder.build()
    }

    private fun buildQueryType(
        ontology: Ontology,
        objectTypes: Map<String, GraphQLObjectType>,
        filterTypes: Map<String, GraphQLInputObjectType>,
    ): GraphQLObjectType {
        val queryBuilder = GraphQLObjectType.newObject().name("Query")

        for ((classId, _) in ontology.classes) {
            val objectType = objectTypes[classId] ?: continue

            val listFieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
                .name(classId)
                .type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(objectType))))
                .argument(GraphQLArgument.newArgument().name("limit").type(Scalars.GraphQLInt).defaultValueLiteral(graphql.language.IntValue.of(20)).build())
                .argument(GraphQLArgument.newArgument().name("offset").type(Scalars.GraphQLInt).defaultValueLiteral(graphql.language.IntValue.of(0)).build())

            filterTypes[classId]?.let { filterType ->
                listFieldBuilder.argument(
                    GraphQLArgument.newArgument().name("filter").type(filterType)
                )
            }

            queryBuilder.field(listFieldBuilder)

            queryBuilder.field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name("${classId}ById")
                    .type(objectType)
                    .argument(GraphQLArgument.newArgument().name("id").type(GraphQLNonNull.nonNull(Scalars.GraphQLID)))
            )
        }

        return queryBuilder.build()
    }

    private fun registerQueryFetchers(
        codeRegistry: GraphQLCodeRegistry.Builder,
        collectionId: String,
        ontology: Ontology,
        filterTypes: Map<String, GraphQLInputObjectType>,
    ) {
        for ((classId, ontologyClass) in ontology.classes) {
            val datatypeProps = ontology.datatypeProperties.values
                .filter { it.domain == classId }
                .associate { it.id to it.uri }

            codeRegistry.dataFetcher(
                FieldCoordinates.coordinates("Query", classId),
                QuadDataFetcher.topLevelListFetcher(quadStore, collectionId, ontologyClass.uri, datatypeProps)
            )
            codeRegistry.dataFetcher(
                FieldCoordinates.coordinates("Query", "${classId}ById"),
                QuadDataFetcher.topLevelByIdFetcher(quadStore, collectionId, ontologyClass.uri)
            )
        }
    }

    private fun registerFieldFetchers(
        codeRegistry: GraphQLCodeRegistry.Builder,
        collectionId: String,
        className: String,
        classUri: String,
        props: ClassProperties?,
        filterType: GraphQLInputObjectType?,
    ) {
        props?.datatypeProperties?.forEach { dp ->
            codeRegistry.dataFetcher(
                FieldCoordinates.coordinates(className, dp.id),
                QuadDataFetcher.datatypePropertyFetcher(quadStore, dp.uri, dp.range, dp.functional)
            )
        }
        props?.objectProperties?.forEach { op ->
            codeRegistry.dataFetcher(
                FieldCoordinates.coordinates(className, op.id),
                QuadDataFetcher.objectPropertyFetcher(quadStore, op.uri, op.functional)
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.dynamicgraphql.DynamicGraphQlSchemaBuilderTest"`
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlSchemaBuilder.kt \
  src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlSchemaBuilderTest.kt
git commit -m "feat(dynamicgraphql): add DynamicGraphQlSchemaBuilder — ontology to GraphQL schema"
```

---

### Task 5: Create DynamicGraphQlController

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlController.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlControllerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.collection.Collection
import com.agentwork.graphmesh.collection.CollectionService
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicGraphQlControllerTest {

    private val registry = DynamicGraphQlRegistry()
    private val collectionService = mockk<CollectionService>()
    private val controller = DynamicGraphQlController(registry, collectionService)

    private fun registerDummySchema(collectionId: String) {
        val queryType = GraphQLObjectType.newObject()
            .name("Query")
            .field { it.name("hello").type(graphql.Scalars.GraphQLString) }
            .build()
        val schema = GraphQLSchema.newSchema()
            .query(queryType)
            .codeRegistry(
                graphql.schema.GraphQLCodeRegistry.newCodeRegistry()
                    .dataFetcher(
                        graphql.schema.FieldCoordinates.coordinates("Query", "hello"),
                        graphql.schema.DataFetcher { "world" }
                    ).build()
            ).build()
        registry.register(collectionId, schema)
    }

    @Test
    fun `returns 404 when collection not found`() {
        every { collectionService.findByName("unknown") } returns null
        val body = mapOf("query" to "{ hello }")
        val response = controller.execute("unknown", body)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `returns 404 when no schema registered for collection`() {
        every { collectionService.findByName("test") } returns Collection(id = "col-1", name = "test")
        val body = mapOf("query" to "{ hello }")
        val response = controller.execute("test", body)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `returns 200 with data for valid query`() {
        every { collectionService.findByName("test") } returns Collection(id = "col-1", name = "test")
        registerDummySchema("col-1")

        val body = mapOf("query" to "{ hello }")
        val response = controller.execute("test", body)

        assertEquals(HttpStatus.OK, response.statusCode)
        val responseBody = response.body!!
        @Suppress("UNCHECKED_CAST")
        val data = responseBody["data"] as Map<String, Any>
        assertEquals("world", data["hello"])
    }

    @Test
    fun `returns 400 when query is missing`() {
        every { collectionService.findByName("test") } returns Collection(id = "col-1", name = "test")
        registerDummySchema("col-1")

        val body = emptyMap<String, Any>()
        val response = controller.execute("test", body)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.agentwork.graphmesh.dynamicgraphql.DynamicGraphQlControllerTest"`
Expected: Compilation error — `DynamicGraphQlController` does not exist.

- [ ] **Step 3: Implement DynamicGraphQlController**

```kotlin
package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.collection.CollectionService
import graphql.ExecutionInput
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class DynamicGraphQlController(
    private val registry: DynamicGraphQlRegistry,
    private val collectionService: CollectionService,
) {

    @PostMapping("/graphql/{collectionName}")
    fun execute(
        @PathVariable collectionName: String,
        @RequestBody body: Map<String, Any>,
    ): ResponseEntity<Map<String, Any>> {
        val collection = collectionService.findByName(collectionName)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "Collection '$collectionName' not found"))

        val graphql = registry.get(collection.id)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "No GraphQL schema generated for collection '$collectionName'"))

        val query = body["query"] as? String
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Missing 'query' field in request body"))

        @Suppress("UNCHECKED_CAST")
        val variables = body["variables"] as? Map<String, Any> ?: emptyMap()
        val operationName = body["operationName"] as? String

        val executionInput = ExecutionInput.newExecutionInput()
            .query(query)
            .variables(variables)
            .operationName(operationName)
            .build()

        val result = graphql.execute(executionInput)

        val responseBody = mutableMapOf<String, Any>()
        responseBody["data"] = result.getData<Any>()
        if (result.errors.isNotEmpty()) {
            responseBody["errors"] = result.errors.map { it.toSpecification() }
        }

        return ResponseEntity.ok(responseBody)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.agentwork.graphmesh.dynamicgraphql.DynamicGraphQlControllerTest"`
Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlController.kt \
  src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlControllerTest.kt
git commit -m "feat(dynamicgraphql): add DynamicGraphQlController — per-collection GraphQL endpoint"
```

---

### Task 6: Wire into existing code (CorsConfig, RdfImportService)

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/api/CorsConfig.kt:10`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt:20-96`

- [ ] **Step 1: Update CorsConfig**

In `src/main/kotlin/com/agentwork/graphmesh/api/CorsConfig.kt`, change line 10:

```kotlin
// Before:
registry.addMapping("/graphql")

// After:
registry.addMapping("/graphql/**")
```

This covers both the static `/graphql` and dynamic `/graphql/{collectionName}` endpoints.

- [ ] **Step 2: Add DynamicGraphQlSchemaBuilder to RdfImportService**

In `src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt`:

Add the import:
```kotlin
import com.agentwork.graphmesh.dynamicgraphql.DynamicGraphQlSchemaBuilder
```

Add the constructor parameter:
```kotlin
@Service
class RdfImportService(
    private val jenaAdapter: JenaAdapter,
    private val quadStore: QuadStore,
    private val embeddingProvider: LLMEmbeddingProvider,
    private val vectorStore: VectorStore,
    private val embeddingConfig: EmbeddingConfig,
    private val dynamicGraphQlSchemaBuilder: DynamicGraphQlSchemaBuilder,
)
```

At the end of the `importRdf()` method, before the `return` statement (after the logging line, before `return ImportResult(...)`), add:

```kotlin
        dynamicGraphQlSchemaBuilder.rebuildIfOntologyAssigned(collectionId)
```

The relevant section becomes:
```kotlin
        logger.info("RDF import into collection '{}': {} triples, {} skipped, {}ms",
            collectionId, imported, skipped, System.currentTimeMillis() - start)

        dynamicGraphQlSchemaBuilder.rebuildIfOntologyAssigned(collectionId)

        return ImportResult(
            tripleCount = imported,
            skippedCount = skipped,
            durationMs = System.currentTimeMillis() - start,
            embeddingsGenerated = embeddingsGenerated,
        )
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/api/CorsConfig.kt \
  src/main/kotlin/com/agentwork/graphmesh/rdfimport/RdfImportService.kt
git commit -m "feat(dynamicgraphql): wire schema builder into RdfImportService and expand CORS"
```

---

### Task 7: End-to-end integration test

**Files:**
- Create: `src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlIntegrationTest.kt`

This test verifies the full pipeline: assign ontology → import RDF → query dynamic endpoint. Requires `docker-compose up` (Cassandra).

- [ ] **Step 1: Write integration test**

```kotlin
package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.collection.CollectionOntologyService
import com.agentwork.graphmesh.collection.CollectionService
import com.agentwork.graphmesh.ontology.DatatypeProperty
import com.agentwork.graphmesh.ontology.ObjectProperty
import com.agentwork.graphmesh.ontology.Ontology
import com.agentwork.graphmesh.ontology.OntologyClass
import com.agentwork.graphmesh.ontology.OntologyMetadata
import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.rdf.XsdTypes
import com.agentwork.graphmesh.rdfimport.RdfFormat
import com.agentwork.graphmesh.rdfimport.RdfImportService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.Test

/**
 * Requires: docker-compose up (Cassandra, Qdrant, MinIO, Kafka)
 */
@SpringBootTest
@AutoConfigureMockMvc
class DynamicGraphQlIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var collectionService: CollectionService
    @Autowired lateinit var collectionOntologyService: CollectionOntologyService
    @Autowired lateinit var ontologyService: OntologyService
    @Autowired lateinit var rdfImportService: RdfImportService
    @Autowired lateinit var registry: DynamicGraphQlRegistry

    @Test
    fun `import RDF with ontology creates dynamic endpoint`() {
        val collection = collectionService.create(
            name = "integration-test-${System.currentTimeMillis()}",
            description = "test"
        )

        ontologyService.importOntology(
            key = "test-onto",
            name = "TestOntology",
            namespace = "http://test.org/",
            content = """
                @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                @prefix : <http://test.org/> .

                :Person a owl:Class .
                :name a owl:DatatypeProperty ;
                    rdfs:domain :Person ;
                    rdfs:range xsd:string .
            """.trimIndent(),
            format = "TURTLE",
            version = "1.0"
        )

        collectionOntologyService.assign(collection.id, "test-onto", "primary", "test")

        val turtle = """
            @prefix : <http://test.org/> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .

            :alice rdf:type :Person ;
                :name "Alice" .

            :bob rdf:type :Person ;
                :name "Bob" .
        """.trimIndent()

        rdfImportService.importRdf(collection.id, turtle, RdfFormat.TURTLE, null, false)

        assert(registry.has(collection.id)) { "Schema should be registered after import" }

        mockMvc.post("/graphql/${collection.name}") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"query": "{ Person(limit: 10) { id name } }"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.Person") { isArray() }
        }
    }
}
```

- [ ] **Step 2: Run integration test (requires docker-compose)**

Run: `./gradlew test --tests "com.agentwork.graphmesh.dynamicgraphql.DynamicGraphQlIntegrationTest"`
Expected: PASS (with `docker-compose up` running).

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/agentwork/graphmesh/dynamicgraphql/DynamicGraphQlIntegrationTest.kt
git commit -m "test(dynamicgraphql): add end-to-end integration test for dynamic GraphQL endpoint"
```

---

### Task 8: Update feature documentation

**Files:**
- Modify: `docs/features/61-dynamic-graphql-from-ontology.md`
- Modify: `docs/features/00-feature-set-overview.md`

- [ ] **Step 1: Update feature spec status to done**

In `docs/features/61-dynamic-graphql-from-ontology.md`, change:

```markdown
## Status: planned
```

to:

```markdown
## Status: done
```

- [ ] **Step 2: Add to feature set overview**

In `docs/features/00-feature-set-overview.md`, add a new row in the feature table:

```markdown
| 61 | Dynamic GraphQL from Ontology | done | Automatische GraphQL-Endpoints pro Collection aus zugewiesener Ontologie |
```

- [ ] **Step 3: Commit**

```bash
git add docs/features/61-dynamic-graphql-from-ontology.md \
  docs/features/00-feature-set-overview.md
git commit -m "docs: mark Feature 61 as done, update feature set overview"
```
