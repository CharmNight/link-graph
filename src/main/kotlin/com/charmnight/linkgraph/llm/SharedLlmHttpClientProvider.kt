package com.charmnight.linkgraph.llm

import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

internal object SharedLlmHttpClientProvider {
    private val clients = ConcurrentHashMap<Int, HttpClient>()

    fun clientFor(request: LlmRequest): HttpClient {
        return clientForTimeoutSeconds(request.timeoutSeconds)
    }

    fun clientForTimeoutSeconds(timeoutSeconds: Int): HttpClient {
        val timeoutBucket = timeoutBucketSeconds(timeoutSeconds)
        return clients.computeIfAbsent(timeoutBucket) { seconds ->
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(seconds.toLong()))
                .build()
        }
    }

    private fun timeoutBucketSeconds(timeoutSeconds: Int): Int =
        when {
            timeoutSeconds <= 1 -> 1
            timeoutSeconds <= 5 -> 5
            timeoutSeconds <= 10 -> 10
            timeoutSeconds <= 30 -> 30
            timeoutSeconds <= 60 -> 60
            else -> 120
        }
}
