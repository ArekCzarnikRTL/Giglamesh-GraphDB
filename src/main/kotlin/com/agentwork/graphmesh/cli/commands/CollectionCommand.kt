package com.agentwork.graphmesh.cli.commands

import com.agentwork.graphmesh.cli.BaseCommand
import com.agentwork.graphmesh.cli.generated.CreateCollection
import com.agentwork.graphmesh.cli.generated.DeleteCollection
import com.agentwork.graphmesh.cli.generated.ListCollections
import com.agentwork.graphmesh.cli.generated.inputs.CreateCollectionInput
import com.agentwork.graphmesh.cli.output.CollectionView
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option

class CollectionCommand : BaseCommand("collection") {
    init {
        subcommands(CollectionList(), CollectionCreate(), CollectionDelete())
    }

    override suspend fun run() = Unit
}

class CollectionList : BaseCommand("list") {
    private val tags by option("--tag", help = "Filter by tag (repeat for multiple)").multiple()

    override suspend fun run() {
        val result = gateway().execute(
            ListCollections(ListCollections.Variables(tags = tags.ifEmpty { null }))
        )
        val items = result.collections.map {
            CollectionView(
                id = it.id,
                name = it.name,
                description = it.description,
                tags = it.tags,
                createdAt = it.createdAt
            )
        }
        out.writeCollections(items)
    }
}

class CollectionCreate : BaseCommand("create") {
    private val name by argument(help = "Collection name")
    private val description by option("--description", "-d", help = "Collection description")
    private val tags by option("--tag", help = "Tag (repeat for multiple)").multiple()

    override suspend fun run() {
        val input = CreateCollectionInput(
            name = name,
            description = description,
            tags = tags.ifEmpty { null },
            metadata = null
        )
        val result = gateway().execute(CreateCollection(CreateCollection.Variables(input = input)))
        val c = result.createCollection
        out.writeCollectionCreated(
            CollectionView(
                id = c.id,
                name = c.name,
                description = c.description,
                tags = c.tags,
                createdAt = c.createdAt
            )
        )
    }
}

class CollectionDelete : BaseCommand("delete") {
    private val id by argument(help = "Collection ID")

    override suspend fun run() {
        gateway().execute(DeleteCollection(DeleteCollection.Variables(id = id)))
        out.writeCollectionDeleted(id)
    }
}
