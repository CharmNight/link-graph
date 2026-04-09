package com.charmnight.linkgraph.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAiCompatibleLlmGatewayTest {
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
