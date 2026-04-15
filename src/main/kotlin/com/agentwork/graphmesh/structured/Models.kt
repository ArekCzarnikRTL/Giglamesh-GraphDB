package com.agentwork.graphmesh.structured

import com.fasterxml.jackson.annotation.JsonIgnore

enum class ColumnType(val cassandraType: String) {
    STRING("text"),
    INTEGER("int"),
    LONG("bigint"),
    FLOAT("float"),
    DOUBLE("double"),
    BOOLEAN("boolean"),
    TIMESTAMP("timestamp");

    companion object {
        private val synonyms: Map<String, ColumnType> = mapOf(
            "TEXT" to STRING, "VARCHAR" to STRING, "CHAR" to STRING, "UUID" to STRING,
            "JSON" to STRING, "OBJECT" to STRING, "ARRAY" to STRING, "LIST" to STRING, "MAP" to STRING,
            "INT" to INTEGER, "INTEGER" to INTEGER, "SMALLINT" to INTEGER,
            "BIGINT" to LONG, "LONG" to LONG,
            "REAL" to FLOAT, "FLOAT" to FLOAT,
            "DOUBLE" to DOUBLE, "NUMERIC" to DOUBLE, "DECIMAL" to DOUBLE, "NUMBER" to DOUBLE,
            "BOOL" to BOOLEAN, "BOOLEAN" to BOOLEAN,
            "DATE" to TIMESTAMP, "DATETIME" to TIMESTAMP, "TIMESTAMP" to TIMESTAMP, "TIME" to TIMESTAMP
        )

        fun fromString(value: String): ColumnType {
            val base = value.trim().substringBefore('[').substringBefore('<').substringBefore('(')
                .trim().uppercase()
            entries.firstOrNull { it.name == base }?.let { return it }
            synonyms[base]?.let { return it }
            return STRING
        }
    }
}

data class ColumnDescriptor(
    val name: String,
    val type: ColumnType,
    val nullable: Boolean = true,
    val description: String? = null,
    val primaryKey: Boolean = false,
    val indexed: Boolean = false
)

data class IndexDefinition(val fields: List<String>)

data class TableSchema(
    val name: String,
    val description: String? = null,
    val version: String = "1.0.0",
    val columns: List<ColumnDescriptor>,
    val indexes: List<IndexDefinition> = emptyList()
) {
    @get:JsonIgnore
    val primaryKeyColumns: List<ColumnDescriptor>
        get() = columns.filter { it.primaryKey }

    @get:JsonIgnore
    val indexedColumns: List<ColumnDescriptor>
        get() = columns.filter { it.indexed && !it.primaryKey }

    @get:JsonIgnore
    val allIndexNames: List<String>
        get() {
            val names = mutableListOf<String>()
            if (primaryKeyColumns.isNotEmpty()) {
                names.add(primaryKeyColumns.sortedBy { it.name }.joinToString(",") { it.name })
            }
            names.addAll(indexedColumns.map { it.name })
            names.addAll(indexes.map { it.fields.sorted().joinToString(",") })
            return names.distinct()
        }
}

data class DataRow(
    val collection: String,
    val schemaName: String,
    val values: Map<String, String>,
    val source: String? = null
)

data class StructuredQuery(
    val collection: String,
    val schemaName: String,
    val indexName: String,
    val indexValue: List<String>,
    val limit: Int = 100
)

data class QueryResult(
    val rows: List<DataRow>,
    val totalCount: Int,
    val hasMore: Boolean
)

data class StoreResult(
    val success: Boolean,
    val rowsWritten: Int = 0,
    val error: String? = null
)
