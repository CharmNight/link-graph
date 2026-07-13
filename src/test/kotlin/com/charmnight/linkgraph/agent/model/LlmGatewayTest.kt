package com.charmnight.linkgraph.agent.model

import com.charmnight.linkgraph.settings.LlmWireProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LlmGatewayTest {
    @Test
    fun defaultStreamDoesNotExposeRawBody() {
        val gateway = object : LlmGateway {
            override fun generate(request: LlmRequest): LlmResponse {
                return LlmResponse(
                    content = "hello",
                    model = request.model,
                    rawBody = """{"secret":"raw"}""",
                )
            }
        }
        val events = mutableListOf<LlmStreamEvent>()

        val response = gateway.stream(testRequest(), events::add)

        assertEquals("hello", response.content)
        assertNull(response.rawBody)
        val completed = events.filterIsInstance<LlmStreamEvent.Completed>().single()
        assertEquals("hello", completed.response.content)
        assertNull(completed.response.rawBody)
    }

    private fun testRequest(): LlmRequest =
        LlmRequest(
            protocol = LlmWireProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = "https://api.example.com/v1",
            apiKey = "token",
            model = "gpt-test",
            timeoutSeconds = 30,
            temperature = 0.2,
            systemPrompt = "system",
            userPrompt = "user",
        )
}
