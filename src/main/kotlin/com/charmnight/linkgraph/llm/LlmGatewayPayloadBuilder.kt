package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.*
import com.charmnight.linkgraph.settings.*

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 远程 LLM 各协议请求体构造工具。
 * 把 [LlmRequest] 这一统一抽象分别序列化为 OpenAI Chat、OpenAI Responses、Anthropic Messages 三种协议各自的 JSON，
 * 供具体网关实现直接复用。
 */
internal object LlmGatewayPayloadBuilder {
    /** 构造 OpenAI Chat Completions 协议要求的请求 JSON。 */
    fun openAiChatPayload(request: LlmRequest): String {
        val root = JsonObject()
        root.addProperty("model", request.model)
        root.addProperty("temperature", finiteTemperature(request.temperature))
        root.addProperty("max_tokens", request.maxOutputTokens)
        root.add(
            "messages",
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", request.systemPrompt)
                    },
                )
                add(
                    JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", request.userPrompt)
                    },
                )
            },
        )
        request.structuredOutput?.let { output ->
            root.add(
                "response_format",
                JsonObject().apply {
                    addProperty("type", "json_schema")
                    add(
                        "json_schema",
                        JsonObject().apply {
                            addProperty("name", output.name)
                            addProperty("strict", output.strict)
                            add("schema", LlmJsonCodec.schemaElement(output.schema))
                        },
                    )
                },
            )
        }
        applyStreamFlag(root, request.deliveryMode)
        return LlmJsonCodec.toJson(root)
    }

    /** 构造 OpenAI Responses 协议要求的请求 JSON，使用 instructions + input 结构。 */
    fun openAiResponsesPayload(request: LlmRequest): String {
        val root = JsonObject()
        root.addProperty("model", request.model)
        root.addProperty("temperature", finiteTemperature(request.temperature))
        root.addProperty("max_output_tokens", request.maxOutputTokens)
        root.addProperty("instructions", request.systemPrompt)
        root.add(
            "input",
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("role", "user")
                        add(
                            "content",
                            JsonArray().apply {
                                add(
                                    JsonObject().apply {
                                        addProperty("type", "input_text")
                                        addProperty("text", request.userPrompt)
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        request.structuredOutput?.let { output ->
            root.add(
                "text",
                JsonObject().apply {
                    add(
                        "format",
                        JsonObject().apply {
                            addProperty("type", "json_schema")
                            addProperty("name", output.name)
                            addProperty("strict", output.strict)
                            add("schema", LlmJsonCodec.schemaElement(output.schema))
                        },
                    )
                },
            )
        }
        applyStreamFlag(root, request.deliveryMode)
        return LlmJsonCodec.toJson(root)
    }

    /** 构造 Anthropic Messages 协议要求的请求 JSON，把系统提示词放入顶层 system 字段。 */
    fun anthropicMessagesPayload(request: LlmRequest): String {
        val root = JsonObject()
        root.addProperty("model", request.model)
        root.addProperty("max_tokens", request.maxOutputTokens)
        root.addProperty("temperature", finiteTemperature(request.temperature))
        root.addProperty("system", request.systemPrompt)
        root.add(
            "messages",
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("role", "user")
                        add(
                            "content",
                            JsonArray().apply {
                                add(
                                    JsonObject().apply {
                                        addProperty("type", "text")
                                        addProperty("text", request.userPrompt)
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        applyStreamFlag(root, request.deliveryMode)
        return LlmJsonCodec.toJson(root)
    }

    /**
     * 把 [LlmDeliveryMode.STREAM] 标志写到协议载荷中。
     *
     * 三种协议（OpenAI Chat / Responses / Anthropic Messages）的 stream 字段名相同，
     * 统一走本函数避免任一协议漏写——历史上 [anthropicMessagesPayload] 没有 stream 分支，
     * 如果未来给 Anthropic gateway 加 `stream()` 覆盖，会静默拿不到流式响应。
     */
    private fun applyStreamFlag(
        root: JsonObject,
        deliveryMode: LlmDeliveryMode,
    ) {
        if (deliveryMode == LlmDeliveryMode.STREAM) {
            root.addProperty("stream", true)
        }
    }

    /** 校验 temperature 必须是有限数值，避免 NaN/Infinity 透传到 provider 端引发歧义。 */
    private fun finiteTemperature(value: Double): Double {
        if (!value.isFinite()) {
            error("LLM request temperature must be finite.")
        }
        return value
    }
}
