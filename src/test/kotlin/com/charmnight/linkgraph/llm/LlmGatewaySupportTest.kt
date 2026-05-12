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

class LlmGatewaySupportTest {
    @Test
    fun extractsModelFromObjectResponseWithoutFailingOnInvalidJson() {
        assertEquals("gpt-5.4", LlmGatewaySupport.extractModel("""{"model":"gpt-5.4"}"""))
        assertEquals(null, LlmGatewaySupport.extractModel("""{"id":"resp_123"}"""))
        assertEquals(null, LlmGatewaySupport.extractModel("not json"))
    }

    @Test
    fun parseObjectRejectsInvalidGatewayJson() {
        assertFailsWith<IllegalStateException> {
            LlmGatewaySupport.parseObject("""{"model":"gpt"} garbage""")
        }
        assertEquals(null, LlmGatewaySupport.parseObjectOrNull("""{"model":"""))
    }

    @Test
    fun buildsFailureMessageFromProviderErrorCodeOrType() {
        val openAiMessage = LlmGatewaySupport.buildFailureMessage(
            statusCode = 503,
            body = """{"error":{"code":"model_not_found","message":"No channel"}}""",
        )
        val anthropicMessage = LlmGatewaySupport.buildFailureMessage(
            statusCode = 429,
            body = """{"error":{"type":"rate_limit_error","message":"Too many requests"}}""",
            errorCodeKeys = listOf("code", "type"),
        )

        assertEquals("Remote LLM request failed with HTTP 503 (model_not_found): No channel", openAiMessage)
        assertEquals("Remote LLM request failed with HTTP 429 (rate_limit_error): Too many requests", anthropicMessage)
    }

    @Test
    fun extractsSseDataPayloadOnlyFromDataLines() {
        assertEquals("""{"delta":"hi"}""", LlmGatewaySupport.extractSseDataPayload(""" data: {"delta":"hi"} """))
        assertEquals("[DONE]", LlmGatewaySupport.extractSseDataPayload("data: [DONE]"))
        assertEquals(null, LlmGatewaySupport.extractSseDataPayload(": keepalive"))
    }

    @Test
    fun generateJsonSendsRequestAndExtractsStandardResponse() {
        val client = RecordingHttpClient(
            response = SimpleHttpResponse(
                statusCode = 200,
                body = """{"model":"remote-model","content":"hello"}""",
            ),
        )
        val request = testLlmRequest()

        val response = LlmGatewaySupport.generateJson(
            client = client,
            request = request,
            url = "https://api.example.com/v1/messages",
            headers = listOf("x-api-key" to "secret"),
            payload = """{"prompt":"hello"}""",
            extractContent = { body -> LlmJsonSupport.parseObject(body)["content"] as String },
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
                body = """{"error":{"type":"invalid_request_error","message":"Bad request"}}""",
            ),
        )

        val failure = assertFailsWith<IllegalStateException> {
            LlmGatewaySupport.generateJson(
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

        val response = LlmGatewaySupport.streamSse(
            client = client,
            request = testLlmRequest(),
            url = "https://api.example.com/v1/responses",
            headers = listOf("Authorization" to "Bearer secret"),
            payload = """{"stream":true}""",
            listener = events::add,
            extractTextDelta = { data -> LlmJsonSupport.parseObject(data)["delta"] as? String },
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
