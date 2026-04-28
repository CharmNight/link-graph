package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteStructuredResponseSupportTest {
    @Test
    fun doesNotInjectNativeStructuredOutputForChatCompletionsRequests() {
        val requests = mutableListOf<LlmRequest>()
        val support = RemoteStructuredResponseSupport(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    requests += request
                    return LlmResponse(content = """{"summary":"remote"}""", model = request.model)
                }
            },
        )

        val result = support.request(
            request = LlmRequest(
                protocol = LlmWireProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "https://api.example.com/v1",
                apiKey = "token",
                model = "gpt-5.4",
                timeoutSeconds = 60,
                temperature = 0.2,
                systemPrompt = "system",
                userPrompt = "user",
                deliveryMode = LlmDeliveryMode.FULL,
            ),
            scene = "实现计划生成",
            schema = LlmStructuredSchemas.GENERATION_PLAN,
        ) { content ->
            content
        }

        assertEquals("""{"summary":"remote"}""", result.value)
        assertEquals(1, requests.size)
        assertEquals(null, requests.single().structuredOutput)
    }

    @Test
    fun streamsPreviewBeforeParsingStructuredResult() {
        val previews = mutableListOf<Pair<String, Boolean>>()
        val support = RemoteStructuredResponseSupport(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    error("generate should not be used for streaming requests")
                }

                override fun stream(
                    request: LlmRequest,
                    listener: (LlmStreamEvent) -> Unit,
                ): LlmResponse {
                    listener(LlmStreamEvent.Started(model = request.model))
                    listener(LlmStreamEvent.TextDelta("{\"summary\":\"rem"))
                    listener(LlmStreamEvent.TextDelta("ote\"}"))
                    val response = LlmResponse(
                        content = "{\"summary\":\"remote\"}",
                        model = request.model,
                    )
                    listener(LlmStreamEvent.Completed(response))
                    return response
                }
            },
        )

        val result = support.request(
            request = LlmRequest(
                protocol = LlmWireProtocol.OPENAI_RESPONSES,
                endpoint = "https://api.openai.com/v1",
                apiKey = "token",
                model = "gpt-5.4",
                timeoutSeconds = 60,
                temperature = 0.2,
                systemPrompt = "system",
                userPrompt = "user",
                deliveryMode = LlmDeliveryMode.STREAM,
            ),
            scene = "实现计划生成",
            schema = """{"summary":"text"}""",
            preferStreaming = true,
            onPreview = { text, finalizing ->
                previews += text to finalizing
            },
        ) { content ->
            content
        }

        assertEquals("{\"summary\":\"remote\"}", result.value)
        assertEquals(
            listOf(
                "{\"summary\":\"rem" to false,
                "{\"summary\":\"remote\"}" to false,
                "{\"summary\":\"remote\"}" to true,
            ),
            previews,
        )
    }

    @Test
    fun injectsConcreteParseFailureIntoRepairPrompt() {
        val requests = mutableListOf<LlmRequest>()
        val support = RemoteStructuredResponseSupport(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    requests += request
                    val content = if (requests.size == 1) {
                        """{"drafts":[{"editOperations":[{"kind":"REPLACE_METHOD_BLOCK"}]}]}"""
                    } else {
                        """{"drafts":[{"editOperations":[{"kind":"REPLACE_METHOD_BLOCK","payload":"method body"}]}]}"""
                    }
                    return LlmResponse(content = content, model = request.model)
                }
            },
        )

        val result = support.request(
            request = LlmRequest(
                protocol = LlmWireProtocol.OPENAI_RESPONSES,
                endpoint = "https://api.openai.com/v1",
                apiKey = "token",
                model = "gpt-5.4",
                timeoutSeconds = 60,
                temperature = 0.2,
                systemPrompt = "system",
                userPrompt = "user",
                deliveryMode = LlmDeliveryMode.FULL,
            ),
            scene = "代码生成",
            schema = """{"drafts":[{"editOperations":[{"payload":"string"}]}]}""",
        ) { content ->
            if (!content.contains("payload")) {
                error("LLM response draft[0].editOperations[0].payload is required.")
            }
            content
        }

        assertEquals(2, requests.size)
        assertTrue(requests[0].structuredOutput?.schema?.contains("\"payload\"") == true)
        assertTrue(requests[1].structuredOutput?.schema?.contains("\"payload\"") == true)
        assertTrue(requests[1].userPrompt.contains("payload is required"))
        assertTrue(requests[1].userPrompt.contains("上一次结构化校验失败"))
        assertEquals(
            listOf("远程 LLM 代码生成 首轮返回不是可解析的结构化 JSON，已自动修复重试 1 次并成功。"),
            result.warnings,
        )
    }
}
