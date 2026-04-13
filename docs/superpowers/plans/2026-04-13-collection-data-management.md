# Feature 47: Collection Data Management — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable per-collection ontology assignment (n:m with roles) and RDF data management (upload, stats, delete) via GraphQL API and a new frontend collection detail page.

**Architecture:** New Cassandra table `collection_ontologies` for the n:m mapping. New `CollectionOntologyService` for CRUD. New `CollectionDataController` for GraphQL endpoints. New QuadStore methods for dataset-level deletion and stats. New frontend page `/collections/[id]` with ontology and data sections.

**Tech Stack:** Kotlin, Spring Boot 4, Spring GraphQL, Cassandra, Next.js 14, Apollo Client v4, Tailwind CSS

---

### Task 1: Cassandra Schema — `collection_ontologies` Table

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/collection/CollectionSchemaInitializer.kt`

- [ ] **Step 1: Add table creation method**

Add to `CollectionSchemaInitializer.kt` after `addTenantColumns()` call in `initializeSchema()`:

```kotlin
@PostConstruct
fun initializeSchema() {
    createTables()
    addTenantColumns()
    createOntologyAssignmentTable()
    logger.info("Collection schema initialized in keyspace '{}'", keyspace)
}

private fun createOntologyAssignmentTable() {
    session.execute("""
        CREATE TABLE IF NOT EXISTS $keyspace.collection_ontologies (
            collection_id text,
            ontology_key  text,
            role          text,
            assigned_at   timestamp,
            assigned_by   text,
            PRIMARY KEY (collection_id, ontology_key)
        )
    """.trimIndent())
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/collection/CollectionSchemaInitializer.kt
git commit -m "feat(collection): add collection_ontologies Cassandra table"
```

---

### Task 2: CollectionOntologyService — Backend Service

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/collection/CollectionOntologyService.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/collection/CollectionOntologyServiceTest.kt`

- [ ] **Step 1: Write the tests**

```kotlin
package com.agentwork.graphmesh.collection

import io.mockk.*
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.datastax.oss.driver.api.core.cql.Row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionOntologyServiceTest {

    private val session = mockk<CqlSession>(relaxed = true)
    private val service = CollectionOntologyService(session, "graphmesh")

    @Test
    fun `assign creates record`() {
        every { session.execute(any<String>()) } returns mockk(relaxed = true)

        val record = service.assign("col-1", "pharma-onto", "domain", "admin")

        assertEquals("col-1", record.collectionId)
        assertEquals("pharma-onto", record.ontologyKey)
        assertEquals("domain", record.role)
        assertEquals("admin", record.assignedBy)
    }

    @Test
    fun `unassign deletes record`() {
        every { session.execute(any<String>()) } returns mockk(relaxed = true)

        service.unassign("col-1", "pharma-onto")

        verify { session.execute(match<String> { it.contains("DELETE") }) }
    }

    @Test
    fun `listForCollection returns records`() {
        val row = mockk<Row>(relaxed = true)
        every { row.getString("ontology_key") } returns "pharma-onto"
        every { row.getString("role") } returns "domain"
        every { row.getString("assigned_by") } returns "admin"
        every { row.getInstant("assigned_at") } returns java.time.Instant.now()
        val rs = mockk<ResultSet>()
        every { rs.iterator() } returns mutableListOf(row).iterator()
        every { session.execute(match<String> { it.contains("SELECT") && it.contains("collection_id") }) } returns rs

        val results = service.listForCollection("col-1")

        assertEquals(1, results.size)
        assertEquals("pharma-onto", results[0].ontologyKey)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.collection.CollectionOntologyServiceTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Write the service implementation**

```kotlin
package com.agentwork.graphmesh.collection

import com.datastax.oss.driver.api.core.CqlSession
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class CollectionOntologyService(
    private val session: CqlSession,
    @Value("\${graphmesh.cassandra.keyspace}") private val keyspace: String
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun assign(collectionId: String, ontologyKey: String, role: String, assignedBy: String): CollectionOntologyRecord {
        val now = Instant.now()
        session.execute(
            "INSERT INTO $keyspace.collection_ontologies (collection_id, ontology_key, role, assigned_at, assigned_by) " +
            "VALUES ('$collectionId', '$ontologyKey', '$role', '$now', '$assignedBy')"
        )
        logger.info("Assigned ontology '{}' to collection '{}' with role '{}'", ontologyKey, collectionId, role)
        return CollectionOntologyRecord(collectionId, ontologyKey, role, now, assignedBy)
    }

    fun unassign(collectionId: String, ontologyKey: String) {
        session.execute(
            "DELETE FROM $keyspace.collection_ontologies WHERE collection_id = '$collectionId' AND ontology_key = '$ontologyKey'"
        )
        logger.info("Unassigned ontology '{}' from collection '{}'", ontologyKey, collectionId)
    }

    fun listForCollection(collectionId: String): List<CollectionOntologyRecord> {
        val rs = session.execute(
            "SELECT ontology_key, role, assigned_at, assigned_by FROM $keyspace.collection_ontologies WHERE collection_id = '$collectionId'"
        )
        return rs.map { row ->
            CollectionOntologyRecord(
                collectionId = collectionId,
                ontologyKey = row.getString("ontology_key")!!,
                role = row.getString("role") ?: "",
                assignedAt = row.getInstant("assigned_at") ?: Instant.now(),
                assignedBy = row.getString("assigned_by") ?: ""
            )
        }
    }
}

data class CollectionOntologyRecord(
    val collectionId: String,
    val ontologyKey: String,
    val role: String,
    val assignedAt: Instant,
    val assignedBy: String
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.collection.CollectionOntologyServiceTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/collection/CollectionOntologyService.kt \
        src/test/kotlin/com/agentwork/graphmesh/collection/CollectionOntologyServiceTest.kt
git commit -m "feat(collection): add CollectionOntologyService for ontology assignment"
```

---

### Task 3: QuadStore Extensions — deleteByDataset and stats

**Files:**
- Modify: `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt`
- Modify: `src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt`

- [ ] **Step 1: Add interface methods to QuadStore.kt**

Add before the closing `}` of the `QuadStore` interface:

```kotlin
/** Deletes all quads in [collection] with the given [dataset]. Returns count of deleted quads. */
fun deleteByDataset(collection: String, dataset: String): Long

/** Returns triple count, entity count, predicate count, and dataset list for [collection]. */
fun stats(collection: String): QuadStoreStats
```

Add below the interface:

```kotlin
data class QuadStoreStats(
    val tripleCount: Long,
    val entityCount: Long,
    val predicateCount: Long,
    val datasets: List<String>
)
```

- [ ] **Step 2: Implement in QuadStoreService.kt**

Add the implementations. `deleteByDataset` filters quads by dataset and deletes them. `stats` reuses `aggregateMetadata` and `scrollAll` for counts:

```kotlin
override fun deleteByDataset(collection: String, dataset: String): Long {
    val quads = query(collection, QuadQuery(dataset = dataset))
    quads.forEach { delete(collection, it) }
    return quads.size.toLong()
}

override fun stats(collection: String): QuadStoreStats {
    val meta = aggregateMetadata(collection)
    val allQuads = scrollAll(collection)
    return QuadStoreStats(
        tripleCount = allQuads.size.toLong(),
        entityCount = allQuads.map { it.subject }.distinct().size.toLong(),
        predicateCount = meta.predicates.size.toLong(),
        datasets = meta.datasets
    )
}
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/storage/QuadStore.kt \
        src/main/kotlin/com/agentwork/graphmesh/storage/QuadStoreService.kt
git commit -m "feat(storage): add deleteByDataset and stats to QuadStore"
```

---

### Task 4: GraphQL Schema — collection-data.graphqls

**Files:**
- Create: `src/main/resources/graphql/collection-data.graphqls`

- [ ] **Step 1: Create the schema file**

```graphql
type CollectionOntology {
    ontologyKey: String!
    role: String!
    assignedAt: String!
    assignedBy: String!
    ontology: OntologyInfo
}

type CollectionDataStats {
    tripleCount: Int!
    entityCount: Int!
    predicateCount: Int!
    datasets: [String!]!
}

extend type Query {
    collectionOntologies(collectionId: ID!): [CollectionOntology!]!
    collectionDataStats(collectionId: ID!): CollectionDataStats!
}

extend type Mutation {
    assignOntology(collectionId: ID!, ontologyKey: String!, role: String!): CollectionOntology!
    unassignOntology(collectionId: ID!, ontologyKey: String!): Boolean!
    deleteTriples(collectionId: ID!, dataset: String): Int!
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/graphql/collection-data.graphqls
git commit -m "feat(api): add GraphQL schema for collection data management"
```

---

### Task 5: CollectionDataController — GraphQL Controller

**Files:**
- Create: `src/main/kotlin/com/agentwork/graphmesh/api/CollectionDataController.kt`
- Create: `src/test/kotlin/com/agentwork/graphmesh/api/CollectionDataControllerTest.kt`

- [ ] **Step 1: Write the tests**

```kotlin
package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.collection.CollectionOntologyRecord
import com.agentwork.graphmesh.collection.CollectionOntologyService
import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.QuadStoreStats
import io.mockk.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionDataControllerTest {

    private val ontologyService = mockk<CollectionOntologyService>()
    private val quadStore = mockk<QuadStore>()
    private val ontService = mockk<OntologyService>()
    private val controller = CollectionDataController(ontologyService, quadStore, ontService)

    @Test
    fun `assignOntology returns payload`() {
        val record = CollectionOntologyRecord("col-1", "onto-1", "domain", Instant.now(), "admin")
        every { ontologyService.assign("col-1", "onto-1", "domain", "system") } returns record
        every { ontService.get("onto-1") } returns null

        val result = controller.assignOntology("col-1", "onto-1", "domain")

        assertEquals("onto-1", result.ontologyKey)
        assertEquals("domain", result.role)
    }

    @Test
    fun `unassignOntology returns true`() {
        every { ontologyService.unassign("col-1", "onto-1") } just runs

        val result = controller.unassignOntology("col-1", "onto-1")

        assertTrue(result)
    }

    @Test
    fun `collectionDataStats returns stats`() {
        every { quadStore.stats("col-1") } returns QuadStoreStats(
            tripleCount = 100, entityCount = 50, predicateCount = 10, datasets = listOf("default")
        )

        val result = controller.collectionDataStats("col-1")

        assertEquals(100, result.tripleCount)
        assertEquals(50, result.entityCount)
        assertEquals(listOf("default"), result.datasets)
    }

    @Test
    fun `deleteTriples with dataset delegates to deleteByDataset`() {
        every { quadStore.deleteByDataset("col-1", "test-ds") } returns 42L

        val result = controller.deleteTriples("col-1", "test-ds")

        assertEquals(42, result)
    }

    @Test
    fun `deleteTriples without dataset delegates to deleteCollection`() {
        every { quadStore.deleteCollection("col-1") } just runs
        every { quadStore.stats("col-1") } returns QuadStoreStats(0, 0, 0, emptyList())

        val result = controller.deleteTriples("col-1", null)

        verify { quadStore.deleteCollection("col-1") }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.agentwork.graphmesh.api.CollectionDataControllerTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Write the controller**

```kotlin
package com.agentwork.graphmesh.api

import com.agentwork.graphmesh.collection.CollectionOntologyService
import com.agentwork.graphmesh.ontology.OntologyInfoPayload
import com.agentwork.graphmesh.ontology.OntologyService
import com.agentwork.graphmesh.storage.QuadStore
import com.agentwork.graphmesh.storage.QuadStoreStats
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class CollectionDataController(
    private val collectionOntologyService: CollectionOntologyService,
    private val quadStore: QuadStore,
    private val ontologyService: OntologyService
) {

    @QueryMapping
    fun collectionOntologies(@Argument collectionId: String): List<CollectionOntologyPayload> =
        collectionOntologyService.listForCollection(collectionId).map { record ->
            val ontology = ontologyService.get(record.ontologyKey)
            CollectionOntologyPayload(
                ontologyKey = record.ontologyKey,
                role = record.role,
                assignedAt = record.assignedAt.toString(),
                assignedBy = record.assignedBy,
                ontology = ontology?.let {
                    OntologyInfoPayload(
                        key = record.ontologyKey,
                        name = it.metadata.name,
                        namespace = it.metadata.namespace,
                        version = it.metadata.version,
                        classCount = it.classes.size,
                        objectPropertyCount = it.objectProperties.size,
                        datatypePropertyCount = it.datatypeProperties.size,
                    )
                }
            )
        }

    @QueryMapping
    fun collectionDataStats(@Argument collectionId: String): CollectionDataStatsPayload {
        val stats = quadStore.stats(collectionId)
        return CollectionDataStatsPayload(
            tripleCount = stats.tripleCount.toInt(),
            entityCount = stats.entityCount.toInt(),
            predicateCount = stats.predicateCount.toInt(),
            datasets = stats.datasets
        )
    }

    @MutationMapping
    fun assignOntology(
        @Argument collectionId: String,
        @Argument ontologyKey: String,
        @Argument role: String
    ): CollectionOntologyPayload {
        val record = collectionOntologyService.assign(collectionId, ontologyKey, role, "system")
        val ontology = ontologyService.get(ontologyKey)
        return CollectionOntologyPayload(
            ontologyKey = record.ontologyKey,
            role = record.role,
            assignedAt = record.assignedAt.toString(),
            assignedBy = record.assignedBy,
            ontology = ontology?.let {
                OntologyInfoPayload(
                    key = record.ontologyKey,
                    name = it.metadata.name,
                    namespace = it.metadata.namespace,
                    version = it.metadata.version,
                    classCount = it.classes.size,
                    objectPropertyCount = it.objectProperties.size,
                    datatypePropertyCount = it.datatypeProperties.size,
                )
            }
        )
    }

    @MutationMapping
    fun unassignOntology(@Argument collectionId: String, @Argument ontologyKey: String): Boolean {
        collectionOntologyService.unassign(collectionId, ontologyKey)
        return true
    }

    @MutationMapping
    fun deleteTriples(@Argument collectionId: String, @Argument dataset: String?): Int {
        return if (dataset != null) {
            quadStore.deleteByDataset(collectionId, dataset).toInt()
        } else {
            quadStore.deleteCollection(collectionId)
            0
        }
    }
}

data class CollectionOntologyPayload(
    val ontologyKey: String,
    val role: String,
    val assignedAt: String,
    val assignedBy: String,
    val ontology: OntologyInfoPayload?
)

data class CollectionDataStatsPayload(
    val tripleCount: Int,
    val entityCount: Int,
    val predicateCount: Int,
    val datasets: List<String>
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.agentwork.graphmesh.api.CollectionDataControllerTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Verify full build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/agentwork/graphmesh/api/CollectionDataController.kt \
        src/test/kotlin/com/agentwork/graphmesh/api/CollectionDataControllerTest.kt
git commit -m "feat(api): add CollectionDataController for ontology assignment and data stats"
```

---

### Task 6: Frontend — GraphQL Queries and Types

**Files:**
- Create: `frontend/src/graphql/collection-data.ts`
- Create: `frontend/src/types/collection-data.ts`

- [ ] **Step 1: Create the types file**

```typescript
export interface CollectionOntology {
  ontologyKey: string;
  role: string;
  assignedAt: string;
  assignedBy: string;
  ontology: {
    key: string;
    name: string;
    namespace: string;
    version: string;
    classCount: number;
    objectPropertyCount: number;
    datatypePropertyCount: number;
  } | null;
}

export interface CollectionDataStats {
  tripleCount: number;
  entityCount: number;
  predicateCount: number;
  datasets: string[];
}
```

- [ ] **Step 2: Create the GraphQL queries file**

```typescript
import { gql } from "@apollo/client";

export const COLLECTION_ONTOLOGIES_QUERY = gql`
  query CollectionOntologies($collectionId: ID!) {
    collectionOntologies(collectionId: $collectionId) {
      ontologyKey
      role
      assignedAt
      assignedBy
      ontology {
        key
        name
        namespace
        version
        classCount
        objectPropertyCount
        datatypePropertyCount
      }
    }
  }
`;

export const COLLECTION_DATA_STATS_QUERY = gql`
  query CollectionDataStats($collectionId: ID!) {
    collectionDataStats(collectionId: $collectionId) {
      tripleCount
      entityCount
      predicateCount
      datasets
    }
  }
`;

export const ASSIGN_ONTOLOGY_MUTATION = gql`
  mutation AssignOntology($collectionId: ID!, $ontologyKey: String!, $role: String!) {
    assignOntology(collectionId: $collectionId, ontologyKey: $ontologyKey, role: $role) {
      ontologyKey
      role
      assignedAt
      ontology {
        key
        name
        classCount
        objectPropertyCount
        datatypePropertyCount
      }
    }
  }
`;

export const UNASSIGN_ONTOLOGY_MUTATION = gql`
  mutation UnassignOntology($collectionId: ID!, $ontologyKey: String!) {
    unassignOntology(collectionId: $collectionId, ontologyKey: $ontologyKey)
  }
`;

export const DELETE_TRIPLES_MUTATION = gql`
  mutation DeleteTriples($collectionId: ID!, $dataset: String) {
    deleteTriples(collectionId: $collectionId, dataset: $dataset)
  }
`;
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/graphql/collection-data.ts frontend/src/types/collection-data.ts
git commit -m "feat(frontend): add GraphQL queries and types for collection data management"
```

---

### Task 7: Frontend — Collection List Page

**Files:**
- Create: `frontend/src/app/collections/page.tsx`
- Modify: `frontend/src/components/SiteNav.tsx`

- [ ] **Step 1: Create the collections list page**

```tsx
"use client";

import { useQuery } from "@apollo/client/react";
import { ADMIN_COLLECTIONS_QUERY } from "@/graphql/admin";
import type { AdminCollection } from "@/types/admin";
import Link from "next/link";

export default function CollectionsPage() {
  const { data, loading } = useQuery<{ collections: AdminCollection[] }>(ADMIN_COLLECTIONS_QUERY);

  return (
    <main className="p-6">
      <h1 className="mb-6 text-2xl font-bold">Collections</h1>
      {loading && <p className="text-muted-foreground">Laden...</p>}
      <div className="grid gap-4">
        {data?.collections?.map((col) => (
          <Link
            key={col.id}
            href={`/collections/${col.id}`}
            className="block rounded-lg border border-border p-4 hover:bg-muted/50 transition-colors"
          >
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-lg font-semibold">{col.name}</h2>
                {col.description && (
                  <p className="mt-1 text-sm text-muted-foreground">{col.description}</p>
                )}
              </div>
              <div className="flex gap-2">
                {col.tags?.map((tag) => (
                  <span key={tag} className="rounded-full bg-secondary px-2 py-0.5 text-xs">
                    {tag}
                  </span>
                ))}
              </div>
            </div>
          </Link>
        ))}
      </div>
    </main>
  );
}
```

- [ ] **Step 2: Add "Collections" link to SiteNav**

In `frontend/src/components/SiteNav.tsx`, add to `NAV_ITEMS` array before the "Admin" entry:

```typescript
const NAV_ITEMS = [
  { href: "/documents", label: "Dokumente" },
  { href: "/collections", label: "Collections" },
  { href: "/graph", label: "Graph" },
  { href: "/query", label: "Query" },
  { href: "/cores", label: "Cores" },
  { href: "/admin", label: "Admin" },
];
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/collections/page.tsx frontend/src/components/SiteNav.tsx
git commit -m "feat(frontend): add collections list page and navigation link"
```

---

### Task 8: Frontend — Collection Detail Page (Ontology Section)

**Files:**
- Create: `frontend/src/app/collections/[id]/page.tsx`
- Create: `frontend/src/components/collections/OntologySection.tsx`
- Create: `frontend/src/components/collections/UploadOntologyDialog.tsx`
- Create: `frontend/src/components/collections/AssignOntologyDialog.tsx`

- [ ] **Step 1: Create UploadOntologyDialog**

```tsx
"use client";

import { useMutation } from "@apollo/client/react";
import { IMPORT_ONTOLOGY_MUTATION } from "@/graphql/admin";
import { ASSIGN_ONTOLOGY_MUTATION } from "@/graphql/collection-data";
import { useState } from "react";

interface Props {
  collectionId: string;
  onClose: () => void;
  onSuccess: () => void;
}

export function UploadOntologyDialog({ collectionId, onClose, onSuccess }: Props) {
  const [key, setKey] = useState("");
  const [name, setName] = useState("");
  const [role, setRole] = useState("domain");
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const [importMutation] = useMutation(IMPORT_ONTOLOGY_MUTATION);
  const [assignMutation] = useMutation(ASSIGN_ONTOLOGY_MUTATION);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) return;
    setError("");
    setLoading(true);
    try {
      const content = btoa(await file.text());
      const format = file.name.endsWith(".rdf") ? "RDFXML" : "TURTLE";
      await importMutation({
        variables: {
          input: {
            key,
            content,
            format,
            name: name || file.name,
            namespace: "http://example.org/ontology/",
          },
        },
      });
      await assignMutation({
        variables: { collectionId, ontologyKey: key, role },
      });
      onSuccess();
    } catch (err) {
      setError((err as Error).message || "Upload fehlgeschlagen");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="w-full max-w-md rounded-lg border border-border bg-card p-6">
        <h2 className="mb-4 text-lg font-semibold">Ontologie hochladen</h2>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="mb-1 block text-sm">Datei (.ttl / .rdf)</label>
            <input
              type="file"
              accept=".ttl,.rdf"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              required
              className="w-full text-sm"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm">Key</label>
            <input
              value={key}
              onChange={(e) => setKey(e.target.value)}
              placeholder="z.B. pharma-ontologie"
              required
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm">Name</label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="z.B. Pharma-Domain-Ontologie"
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm">Rolle</label>
            <select
              value={role}
              onChange={(e) => setRole(e.target.value)}
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            >
              <option value="domain">Domain</option>
              <option value="upper">Upper</option>
              <option value="skos">SKOS</option>
              <option value="custom">Custom</option>
            </select>
          </div>
          {error && <p className="text-sm text-red-400">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className="rounded-md px-4 py-2 text-sm hover:bg-muted">
              Abbrechen
            </button>
            <button
              type="submit"
              disabled={loading}
              className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {loading ? "Hochladen..." : "Hochladen & Zuordnen"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Create AssignOntologyDialog**

```tsx
"use client";

import { useMutation, useQuery } from "@apollo/client/react";
import { LIST_ONTOLOGIES_QUERY } from "@/graphql/admin";
import { ASSIGN_ONTOLOGY_MUTATION } from "@/graphql/collection-data";
import type { CollectionOntology } from "@/types/collection-data";
import { useState } from "react";

interface Props {
  collectionId: string;
  existingKeys: string[];
  onClose: () => void;
  onSuccess: () => void;
}

export function AssignOntologyDialog({ collectionId, existingKeys, onClose, onSuccess }: Props) {
  const [ontologyKey, setOntologyKey] = useState("");
  const [role, setRole] = useState("domain");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const { data } = useQuery<{ listOntologies: { key: string; name: string }[] }>(LIST_ONTOLOGIES_QUERY);
  const [assignMutation] = useMutation(ASSIGN_ONTOLOGY_MUTATION);

  const available = data?.listOntologies?.filter((o) => !existingKeys.includes(o.key)) ?? [];

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!ontologyKey) return;
    setError("");
    setLoading(true);
    try {
      await assignMutation({
        variables: { collectionId, ontologyKey, role },
      });
      onSuccess();
    } catch (err) {
      setError((err as Error).message || "Zuordnung fehlgeschlagen");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="w-full max-w-md rounded-lg border border-border bg-card p-6">
        <h2 className="mb-4 text-lg font-semibold">Bestehende Ontologie zuordnen</h2>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="mb-1 block text-sm">Ontologie</label>
            <select
              value={ontologyKey}
              onChange={(e) => setOntologyKey(e.target.value)}
              required
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            >
              <option value="">Ontologie waehlen...</option>
              {available.map((o) => (
                <option key={o.key} value={o.key}>
                  {o.name || o.key}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="mb-1 block text-sm">Rolle</label>
            <select
              value={role}
              onChange={(e) => setRole(e.target.value)}
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            >
              <option value="domain">Domain</option>
              <option value="upper">Upper</option>
              <option value="skos">SKOS</option>
              <option value="custom">Custom</option>
            </select>
          </div>
          {error && <p className="text-sm text-red-400">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className="rounded-md px-4 py-2 text-sm hover:bg-muted">
              Abbrechen
            </button>
            <button
              type="submit"
              disabled={loading || !ontologyKey}
              className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {loading ? "Zuordnen..." : "Zuordnen"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Create OntologySection**

```tsx
"use client";

import { useMutation, useQuery } from "@apollo/client/react";
import { COLLECTION_ONTOLOGIES_QUERY, UNASSIGN_ONTOLOGY_MUTATION } from "@/graphql/collection-data";
import type { CollectionOntology } from "@/types/collection-data";
import { UploadOntologyDialog } from "./UploadOntologyDialog";
import { AssignOntologyDialog } from "./AssignOntologyDialog";
import { useState } from "react";

interface Props {
  collectionId: string;
}

export function OntologySection({ collectionId }: Props) {
  const [showUpload, setShowUpload] = useState(false);
  const [showAssign, setShowAssign] = useState(false);

  const { data, refetch } = useQuery<{ collectionOntologies: CollectionOntology[] }>(
    COLLECTION_ONTOLOGIES_QUERY,
    { variables: { collectionId } }
  );
  const [unassignMutation] = useMutation(UNASSIGN_ONTOLOGY_MUTATION);

  const ontologies = data?.collectionOntologies ?? [];

  const handleUnassign = async (ontologyKey: string) => {
    if (!confirm(`Zuordnung von "${ontologyKey}" wirklich entfernen?`)) return;
    await unassignMutation({ variables: { collectionId, ontologyKey } });
    refetch();
  };

  const handleSuccess = () => {
    setShowUpload(false);
    setShowAssign(false);
    refetch();
  };

  return (
    <section>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-semibold">Ontologien</h2>
        <div className="flex gap-2">
          <button
            onClick={() => setShowAssign(true)}
            className="rounded-md border border-border px-3 py-1.5 text-sm hover:bg-muted"
          >
            Bestehende zuordnen
          </button>
          <button
            onClick={() => setShowUpload(true)}
            className="rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
          >
            Hochladen
          </button>
        </div>
      </div>
      {ontologies.length === 0 ? (
        <p className="text-sm text-muted-foreground">Keine Ontologien zugeordnet.</p>
      ) : (
        <div className="overflow-hidden rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="bg-muted/50">
              <tr>
                <th className="px-4 py-2 text-left font-medium">Key</th>
                <th className="px-4 py-2 text-left font-medium">Name</th>
                <th className="px-4 py-2 text-left font-medium">Rolle</th>
                <th className="px-4 py-2 text-left font-medium">Klassen</th>
                <th className="px-4 py-2 text-left font-medium">Properties</th>
                <th className="px-4 py-2 text-right font-medium">Aktion</th>
              </tr>
            </thead>
            <tbody>
              {ontologies.map((o) => (
                <tr key={o.ontologyKey} className="border-t border-border">
                  <td className="px-4 py-2 font-mono text-xs">{o.ontologyKey}</td>
                  <td className="px-4 py-2">{o.ontology?.name ?? "—"}</td>
                  <td className="px-4 py-2">
                    <span className="rounded-full bg-secondary px-2 py-0.5 text-xs">{o.role}</span>
                  </td>
                  <td className="px-4 py-2">{o.ontology?.classCount ?? 0}</td>
                  <td className="px-4 py-2">
                    {(o.ontology?.objectPropertyCount ?? 0) + (o.ontology?.datatypePropertyCount ?? 0)}
                  </td>
                  <td className="px-4 py-2 text-right">
                    <button
                      onClick={() => handleUnassign(o.ontologyKey)}
                      className="text-xs text-red-400 hover:text-red-300"
                    >
                      Entfernen
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {showUpload && (
        <UploadOntologyDialog collectionId={collectionId} onClose={() => setShowUpload(false)} onSuccess={handleSuccess} />
      )}
      {showAssign && (
        <AssignOntologyDialog
          collectionId={collectionId}
          existingKeys={ontologies.map((o) => o.ontologyKey)}
          onClose={() => setShowAssign(false)}
          onSuccess={handleSuccess}
        />
      )}
    </section>
  );
}
```

- [ ] **Step 4: Create the collection detail page (ontology section only for now)**

```tsx
"use client";

import { useQuery } from "@apollo/client/react";
import { useParams } from "next/navigation";
import { ADMIN_COLLECTIONS_QUERY } from "@/graphql/admin";
import type { AdminCollection } from "@/types/admin";
import { OntologySection } from "@/components/collections/OntologySection";

export default function CollectionDetailPage() {
  const { id } = useParams<{ id: string }>();

  const { data } = useQuery<{ collections: AdminCollection[] }>(ADMIN_COLLECTIONS_QUERY);
  const collection = data?.collections?.find((c) => c.id === id);

  return (
    <main className="p-6">
      <div className="mb-8">
        <h1 className="text-2xl font-bold">{collection?.name ?? id}</h1>
        {collection?.description && (
          <p className="mt-1 text-muted-foreground">{collection.description}</p>
        )}
        {collection?.tags && collection.tags.length > 0 && (
          <div className="mt-2 flex gap-2">
            {collection.tags.map((tag) => (
              <span key={tag} className="rounded-full bg-secondary px-2 py-0.5 text-xs">{tag}</span>
            ))}
          </div>
        )}
      </div>

      <div className="space-y-10">
        <OntologySection collectionId={id} />
      </div>
    </main>
  );
}
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/collections/\[id\]/page.tsx \
        frontend/src/components/collections/OntologySection.tsx \
        frontend/src/components/collections/UploadOntologyDialog.tsx \
        frontend/src/components/collections/AssignOntologyDialog.tsx
git commit -m "feat(frontend): add collection detail page with ontology management"
```

---

### Task 9: Frontend — Data Section (Upload, Stats, Delete)

**Files:**
- Create: `frontend/src/components/collections/DataSection.tsx`
- Create: `frontend/src/components/collections/UploadDataDialog.tsx`
- Modify: `frontend/src/app/collections/[id]/page.tsx`

- [ ] **Step 1: Create UploadDataDialog**

```tsx
"use client";

import { useMutation } from "@apollo/client/react";
import { IMPORT_RDF_MUTATION } from "@/graphql/admin";
import { useState } from "react";

interface Props {
  collectionId: string;
  onClose: () => void;
  onSuccess: () => void;
}

export function UploadDataDialog({ collectionId, onClose, onSuccess }: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [dataset, setDataset] = useState("");
  const [generateEmbeddings, setGenerateEmbeddings] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const [importMutation] = useMutation(IMPORT_RDF_MUTATION);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) return;
    setError("");
    setLoading(true);
    try {
      const content = btoa(await file.text());
      let format = "TURTLE";
      if (file.name.endsWith(".rdf")) format = "RDFXML";
      else if (file.name.endsWith(".nt")) format = "NTRIPLES";

      await importMutation({
        variables: {
          input: {
            collectionId,
            content,
            format,
            dataset: dataset || null,
            generateEmbeddings,
          },
        },
      });
      onSuccess();
    } catch (err) {
      setError((err as Error).message || "Import fehlgeschlagen");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="w-full max-w-md rounded-lg border border-border bg-card p-6">
        <h2 className="mb-4 text-lg font-semibold">RDF-Daten importieren</h2>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="mb-1 block text-sm">Datei (.ttl / .rdf / .nt)</label>
            <input
              type="file"
              accept=".ttl,.rdf,.nt"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              required
              className="w-full text-sm"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm">Dataset (optional)</label>
            <input
              value={dataset}
              onChange={(e) => setDataset(e.target.value)}
              placeholder="z.B. import-2026-04"
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
            />
          </div>
          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              id="embeddings"
              checked={generateEmbeddings}
              onChange={(e) => setGenerateEmbeddings(e.target.checked)}
              className="rounded"
            />
            <label htmlFor="embeddings" className="text-sm">Embeddings erzeugen</label>
          </div>
          {error && <p className="text-sm text-red-400">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className="rounded-md px-4 py-2 text-sm hover:bg-muted">
              Abbrechen
            </button>
            <button
              type="submit"
              disabled={loading}
              className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {loading ? "Importieren..." : "Importieren"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Create DataSection**

```tsx
"use client";

import { useMutation, useQuery } from "@apollo/client/react";
import { COLLECTION_DATA_STATS_QUERY, DELETE_TRIPLES_MUTATION } from "@/graphql/collection-data";
import type { CollectionDataStats } from "@/types/collection-data";
import { UploadDataDialog } from "./UploadDataDialog";
import { useState } from "react";

interface Props {
  collectionId: string;
}

export function DataSection({ collectionId }: Props) {
  const [showUpload, setShowUpload] = useState(false);

  const { data, refetch } = useQuery<{ collectionDataStats: CollectionDataStats }>(
    COLLECTION_DATA_STATS_QUERY,
    { variables: { collectionId } }
  );
  const [deleteMutation] = useMutation(DELETE_TRIPLES_MUTATION);

  const stats = data?.collectionDataStats;

  const handleDeleteDataset = async (dataset: string) => {
    if (!confirm(`Alle Tripel im Dataset "${dataset}" wirklich loeschen?`)) return;
    await deleteMutation({ variables: { collectionId, dataset } });
    refetch();
  };

  const handleDeleteAll = async () => {
    if (!confirm("ALLE Tripel in dieser Collection wirklich loeschen? Diese Aktion kann nicht rueckgaengig gemacht werden.")) return;
    await deleteMutation({ variables: { collectionId, dataset: null } });
    refetch();
  };

  const handleSuccess = () => {
    setShowUpload(false);
    refetch();
  };

  return (
    <section>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-semibold">RDF-Daten</h2>
        <div className="flex gap-2">
          {stats && stats.tripleCount > 0 && (
            <button
              onClick={handleDeleteAll}
              className="rounded-md border border-red-400/50 px-3 py-1.5 text-sm text-red-400 hover:bg-red-400/10"
            >
              Alle loeschen
            </button>
          )}
          <button
            onClick={() => setShowUpload(true)}
            className="rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
          >
            Daten hochladen
          </button>
        </div>
      </div>

      {stats ? (
        <>
          <div className="mb-6 grid grid-cols-3 gap-4">
            <div className="rounded-lg border border-border p-4">
              <div className="text-2xl font-bold">{stats.tripleCount.toLocaleString()}</div>
              <div className="text-sm text-muted-foreground">Tripel</div>
            </div>
            <div className="rounded-lg border border-border p-4">
              <div className="text-2xl font-bold">{stats.entityCount.toLocaleString()}</div>
              <div className="text-sm text-muted-foreground">Entitaeten</div>
            </div>
            <div className="rounded-lg border border-border p-4">
              <div className="text-2xl font-bold">{stats.predicateCount.toLocaleString()}</div>
              <div className="text-sm text-muted-foreground">Praedikate</div>
            </div>
          </div>

          {stats.datasets.length > 0 && (
            <div className="overflow-hidden rounded-lg border border-border">
              <table className="w-full text-sm">
                <thead className="bg-muted/50">
                  <tr>
                    <th className="px-4 py-2 text-left font-medium">Dataset</th>
                    <th className="px-4 py-2 text-right font-medium">Aktion</th>
                  </tr>
                </thead>
                <tbody>
                  {stats.datasets.map((ds) => (
                    <tr key={ds} className="border-t border-border">
                      <td className="px-4 py-2 font-mono text-xs">{ds}</td>
                      <td className="px-4 py-2 text-right">
                        <button
                          onClick={() => handleDeleteDataset(ds)}
                          className="text-xs text-red-400 hover:text-red-300"
                        >
                          Loeschen
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      ) : (
        <p className="text-sm text-muted-foreground">Keine Daten vorhanden.</p>
      )}

      {showUpload && (
        <UploadDataDialog collectionId={collectionId} onClose={() => setShowUpload(false)} onSuccess={handleSuccess} />
      )}
    </section>
  );
}
```

- [ ] **Step 3: Add DataSection to collection detail page**

Update `frontend/src/app/collections/[id]/page.tsx` — add the import and component:

```tsx
import { DataSection } from "@/components/collections/DataSection";
```

And add after `<OntologySection>`:

```tsx
<div className="space-y-10">
  <OntologySection collectionId={id} />
  <DataSection collectionId={id} />
</div>
```

- [ ] **Step 4: Check that IMPORT_RDF_MUTATION exists in frontend/src/graphql/admin.ts**

The existing `importRdf` mutation should already be defined there. If not, add it:

```typescript
export const IMPORT_RDF_MUTATION = gql`
  mutation ImportRdf($input: ImportRdfInput!) {
    importRdf(input: $input) {
      tripleCount
      skippedCount
      durationMs
      embeddingsGenerated
    }
  }
`;
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/collections/DataSection.tsx \
        frontend/src/components/collections/UploadDataDialog.tsx \
        frontend/src/app/collections/\[id\]/page.tsx
git commit -m "feat(frontend): add data section with upload, stats and delete to collection detail page"
```

---

### Task 10: Full Build Verification and Final Commit

**Files:** None new — verification only.

- [ ] **Step 1: Backend full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Frontend type check**

Run: `cd frontend && npx tsc --noEmit`
Expected: No new errors (pre-existing test errors are OK)

- [ ] **Step 3: Start dev server and verify pages**

Run: `./start.sh`

Verify:
1. `/collections` — shows collection list
2. `/collections/<id>` — shows detail page with Ontologien and RDF-Daten sections
3. Upload ontology dialog works
4. Assign existing ontology dialog works
5. Upload RDF data dialog works
6. Stats display correctly
7. Delete dataset/all works

- [ ] **Step 4: Create done file**

Create `docs/features/47-collection-data-management-done.md` documenting the implementation and any deviations.
