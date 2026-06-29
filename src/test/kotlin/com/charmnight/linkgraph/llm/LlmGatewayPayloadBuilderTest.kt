package com.charmnight.linkgraph.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        val root = LlmJsonCodec.parseJsonObject(payload)

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
        val root = LlmJsonCodec.parseJsonObject(payload)

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
        val root = LlmJsonCodec.parseJsonObject(payload)

        assertEquals("gpt-5.4", root.get("model").asString)
        assertEquals(4096, root.get("max_tokens").asInt)
        assertEquals(
            "text",
            root.getAsJsonArray("messages")[0].asJsonObject
                .getAsJsonArray("content")[0].asJsonObject.get("type").asString,
        )
    }

    @Test
    fun anthropicPayloadUsesPresetMaxOutputTokens() {
        val payload = LlmGatewayPayloadBuilder.anthropicMessagesPayload(
            request = request(
                protocol = LlmWireProtocol.ANTHROPIC_MESSAGES,
                maxOutputTokens = 8192,
            ),
        )
        val root = LlmJsonCodec.parseJsonObject(payload)

        assertEquals(8192, root.get("max_tokens").asInt)
    }

    @Test
    fun openAiChatPayloadUsesPresetMaxOutputTokens() {
        val payload = LlmGatewayPayloadBuilder.openAiChatPayload(
            request = request(
                protocol = LlmWireProtocol.OPENAI_CHAT_COMPLETIONS,
                maxOutputTokens = 8192,
            ),
        )
        val root = LlmJsonCodec.parseJsonObject(payload)

        assertEquals(8192, root.get("max_tokens").asInt)
    }

    @Test
    fun openAiResponsesPayloadUsesPresetMaxOutputTokens() {
        val payload = LlmGatewayPayloadBuilder.openAiResponsesPayload(
            request = request(
                protocol = LlmWireProtocol.OPENAI_RESPONSES,
                maxOutputTokens = 8192,
            ),
        )
        val root = LlmJsonCodec.parseJsonObject(payload)

        assertEquals(8192, root.get("max_output_tokens").asInt)
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

    @Test
    fun anthropicPayloadWritesStreamFlagWhenDeliveryModeIsStream() {
        // 修复 M9：之前 anthropicMessagesPayload 没有处理 STREAM，导致后续若给 Anthropic gateway
        // 加 stream() 覆盖，会静默拿不到流式响应。三个协议必须对称写 stream 字段。
        val payload = LlmGatewayPayloadBuilder.anthropicMessagesPayload(
            request = request(
                protocol = LlmWireProtocol.ANTHROPIC_MESSAGES,
                deliveryMode = LlmDeliveryMode.STREAM,
            ),
        )
        val root = LlmJsonCodec.parseJsonObject(payload)

        assertEquals(true, root.get("stream").asBoolean)
    }

    @Test
    fun allProtocolPayloadsOmitStreamFlagWhenDeliveryModeIsFull() {
        // 三协议 FULL 模式都不应写 stream 字段
        data class ProtocolCase(
            val protocol: LlmWireProtocol,
            val builder: (LlmRequest) -> String,
            val maxField: String,
        )
        val cases = listOf(
            ProtocolCase(LlmWireProtocol.OPENAI_CHAT_COMPLETIONS, LlmGatewayPayloadBuilder::openAiChatPayload, "max_tokens"),
            ProtocolCase(LlmWireProtocol.OPENAI_RESPONSES, LlmGatewayPayloadBuilder::openAiResponsesPayload, "max_output_tokens"),
            ProtocolCase(LlmWireProtocol.ANTHROPIC_MESSAGES, LlmGatewayPayloadBuilder::anthropicMessagesPayload, "max_tokens"),
        )
        cases.forEach { (protocol, builder, maxField) ->
            val payload = builder.invoke(
                request(protocol = protocol, deliveryMode = LlmDeliveryMode.FULL),
            )
            val root = LlmJsonCodec.parseJsonObject(payload)
            // FULL 模式不应有 stream 字段；maxField 各协议不同但一定存在
            assertFalse(root.has("stream"), "$protocol FULL 模式不应写 stream 字段")
            assertTrue(root.has(maxField), "$protocol payload 应含 $maxField 字段")
        }
    }

    private fun request(
        protocol: LlmWireProtocol,
        temperature: Double = 0.2,
        systemPrompt: String = "system prompt",
        userPrompt: String = "user prompt",
        deliveryMode: LlmDeliveryMode = LlmDeliveryMode.FULL,
        structuredOutput: LlmStructuredOutput? = null,
        maxOutputTokens: Int = 4096,
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
        maxOutputTokens = maxOutputTokens,
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
