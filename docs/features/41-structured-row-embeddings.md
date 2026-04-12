# Feature 41: Structured Row Embeddings

## Problem

Mit Feature 22 (Structured Data Storage) und Feature 23 (Structured Data Extractor)
speichert GraphMesh tabellarische Daten -- z.B. Kundenverzeichnisse, Produktkataloge,
Labor-Messwerte -- im `CassandraRowStore`. Abgefragt werden diese Zeilen bislang nur
strukturiert: `StructuredQuery(collection, schemaName, indexName, indexValue)`.

Das reicht fuer bekannte Zugriffs-Patterns (`kundenNr = 12345`), aber nicht fuer
semantische Fragen:

1. **"Finde Kunden aehnlich zu X"** -- Aehnlichkeit ueber mehrere Spalten hinweg.
2. **"Welche Produkte passen zur Beschreibung '...'"** -- Natuerlichsprachliche Anfrage
   ueber tabellarische Daten.
3. **"Rows, die thematisch zu diesem Chunk passen"** -- Cross-Modal: Dokument-Chunk
   ist Query, Tabellenzeilen sind Treffer.
4. **RAG ueber Strukturdaten** -- DocRAG/GraphRAG kennt nur Text/Triples; strukturierte
   Zeilen sind "unsichtbar" fuer den Retrieval-Schritt.

XGraph loest das mit "Row/Structured Embeddings": jede Zeile wird zu einem Embedding
serialisiert und in einer separaten Vector-Collection abgelegt. GraphMesh hat die
Bausteine (Qdrant, `EmbeddingService`, `StructuredDataService`), aber keinen
dedizierten Row-Embedding-Pfad.

## Ziel

Einfuehrung eines `RowEmbeddingService`, der Zeilen aus dem `CassandraRowStore` in
strukturierte Text-Darstellungen umwandelt, embedded und in einer dedizierten
Qdrant-Collection speichert -- inklusive Kafka-Integration fuer laufende Updates.

1. **Row-Serializer** -- konfigurierbare Text-Darstellung pro `TableSchema`
   ("Name: Max Mustermann. Stadt: Berlin. Rolle: CTO.").
2. **Embedding-Pipeline** -- wiederverwendet `LLMEmbeddingProvider` aus Feature 13;
   dieselbe Modell-Resolver-Logik (`resolveLlmModel`).
3. **Vector-Collection-Namen** -- `rows_<schemaName>_<dimension>` via
   `CollectionNaming.physicalName`.
4. **Payload** -- jede VectorPoint enthaelt `collection`, `schemaName`, `rowPk`,
   `source`, `serializedText` sowie ausgewaehlte Original-Spalten fuer den Schnellzugriff.
5. **Kafka-Consumer** -- laufende Updates via `graphmesh.row.upserted` bzw. direkt beim
   Aufruf von `StructuredDataService.store`.
6. **Query-API** -- `RowSimilaritySearchService.search(schemaName, query, topK, filter)`
   liefert Rows samt Score.
7. **GraphQL-Erweiterung** -- `rowSimilaritySearch(...)`-Feld fuer die Query-UI.

## Voraussetzungen

| Abhaengigkeit                                                        | Status    | Blocker? |
|----------------------------------------------------------------------|-----------|----------|
| Feature 04: Qdrant Vector Store (`VectorStore`, `CollectionNaming`)  | Vorhanden | Ja       |
| Feature 05: LLM Provider Abstraction (`resolveLlmModel`)             | Vorhanden | Ja       |
| Feature 13: Document Embeddings (`LLMEmbeddingProvider`-Bean)        | Vorhanden | Ja       |
| Feature 22: Structured Data Storage (`CassandraRowStore`, `TableSchema`) | Vorhanden | Ja   |
| Feature 23: Structured Data Extractor                                | Empfohlen | Nein     |
| Feature 14: GraphQL API                                              | Vorhanden | Ja       |

## Architektur

### Konfiguration am TableSchema

`TableSchema` bekommt einen optionalen Serializer-Hinweis pro Spalte und ein
Schema-weites Embedding-Profil:

```kotlin
package com.agentwork.graphmesh.structured

// Erweiterung vorhandener Datenklassen in src/main/kotlin/com/agentwork/graphmesh/structured/Models.kt

data class EmbeddingProfile(
    val enabled: Boolean = false,
    /** Welche Spalten in die Text-Darstellung einfliessen. Leere Liste = alle. */
    val includeColumns: List<String> = emptyList(),
    /** Spalten, deren Werte als Tags/Payload mitgegeben werden. */
    val payloadColumns: List<String> = emptyList(),
    /** Optionales Template, z.B. "Kunde {{ name }} ({{ city }}): {{ notes }}". */
    val template: String? = null
)

data class TableSchema(
    val name: String,
    val description: String? = null,
    val version: String = "1.0.0",
    val columns: List<ColumnDescriptor>,
    val indexes: List<IndexDefinition> = emptyList(),
    val embedding: EmbeddingProfile = EmbeddingProfile()   // NEU
)
```

### RowSerializer

```kotlin
package com.agentwork.graphmesh.structured.embedding

import com.agentwork.graphmesh.structured.DataRow
import com.agentwork.graphmesh.structured.TableSchema

/**
 * Wandelt eine Zeile in einen kompakten Text fuer das Embedding-Modell um.
 * Drei Modi:
 *  1. Template (`"Kunde {{ name }} ({{ city }}): {{ notes }}"`)
 *  2. Explizite `includeColumns` -> "col: value. col2: value2."
 *  3. Fallback: alle non-null-Spalten in der Schema-Reihenfolge.
 */
object RowSerializer {

    fun serialize(row: DataRow, schema: TableSchema): String {
        val profile = schema.embedding
        val cols = if (profile.includeColumns.isNotEmpty()) profile.includeColumns
        else schema.columns.map { it.name }

        profile.template?.let { tpl ->
            return applyTemplate(tpl, row.values)
        }

        return cols.mapNotNull { colName ->
            row.values[colName]?.takeIf { it.isNotBlank() }?.let { "$colName: $it" }
        }.joinToString(". ")
    }

    internal fun applyTemplate(template: String, values: Map<String, String>): String {
        val pattern = Regex("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\}\\}")
        return pattern.replace(template) { m -> values[m.groupValues[1]] ?: "" }.trim()
    }
}
```

### RowEmbeddingService

```kotlin
package com.agentwork.graphmesh.structured.embedding

import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import com.agentwork.graphmesh.extraction.embedding.EmbeddingConfig
import com.agentwork.graphmesh.llm.resolveLlmModel
import com.agentwork.graphmesh.storage.vector.CollectionNaming
import com.agentwork.graphmesh.storage.vector.VectorPoint
import com.agentwork.graphmesh.storage.vector.VectorStore
import com.agentwork.graphmesh.structured.DataRow
import com.agentwork.graphmesh.structured.SchemaStore
import com.agentwork.graphmesh.structured.TableSchema
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class RowEmbeddingService(
    private val embeddingProvider: LLMEmbeddingProvider,
    private val vectorStore: VectorStore,
    private val schemaStore: SchemaStore,
    private val config: EmbeddingConfig
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun embedRow(row: DataRow): EmbedRowResult {
        val schema = schemaStore.load(row.schemaName)
            ?: return EmbedRowResult.SchemaMissing(row.schemaName)

        if (!schema.embedding.enabled) {
            return EmbedRowResult.Skipped("Embedding disabled for schema ${schema.name}")
        }

        val text = RowSerializer.serialize(row, schema)
        if (text.isBlank()) {
            return EmbedRowResult.Skipped("Empty serialization for row pk=${primaryKey(row, schema)}")
        }

        val model = resolveLlmModel(config.model)
        val embedding = runBlocking { embeddingProvider.embed(text, model) }
        val vector = FloatArray(embedding.size) { embedding[it].toFloat() }

        val pk = primaryKey(row, schema)
        val pointId = "${row.collection}::${schema.name}::$pk"
        val payload = buildPayload(row, schema, text)

        val collectionName = rowCollectionName(schema.name)
        vectorStore.upsert(
            CollectionNaming.physicalName(collectionName, vector.size),
            listOf(VectorPoint(pointId, vector, payload))
        )

        logger.debug("Embedded row: schema={}, pk={}, dim={}", schema.name, pk, vector.size)
        return EmbedRowResult.Ok(pointId, vector.size)
    }

    fun embedBatch(rows: List<DataRow>) {
        rows.forEach { embedRow(it) }
    }

    private fun rowCollectionName(schemaName: String): String = "rows_$schemaName"

    private fun primaryKey(row: DataRow, schema: TableSchema): String =
        schema.primaryKeyColumns.joinToString("|") { col -> row.values[col.name].orEmpty() }

    private fun buildPayload(row: DataRow, schema: TableSchema, text: String): Map<String, Any> {
        val out = mutableMapOf<String, Any>(
            "collection" to row.collection,
            "schema" to schema.name,
            "primaryKey" to primaryKey(row, schema),
            "serializedText" to text
        )
        for (col in schema.embedding.payloadColumns) {
            row.values[col]?.let { out[col] = it }
        }
        row.source?.let { out["source"] = it }
        return out
    }
}

sealed class EmbedRowResult {
    data class Ok(val pointId: String, val dimension: Int) : EmbedRowResult()
    data class Skipped(val reason: String) : EmbedRowResult()
    data class SchemaMissing(val schemaName: String) : EmbedRowResult()
}
```

### Integration in StructuredDataService

`StructuredDataService.store`/`storeBatch` ruft nach erfolgreichem Cassandra-Insert den
`RowEmbeddingService` auf -- aber nur, wenn das Schema `embedding.enabled = true` hat:

```kotlin
// src/main/kotlin/com/agentwork/graphmesh/structured/StructuredDataService.kt

fun store(row: DataRow): StoreResult {
    val schema = schemaStore.load(row.schemaName)
        ?: return StoreResult(success = false, error = "Schema '${row.schemaName}' not found")

    val validationError = validateRow(row, schema)
    if (validationError != null) return StoreResult(success = false, error = validationError)

    rowStore.insert(row)
    if (schema.embedding.enabled) {
        runCatching { rowEmbeddingService.embedRow(row) }
            .onFailure { logger.warn("Row embedding failed for ${schema.name}: ${it.message}") }
    }
    return StoreResult(success = true, rowsWritten = 1)
}
```

Embeddings-Fehler darf das Schreiben **nicht** scheitern lassen (Eventual Consistency).
Ein Reconciliation-Job (siehe unten) holt fehlende Rows nach.

### RowSimilaritySearchService

```kotlin
package com.agentwork.graphmesh.structured.embedding

import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import com.agentwork.graphmesh.extraction.embedding.EmbeddingConfig
import com.agentwork.graphmesh.llm.resolveLlmModel
import com.agentwork.graphmesh.storage.vector.CollectionNaming
import com.agentwork.graphmesh.storage.vector.SearchResult
import com.agentwork.graphmesh.storage.vector.VectorFilter
import com.agentwork.graphmesh.storage.vector.VectorStore
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Service

@Service
class RowSimilaritySearchService(
    private val embeddingProvider: LLMEmbeddingProvider,
    private val vectorStore: VectorStore,
    private val config: EmbeddingConfig
) {

    fun search(
        collection: String,
        schemaName: String,
        queryText: String,
        topK: Int = 20,
        scoreThreshold: Float? = null
    ): List<SearchResult> {
        val model = resolveLlmModel(config.model)
        val query = runBlocking { embeddingProvider.embed(queryText, model) }
        val vector = FloatArray(query.size) { query[it].toFloat() }

        val filter = VectorFilter.And(
            listOf(
                VectorFilter.Equals("collection", collection),
                VectorFilter.Equals("schema", schemaName)
            )
        )

        val physical = CollectionNaming.physicalName("rows_$schemaName", vector.size)
        return vectorStore.search(
            collection = physical,
            queryVector = vector,
            limit = topK,
            filter = filter,
            scoreThreshold = scoreThreshold
        )
    }
}
```

### Reconciliation-Job

Falls Embeddings beim Insert ausfallen (z.B. Ollama offline) oder ein neues Schema
nachtraeglich `embedding.enabled = true` bekommt, baut ein Scheduler fehlende Rows
nach:

```kotlin
package com.agentwork.graphmesh.structured.embedding

import com.agentwork.graphmesh.structured.CassandraRowStore
import com.agentwork.graphmesh.structured.SchemaStore
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class RowEmbeddingReconciler(
    private val rowStore: CassandraRowStore,
    private val schemaStore: SchemaStore,
    private val rowEmbeddingService: RowEmbeddingService
) {

    @Scheduled(fixedDelayString = "\${graphmesh.structured.embedding.reconcileMs:600000}")
    fun run() {
        for (name in schemaStore.listNames()) {
            val schema = schemaStore.load(name) ?: continue
            if (!schema.embedding.enabled) continue
            rowStore.scroll(name).forEach { row -> rowEmbeddingService.embedRow(row) }
        }
    }
}
```

### GraphQL-Erweiterung

```graphql
type RowSearchResult {
  pointId: String!
  score: Float!
  collection: String!
  schema: String!
  primaryKey: String!
  serializedText: String!
  payload: JSON
}

extend type Query {
  rowSimilaritySearch(
    collection: String!,
    schemaName: String!,
    queryText: String!,
    topK: Int = 20,
    scoreThreshold: Float
  ): [RowSearchResult!]!
}
```

### Konfiguration

```yaml
graphmesh:
  structured:
    embedding:
      reconcileMs: 600000   # Reconciler-Intervall
```

Das Embedding-Modell kommt weiterhin aus `graphmesh.embedding.model` (Feature 13).

## Betroffene Dateien

### Backend

| Datei                                                                                                    | Aenderung                                        |
|----------------------------------------------------------------------------------------------------------|--------------------------------------------------|
| `src/main/kotlin/com/agentwork/graphmesh/structured/Models.kt`                                           | UPDATE - `EmbeddingProfile` + Feld in `TableSchema` |
| `src/main/kotlin/com/agentwork/graphmesh/structured/SchemaStore.kt`                                      | UPDATE - persistiert das Profil (JSON-Spalte)    |
| `src/main/kotlin/com/agentwork/graphmesh/structured/StructuredDataService.kt`                            | UPDATE - Post-Insert-Hook auf RowEmbeddingService|
| `src/main/kotlin/com/agentwork/graphmesh/structured/embedding/RowSerializer.kt`                          | NEU - Text-Darstellung pro Schema                |
| `src/main/kotlin/com/agentwork/graphmesh/structured/embedding/RowEmbeddingService.kt`                    | NEU - Embedding-Pipeline fuer Rows               |
| `src/main/kotlin/com/agentwork/graphmesh/structured/embedding/RowSimilaritySearchService.kt`             | NEU - Query-API                                  |
| `src/main/kotlin/com/agentwork/graphmesh/structured/embedding/RowEmbeddingReconciler.kt`                 | NEU - Scheduled Rebuild                          |
| `src/main/kotlin/com/agentwork/graphmesh/api/graphql/RowSearchDataFetcher.kt`                            | NEU - DGS-DataFetcher                            |
| `src/main/resources/schema/row-search.graphqls`                                                          | NEU - GraphQL-Typ `RowSearchResult`              |
| `src/main/resources/application.yml`                                                                    | UPDATE - `graphmesh.structured.embedding.*`      |

### Frontend

| Datei                                                                  | Aenderung                                       |
|------------------------------------------------------------------------|-------------------------------------------------|
| `frontend/src/app/structured/[schema]/search/page.tsx`                 | NEU - Eingabe + Trefferliste                    |
| `frontend/src/components/structured/RowSearchResults.tsx`              | NEU - Tabelle mit Score + Payload               |
| `frontend/src/lib/graphql/rowSearch.ts`                                | NEU - Apollo-Query                              |

### Tests

| Datei                                                                                                    | Aenderung                                        |
|----------------------------------------------------------------------------------------------------------|--------------------------------------------------|
| `src/test/kotlin/com/agentwork/graphmesh/structured/embedding/RowSerializerTest.kt`                      | NEU - Template / includeColumns / Fallback       |
| `src/test/kotlin/com/agentwork/graphmesh/structured/embedding/RowEmbeddingServiceTest.kt`                | NEU - Fake LLMEmbeddingProvider + VectorStore    |
| `src/test/kotlin/com/agentwork/graphmesh/structured/embedding/RowSimilaritySearchServiceTest.kt`         | NEU - Filter, topK, threshold                    |
| `src/test/kotlin/com/agentwork/graphmesh/structured/embedding/StructuredDataServiceEmbeddingTest.kt`     | NEU - Insert triggert Embedding; Fehler bricht Insert nicht ab |
| `src/test/kotlin/com/agentwork/graphmesh/structured/embedding/RowEmbeddingReconcilerTest.kt`             | NEU - Scheduled Rebuild findet Gaps              |

## Platform-Einschraenkungen

| Backend           | Verfuegbar? | Grund                                           |
|-------------------|-------------|-------------------------------------------------|
| Spring Boot (JVM) | Ja          | Koog Embedding Provider + Qdrant-Client JVM-only|

## Akzeptanzkriterien

- [ ] Schemas haben ein optionales `embedding`-Feld; Default = `disabled`.
- [ ] `RowSerializer` unterstuetzt drei Modi: Template, `includeColumns`, Fallback (alle Spalten).
- [ ] `RowEmbeddingService.embedRow` erzeugt genau einen VectorPoint mit deterministischer `pointId` (`collection::schema::pk`).
- [ ] Vector-Collection-Name folgt `rows_<schema>_<dim>` (via `CollectionNaming.physicalName`).
- [ ] Der Payload enthaelt `collection`, `schema`, `primaryKey`, `serializedText` und alle `payloadColumns`.
- [ ] `StructuredDataService.store` ruft bei Erfolg `embedRow` auf; Exceptions im Embedding scheitern den Insert **nicht**.
- [ ] Bei deaktiviertem Embedding passiert kein Vector-Aufruf.
- [ ] `RowSimilaritySearchService.search` filtert per `collection` und `schema` und gibt VectorStore-`SearchResult`-Objekte zurueck.
- [ ] Reconciler laeuft scheduled und schliesst Luecken fuer aktivierte Schemas.
- [ ] GraphQL-Query `rowSimilaritySearch` liefert sortierte Treffer mit Score.
- [ ] Integrationstest: nach `store` von 3 Rows liefert `rowSimilaritySearch` mit einer semantisch passenden Query genau den erwarteten Top-Hit.
- [ ] Das mitverwendete Embedding-Modell kommt aus `graphmesh.embedding.model` (keine Duplikation der Konfiguration).
