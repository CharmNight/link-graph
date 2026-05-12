package com.charmnight.linkgraph.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class LlmGatewayPayloadBuilderTest {
    @Test
    fun buildsOpenAiChatPayloadAsValidJsonWithStructuredSchema() {
        val payload = LlmGatewayPayloadBuilder.openAiChatPayload(
            request = request(
                protocol = LlmWireProtocol.OPENAI_CHAT_COMPLETIONS,
                systemPrompt = "system\nprompt",
                userPrompt = "user\u0001prompt",
                deliveryMode = LlmDeliveryMode.STREAM,
                structuredOutput = structuredOutput(),
            ),
        )
        val root = LlmJsonSupport.parseJsonObject(payload)

        assertEquals("gpt-5.4", root.get("model").asString)
        assertEquals(0.2, root.get("temperature").asDouble)
        assertEquals(true, root.get("stream").asBoolean)
        assertEquals("system\nprompt", root.getAsJsonArray("messages")[0].asJsonObject.get("content").asString)
        assertEquals("user\u0001prompt", root.getAsJsonArray("messages")[1].asJsonObject.get("content").asString)
        assertNotNull(
            root.getAsJsonObject("response_format")
                .getAsJsonObject("json_schema")
                .getAsJsonObject("schema")
                .getAsJsonObject("properties")
                .getAsJsonObject("payload"),
        )
    }

    @Test
    fun buildsOpenAiResponsesPayloadAsValidJsonWithStructuredSchema() {
        val payload = LlmGatewayPayloadBuilder.openAiResponsesPayload(
            request = request(
                protocol = LlmWireProtocol.OPENAI_RESPONSES,
                deliveryMode = LlmDeliveryMode.STREAM,
                structuredOutput = structuredOutput(),
            ),
        )
        val root = LlmJsonSupport.parseJsonObject(payload)

        assertEquals("gpt-5.4", root.get("model").asString)
        assertEquals(true, root.get("stream").asBoolean)
        assertEquals("json_schema", root.getAsJsonObject("text").getAsJsonObject("format").get("type").asString)
        assertEquals(
            "input_text",
            root.getAsJsonArray("input")[0].asJsonObject
                .getAsJsonArray("content")[0].asJsonObject.get("type").asString,
        )
    }

    @Test
    fun buildsAnthropicPayloadAsValidJson() {
        val payload = LlmGatewayPayloadBuilder.anthropicMessagesPayload(
            request = request(protocol = LlmWireProtocol.ANTHROPIC_MESSAGES),
        )
        val root = LlmJsonSupport.parseJsonObject(payload)

        assertEquals("gpt-5.4", root.get("model").asString)
        assertEquals(4096, root.get("max_tokens").asInt)
        assertEquals(
            "text",
            root.getAsJsonArray("messages")[0].asJsonObject
                .getAsJsonArray("content")[0].asJsonObject.get("type").asString,
        )
    }

    @Test
    fun rejectsInvalidStructuredSchemaBeforeRemoteCall() {
        assertFailsWith<IllegalStateException> {
            LlmGatewayPayloadBuilder.openAiResponsesPayload(
                request = request(
                    protocol = LlmWireProtocol.OPENAI_RESPONSES,
                    structuredOutput = LlmStructuredOutput(
                        name = "bad_schema",
                        schema = """{"type":"object"""",
                    ),
                ),
            )
        }
    }

    @Test
    fun rejectsNonFiniteTemperature() {
        assertFailsWith<IllegalStateException> {
            LlmGatewayPayloadBuilder.openAiChatPayload(
                request = request(
                    protocol = LlmWireProtocol.OPENAI_CHAT_COMPLETIONS,
                    temperature = Double.NaN,
                ),
            )
        }
    }

    private fun request(
        protocol: LlmWireProtocol,
        temperature: Double = 0.2,
        systemPrompt: String = "system prompt",
        userPrompt: String = "user prompt",
        deliveryMode: LlmDeliveryMode = LlmDeliveryMode.FULL,
        structuredOutput: LlmStructuredOutput? = null,
    ) = LlmRequest(
        protocol = protocol,
        endpoint = "https://api.example.com/v1",
        apiKey = "token",
        model = "gpt-5.4",
        timeoutSeconds = 60,
        temperature = temperature,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        deliveryMode = deliveryMode,
        structuredOutput = structuredOutput,
    )

    private fun structuredOutput() = LlmStructuredOutput(
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
    )
}
