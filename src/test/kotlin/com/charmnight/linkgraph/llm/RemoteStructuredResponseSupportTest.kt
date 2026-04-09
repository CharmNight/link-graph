package com.charmnight.linkgraph.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteStructuredResponseSupportTest {
    @Test
    fun streamsPreviewBeforeParsingStructuredResult() {
        val previews = mutableListOf<Pair<String, Boolean>>()
        val support = RemoteStructuredResponseSupport(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    error("generate should not be used for streaming requests")
                }

                override fun stream(
                    request: LlmRequest,
                    listener: (LlmStreamEvent) -> Unit,
                ): LlmResponse {
                    listener(LlmStreamEvent.Started(model = request.model))
                    listener(LlmStreamEvent.TextDelta("{\"summary\":\"rem"))
                    listener(LlmStreamEvent.TextDelta("ote\"}"))
                    val response = LlmResponse(
                        content = "{\"summary\":\"remote\"}",
                        model = request.model,
                    )
                    listener(LlmStreamEvent.Completed(response))
                    return response
                }
            },
        )

        val result = support.request(
            request = LlmRequest(
                protocol = LlmWireProtocol.OPENAI_RESPONSES,
                endpoint = "https://api.openai.com/v1",
                apiKey = "token",
                model = "gpt-5.4",
                timeoutSeconds = 60,
                temperature = 0.2,
                systemPrompt = "system",
                userPrompt = "user",
                deliveryMode = LlmDeliveryMode.STREAM,
            ),
            scene = "实现计划生成",
            schema = """{"summary":"text"}""",
            preferStreaming = true,
            onPreview = { text, finalizing ->
                previews += text to finalizing
            },
        ) { content ->
            content
        }

        assertEquals("{\"summary\":\"remote\"}", result.value)
        assertEquals(
            listOf(
                "{\"summary\":\"rem" to false,
                "{\"summary\":\"remote\"}" to false,
                "{\"summary\":\"remote\"}" to true,
            ),
            previews,
        )
    }
}
