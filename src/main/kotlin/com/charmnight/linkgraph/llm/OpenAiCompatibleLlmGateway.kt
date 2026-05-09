package com.charmnight.linkgraph.llm

import java.net.http.HttpClient

/**
 * 最小 OpenAI 兼容网关。
 * 配置入口一旦填写完整，插件即可向兼容 `/chat/completions` 的服务请求生成计划。
 */
class OpenAiCompatibleLlmGateway(
    /** 根据请求动态创建 HTTP 客户端，便于测试或按需调整超时。 */
    private val clientFactory: (LlmRequest) -> HttpClient = LlmGatewaySupport::defaultHttpClient,
) : LlmGateway {
    /** 调用兼容 OpenAI Chat Completions 的远程服务。 */
    override fun generate(request: LlmRequest): LlmResponse {
        return LlmGatewaySupport.generateJson(
            client = clientFactory(request),
            request = request,
            url = resolveCompletionUrl(request.endpoint),
            headers = openAiHeaders(request),
            payload = buildPayload(request),
            extractContent = ::extractContent,
        )
    }

    override fun stream(
        request: LlmRequest,
        listener: (LlmStreamEvent) -> Unit,
    ): LlmResponse {
        return LlmGatewaySupport.streamSse(
            client = clientFactory(request),
            request = request,
            url = resolveCompletionUrl(request.endpoint),
            headers = openAiHeaders(request),
            payload = buildPayload(request.copy(deliveryMode = LlmDeliveryMode.STREAM)),
            listener = listener,
            extractTextDelta = ::extractTextDelta,
        )
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
                      "name": "${LlmGatewaySupport.escapeJson(structuredOutput.name)}",
                      "strict": ${structuredOutput.strict},
                      "schema": ${structuredOutput.schema.trim()}
                    }
                  }
            """.trimIndent()
        }.orEmpty()
        return """
            {
              "model": "${LlmGatewaySupport.escapeJson(request.model)}",
              "temperature": ${request.temperature},
              "messages": [
                {
                  "role": "system",
                  "content": "${LlmGatewaySupport.escapeJson(request.systemPrompt)}"
                },
                {
                  "role": "user",
                  "content": "${LlmGatewaySupport.escapeJson(request.userPrompt)}"
                }
              ]$structuredOutputField$streamField
            }
        """.trimIndent()
    }

    /** 从远程返回 JSON 中提取最终文本内容。 */
    internal fun extractContent(body: String): String {
        /** 解析后的 JSON 根对象。 */
        val root = LlmGatewaySupport.parseObject(body)
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

    /** 拼装失败响应的可读错误信息。 */
    internal fun buildFailureMessage(
        statusCode: Int,
        body: String,
    ): String {
        return LlmGatewaySupport.buildFailureMessage(statusCode, body)
    }

    private fun openAiHeaders(request: LlmRequest): List<Pair<String, String>> {
        return listOf("Authorization" to "Bearer ${request.apiKey}")
    }
}
