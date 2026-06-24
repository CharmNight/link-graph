package com.charmnight.linkgraph.llm

import java.net.http.HttpClient

/**
 * 面向 Anthropic Messages 协议的远程网关实现。
 *
 * 负责组装请求、发送 HTTP 调用，并从返回 JSON 中提取标准化内容。
 * 与 OpenAI 系列网关共用 [LlmGatewayClient] 的通用 HTTP/JSON 处理逻辑，
 * 仅在 URL、Header、Payload 构造与响应解析上有差异。
 *
 * @param clientFactory 根据请求动态创建 HTTP 客户端，便于测试或按需调整超时
 */
class AnthropicCompatibleLlmGateway(
    private val clientFactory: (LlmRequest) -> HttpClient = LlmGatewayClient::defaultHttpClient,
) : LlmGateway {
    /**
     * 调用远程服务并把结果转换成统一的 [LlmResponse]。
     * 把协议特定的细节（URL/Header/Payload/解析）委托给本类的辅助方法。
     */
    override fun generate(request: LlmRequest): LlmResponse {
        return LlmGatewayClient.generateJson(
            client = clientFactory(request),
            request = request,
            url = resolveMessagesUrl(request.endpoint),
            headers = anthropicHeaders(request),
            payload = buildPayload(request),
            // Anthropic 错误码可能在 code 或 type 字段中
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

    /**
     * 从远程返回 JSON 中提取文本内容。
     *
     * Anthropic 的 content 字段既可能是字符串也可能是结构化块列表：
     * - 字符串：直接使用；
     * - 列表：把每块的 text 字段拼接成单字符串。
     * 都不匹配时抛错，让上游感知协议不兼容。
     */
    internal fun extractContent(body: String): String {
        /** 解析后的 JSON 根对象。 */
        val root = LlmGatewayClient.parseObject(body)
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

    /** 构造 Anthropic 协议要求的请求头。 */
    private fun anthropicHeaders(request: LlmRequest): List<Pair<String, String>> {
        return listOf(
            // API key 走自定义 header，与 OpenAI 的 Authorization 不同
            "x-api-key" to request.apiKey,
            // Anthropic 要求显式指定 API 版本
            "anthropic-version" to "2023-06-01",
        )
    }

    private companion object {
        /** Anthropic 错误响应中可能携带错误码的字段名。 */
        private val ANTHROPIC_ERROR_CODE_KEYS = listOf("code", "type")
    }
}
