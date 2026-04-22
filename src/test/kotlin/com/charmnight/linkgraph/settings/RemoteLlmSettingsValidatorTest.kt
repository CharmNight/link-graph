package com.charmnight.linkgraph.settings

import com.charmnight.linkgraph.llm.LlmGateway
import com.charmnight.linkgraph.llm.LlmDeliveryMode
import com.charmnight.linkgraph.llm.LlmProviderPresets
import com.charmnight.linkgraph.llm.LlmRequest
import com.charmnight.linkgraph.llm.LlmResponse
import com.charmnight.linkgraph.llm.LlmStreamEvent
import com.charmnight.linkgraph.llm.LlmWireProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteLlmSettingsValidatorTest {
    @Test
    fun reportsMissingRemoteFieldsBeforeSaving() {
        val result = RemoteLlmSettingsValidator(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    error("should not call gateway when settings are incomplete")
                }
            },
        ).validate(
            LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "",
                apiKey = "",
                model = "",
            ),
        )

        assertFalse(result.ok)
        assertEquals(
            "远程 LLM 配置不完整。\n缺少：请求地址、API 密钥\n位置：链路图设置（IDE 设置 > 工具 > 链路图）\n下一步：补全后点击“验证远程配置”。",
            result.message,
        )
    }

    @Test
    fun translatesRemoteModelErrorsIntoReadableChinese() {
        val result = RemoteLlmSettingsValidator(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    error("Remote LLM request failed with HTTP 503 (model_not_found): No available channel for model gpt-5.4 under group free (distributor)")
                }
            },
        ).validate(
            LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "https://example.com/v1",
                apiKey = "token",
                model = "gpt-5.4",
            ),
        )

        assertFalse(result.ok)
        assertTrue(result.message.contains("模型 gpt-5.4 在当前兼容服务中不可用"))
        assertTrue(result.message.contains("HTTP 503 / model_not_found"))
        assertTrue(result.message.contains("改成服务端已开通的模型"))
        assertTrue(result.message.contains("\n已尝试接口：https://example.com/v1/chat/completions"))
        assertTrue(result.message.contains("\n模型：gpt-5.4"))
    }

    @Test
    fun passesWhenRemoteConnectionSucceeds() {
        val result = RemoteLlmSettingsValidator(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    return LlmResponse(
                        content = """{"answer":"ok"}""",
                        model = request.model,
                    )
                }
            },
        ).validate(
            LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "https://example.com/v1",
                apiKey = "token",
                model = "gpt-4.1-mini",
            ),
        )

        assertTrue(result.ok)
        assertEquals(
            "远程 LLM 配置验证通过。\n已验证接口：https://example.com/v1/chat/completions\n模型：gpt-4.1-mini",
            result.message,
        )
    }

    @Test
    fun validatesStreamingProbeForStreamingCapableOpenAiCompatiblePreset() {
        val streamedRequests = mutableListOf<LlmRequest>()
        val result = RemoteLlmSettingsValidator(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    error("validator should probe streaming because question requests use streaming for this preset")
                }

                override fun stream(
                    request: LlmRequest,
                    listener: (LlmStreamEvent) -> Unit,
                ): LlmResponse {
                    streamedRequests += request
                    val response = LlmResponse(content = "OK", model = request.model)
                    listener(LlmStreamEvent.Started(model = request.model))
                    listener(LlmStreamEvent.TextDelta("OK"))
                    listener(LlmStreamEvent.Completed(response))
                    return response
                }
            },
        ).validate(
            LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderType.OPENAI_COMPATIBLE.name,
                endpoint = "https://example.com/v1",
                apiKey = "token",
                model = "gpt-4.1-mini",
            ),
        )

        assertTrue(result.ok)
        assertEquals(1, streamedRequests.size)
        assertEquals(LlmDeliveryMode.STREAM, streamedRequests.single().deliveryMode)
        assertEquals(LlmWireProtocol.OPENAI_CHAT_COMPLETIONS, streamedRequests.single().protocol)
    }

    @Test
    fun validatesMiniMaxAnthropicPresetWithDefaultEndpointAndModel() {
        val requests = mutableListOf<LlmRequest>()
        val result = RemoteLlmSettingsValidator(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    requests += request
                    return LlmResponse(
                        content = """{"content":[{"type":"text","text":"OK"}]}""",
                        model = request.model,
                    )
                }
            },
        ).validate(
            LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.MINIMAX_ANTHROPIC.id,
                endpoint = "",
                apiKey = "token",
                model = "",
            ),
        )

        assertTrue(result.ok)
        assertEquals(1, requests.size)
        assertEquals(LlmWireProtocol.ANTHROPIC_MESSAGES, requests.single().protocol)
        assertEquals("https://api.minimax.io/anthropic", requests.single().endpoint)
        assertEquals("MiniMax-M2.7", requests.single().model)
        assertEquals(
            "远程 LLM 配置验证通过。\n已验证接口：https://api.minimax.io/anthropic/v1/messages\n模型：MiniMax-M2.7",
            result.message,
        )
    }

    @Test
    fun validatesOpenAiResponsesPresetWithResponsesEndpoint() {
        val streamedRequests = mutableListOf<LlmRequest>()
        val result = RemoteLlmSettingsValidator(
            gateway = object : LlmGateway {
                override fun generate(request: LlmRequest): LlmResponse {
                    error("validator should probe streaming structured responses because question requests use them")
                }

                override fun stream(
                    request: LlmRequest,
                    listener: (LlmStreamEvent) -> Unit,
                ): LlmResponse {
                    streamedRequests += request
                    val response = LlmResponse(
                        content = """{"output":[{"type":"message","content":[{"type":"output_text","text":"OK"}]}]}""",
                        model = request.model,
                    )
                    listener(LlmStreamEvent.Started(model = request.model))
                    listener(LlmStreamEvent.TextDelta(response.content))
                    listener(LlmStreamEvent.Completed(response))
                    return response
                }
            },
        ).validate(
            LinkGraphSettingsState(
                llmEnabled = true,
                provider = LlmProviderPresets.OPENAI_RESPONSES.id,
                endpoint = "",
                apiKey = "token",
                model = "",
            ),
        )

        assertTrue(result.ok)
        assertEquals(1, streamedRequests.size)
        assertEquals(LlmWireProtocol.OPENAI_RESPONSES, streamedRequests.single().protocol)
        assertEquals(LlmDeliveryMode.STREAM, streamedRequests.single().deliveryMode)
        assertEquals("https://api.openai.com/v1", streamedRequests.single().endpoint)
        assertTrue(streamedRequests.single().structuredOutput?.schema?.contains("candidateChanges") == true)
        assertEquals(
            "远程 LLM 配置验证通过。\n已验证接口：https://api.openai.com/v1/responses\n模型：gpt-4.1-mini",
            result.message,
        )
    }
}
