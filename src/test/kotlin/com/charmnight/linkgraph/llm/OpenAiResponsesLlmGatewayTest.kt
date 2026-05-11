package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

        val root = LlmJsonSupport.parseJsonObject(payload)

        assertEquals("gpt-5.4", root.get("model").asString)
        assertEquals("system prompt", root.get("instructions").asString)
        assertEquals(
            "user prompt",
            root.getAsJsonArray("input")[0].asJsonObject
                .getAsJsonArray("content")[0].asJsonObject.get("text").asString,
        )
        assertEquals(true, root.get("stream").asBoolean)
    }

    @Test
    fun buildsResponsesPayloadWithNativeStructuredOutputFormat() {
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
                structuredOutput = LlmStructuredOutput(
                    name = "code_generation",
                    schema = """
                        {
                          "type": "object",
                          "properties": {
                            "payload": { "type": "string" }
                          },
                          "required": ["payload"],
                          "additionalProperties": false
                        }
                    """.trimIndent(),
                ),
            ),
        )

        val root = LlmJsonSupport.parseJsonObject(payload)
        val format = root.getAsJsonObject("text").getAsJsonObject("format")

        assertEquals("json_schema", format.get("type").asString)
        assertEquals("code_generation", format.get("name").asString)
        assertEquals(true, format.get("strict").asBoolean)
        assertNotNull(
            format.getAsJsonObject("schema")
                .getAsJsonArray("required")
                .firstOrNull { it.asString == "payload" },
        )
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
