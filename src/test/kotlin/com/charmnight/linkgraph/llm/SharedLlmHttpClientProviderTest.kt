package com.charmnight.linkgraph.llm

import kotlin.test.Test
import kotlin.test.assertSame

class SharedLlmHttpClientProviderTest {
    @Test
    fun normalizesTimeoutsIntoBoundedClientBuckets() {
        val oneSecond = SharedLlmHttpClientProvider.clientForTimeoutSeconds(0)
        assertSame(oneSecond, SharedLlmHttpClientProvider.clientForTimeoutSeconds(1))

        val fiveSeconds = SharedLlmHttpClientProvider.clientForTimeoutSeconds(2)
        assertSame(fiveSeconds, SharedLlmHttpClientProvider.clientForTimeoutSeconds(5))

        val tenSeconds = SharedLlmHttpClientProvider.clientForTimeoutSeconds(6)
        assertSame(tenSeconds, SharedLlmHttpClientProvider.clientForTimeoutSeconds(10))

        val thirtySeconds = SharedLlmHttpClientProvider.clientForTimeoutSeconds(11)
        assertSame(thirtySeconds, SharedLlmHttpClientProvider.clientForTimeoutSeconds(30))

        val sixtySeconds = SharedLlmHttpClientProvider.clientForTimeoutSeconds(31)
        assertSame(sixtySeconds, SharedLlmHttpClientProvider.clientForTimeoutSeconds(60))

        val maximumBucket = SharedLlmHttpClientProvider.clientForTimeoutSeconds(61)
        assertSame(maximumBucket, SharedLlmHttpClientProvider.clientForTimeoutSeconds(120))
        assertSame(maximumBucket, SharedLlmHttpClientProvider.clientForTimeoutSeconds(3_600))
    }
}
