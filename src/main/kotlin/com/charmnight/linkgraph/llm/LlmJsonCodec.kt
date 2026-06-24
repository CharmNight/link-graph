package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.json.JsonCodec
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * LLM 模块的 JSON 编解码工具。
 *
 * 在通用 [JsonCodec] 之上做了一层包装，附加 LLM 特定的语义：
 * - 错误信息中带上 "Remote LLM response" 上下文；
 * - 提供"宽松版"的解析（返回 null 而非抛异常）；
 * - 校验结构化输出 schema 必须是 JSON 对象。
 */
internal object LlmJsonCodec {
    /** 把 JSON 文本解析为任意值（对象/数组/基本类型）。 */
    fun parseValue(text: String): Any? = JsonCodec.parseValue(text)

    /** 把 JSON 文本解析为 Map；解析失败时抛出带上下文的错误。 */
    fun parseObject(text: String): Map<*, *> {
        return JsonCodec.parseObject(text, rootDescription = "Remote LLM response")
    }

    /** 把 JSON 文本解析为 Map；任何异常都吞掉返回 null，适合容错场景。 */
    fun parseObjectOrNull(text: String): Map<*, *>? {
        return runCatching { parseObject(text) }.getOrNull()
    }

    /**
     * 把 JSON 文本解析为 JsonObject。
     * 根不是对象时抛出错误——LLM 响应顶层必须是对象。
     */
    fun parseJsonObject(text: String): JsonObject {
        val element = JsonCodec.parseJsonElement(text)
        return element.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: error("JSON root must be an object.")
    }

    /** 把 JSON 文本解析为 JsonElement，便于调用方按需进一步取字段。 */
    fun parseJsonElement(text: String): JsonElement = JsonCodec.parseJsonElement(text)

    /** 把任意对象序列化为 JSON 字符串。 */
    fun toJson(value: Any?): String = JsonCodec.toJson(value)

    /**
     * 解析结构化输出 schema。
     * schema 必须是 JSON 对象，否则抛出错误（让调用方知道模型给的 schema 不合法）。
     */
    fun schemaElement(schema: String): JsonElement {
        val element = parseJsonElement(schema.trim())
        if (!element.isJsonObject) {
            error("Structured output schema must be a JSON object.")
        }
        return element
    }
}
