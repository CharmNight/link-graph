package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.json.JsonCodec
import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal object LlmJsonSupport {
    fun parseValue(text: String): Any? = JsonCodec.parseValue(text)

    fun parseObject(text: String): Map<*, *> {
        return JsonCodec.parseObject(text, rootDescription = "Remote LLM response")
    }

    fun parseObjectOrNull(text: String): Map<*, *>? {
        return runCatching { parseObject(text) }.getOrNull()
    }

    fun parseJsonObject(text: String): JsonObject {
        val element = JsonCodec.parseJsonElement(text)
        return element.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: error("JSON root must be an object.")
    }

    fun parseJsonElement(text: String): JsonElement = JsonCodec.parseJsonElement(text)

    fun toJson(value: Any?): String = JsonCodec.toJson(value)

    fun schemaElement(schema: String): JsonElement {
        val element = parseJsonElement(schema.trim())
        if (!element.isJsonObject) {
            error("Structured output schema must be a JSON object.")
        }
        return element
    }
}
