package com.charmnight.linkgraph.llm

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal object LlmGatewayPayloadBuilder {
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
                            add("schema", LlmJsonSupport.schemaElement(output.schema))
                        },
                    )
                },
            )
        }
        if (request.deliveryMode == LlmDeliveryMode.STREAM) {
            root.addProperty("stream", true)
        }
        return LlmJsonSupport.toJson(root)
    }

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
                            add("schema", LlmJsonSupport.schemaElement(output.schema))
                        },
                    )
                },
            )
        }
        if (request.deliveryMode == LlmDeliveryMode.STREAM) {
            root.addProperty("stream", true)
        }
        return LlmJsonSupport.toJson(root)
    }

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
        return LlmJsonSupport.toJson(root)
    }

    private fun finiteTemperature(value: Double): Double {
        if (!value.isFinite()) {
            error("LLM request temperature must be finite.")
        }
        return value
    }
}
