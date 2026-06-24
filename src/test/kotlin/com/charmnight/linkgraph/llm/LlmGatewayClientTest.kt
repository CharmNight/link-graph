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

    private class SimpleHttpResponse<T>(
        private val statusCode: Int,
        private val body: T,
    ) : HttpResponse<T> {
        override fun statusCode(): Int = statusCode
        override fun request(): HttpRequest? = null
        override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
        override fun body(): T = body
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = URI.create("https://api.example.com")
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}
