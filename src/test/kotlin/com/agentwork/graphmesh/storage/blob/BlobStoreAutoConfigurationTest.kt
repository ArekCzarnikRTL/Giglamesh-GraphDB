package com.agentwork.graphmesh.storage.blob

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments

class BlobStoreAutoConfigurationTest {

    private val config = BlobStoreAutoConfiguration()

    @Test
    fun `creates default bucket when autoCreateBuckets is true`() {
        val blobStore = mockk<BlobStore>(relaxed = true)
        val props = BlobStoreProperties(autoCreateBuckets = true, defaultBucket = "graphmesh")

        config.blobStoreInitializer(blobStore, props).run(DefaultApplicationArguments())

        verify(exactly = 1) { blobStore.ensureBucket("graphmesh") }
    }

    @Test
    fun `does not create bucket when autoCreateBuckets is false`() {
        val blobStore = mockk<BlobStore>(relaxed = true)
        val props = BlobStoreProperties(autoCreateBuckets = false, defaultBucket = "graphmesh")

        config.blobStoreInitializer(blobStore, props).run(DefaultApplicationArguments())

        verify(exactly = 0) { blobStore.ensureBucket(any()) }
    }
}
