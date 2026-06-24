package com.charmnight.linkgraph.llm

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
        if (request.deliveryMode == LlmDeliveryMode.STREAM) {
            root.addProperty("stream", true)
        }
        return LlmJsonCodec.toJson(root)
    }

    /** 构造 OpenAI Responses 协议要求的请求 JSON，使用 instructions + input 结构。 */
    fun openAiResponsesPayload(request: LlmRequest): String {
        val root = JsonObject()
        root.addProperty("model", request.model)
        root.addProperty("temperature", finiteTemperature(request.temperature))
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
        if (request.deliveryMode == LlmDeliveryMode.STREAM) {
            root.addProperty("stream", true)
        }
        return LlmJsonCodec.toJson(root)
    }

    /** 构造 Anthropic Messages 协议要求的请求 JSON，把系统提示词放入顶层 system 字段。 */
    fun anthropicMessagesPayload(request: LlmRequest): String {
        val root = JsonObject()
        root.addProperty("model", request.model)
        root.addProperty("max_tokens", 4096)
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
        return LlmJsonCodec.toJson(root)
    }

    /** 校验 temperature 必须是有限数值，避免 NaN/Infinity 透传到 provider 端引发歧义。 */
    private fun finiteTemperature(value: Double): Double {
        if (!value.isFinite()) {
            error("LLM request temperature must be finite.")
        }
        return value
    }
}
