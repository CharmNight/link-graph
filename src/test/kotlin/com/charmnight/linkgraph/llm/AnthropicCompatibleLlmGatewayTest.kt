package com.charmnight.linkgraph.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnthropicCompatibleLlmGatewayTest {
    @Test
    fun resolvesMessagesEndpointFromBaseUrl() {
        val gateway = AnthropicCompatibleLlmGateway()

        assertEquals(
            "https://api.minimax.io/anthropic/v1/messages",
            gateway.resolveMessagesUrl("https://api.minimax.io/anthropic"),
        )
        assertEquals(
            "https://api.minimax.io/anthropic/v1/messages",
            gateway.resolveMessagesUrl("https://api.minimax.io/anthropic/v1/messages"),
        )
    }

    @Test
    fun buildsAnthropicMessagesPayload() {
        val gateway = AnthropicCompatibleLlmGateway()

        val payload = gateway.buildPayload(
            LlmRequest(
                protocol = LlmWireProtocol.ANTHROPIC_MESSAGES,
                endpoint = "https://api.minimax.io/anthropic",
                apiKey = "token",
                model = "MiniMax-M2.7",
                timeoutSeconds = 60,
                temperature = 0.2,
                systemPrompt = "system prompt",
                userPrompt = "user prompt",
            ),
        )

        assertTrue(payload.contains("\"model\": \"MiniMax-M2.7\""))
        assertTrue(payload.contains("\"system\": \"system prompt\""))
        assertTrue(payload.contains("\"role\": \"user\""))
        assertTrue(payload.contains("\"text\": \"user prompt\""))
        assertTrue(payload.contains("\"max_tokens\": 4096"))
    }

    @Test
    fun extractsTextContentFromAnthropicResponse() {
        val gateway = AnthropicCompatibleLlmGateway()

        val content = gateway.extractContent(
            """
                {
                  "id": "msg_123",
                  "type": "message",
                  "role": "assistant",
                  "model": "MiniMax-M2.7",
                  "content": [
                    {
                      "type": "text",
                      "text": "{\"summary\":\"remote\"}"
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals("{\"summary\":\"remote\"}", content)
    }
}
