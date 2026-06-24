package com.charmnight.linkgraph.llm

import java.net.http.HttpClient

/**
 * 面向 OpenAI Responses 协议的远程网关实现。
 */
class OpenAiResponsesLlmGateway(
    /** 根据请求动态创建 HTTP 客户端，便于测试或按需调整超时。 */
    private val clientFactory: (LlmRequest) -> HttpClient = LlmGatewayClient::defaultHttpClient,
) : LlmGateway {
    /** 调用 OpenAI Responses 协议服务，返回一次性完整响应。 */
    override fun generate(request: LlmRequest): LlmResponse {
        return LlmGatewayClient.generateJson(
            client = clientFactory(request),
            request = request,
            url = resolveResponsesUrl(request.endpoint),
            headers = openAiHeaders(request),
            payload = buildPayload(request.copy(deliveryMode = LlmDeliveryMode.FULL)),
            extractContent = ::extractContent,
        )
    }

    /** 以 SSE 流式方式调用 OpenAI Responses 协议服务。 */
    override fun stream(
        request: LlmRequest,
        listener: (LlmStreamEvent) -> Unit,
    ): LlmResponse {
        return LlmGatewayClient.streamSse(
            client = clientFactory(request),
            request = request,
            url = resolveResponsesUrl(request.endpoint),
            headers = openAiHeaders(request),
            payload = buildPayload(request.copy(deliveryMode = LlmDeliveryMode.STREAM)),
            listener = listener,
            extractTextDelta = ::extractTextDelta,
        )
    }

    /** 把用户配置的 endpoint 解析为完整的 OpenAI Responses 请求地址。 */
    internal fun resolveResponsesUrl(endpoint: String): String {
        return LlmProtocolUrlResolver.resolve(endpoint, LlmWireProtocol.OPENAI_RESPONSES)
    }

    /** 构造 OpenAI Responses 协议风格的请求 JSON。 */
    internal fun buildPayload(request: LlmRequest): String {
        return LlmGatewayPayloadBuilder.openAiResponsesPayload(request)
    }

    /** 从远程返回 JSON 中提取最终输出文本，优先使用 output_text 字段，缺失时回退到 output 列表。 */
    internal fun extractContent(body: String): String {
        val root = LlmGatewayClient.parseObject(body)
        val outputText = root["output_text"] as? String
        if (!outputText.isNullOrBlank()) {
            return outputText
        }
        val output = root["output"] as? List<*>
        val fragments = output.orEmpty().flatMap { item ->
            val message = item as? Map<*, *> ?: return@flatMap emptyList()
            val content = message["content"] as? List<*> ?: return@flatMap emptyList()
            content.mapNotNull { part ->
                val block = part as? Map<*, *> ?: return@mapNotNull null
                when (block["type"] as? String) {
                    "output_text", "text" -> block["text"] as? String
                    else -> null
                }
            }
        }
        return fragments.joinToString(separator = "").ifBlank {
            error("Remote LLM response did not contain output text.")
        }
    }

    /** 从 OpenAI Responses 流事件中提取文本增量或最终完整文本。 */
    internal fun extractTextDelta(data: String): String? {
        val root = LlmJsonCodec.parseObjectOrNull(data) ?: return null
        return when (root["type"] as? String) {
            "response.output_text.delta" -> root["delta"] as? String
            "response.output_text.done" -> root["text"] as? String
            else -> null
        }
    }

    /** 构造 OpenAI Responses 协议要求的鉴权请求头。 */
    private fun openAiHeaders(request: LlmRequest): List<Pair<String, String>> {
        return listOf("Authorization" to "Bearer ${request.apiKey}")
    }
}
