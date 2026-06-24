package com.charmnight.linkgraph.llm

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpHeaders
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

/**
 * 远程 LLM HTTP 请求失败时抛出的类型化异常。
 *
 * - 继承 [IllegalStateException]：与既有错误处理路径兼容（generateJson 原本就对非 2xx 抛 IllegalStateException）
 * - 同时携带 [statusCode]、[retryAfterSeconds]、[body]，方便 [LlmGatewayClient.retryWithBackoff]
 *   据此判断是否重试，以及上层（网关、parser）按状态码做更细的处理
 *
 * @param statusCode HTTP 状态码，例如 429 / 503
 * @param retryAfterSeconds 服务端 `Retry-After` 头解析出的秒数；不解析日期格式，无法解析时为 null
 * @param body 响应体（已通过 size guard 限制规模），用于错误消息
 */
internal class LlmHttpException(
    val statusCode: Int,
    val retryAfterSeconds: Long?,
    body: String,
) : IllegalStateException(
    buildString {
        append("Remote LLM request failed with HTTP ")
        append(statusCode)
        retryAfterSeconds?.let {
            append(" (Retry-After=")
            append(it)
            append("s)")
        }
        if (body.isNotBlank()) {
            append(": ")
            append(body)
        }
    },
)

/**
 * 远程 LLM 网关底层客户端工具。
 * 统一负责一次性 JSON 请求、SSE 流式请求、错误消息构造和 JSON 解析辅助，
 * 由具体协议的网关实现复用，避免每种协议重复实现底层细节。
 */
internal object LlmGatewayClient {
    /** SSE 流中表示响应结束的标记。 */
    private const val DONE_MARKER = "[DONE]"
    /** 一次性响应体的最大字符上限，超过则视为异常返回，避免占用过多内存。 */
    private const val MAX_RESPONSE_CHARS = 2_000_000
    /** 单次 SSE 流读取过程中可缓存的原始事件文本上限，避免流式响应无限增长。 */
    private const val MAX_SSE_EVENT_CHARS = 2_000_000
    /** 触发自动重试的 HTTP 状态码集合：429 限流、503 临时不可用。 */
    private val RETRYABLE_STATUSES: Set<Int> = setOf(429, 503)
    /** Retry-After 上限，避免恶意/异常服务器返回超大值（如 86400）卡死 IDE 后台线程。 */
    private const val MAX_RETRY_AFTER_SECONDS = 180L
    /** 指数退避的 base 上限，避免 attempt 异常大时计算溢出。 */
    private const val MAX_BACKOFF_BASE_SECONDS = 8L
    /** 重试最大尝试次数（含首次），默认 3 = 首次 + 2 次重试。 */
    private const val DEFAULT_MAX_ATTEMPTS = 3

    /** 根据当前请求参数选择共享的 HTTP 客户端实例。 */
    fun defaultHttpClient(request: LlmRequest): HttpClient {
        return SharedLlmHttpClientProvider.clientFor(request)
    }

    /**
     * 以一次性 JSON 形式发起远程生成请求。
     *
     * @param client 复用的 HTTP 客户端实例。
     * @param request 当前 LLM 请求参数，用于设置超时等。
     * @param url 目标请求 URL。
     * @param headers 附加请求头键值对。
     * @param payload 已序列化为字符串的请求体。
     * @param errorCodeKeys 解析错误码时尝试的字段顺序。
     * @param extractContent 从响应体提取最终文本内容的回调。
     */
    fun generateJson(
        client: HttpClient,
        request: LlmRequest,
        url: String,
        headers: List<Pair<String, String>>,
        payload: String,
        errorCodeKeys: List<String> = listOf("code"),
        extractContent: (String) -> String,
    ): LlmResponse {
        // 重试只覆盖 send + status check：429/503 时抛 LlmHttpException 由 retryWithBackoff 处理。
        // body 读到一半的错误（size guard 触发、IO 异常等）不是可重试 HTTP 错误，正常向上抛。
        val (status, body) = retryWithBackoff { _ ->
            val response = client.send(
                jsonRequestBuilder(request, url, headers)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            val responseStatus = response.statusCode()
            // 注意：429/503 也要读完 body 才能复用连接、避免连接泄漏；size guard 仍生效。
            val responseBody = response.body().use { input -> readBodyWithSizeGuard(input) }
            if (responseStatus in RETRYABLE_STATUSES) {
                val retryAfter = parseRetryAfterSeconds(response.headers())
                throw LlmHttpException(responseStatus, retryAfter, responseBody)
            }
            responseStatus to responseBody
        }

        if (status !in 200..299) {
            error(buildFailureMessage(status, body, errorCodeKeys))
        }

        return LlmResponse(
            content = extractContent(body),
            model = extractModel(body) ?: request.model,
            rawBody = body,
        )
    }

    /**
     * 以 SSE 流式方式发起远程生成请求，把收到的文本增量通过事件回调实时回传。
     *
     * @param listener 用于接收 Started/TextDelta/Completed 事件的回调。
     * @param extractTextDelta 从单个 SSE data 帧中提取文本增量的回调，返回 null 表示该帧没有文本。
     */
    fun streamSse(
        client: HttpClient,
        request: LlmRequest,
        url: String,
        headers: List<Pair<String, String>>,
        payload: String,
        listener: (LlmStreamEvent) -> Unit,
        errorCodeKeys: List<String> = listOf("code"),
        extractTextDelta: (String) -> String?,
    ): LlmResponse {
        // 重试只在初始 send + status check 阶段：429/503 时抛 LlmHttpException 由 retryWithBackoff 处理。
        // 一旦进入流式 body 读取就不再重试（streaming 语义要求事务性，半截流不能续传）。
        val response = retryWithBackoff { _ ->
            val resp = client.send(
                jsonRequestBuilder(request, url, headers)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            if (resp.statusCode() in RETRYABLE_STATUSES) {
                val retryAfter = parseRetryAfterSeconds(resp.headers())
                val errorBody = resp.body().use { input -> readBodyWithSizeGuard(input) }
                throw LlmHttpException(resp.statusCode(), retryAfter, errorBody)
            }
            resp
        }

        if (response.statusCode() !in 200..299) {
            // error 路径同样要 size guard：恶意/异常服务器可能返回 200MB 的错误 JSON。
            val body = response.body().use { input -> readBodyWithSizeGuard(input) }
            error(buildFailureMessage(response.statusCode(), body, errorCodeKeys))
        }

        val rawEvents = StringBuilder()
        val content = StringBuilder()
        listener(LlmStreamEvent.Started(model = request.model))
        response.body().buffered().use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    rawEvents.append(line).append('\n')
                    if (rawEvents.length > MAX_SSE_EVENT_CHARS) {
                        error("Remote LLM stream exceeded maximum supported size.")
                    }
                    extractSseDataPayload(line)?.let { data ->
                        if (data == DONE_MARKER) {
                            return@forEach
                        }
                        extractTextDelta(data)?.takeIf(String::isNotEmpty)?.let { delta ->
                            content.append(delta)
                            listener(LlmStreamEvent.TextDelta(delta))
                        }
                    }
                }
            }
        }
        val finalResponse = LlmResponse(
            content = content.toString(),
            model = request.model,
            rawBody = rawEvents.toString(),
        )
        listener(LlmStreamEvent.Completed(finalResponse))
        return finalResponse
    }

    /** 把响应体解析为对象映射；解析失败会抛出异常，确保调用方感知非法 JSON。 */
    fun parseObject(body: String): Map<*, *> {
        return LlmJsonCodec.parseObject(body)
    }

    /** 与 [parseObject] 等价，但解析失败时返回 null，便于容错场景使用。 */
    fun parseObjectOrNull(body: String): Map<*, *>? {
        return LlmJsonCodec.parseObjectOrNull(body)
    }

    /** 从响应体中提取模型名字段，便于记录实际命中模型。 */
    fun extractModel(body: String): String? {
        val root = parseObjectOrNull(body) ?: return null
        return root["model"] as? String
    }

    /**
     * 构造远程请求失败时使用的错误消息。
     * 尽量保留 HTTP 状态、provider 错误码和具体原因，避免上层只能拿到模糊的失败提示。
     */
    fun buildFailureMessage(
        statusCode: Int,
        body: String,
        errorCodeKeys: List<String> = listOf("code"),
    ): String {
        val root = parseObjectOrNull(body)
        val errorObject = root?.get("error") as? Map<*, *>
        val errorCode = errorCodeKeys.firstNotNullOfOrNull { key ->
            (errorObject?.get(key) as? String)?.trim()?.takeIf(String::isNotEmpty)
        }.orEmpty()
        val errorMessage = (errorObject?.get("message") as? String)?.trim().orEmpty()

        return buildString {
            append("Remote LLM request failed with HTTP ")
            append(statusCode)
            if (errorCode.isNotEmpty()) {
                append(" (")
                append(errorCode)
                append(")")
            }
            if (errorMessage.isNotEmpty()) {
                append(": ")
                append(errorMessage)
            }
        }
    }

    /** 从单行 SSE 文本中提取 `data:` 后的有效负载，非 data 行返回 null。 */
    fun extractSseDataPayload(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) {
            return null
        }
        return trimmed.removePrefix("data:").trim()
    }

    /**
     * 以流式 + size guard 读取 HTTP 响应体，避免一次性把整 body 物化进堆。
     *
     * - 用 `InputStreamReader` 而非手工 `String(bytes, UTF_8)` 切片，确保 UTF-8
     *   多字节字符跨 chunk 边界时仍能正确解码（不会出现 � 替换字符）。
     * - 每次向 StringBuilder append 后检查 char 数；超 [MAX_RESPONSE_CHARS] 立即抛
     *   IllegalStateException，让上游感知并关闭连接，不再继续消费 InputStream。
     */
    private fun readBodyWithSizeGuard(input: InputStream): String {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        val buf = StringBuilder()
        val chunk = CharArray(8192)
        while (true) {
            val read = reader.read(chunk)
            if (read <= 0) break
            buf.append(chunk, 0, read)
            if (buf.length > MAX_RESPONSE_CHARS) {
                error("Remote LLM response exceeded maximum supported size ($MAX_RESPONSE_CHARS chars).")
            }
        }
        return buf.toString()
    }

    /**
     * 带退避的重试包装器，专为 HTTP 429/503 设计。
     *
     * - 仅捕获 [LlmHttpException]；其他异常（size guard 触发的 IllegalStateException、
     *   IO 异常、超时等）一律向上抛，由各自既有路径处理
     * - 仅当 statusCode ∈ [RETRYABLE_STATUSES] 时重试，否则直接抛
     * - 退避时长：优先用服务端 `Retry-After`（cap 在 [MAX_RETRY_AFTER_SECONDS]）；
     *   没有则用指数退避 `1L shl attempt`（cap 在 [MAX_BACKOFF_BASE_SECONDS]）；
     *   最后加 0-500ms jitter，避免雷同客户端同步重试惊群
     * - 最后一次 attempt 失败时直接抛 LlmHttpException，不再 sleep
     *
     * 注：cancel 是 best-effort——Thread.sleep 可被 interrupt 打断，但不主动调
     * ProgressManager.checkCanceled，保持 LlmGatewayClient 与 IntelliJ 平台解耦。
     * 上层 workflow 已有自己的 checkCanceled。
     */
    private fun <T> retryWithBackoff(
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        block: (attempt: Int) -> T,
    ): T {
        require(maxAttempts > 0)
        var lastError: LlmHttpException? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block(attempt)
            } catch (e: LlmHttpException) {
                lastError = e
                if (e.statusCode !in RETRYABLE_STATUSES) throw e
                if (attempt == maxAttempts - 1) throw e
                // 优先用服务端 Retry-After（cap 180s）；没有则指数退避（cap 8s）。
                val baseSeconds = when (val retryAfter = e.retryAfterSeconds) {
                    null -> (1L shl attempt).coerceAtMost(MAX_BACKOFF_BASE_SECONDS)
                    else -> retryAfter.coerceAtMost(MAX_RETRY_AFTER_SECONDS)
                }
                val delayMs = baseSeconds * 1000L + ThreadLocalRandom.current().nextLong(0, 500)
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        // 理论不可达（repeat 内一定会 return 或 throw），保险起见显式抛。
        throw lastError ?: error("retryWithBackoff exhausted without exception")
    }

    /**
     * 解析 `Retry-After` 响应头，仅支持秒数格式。
     *
     * HTTP 规范允许两种格式：
     * - 秒数：`Retry-After: 5`
     * - HTTP 日期：`Retry-After: Wed, 24 Jun 2026 12:00:00 GMT`
     *
     * 实际 LLM provider（Anthropic / OpenAI）都用秒数；日期格式当无效处理，
     * 由 retryWithBackoff 回退到指数退避。
     */
    private fun parseRetryAfterSeconds(headers: HttpHeaders): Long? {
        val raw = headers.firstValue("retry-after").orElse(null) ?: return null
        return raw.trim().toLongOrNull()?.takeIf { it >= 0 }
    }

    /** 构造统一的 HTTP 请求构造器：设置目标 URL、超时、Content-Type 与自定义请求头。 */
    private fun jsonRequestBuilder(
        request: LlmRequest,
        url: String,
        headers: List<Pair<String, String>>,
    ): HttpRequest.Builder {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(request.timeoutSeconds.toLong()))
            .header("Content-Type", "application/json")
            .apply {
                headers.forEach { (name, value) -> header(name, value) }
            }
    }
}
