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
 * 面向 OpenAI Responses 协议的远程网关实现。
 */
class OpenAiResponsesLlmGateway(
    /** 根据请求动态创建 HTTP 客户端，便于测试或按需调整超时。 */
    private val clientFactory: (LlmRequest) -> HttpClient = { request ->
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(request.timeoutSeconds.toLong()))
            .build()
    },
) : LlmGateway {
    override fun generate(request: LlmRequest): LlmResponse {
        val client = clientFactory(request)
        val payload = buildPayload(request.copy(deliveryMode = LlmDeliveryMode.FULL))
        val response = client.send(
            HttpRequest.newBuilder(URI.create(resolveResponsesUrl(request.endpoint)))
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
            HttpRequest.newBuilder(URI.create(resolveResponsesUrl(request.endpoint)))
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
                        val delta = extractTextDelta(data)
                        if (!delta.isNullOrEmpty()) {
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
              "instructions": "${escape(request.systemPrompt)}",
              "input": [
                {
                  "role": "user",
                  "content": [
                    {
                      "type": "input_text",
                      "text": "${escape(request.userPrompt)}"
                    }
                  ]
                }
              ]$structuredOutputField$streamField
            }
        """.trimIndent()
    }

    internal fun extractContent(body: String): String {
        val root = LlmGatewayJsonParser(body).parseValue() as? Map<*, *>
            ?: error("Remote LLM response must be a JSON object.")
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

    private fun extractDataPayload(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) {
            return null
        }
        return trimmed.removePrefix("data:").trim()
    }

    private fun extractModel(body: String): String? {
        val root = runCatching { LlmGatewayJsonParser(body).parseValue() as? Map<*, *> }.getOrNull() ?: return null
        return root["model"] as? String
    }

    internal fun buildFailureMessage(
        statusCode: Int,
        body: String,
    ): String {
        val root = runCatching { LlmGatewayJsonParser(body).parseValue() as? Map<*, *> }.getOrNull()
        val errorObject = root?.get("error") as? Map<*, *>
        val errorCode = (errorObject?.get("code") as? String)?.trim().orEmpty()
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
