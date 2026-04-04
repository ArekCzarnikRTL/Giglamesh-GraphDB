package com.agentwork.graphmesh.storage.vector

import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(VectorStoreProperties::class)
class VectorStoreAutoConfiguration {

    @Bean
    fun qdrantClient(props: VectorStoreProperties): QdrantClient {
        val builder = QdrantGrpcClient.newBuilder(props.host, props.grpcPort, props.useTls)
        props.apiKey?.let { builder.withApiKey(it) }
        return QdrantClient(builder.build())
    }

    @Bean
    fun vectorStore(qdrantClient: QdrantClient): VectorStore =
        QdrantVectorStore(qdrantClient)
}
