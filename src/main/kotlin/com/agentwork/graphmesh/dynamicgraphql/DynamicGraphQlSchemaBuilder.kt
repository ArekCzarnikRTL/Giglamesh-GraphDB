package com.agentwork.graphmesh.dynamicgraphql

import com.agentwork.graphmesh.collection.CollectionOntologyService
import com.agentwork.graphmesh.ontology.DatatypeProperty
import com.agentwork.graphmesh.ontology.ObjectProperty
import com.agentwork.graphmesh.ontology.Ontology
import com.agentwork.graphmesh.ontology.OntologyCache
import com.agentwork.graphmesh.storage.QuadStore
import graphql.Scalars
import graphql.language.IntValue
import graphql.schema.FieldCoordinates
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
            val props = propertiesByDomain[classId]

            val filterType = buildFilterType(classId, props?.datatypeProperties ?: emptyList())
            if (filterType != null) filterTypes[classId] = filterType

            val objectType = buildObjectType(classId, props)
            objectTypes[classId] = objectType

            registerFieldFetchers(codeRegistry, collectionId, classId, props)
        }

        val queryType = buildQueryType(ontology, objectTypes, filterTypes)
        registerQueryFetchers(codeRegistry, collectionId, ontology)

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

    private fun buildObjectType(className: String, props: ClassProperties?): GraphQLObjectType {
        val builder = GraphQLObjectType.newObject().name(className)
        builder.field(
            GraphQLFieldDefinition.newFieldDefinition()
                .name("id").type(GraphQLNonNull.nonNull(Scalars.GraphQLID))
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
                fieldBuilder.argument(GraphQLArgument.newArgument().name("limit").type(Scalars.GraphQLInt).defaultValueLiteral(IntValue.of(10)).build())
                fieldBuilder.argument(GraphQLArgument.newArgument().name("offset").type(Scalars.GraphQLInt).defaultValueLiteral(IntValue.of(0)).build())
            }
            builder.field(fieldBuilder)
        }
        return builder.build()
    }

    private fun buildFilterType(className: String, datatypeProperties: List<DatatypeProperty>): GraphQLInputObjectType? {
        if (datatypeProperties.isEmpty()) return null
        val builder = GraphQLInputObjectType.newInputObject().name("${className}Filter")
        datatypeProperties.forEach { dp ->
            builder.field(
                GraphQLInputObjectField.newInputObjectField()
                    .name(dp.id).type(XsdScalarMapping.resolve(dp.range))
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
                .argument(GraphQLArgument.newArgument().name("limit").type(Scalars.GraphQLInt).defaultValueLiteral(IntValue.of(20)).build())
                .argument(GraphQLArgument.newArgument().name("offset").type(Scalars.GraphQLInt).defaultValueLiteral(IntValue.of(0)).build())
            filterTypes[classId]?.let { filterType ->
                listFieldBuilder.argument(GraphQLArgument.newArgument().name("filter").type(filterType))
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
        props: ClassProperties?,
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
