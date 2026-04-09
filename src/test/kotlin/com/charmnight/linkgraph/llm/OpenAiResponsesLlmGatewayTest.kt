package com.charmnight.linkgraph.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiResponsesLlmGatewayTest {
    @Test
    fun resolvesResponsesEndpointFromBaseUrl() {
        val gateway = OpenAiResponsesLlmGateway()

        assertEquals(
            "https://api.openai.com/v1/responses",
            gateway.resolveResponsesUrl("https://api.openai.com/v1"),
        )
        assertEquals(
            "https://api.openai.com/v1/responses",
            gateway.resolveResponsesUrl("https://api.openai.com/v1/responses"),
        )
    }

    @Test
    fun buildsResponsesPayloadWithStreamingFlag() {
        val gateway = OpenAiResponsesLlmGateway()

        val payload = gateway.buildPayload(
            LlmRequest(
                protocol = LlmWireProtocol.OPENAI_RESPONSES,
                endpoint = "https://api.openai.com/v1",
                apiKey = "token",
                model = "gpt-5.4",
                timeoutSeconds = 60,
                temperature = 0.2,
                systemPrompt = "system prompt",
                userPrompt = "user prompt",
                deliveryMode = LlmDeliveryMode.STREAM,
            ),
        )

        assertTrue(payload.contains("\"model\": \"gpt-5.4\""))
        assertTrue(payload.contains("\"instructions\": \"system prompt\""))
        assertTrue(payload.contains("\"text\": \"user prompt\""))
        assertTrue(payload.contains("\"stream\": true"))
    }

    @Test
    fun extractsTextContentFromResponsesResponse() {
        val gateway = OpenAiResponsesLlmGateway()

        val content = gateway.extractContent(
            """
                {
                  "id": "resp_123",
                  "model": "gpt-5.4",
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "{\"summary\":\"remote\"}"
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals("{\"summary\":\"remote\"}", content)
    }

    @Test
    fun extractsTextDeltaFromResponsesStreamEvent() {
        val gateway = OpenAiResponsesLlmGateway()

        val delta = gateway.extractTextDelta(
            """{"type":"response.output_text.delta","delta":"hello"}""",
        )

        assertEquals("hello", delta)
    }
}
