package com.charmnight.linkgraph.json

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal

object JsonCodec {
    private val gson: Gson = GsonBuilder()
        .serializeNulls()
        .disableHtmlEscaping()
        .create()

    fun toJson(value: Any?): String =
        normalizeControlEscapes(gson.toJson(value))

    fun toScriptSafeJson(value: Any?): String =
        toScriptSafeJsonText(toJson(value))

    fun toScriptSafeJsonText(encoded: String): String =
        encoded
            .replace("<", "\\u003C")
            .replace(">", "\\u003E")
            .replace("&", "\\u0026")

    fun parseValue(text: String): Any? =
        toKotlin(parseJsonElement(text))

    fun parseObject(text: String, rootDescription: String = "JSON root"): Map<*, *> =
        parseValue(text) as? Map<*, *>
            ?: error("$rootDescription must be an object.")

    fun parseObjectOrNull(text: String): Map<*, *>? =
        runCatching { parseObject(text) }.getOrNull()

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

    private fun normalizeControlEscapes(encoded: String): String {
        val out = StringBuilder(encoded.length)
        var index = 0
        while (index < encoded.length) {
            val ch = encoded[index]
            if (ch == '\\' && index + 1 < encoded.length && isUnescapedBackslash(encoded, index)) {
                val replacement = when (encoded[index + 1]) {
                    'b' -> "\\u0008"
                    'f' -> "\\u000c"
                    'n' -> "\\u000a"
                    'r' -> "\\u000d"
                    't' -> "\\u0009"
                    else -> null
                }
                if (replacement != null) {
                    out.append(replacement)
                    index += 2
                    continue
                }
            }
            out.append(ch)
            index++
        }
        return out.toString()
    }

    private fun isUnescapedBackslash(text: String, index: Int): Boolean {
        var count = 0
        var cursor = index - 1
        while (cursor >= 0 && text[cursor] == '\\') {
            count++
            cursor--
        }
        return count % 2 == 0
    }
}
