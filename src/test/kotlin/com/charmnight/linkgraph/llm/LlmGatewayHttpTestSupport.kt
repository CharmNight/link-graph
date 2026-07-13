package com.charmnight.linkgraph.llm

import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession

internal class RecordingHttpClient(
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

internal class SimpleHttpResponse<T>(
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
