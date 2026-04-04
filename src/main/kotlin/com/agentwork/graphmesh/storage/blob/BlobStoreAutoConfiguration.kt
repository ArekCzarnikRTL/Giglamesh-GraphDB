package com.agentwork.graphmesh.storage.blob

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

@Configuration
@EnableConfigurationProperties(BlobStoreProperties::class)
class BlobStoreAutoConfiguration {

    @Bean
    fun s3Client(props: BlobStoreProperties): S3Client =
        S3Client.builder()
            .endpointOverride(URI.create(props.endpoint))
            .region(Region.of(props.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey, props.secretKey)
                )
            )
            .forcePathStyle(props.pathStyleAccess)
            .build()

    @Bean
    fun s3Presigner(props: BlobStoreProperties): S3Presigner =
        S3Presigner.builder()
            .endpointOverride(URI.create(props.endpoint))
            .region(Region.of(props.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey, props.secretKey)
                )
            )
            .build()

    @Bean
    fun blobStore(s3Client: S3Client, s3Presigner: S3Presigner): BlobStore =
        S3BlobStore(s3Client, s3Presigner)
}
