package com.charmnight.linkgraph.llm

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

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
        val response = client.send(
            jsonRequestBuilder(request, url, headers)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )

        val status = response.statusCode()
        // error / 2xx 都走同一套 size-guarded reader，避免恶意或异常服务器返回超大 body 触发 OOM。
        val body = response.body().use { input -> readBodyWithSizeGuard(input) }
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
        val response = client.send(
            jsonRequestBuilder(request, url, headers)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )

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
