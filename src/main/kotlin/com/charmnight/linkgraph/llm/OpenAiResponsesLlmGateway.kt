package com.charmnight.linkgraph.llm

import java.net.http.HttpClient

/**
 * 面向 OpenAI Responses 协议的远程网关实现。
 */
class OpenAiResponsesLlmGateway(
    /** 根据请求动态创建 HTTP 客户端，便于测试或按需调整超时。 */
    private val clientFactory: (LlmRequest) -> HttpClient = LlmGatewaySupport::defaultHttpClient,
) : LlmGateway {
    override fun generate(request: LlmRequest): LlmResponse {
        return LlmGatewaySupport.generateJson(
            client = clientFactory(request),
            request = request,
            url = resolveResponsesUrl(request.endpoint),
            headers = openAiHeaders(request),
            payload = buildPayload(request.copy(deliveryMode = LlmDeliveryMode.FULL)),
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
            url = resolveResponsesUrl(request.endpoint),
            headers = openAiHeaders(request),
            payload = buildPayload(request.copy(deliveryMode = LlmDeliveryMode.STREAM)),
            listener = listener,
            extractTextDelta = ::extractTextDelta,
        )
    }

    internal fun resolveResponsesUrl(endpoint: String): String {
        return LlmProtocolUrlResolver.resolve(endpoint, LlmWireProtocol.OPENAI_RESPONSES)
    }

    internal fun buildPayload(request: LlmRequest): String {
        val streamField = if (request.deliveryMode == LlmDeliveryMode.STREAM) {
            ",\n  \"stream\": true"
        } else {
            ""
        }
        val structuredOutputField = request.structuredOutput?.let { structuredOutput ->
            """
                ,
                  "text": {
                    "format": {
                      "type": "json_schema",
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
              "instructions": "${LlmGatewaySupport.escapeJson(request.systemPrompt)}",
              "input": [
                {
                  "role": "user",
                  "content": [
                    {
                      "type": "input_text",
                      "text": "${LlmGatewaySupport.escapeJson(request.userPrompt)}"
                    }
                  ]
                }
              ]$structuredOutputField$streamField
            }
        """.trimIndent()
    }

    internal fun extractContent(body: String): String {
        val root = LlmGatewaySupport.parseObject(body)
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

    internal fun extractTextDelta(data: String): String? {
        val root = runCatching { LlmGatewayJsonParser(data).parseValue() as? Map<*, *> }.getOrNull() ?: return null
        return when (root["type"] as? String) {
            "response.output_text.delta" -> root["delta"] as? String
            "response.output_text.done" -> root["text"] as? String
            else -> null
        }
    }

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
