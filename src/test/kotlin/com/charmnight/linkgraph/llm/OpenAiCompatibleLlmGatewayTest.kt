package com.charmnight.linkgraph.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiCompatibleLlmGatewayTest {
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

        assertTrue(payload.contains("\"response_format\":"))
        assertTrue(payload.contains("\"type\": \"json_schema\""))
        assertTrue(payload.contains("\"name\": \"code_generation\""))
        assertTrue(payload.contains("\"strict\": true"))
        assertTrue(payload.contains("\"required\": [\"payload\"]"))
    }

    @Test
    fun extractsProviderErrorCodeAndMessageFromFailureBody() {
        val gateway = OpenAiCompatibleLlmGateway()

        val message = gateway.buildFailureMessage(
            statusCode = 503,
            body = """
                {
                  "error": {
                    "code": "model_not_found",
                    "message": "No available channel for model gpt-5.4 under group free (distributor)",
                    "type": "new_api_error"
                  }
                }
            """.trimIndent(),
        )

        assertEquals(
            "Remote LLM request failed with HTTP 503 (model_not_found): No available channel for model gpt-5.4 under group free (distributor)",
            message,
        )
    }
}
