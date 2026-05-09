package com.charmnight.linkgraph.llm

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

internal object LlmGatewaySupport {
    private const val DONE_MARKER = "[DONE]"

    fun defaultHttpClient(request: LlmRequest): HttpClient {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(request.timeoutSeconds.toLong()))
            .build()
    }

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
            HttpResponse.BodyHandlers.ofString(),
        )

        if (response.statusCode() !in 200..299) {
            error(buildFailureMessage(response.statusCode(), response.body(), errorCodeKeys))
        }

        val body = response.body()
        return LlmResponse(
            content = extractContent(body),
            model = extractModel(body) ?: request.model,
            rawBody = body,
        )
    }

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
            val body = response.body().use { input -> String(input.readAllBytes(), StandardCharsets.UTF_8) }
            error(buildFailureMessage(response.statusCode(), body, errorCodeKeys))
        }

        val rawEvents = StringBuilder()
        val content = StringBuilder()
        listener(LlmStreamEvent.Started(model = request.model))
        response.body().buffered().use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    rawEvents.append(line).append('\n')
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

    fun parseObject(body: String): Map<*, *> {
        return LlmGatewayJsonParser(body).parseValue() as? Map<*, *>
            ?: error("Remote LLM response must be a JSON object.")
    }

    fun parseObjectOrNull(body: String): Map<*, *>? {
        return runCatching { LlmGatewayJsonParser(body).parseValue() as? Map<*, *> }.getOrNull()
    }

    fun extractModel(body: String): String? {
        val root = parseObjectOrNull(body) ?: return null
        return root["model"] as? String
    }

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

    fun extractSseDataPayload(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) {
            return null
        }
        return trimmed.removePrefix("data:").trim()
    }

    fun escapeJson(value: String): String {
        return buildString(value.length + 8) {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch < ' ') {
                            append("\\u")
                            append(ch.code.toString(16).padStart(4, '0'))
                        } else {
                            append(ch)
                        }
                    }
                }
            }
        }
    }

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
