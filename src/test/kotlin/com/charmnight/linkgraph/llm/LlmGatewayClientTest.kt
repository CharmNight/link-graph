package com.charmnight.linkgraph.llm

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LlmGatewayClientTest {
    @Test
    fun extractsModelFromObjectResponseWithoutFailingOnInvalidJson() {
        assertEquals("gpt-5.4", LlmGatewayClient.extractModel("""{"model":"gpt-5.4"}"""))
        assertEquals(null, LlmGatewayClient.extractModel("""{"id":"resp_123"}"""))
        assertEquals(null, LlmGatewayClient.extractModel("not json"))
    }

    @Test
    fun parseObjectRejectsInvalidGatewayJson() {
        assertFailsWith<IllegalStateException> {
            LlmGatewayClient.parseObject("""{"model":"gpt"} garbage""")
        }
        assertEquals(null, LlmGatewayClient.parseObjectOrNull("""{"model":"""))
    }

    @Test
    fun buildsFailureMessageFromProviderErrorCodeOrType() {
        val openAiMessage = LlmGatewayClient.buildFailureMessage(
            statusCode = 503,
            body = """{"error":{"code":"model_not_found","message":"No channel"}}""",
        )
        val anthropicMessage = LlmGatewayClient.buildFailureMessage(
            statusCode = 429,
            body = """{"error":{"type":"rate_limit_error","message":"Too many requests"}}""",
            errorCodeKeys = listOf("code", "type"),
        )

        assertEquals("Remote LLM request failed with HTTP 503 (model_not_found): No channel", openAiMessage)
        assertEquals("Remote LLM request failed with HTTP 429 (rate_limit_error): Too many requests", anthropicMessage)
    }

    @Test
    fun extractsSseDataPayloadOnlyFromDataLines() {
        assertEquals("""{"delta":"hi"}""", LlmGatewayClient.extractSseDataPayload(""" data: {"delta":"hi"} """))
        assertEquals("[DONE]", LlmGatewayClient.extractSseDataPayload("data: [DONE]"))
        assertEquals(null, LlmGatewayClient.extractSseDataPayload(": keepalive"))
    }

    @Test
    fun withoutRawBodyStripsRawBodyField() {
        val original = LlmResponse(content = "hello", model = "m", rawBody = """{"raw":"secret"}""")
        val stripped = original.withoutRawBody()
        assertEquals("hello", stripped.content)
        assertEquals("m", stripped.model)
        assertEquals(null, stripped.rawBody, "withoutRawBody 必须把 rawBody 擦成 null")
    }

    @Test
    fun redactForTraceReplacesKeyLookingLines() {
        val input = """
            line one is clean
            api_key: abc123DEF456
            token=Bearer XYZ
            password: hunter2
            normal code here
            apiKey = "shhh"
            SECRET: hidden
        """.trimIndent()
        val redacted = redactForTrace(input)
        val lines = redacted.split("\n")

        assertEquals("line one is clean", lines[0])
        assertEquals("[REDACTED]", lines[1])
        assertEquals("[REDACTED]", lines[2])
        assertEquals("[REDACTED]", lines[3])
        assertEquals("normal code here", lines[4])
        assertEquals("[REDACTED]", lines[5])
        assertEquals("[REDACTED]", lines[6])
    }

    @Test
    fun redactForTraceReplacesAuthorizationAndCookieHeaders() {
        val input = """
            Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
            Cookie: session=abc; theme=dark
            Set-Cookie: jwt=xyz; HttpOnly
            x-api-key: abc123
            session_id: 0xDEADBEEF
            credentials: {"user":"admin","password":"hunter2"}
            access_token:Bearer 12345
            private_key="-----BEGIN RSA PRIVATE KEY-----"
        """.trimIndent()
        val redacted = redactForTrace(input)
        // 所有行均命中关键字，应全部替换
        redacted.split("\n").forEach { line ->
            assertEquals("[REDACTED]", line, "敏感关键字行未脱敏：$line")
        }
    }

    @Test
    fun redactForTraceLeavesNonKeyContentIntact() {
        val input = "val user = User(name = \"Alice\")"
        assertEquals(input, redactForTrace(input))
    }

    @Test
    fun generateJsonSendsRequestAndExtractsStandardResponse() {
        val client = RecordingHttpClient(
            response = SimpleHttpResponse(
                statusCode = 200,
                body = ByteArrayInputStream(
                    """{"model":"remote-model","content":"hello"}""".toByteArray(StandardCharsets.UTF_8),
                ),
            ),
        )
        val request = testLlmRequest()

        val response = LlmGatewayClient.generateJson(
            client = client,
            request = request,
            url = "https://api.example.com/v1/messages",
            headers = listOf("x-api-key" to "secret"),
            payload = """{"prompt":"hello"}""",
            extractContent = { body -> LlmJsonCodec.parseObject(body)["content"] as String },
        )

        assertEquals("hello", response.content)
        assertEquals("remote-model", response.model)
        assertEquals("""{"model":"remote-model","content":"hello"}""", response.rawBody)
        assertEquals(URI.create("https://api.example.com/v1/messages"), client.lastRequest?.uri())
        assertEquals(Optional.of("secret"), client.lastRequest?.headers()?.firstValue("x-api-key"))
    }

    @Test
    fun generateJsonReportsProviderFailureBody() {
        val client = RecordingHttpClient(
            response = SimpleHttpResponse(
                statusCode = 400,
                body = ByteArrayInputStream(
                    """{"error":{"type":"invalid_request_error","message":"Bad request"}}"""
                        .toByteArray(StandardCharsets.UTF_8),
                ),
            ),
        )

        val failure = assertFailsWith<IllegalStateException> {
            LlmGatewayClient.generateJson(
                client = client,
                request = testLlmRequest(),
                url = "https://api.example.com/v1/messages",
                headers = emptyList(),
                payload = "{}",
                errorCodeKeys = listOf("code", "type"),
                extractContent = { error("must not parse failed responses") },
            )
        }

        assertEquals(
            "Remote LLM request failed with HTTP 400 (invalid_request_error): Bad request",
            failure.message,
        )
    }

    @Test
    fun generateJsonAbortsWhenBodyExceedsMaxSize() {
        // 2.5M 字符 > MAX_RESPONSE_CHARS(2M)；服务端返回超大 body 时应提前抛错而非 OOM。
        val hugeBody = ByteArray(2_500_000) { 'a'.code.toByte() }
        val client = RecordingHttpClient(
            response = SimpleHttpResponse(
                statusCode = 200,
                body = ByteArrayInputStream(hugeBody),
            ),
        )

        val failure = assertFailsWith<IllegalStateException> {
            LlmGatewayClient.generateJson(
                client = client,
                request = testLlmRequest(),
                url = "https://api.example.com/v1/messages",
                headers = emptyList(),
                payload = "{}",
                extractContent = { error("must not be called for oversized body") },
            )
        }
        assertTrue(
            failure.message!!.contains("exceeded maximum supported size"),
            "实际：${failure.message}",
        )
    }

    @Test
    fun generateJsonAbortsWhenErrorBodyExceedsMaxSize() {
        // 5xx + 超大错误 body：error 路径也应走 size guard，不应尝试解析错误 JSON。
        val hugeErrorBody = ByteArray(2_500_000) { 'a'.code.toByte() }
        val client = RecordingHttpClient(
            response = SimpleHttpResponse(
                statusCode = 500,
                body = ByteArrayInputStream(hugeErrorBody),
            ),
        )

        val failure = assertFailsWith<IllegalStateException> {
            LlmGatewayClient.generateJson(
                client = client,
                request = testLlmRequest(),
                url = "https://api.example.com/v1/messages",
                headers = emptyList(),
                payload = "{}",
                extractContent = { error("must not be called for oversized body") },
            )
        }
        assertTrue(
            failure.message!!.contains("exceeded maximum supported size"),
            "实际：${failure.message}",
        )
    }

    @Test
    fun generateJsonHandlesUtf8MultiByteAcrossChunkBoundary() {
        // 构造一个 8190 字节 ASCII 填充 + 3 字节汉字（“中”）+ 余下 ASCII 的 body。
        // readBodyWithSizeGuard 用 8192 字符 chunk 读；汉字跨 chunk 边界时若用
        // 字节切片方案会产生 U+FFFD；用 InputStreamReader 解码器跨 chunk 缓冲则正确还原。
        val prefix = "a".repeat(8190)
        val chineseChar = "中"  // UTF-8 占 3 字节
        val suffix = "b".repeat(50)
        val rawBody = """{"model":"m","content":"$prefix$chineseChar$suffix"}"""
        val client = RecordingHttpClient(
            response = SimpleHttpResponse(
                statusCode = 200,
                body = ByteArrayInputStream(rawBody.toByteArray(StandardCharsets.UTF_8)),
            ),
        )

        val response = LlmGatewayClient.generateJson(
            client = client,
            request = testLlmRequest(),
            url = "https://api.example.com/v1/messages",
            headers = emptyList(),
            payload = "{}",
            extractContent = { body -> LlmJsonCodec.parseObject(body)["content"] as String },
        )

        assertTrue(
            response.content.contains("中"),
            "UTF-8 多字节字符跨 chunk 边界后应完整保留，不应被替换为 U+FFFD；实际：${response.content.takeLast(60)}",
        )
        assertEquals(8190 + 1 + 50, response.content.length)
    }

    @Test
    fun streamSseAbortsWhenErrorBodyExceedsMaxSize() {
        // streamSse 在非 2xx 时也要 size guard；恶意服务器可能返回超大错误 JSON。
        val hugeErrorBody = ByteArray(2_500_000) { 'a'.code.toByte() }
        val client = RecordingHttpClient(
            response = SimpleHttpResponse(
                statusCode = 500,
                body = ByteArrayInputStream(hugeErrorBody),
            ),
        )

        val failure = assertFailsWith<IllegalStateException> {
            LlmGatewayClient.streamSse(
                client = client,
                request = testLlmRequest(),
                url = "https://api.example.com/v1/responses",
                headers = emptyList(),
                payload = """{"stream":true}""",
                listener = { /* no-op */ },
                extractTextDelta = { error("must not be called for oversized error body") },
            )
        }
        assertTrue(
            failure.message!!.contains("exceeded maximum supported size"),
            "实际：${failure.message}",
        )
    }

    @Test
    fun streamSseEmitsDeltasAndFinalResponseFromSharedLoop() {
        val body = """
            : keepalive
            data: {"delta":"hel"}
            data: {"delta":"lo"}
            data: [DONE]
        """.trimIndent()
        val client = RecordingHttpClient(
            response = SimpleHttpResponse(
                statusCode = 200,
                body = ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8)),
            ),
        )
        val events = mutableListOf<LlmStreamEvent>()

        val response = LlmGatewayClient.streamSse(
            client = client,
            request = testLlmRequest(),
            url = "https://api.example.com/v1/responses",
            headers = listOf("Authorization" to "Bearer secret"),
            payload = """{"stream":true}""",
            listener = events::add,
            extractTextDelta = { data -> LlmJsonCodec.parseObject(data)["delta"] as? String },
        )

        assertEquals("hello", response.content)
        assertEquals("gpt-5.4", response.model)
        assertTrue(response.rawBody.orEmpty().contains("""data: {"delta":"hel"}"""))
        assertEquals(LlmStreamEvent.Started(model = "gpt-5.4"), events[0])
        assertEquals(LlmStreamEvent.TextDelta("hel"), events[1])
        assertEquals(LlmStreamEvent.TextDelta("lo"), events[2])
        assertEquals(LlmStreamEvent.Completed(response), events[3])
    }

    @Test
    fun generateJsonRetriesOn429AndSucceeds() {
        // 队列：第 1 次 429（带 Retry-After: 0，避免测试 sleep），第 2 次 200。
        val client = QueueHttpClient(
            responses = listOf(
                SimpleHttpResponse(
                    statusCode = 429,
                    body = ByteArrayInputStream("""{"error":"rate limited"}""".toByteArray(StandardCharsets.UTF_8)),
                    headerMap = mapOf("retry-after" to listOf("0")),
                ),
                SimpleHttpResponse(
                    statusCode = 200,
                    body = ByteArrayInputStream("""{"model":"m","content":"after-retry"}""".toByteArray(StandardCharsets.UTF_8)),
                ),
            ),
        )

        val response = LlmGatewayClient.generateJson(
            client = client,
            request = testLlmRequest(),
            url = "https://api.example.com/v1/messages",
            headers = emptyList(),
            payload = "{}",
            extractContent = { body -> LlmJsonCodec.parseObject(body)["content"] as String },
        )

        assertEquals("after-retry", response.content)
        assertEquals(2, client.sendCount, "首次 429 + 重试成功 = 共 2 次 send")
    }

    @Test
    fun generateJsonRetriesOn503UpToThreeAttemptsThenFails() {
        // 连续 3 次 503，全部用 Retry-After: 0 保持测试快速。
        val client = QueueHttpClient(
            responses = (1..3).map {
                SimpleHttpResponse(
                    statusCode = 503,
                    body = ByteArrayInputStream("""{"error":"unavailable"}""".toByteArray(StandardCharsets.UTF_8)),
                    headerMap = mapOf("retry-after" to listOf("0")),
                )
            },
        )

        val failure = assertFailsWith<LlmHttpException> {
            LlmGatewayClient.generateJson(
                client = client,
                request = testLlmRequest(),
                url = "https://api.example.com/v1/messages",
                headers = emptyList(),
                payload = "{}",
                extractContent = { error("must not be called for failed responses") },
            )
        }
        assertEquals(503, failure.statusCode)
        assertEquals(3, client.sendCount, "DEFAULT_MAX_ATTEMPTS=3，重试耗尽后抛")
    }

    @Test
    fun generateJsonDoesNotRetryOn400() {
        // 400 不是可重试状态，只应 send 一次。QueueHttpClient 在第二次 send 时抛 "队列耗尽"。
        val client = QueueHttpClient(
            responses = listOf(
                SimpleHttpResponse(
                    statusCode = 400,
                    body = ByteArrayInputStream(
                        """{"error":{"type":"invalid_request_error","message":"Bad request"}}"""
                            .toByteArray(StandardCharsets.UTF_8),
                    ),
                ),
            ),
        )

        val failure = assertFailsWith<IllegalStateException> {
            LlmGatewayClient.generateJson(
                client = client,
                request = testLlmRequest(),
                url = "https://api.example.com/v1/messages",
                headers = emptyList(),
                payload = "{}",
                errorCodeKeys = listOf("code", "type"),
                extractContent = { error("must not be called for failed responses") },
            )
        }
        assertEquals(1, client.sendCount, "400 不可重试，只应 send 1 次")
        assertTrue(
            failure.message!!.contains("HTTP 400"),
            "message 应保留状态码；实际：${failure.message}",
        )
    }

    @Test
    fun generateJsonRespectsRetryAfterHeader() {
        // 用 1s Retry-After 验证 retryAfter 解析路径（不直接断言 sleep 时长，避免 flaky）。
        // 但要确认：服务端给了 Retry-After=1，重试成功后最终拿到 200。
        val client = QueueHttpClient(
            responses = listOf(
                SimpleHttpResponse(
                    statusCode = 429,
                    body = ByteArrayInputStream(ByteArray(0)),
                    headerMap = mapOf("retry-after" to listOf("1")),
                ),
                SimpleHttpResponse(
                    statusCode = 200,
                    body = ByteArrayInputStream("""{"model":"m","content":"ok"}""".toByteArray(StandardCharsets.UTF_8)),
                ),
            ),
        )

        val started = System.currentTimeMillis()
        val response = LlmGatewayClient.generateJson(
            client = client,
            request = testLlmRequest(),
            url = "https://api.example.com/v1/messages",
            headers = emptyList(),
            payload = "{}",
            extractContent = { body -> LlmJsonCodec.parseObject(body)["content"] as String },
        )
        val elapsed = System.currentTimeMillis() - started

        assertEquals("ok", response.content)
        assertEquals(2, client.sendCount)
        // 至少 sleep 了 ~1s（jitter 0-500ms，允许下限略小于 1000ms 但不应明显小于）。
        assertTrue(
            elapsed >= 900,
            "Retry-After: 1 应触发 ≥1s sleep；实际耗时 ${elapsed}ms",
        )
    }

    @Test
    fun streamSseRetriesOn429BeforeStreamingStarts() {
        // 第 1 次 429，第 2 次 200 + SSE 流。验证 streaming 起始前的重试。
        val sseBody = """
            data: {"delta":"hi"}
            data: [DONE]
        """.trimIndent()
        val client = QueueHttpClient(
            responses = listOf(
                SimpleHttpResponse(
                    statusCode = 429,
                    body = ByteArrayInputStream(ByteArray(0)),
                    headerMap = mapOf("retry-after" to listOf("0")),
                ),
                SimpleHttpResponse(
                    statusCode = 200,
                    body = ByteArrayInputStream(sseBody.toByteArray(StandardCharsets.UTF_8)),
                ),
            ),
        )
        val events = mutableListOf<LlmStreamEvent>()

        val response = LlmGatewayClient.streamSse(
            client = client,
            request = testLlmRequest(),
            url = "https://api.example.com/v1/responses",
            headers = listOf("Authorization" to "Bearer secret"),
            payload = """{"stream":true}""",
            listener = events::add,
            extractTextDelta = { data -> LlmJsonCodec.parseObject(data)["delta"] as? String },
        )

        assertEquals("hi", response.content)
        assertEquals(2, client.sendCount)
        assertEquals(LlmStreamEvent.TextDelta("hi"), events[1])
    }

    private fun testLlmRequest(): LlmRequest {
        return LlmRequest(
            protocol = LlmWireProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = "https://api.example.com/v1",
            apiKey = "secret",
            model = "gpt-5.4",
            timeoutSeconds = 60,
            temperature = 0.2,
            systemPrompt = "system",
            userPrompt = "user",
        )
    }

    @Test
    fun generateJsonRejectsLoopbackUrlViaSsrfPolicy() {
        val client = RecordingHttpClient(
            response = SimpleHttpResponse(
                statusCode = 200,
                body = ByteArrayInputStream("""{"content":"x"}""".toByteArray(StandardCharsets.UTF_8)),
            ),
        )
        // 默认 policy 拒绝 127.0.0.1（loopback）；不应真正发出 send（client.sendCount 仍为 0）。
        // 用 HTTPS 避免 scheme 校验先于 host 校验触发
        val failure = assertFailsWith<IllegalStateException> {
            LlmGatewayClient.generateJson(
                client = client,
                request = testLlmRequest(),
                url = "https://127.0.0.1:8080/v1/messages",
                headers = emptyList(),
                payload = "{}",
                extractContent = { "x" },
            )
        }
        assertTrue(failure.message!!.contains("禁止访问内网或元数据服务地址"))
    }

    @Test
    fun streamSseRejectsMetadataUrlViaSsrfPolicy() {
        val client = RecordingHttpClient(
            response = SimpleHttpResponse(
                statusCode = 200,
                body = ByteArrayInputStream("data: {}".toByteArray(StandardCharsets.UTF_8)),
            ),
        )
        val failure = assertFailsWith<IllegalStateException> {
            LlmGatewayClient.streamSse(
                client = client,
                request = testLlmRequest(),
                url = "https://169.254.169.254/latest/meta-data/",
                headers = emptyList(),
                payload = "{}",
                listener = {},
                extractTextDelta = { null },
            )
        }
        assertTrue(failure.message!!.contains("禁止访问内网或元数据服务地址"))
    }

    @Test
    fun generateJsonAcceptsHttpUrlWhenPolicyRelaxedForTesting() {
        val client = RecordingHttpClient(
            response = SimpleHttpResponse(
                statusCode = 200,
                body = ByteArrayInputStream("""{"content":"x"}""".toByteArray(StandardCharsets.UTF_8)),
            ),
        )
        try {
            // 公网 IP 字面量（不依赖 DNS）+ allowInsecureHttp：演示 policy 可注入，
            // 校验通过后 send 才真正发生。
            LlmGatewayClient.setEndpointPolicyForTesting(RemoteLlmEndpointPolicy(allowInsecureHttp = true))
            val response = LlmGatewayClient.generateJson(
                client = client,
                request = testLlmRequest(),
                url = "http://8.8.8.8/v1/messages",
                headers = emptyList(),
                payload = "{}",
                extractContent = { "ok" },
            )
            assertEquals("ok", response.content)
        } finally {
            LlmGatewayClient.setEndpointPolicyForTesting(RemoteLlmEndpointPolicy())
        }
    }

    private class RecordingHttpClient(
        private val response: HttpResponse<*>,
    ) : HttpClient() {
        var lastRequest: HttpRequest? = null

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            lastRequest = request
            return response as HttpResponse<T>
        }

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> {
            error("sendAsync is not used by gateway support tests.")
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> {
            error("sendAsync is not used by gateway support tests.")
        }
    }

    /**
     * 测试用 HTTP 客户端：按入队顺序依次返回 response；队空时抛错。
     * 用于验证重试次数——若被测代码错误地多发了 send，会立即暴露为 "no more queued responses"。
     */
    private class QueueHttpClient(
        responses: List<HttpResponse<*>>,
    ) : HttpClient() {
        private val queue: ArrayDeque<HttpResponse<*>> = ArrayDeque(responses)
        var sendCount: Int = 0
            private set

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            sendCount += 1
            val next = queue.removeFirstOrNull()
                ?: error("no more queued responses (send called ${sendCount} times)")
            return next as HttpResponse<T>
        }

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> {
            error("sendAsync is not used by gateway support tests.")
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> {
            error("sendAsync is not used by gateway support tests.")
        }
    }

    private class SimpleHttpResponse<T>(
        private val statusCode: Int,
        private val body: T,
        private val headerMap: Map<String, List<String>> = emptyMap(),
    ) : HttpResponse<T> {
        override fun statusCode(): Int = statusCode
        override fun request(): HttpRequest? = null
        override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(headerMap) { _, _ -> true }
        override fun body(): T = body
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = URI.create("https://api.example.com")
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}
