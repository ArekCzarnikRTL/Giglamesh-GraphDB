package com.agentwork.graphmesh.cli

import com.expediagroup.graphql.client.types.GraphQLClientRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.test.assertFailsWith

class GraphQlGatewayTest {

    private data class FakeData(val value: String)
    private class FakeRequest : GraphQLClientRequest<FakeData> {
        override val query: String = "query { __typename }"
        override fun responseType(): KClass<FakeData> = FakeData::class
    }

    @Test
    fun `execute throws when connecting to unreachable endpoint`() = runBlocking {
        val cfg = CliConfig(
            endpoint = "http://127.0.0.1:1/graphql",  // unroutable
            token = "",
            format = OutputFormat.TABLE
        )
        val gateway = GraphQlGateway(cfg)
        // Connecting will fail (CIO will throw); any Throwable is acceptable.
        assertFailsWith<Throwable> {
            gateway.execute(FakeRequest())
        }
        gateway.close()
    }
}
