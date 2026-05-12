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
        val safeTimeout = timeoutSeconds.coerceAtLeast(1)
        return clients.computeIfAbsent(safeTimeout) { seconds ->
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(seconds.toLong()))
                .build()
        }
    }
}
