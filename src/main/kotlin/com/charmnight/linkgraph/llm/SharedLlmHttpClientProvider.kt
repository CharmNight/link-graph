package com.charmnight.linkgraph.llm

import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * LLM HTTP 客户端的共享池。
 *
 * 不同请求可能有不同的超时需求，但每次新建 HttpClient 都要建立连接池、初始化 SSL 等开销。
 * 本池按"超时桶"复用 HttpClient：相同桶的请求共享同一 client，避免重复构造。
 * 桶值经过量化（1/5/10/30/60/120 秒），保证桶数量有限。
 */
internal object SharedLlmHttpClientProvider {
    /** 桶 → client 的并发安全映射。 */
    private val clients = ConcurrentHashMap<Int, HttpClient>()

    /** 根据 LLM 请求的超时取得合适的 client。 */
    fun clientFor(request: LlmRequest): HttpClient {
        return clientForTimeoutSeconds(request.timeoutSeconds)
    }

    /** 根据超时秒数取得（或惰性创建）client。 */
    fun clientForTimeoutSeconds(timeoutSeconds: Int): HttpClient {
        // 量化到桶值，避免 1s 和 2s 各自占用一个 client
        val timeoutBucket = timeoutBucketSeconds(timeoutSeconds)
        return clients.computeIfAbsent(timeoutBucket) { seconds ->
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(seconds.toLong()))
                .build()
        }
    }

    /**
     * 把任意超时秒数映射到有限的桶值。
     * 桶值经过经验性选择，覆盖常见超时档位。
     */
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
