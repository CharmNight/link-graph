package com.charmnight.linkgraph.llm

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 面向 Anthropic Messages 协议的远程网关实现。
 * 负责组装请求、发送 HTTP 调用，并从返回 JSON 中提取标准化内容。
 */
class AnthropicCompatibleLlmGateway(
    /** 根据请求动态创建 HTTP 客户端，便于测试或按需调整超时。 */
    private val clientFactory: (LlmRequest) -> HttpClient = { request ->
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(request.timeoutSeconds.toLong()))
            .build()
    },
) : LlmGateway {
    /** 调用远程服务并把结果转换成统一的 `LlmResponse`。 */
    override fun generate(request: LlmRequest): LlmResponse {
        /** 当前请求对应的 HTTP 客户端。 */
        val client = clientFactory(request)
        /** 发送给远程服务的 JSON 负载。 */
        val payload = buildPayload(request)
        /** 远程服务返回的原始 HTTP 响应。 */
        val response = client.send(
            HttpRequest.newBuilder(URI.create(resolveMessagesUrl(request.endpoint)))
                .timeout(Duration.ofSeconds(request.timeoutSeconds.toLong()))
                .header("Content-Type", "application/json")
                .header("x-api-key", request.apiKey)
                .header("anthropic-version", "2023-06-01")
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

    /** 把用户配置的 endpoint 解析为 Anthropic Messages 的完整访问地址。 */
    internal fun resolveMessagesUrl(endpoint: String): String {
        return LlmProtocolUrlResolver.resolve(endpoint, LlmWireProtocol.ANTHROPIC_MESSAGES)
    }

    /** 构造 Anthropic Messages 协议要求的请求 JSON。 */
    internal fun buildPayload(request: LlmRequest): String {
        return """
            {
              "model": "${escape(request.model)}",
              "max_tokens": 4096,
              "temperature": ${request.temperature},
              "system": "${escape(request.systemPrompt)}",
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {
                      "type": "text",
                      "text": "${escape(request.userPrompt)}"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    /** 从远程返回 JSON 中提取文本内容。 */
    internal fun extractContent(body: String): String {
        /** 解析后的 JSON 根对象。 */
        val root = LlmGatewayJsonParser(body).parseValue() as? Map<*, *>
            ?: error("Remote LLM response must be a JSON object.")
        /** Anthropic 返回的内容字段。 */
        val content = root["content"]
        return when (content) {
            is String -> content
            is List<*> -> content.joinToString(separator = "") { part ->
                when (part) {
                    is String -> part
                    is Map<*, *> -> part["text"] as? String ?: ""
                    else -> ""
                }
            }

            else -> error("Remote LLM response did not contain content blocks.")
        }
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
        /** 错误码或错误类型。 */
        val errorCode = ((errorObject?.get("code") as? String) ?: (errorObject?.get("type") as? String)).orEmpty().trim()
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
}
