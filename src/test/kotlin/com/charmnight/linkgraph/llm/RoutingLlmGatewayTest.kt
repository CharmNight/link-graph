package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.*
import com.charmnight.linkgraph.settings.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RoutingLlmGatewayTest {
    @Test
    fun routesInstantiateOnlySelectedGateway() {
        val created = mutableListOf<String>()
        val gateway = RoutingLlmGateway(
            openAiGatewayFactory = {
                created += "chat"
                fakeGateway("chat")
            },
            openAiResponsesGatewayFactory = {
                created += "responses"
                fakeGateway("responses")
            },
            anthropicGatewayFactory = {
                created += "anthropic"
                fakeGateway("anthropic")
            },
        )

        val response = gateway.generate(request(LlmWireProtocol.OPENAI_RESPONSES))

        assertEquals("responses", response.content)
        assertEquals(listOf("responses"), created)
    }

    @Test
    fun sharedClientProviderReusesClientForSameTimeout() {
        val first = SharedLlmHttpClientProvider.clientForTimeoutSeconds(60)
        val second = SharedLlmHttpClientProvider.clientForTimeoutSeconds(60)

        assertSame(first, second)
    }

    private fun fakeGateway(content: String) = object : LlmGateway {
        override fun generate(request: LlmRequest): LlmResponse {
            return LlmResponse(content = content, model = request.model)
        }
    }

    private fun request(protocol: LlmWireProtocol) = LlmRequest(
        protocol = protocol,
        endpoint = "https://api.example.com/v1",
        apiKey = "token",
        model = "gpt-5.4",
        timeoutSeconds = 60,
        temperature = 0.2,
        systemPrompt = "system",
        userPrompt = "user",
    )
}
