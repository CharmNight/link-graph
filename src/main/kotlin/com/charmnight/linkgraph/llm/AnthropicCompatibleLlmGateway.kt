package com.charmnight.linkgraph.llm

import java.net.http.HttpClient

/**
 * 面向 Anthropic Messages 协议的远程网关实现。
 * 负责组装请求、发送 HTTP 调用，并从返回 JSON 中提取标准化内容。
 */
class AnthropicCompatibleLlmGateway(
    /** 根据请求动态创建 HTTP 客户端，便于测试或按需调整超时。 */
    private val clientFactory: (LlmRequest) -> HttpClient = LlmGatewaySupport::defaultHttpClient,
) : LlmGateway {
    /** 调用远程服务并把结果转换成统一的 `LlmResponse`。 */
    override fun generate(request: LlmRequest): LlmResponse {
        return LlmGatewaySupport.generateJson(
            client = clientFactory(request),
            request = request,
            url = resolveMessagesUrl(request.endpoint),
            headers = anthropicHeaders(request),
            payload = buildPayload(request),
            errorCodeKeys = ANTHROPIC_ERROR_CODE_KEYS,
            extractContent = ::extractContent,
        )
    }

    /** 把用户配置的 endpoint 解析为 Anthropic Messages 的完整访问地址。 */
    internal fun resolveMessagesUrl(endpoint: String): String {
        return LlmProtocolUrlResolver.resolve(endpoint, LlmWireProtocol.ANTHROPIC_MESSAGES)
    }

    /** 构造 Anthropic Messages 协议要求的请求 JSON。 */
    internal fun buildPayload(request: LlmRequest): String {
        return LlmGatewayPayloadBuilder.anthropicMessagesPayload(request)
    }

    /** 从远程返回 JSON 中提取文本内容。 */
    internal fun extractContent(body: String): String {
        /** 解析后的 JSON 根对象。 */
        val root = LlmGatewaySupport.parseObject(body)
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

    private fun anthropicHeaders(request: LlmRequest): List<Pair<String, String>> {
        return listOf(
            "x-api-key" to request.apiKey,
            "anthropic-version" to "2023-06-01",
        )
    }

    private companion object {
        private val ANTHROPIC_ERROR_CODE_KEYS = listOf("code", "type")
    }
}
