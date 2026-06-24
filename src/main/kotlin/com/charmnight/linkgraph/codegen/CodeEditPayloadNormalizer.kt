package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.LlmJsonCodec

/**
 * 代码编辑载荷归一化器：把不同命名的 LLM 输出字段统一映射为代码替换所需的字段。
 * 处理候选键集合与元数据键集合，从原始映射中提取目标文本。
 */
internal object CodeEditPayloadNormalizer {
    private val candidateKeys = listOf(
        "with",
        "replacement",
        "replacementCode",
        "replacementBody",
        "newText",
        "newBody",
        "newMethodBody",
        "newBlock",
        "newImplementation",
        "newSource",
        "newContent",
        "newCode",
        "methodBody",
        "methodImplementation",
        "body",
        "content",
        "code",
        "source",
        "text",
        "after",
    )
    private val metadataKeys = setOf(
        "id",
        "operationId",
        "filePath",
        "targetPath",
        "scopeId",
        "kind",
        "changeType",
        "methodSignature",
        "symbolSignature",
        "existingCodeSnippet",
        "existingImplementation",
        "before",
        "beforeText",
        "warnings",
        "editOperations",
        "editScopes",
    )

    fun normalize(payload: String): String {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return payload
        }
        val parsed = LlmJsonCodec.parseObjectOrNull(trimmed) ?: return payload
        findReplacementText(parsed)?.let { return it }
        if (containsMetadataKey(parsed)) {
            error("Code edit payload contains metadata JSON but no source replacement field.")
        }
        return payload
    }

    private fun findReplacementText(parsed: Map<*, *>): String? {
        return candidateKeys.firstNotNullOfOrNull { key ->
            replacementTextFromValue(parsed[key])
        }
    }

    private fun replacementTextFromValue(value: Any?): String? {
        return when (value) {
            is String -> value.trim().takeIf(String::isNotEmpty)?.let(::normalizeNestedReplacementText)
            is Map<*, *> -> findReplacementText(value)
            else -> null
        }
    }

    private fun normalizeNestedReplacementText(text: String): String {
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return text
        }
        return runCatching { normalize(text) }.getOrElse { text }
    }

    private fun containsMetadataKey(parsed: Map<*, *>): Boolean {
        return parsed.keys.any { key -> key is String && key in metadataKeys }
    }
}
