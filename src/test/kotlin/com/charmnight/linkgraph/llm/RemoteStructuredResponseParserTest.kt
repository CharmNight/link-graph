package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.testing.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteStructuredResponseParserTest {
    @Test
    fun doesNotInjectNativeStructuredOutputForChatCompletionsRequests() {
        val requests = mutableListOf<LlmRequest>()
        val support = RemoteStructuredResponseParser(
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
        val support = RemoteStructuredResponseParser(
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
        val support = RemoteStructuredResponseParser(
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
        // 不断言具体文案——文案改动不应破坏测试。只检查语义：
        // - 唯一一条 warning
        // - 包含 scene 名 + 修复 + 重试关键词
        assertEquals(1, result.warnings.size, "修复重试成功路径应只产生一条 warning；实际：${result.warnings}")
        val warning = result.warnings.single()
        assertTrue(warning.contains("代码生成"), "warning 应包含 scene 名；实际：$warning")
        assertTrue(warning.contains("修复"), "warning 应描述修复行为；实际：$warning")
        assertTrue(warning.contains("重试"), "warning 应说明重试；实际：$warning")
    }
}
