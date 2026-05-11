package com.charmnight.linkgraph.llm

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal

internal object LlmJsonSupport {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun parseValue(text: String): Any? = toKotlin(parseJsonElement(text))

    fun parseObject(text: String): Map<*, *> {
        return parseValue(text) as? Map<*, *>
            ?: error("Remote LLM response must be a JSON object.")
    }

    fun parseObjectOrNull(text: String): Map<*, *>? {
        return runCatching { parseObject(text) }.getOrNull()
    }

    fun parseJsonObject(text: String): JsonObject {
        val element = parseJsonElement(text)
        return element.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: error("JSON root must be an object.")
    }

    fun parseJsonElement(text: String): JsonElement {
        validateControlCharacters(text)
        return runCatching { JsonParser.parseString(text) }
            .getOrElse { error("Invalid JSON: ${it.message}") }
    }

    fun toJson(value: Any?): String = gson.toJson(value)

    fun schemaElement(schema: String): JsonElement {
        val element = parseJsonElement(schema.trim())
        if (!element.isJsonObject) {
            error("Structured output schema must be a JSON object.")
        }
        return element
    }

    private fun toKotlin(element: JsonElement): Any? {
        return when {
            element is JsonNull || element.isJsonNull -> null
            element.isJsonObject -> element.asJsonObject.entrySet().associateTo(linkedMapOf()) { entry ->
                entry.key to toKotlin(entry.value)
            }
            element.isJsonArray -> element.asJsonArray.map(::toKotlin)
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                when {
                    primitive.isBoolean -> primitive.asBoolean
                    primitive.isString -> primitive.asString
                    primitive.isNumber -> parseNumber(primitive.asString)
                    else -> primitive.asString
                }
            }
            else -> null
        }
    }

    private fun parseNumber(raw: String): Number {
        return if (raw.contains('.') || raw.contains('e', ignoreCase = true)) {
            raw.toDouble()
        } else {
            raw.toLongOrNull() ?: BigDecimal(raw)
        }
    }

    private fun validateControlCharacters(text: String) {
        var inString = false
        var escaped = false
        text.forEach { ch ->
            if (inString) {
                if (escaped) {
                    escaped = false
                } else {
                    when (ch) {
                        '\\' -> escaped = true
                        '"' -> inString = false
                        in '\u0000'..'\u001f' -> error("Invalid JSON: raw control characters are not allowed in strings.")
                    }
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    ' ', '\n', '\r', '\t' -> Unit
                    in '\u0000'..'\u001f' -> error("Invalid JSON: unsupported control character.")
                }
            }
        }
    }
}
