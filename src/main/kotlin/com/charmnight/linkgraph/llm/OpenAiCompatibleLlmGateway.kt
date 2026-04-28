package com.charmnight.linkgraph.llm

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * 最小 OpenAI 兼容网关。
 * 配置入口一旦填写完整，插件即可向兼容 `/chat/completions` 的服务请求生成计划。
 */
class OpenAiCompatibleLlmGateway(
    /** 根据请求动态创建 HTTP 客户端，便于测试或按需调整超时。 */
    private val clientFactory: (LlmRequest) -> HttpClient = { request ->
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(request.timeoutSeconds.toLong()))
            .build()
    },
) : LlmGateway {
    /** 调用兼容 OpenAI Chat Completions 的远程服务。 */
    override fun generate(request: LlmRequest): LlmResponse {
        /** 当前请求对应的 HTTP 客户端。 */
        val client = clientFactory(request)
        /** 发送给远程服务的 JSON 负载。 */
        val payload = buildPayload(request)
        /** 远程服务返回的原始 HTTP 响应。 */
        val response = client.send(
            HttpRequest.newBuilder(URI.create(resolveCompletionUrl(request.endpoint)))
                .timeout(Duration.ofSeconds(request.timeoutSeconds.toLong()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${request.apiKey}")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        if (response.statusCode() !in 200..299) {
            error(buildFailureMessage(response.statusCode(), response.body()))
        }

        /** 成功响应的原始 JSON 文本。 */
        val body = response.body()
        return LlmResponse(
            content = extractContent(body),
            model = extractModel(body) ?: request.model,
            rawBody = body,
        )
    }

    override fun stream(
        request: LlmRequest,
        listener: (LlmStreamEvent) -> Unit,
    ): LlmResponse {
        val client = clientFactory(request)
        val payload = buildPayload(request.copy(deliveryMode = LlmDeliveryMode.STREAM))
        val response = client.send(
            HttpRequest.newBuilder(URI.create(resolveCompletionUrl(request.endpoint)))
                .timeout(Duration.ofSeconds(request.timeoutSeconds.toLong()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${request.apiKey}")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )

        if (response.statusCode() !in 200..299) {
            val body = response.body().use { input -> String(input.readAllBytes(), StandardCharsets.UTF_8) }
            error(buildFailureMessage(response.statusCode(), body))
        }

        val rawEvents = StringBuilder()
        val content = StringBuilder()
        listener(LlmStreamEvent.Started(model = request.model))
        response.body().buffered().use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    rawEvents.append(line).append('\n')
                    extractDataPayload(line)?.let { data ->
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

    /** 把用户配置的 endpoint 解析为完整的 `/chat/completions` 地址。 */
    internal fun resolveCompletionUrl(endpoint: String): String {
        return LlmProtocolUrlResolver.resolve(endpoint, LlmWireProtocol.OPENAI_CHAT_COMPLETIONS)
    }

    /** 构造 OpenAI Chat Completions 风格的请求 JSON。 */
    internal fun buildPayload(request: LlmRequest): String {
        val streamField = if (request.deliveryMode == LlmDeliveryMode.STREAM) {
            ",\n  \"stream\": true"
        } else {
            ""
        }
        val structuredOutputField = request.structuredOutput?.let { structuredOutput ->
            """
                ,
                  "response_format": {
                    "type": "json_schema",
                    "json_schema": {
                      "name": "${escape(structuredOutput.name)}",
                      "strict": ${structuredOutput.strict},
                      "schema": ${structuredOutput.schema.trim()}
                    }
                  }
            """.trimIndent()
        }.orEmpty()
        return """
            {
              "model": "${escape(request.model)}",
              "temperature": ${request.temperature},
              "messages": [
                {
                  "role": "system",
                  "content": "${escape(request.systemPrompt)}"
                },
                {
                  "role": "user",
                  "content": "${escape(request.userPrompt)}"
                }
              ]$structuredOutputField$streamField
            }
        """.trimIndent()
    }

    /** 从远程返回 JSON 中提取最终文本内容。 */
    internal fun extractContent(body: String): String {
        /** 解析后的 JSON 根对象。 */
        val root = LlmGatewayJsonParser(body).parseValue() as? Map<*, *>
            ?: error("Remote LLM response must be a JSON object.")
        /** choices 列表。 */
        val choices = root["choices"] as? List<*>
        /** 第一条候选结果。 */
        val firstChoice = choices?.firstOrNull() as? Map<*, *>
        /** 第一条候选结果中的 message 对象。 */
        val message = firstChoice?.get("message") as? Map<*, *>
        /** message.content 字段。 */
        val content = message?.get("content")
        return when (content) {
            is String -> content
            is List<*> -> content.joinToString(separator = "") { part ->
                when (part) {
                    is String -> part
                    is Map<*, *> -> part["text"] as? String ?: ""
                    else -> ""
                }
            }

            else -> root["content"] as? String ?: error("Remote LLM response did not contain message content.")
        }
    }

    /** 从 OpenAI Chat Completions 流事件中提取文本增量。 */
    internal fun extractTextDelta(data: String): String? {
        val root = runCatching { LlmGatewayJsonParser(data).parseValue() as? Map<*, *> }.getOrNull() ?: return null
        val choices = root["choices"] as? List<*>
        val firstChoice = choices?.firstOrNull() as? Map<*, *>
        val delta = firstChoice?.get("delta") as? Map<*, *>
        val content = delta?.get("content")
        return when (content) {
            is String -> content
            is List<*> -> content.joinToString(separator = "") { part ->
                when (part) {
                    is String -> part
                    is Map<*, *> -> part["text"] as? String ?: ""
                    else -> ""
                }
            }

            else -> null
        }
    }

    private fun extractDataPayload(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) {
            return null
        }
        return trimmed.removePrefix("data:").trim()
    }

    /** 尝试从响应中提取服务端返回的模型名。 */
    private fun extractModel(body: String): String? {
        /** 容错解析后的 JSON 根对象。 */
        val root = runCatching { LlmGatewayJsonParser(body).parseValue() as? Map<*, *> }.getOrNull() ?: return null
        return root["model"] as? String
    }

    /** 拼装失败响应的可读错误信息。 */
    internal fun buildFailureMessage(
        statusCode: Int,
        body: String,
    ): String {
        /** 容错解析后的 JSON 根对象。 */
        val root = runCatching { LlmGatewayJsonParser(body).parseValue() as? Map<*, *> }.getOrNull()
        /** 服务端约定的错误对象。 */
        val errorObject = root?.get("error") as? Map<*, *>
        /** 错误码。 */
        val errorCode = (errorObject?.get("code") as? String)?.trim().orEmpty()
        /** 错误描述文本。 */
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

    /** 对字符串做 JSON 转义，避免破坏请求体结构。 */
    private fun escape(value: String): String {
        return buildString(value.length + 8) {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }

    private companion object {
        private const val DONE_MARKER = "[DONE]"
    }
}
