package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.*
import com.charmnight.linkgraph.settings.*

import kotlin.test.Test
import kotlin.test.assertEquals

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

        val root = LlmJsonCodec.parseJsonObject(payload)

        assertEquals("MiniMax-M2.7", root.get("model").asString)
        assertEquals("system prompt", root.get("system").asString)
        assertEquals(4096, root.get("max_tokens").asInt)
        assertEquals("user", root.getAsJsonArray("messages")[0].asJsonObject.get("role").asString)
        assertEquals(
            "user prompt",
            root.getAsJsonArray("messages")[0].asJsonObject
                .getAsJsonArray("content")[0].asJsonObject.get("text").asString,
        )
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
