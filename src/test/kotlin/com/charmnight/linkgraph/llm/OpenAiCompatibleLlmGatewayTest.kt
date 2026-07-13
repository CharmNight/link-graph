package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.*
import com.charmnight.linkgraph.settings.*

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OpenAiCompatibleLlmGatewayTest {
    @Test
    fun streamDoesNotExposeRawBodyInCompletedEvent() {
        val body = """
            data: {"choices":[{"delta":{"content":"hel"}}]}
            data: {"choices":[{"delta":{"content":"lo"}}]}
            data: [DONE]
        """.trimIndent()
        val client = RecordingHttpClient(
            SimpleHttpResponse(
                statusCode = 200,
                body = ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8)),
            ),
        )
        val gateway = OpenAiCompatibleLlmGateway(clientFactory = { client })
        val events = mutableListOf<LlmStreamEvent>()

        val response = gateway.stream(streamRequest(), events::add)

        assertEquals("hello", response.content)
        assertNull(response.rawBody)
        val completed = events.filterIsInstance<LlmStreamEvent.Completed>().single()
        assertEquals("hello", completed.response.content)
        assertNull(completed.response.rawBody)
    }

    @Test
    fun buildsChatCompletionsPayloadWithNativeStructuredOutputFormat() {
        val gateway = OpenAiCompatibleLlmGateway()

        val payload = gateway.buildPayload(
            LlmRequest(
                protocol = LlmWireProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "https://api.example.com/v1",
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

        val root = LlmJsonCodec.parseJsonObject(payload)
        val jsonSchema = root.getAsJsonObject("response_format").getAsJsonObject("json_schema")

        assertEquals("json_schema", root.getAsJsonObject("response_format").get("type").asString)
        assertEquals("code_generation", jsonSchema.get("name").asString)
        assertEquals(true, jsonSchema.get("strict").asBoolean)
        assertNotNull(
            jsonSchema.getAsJsonObject("schema")
                .getAsJsonArray("required")
                .firstOrNull { it.asString == "payload" },
        )
    }

    private fun streamRequest(): LlmRequest = LlmRequest(
        protocol = LlmWireProtocol.OPENAI_CHAT_COMPLETIONS,
        endpoint = "https://api.example.com/v1",
        apiKey = "token",
        model = "gpt-5.4",
        timeoutSeconds = 60,
        temperature = 0.2,
        systemPrompt = "system prompt",
        userPrompt = "user prompt",
        deliveryMode = LlmDeliveryMode.STREAM,
    )
}
