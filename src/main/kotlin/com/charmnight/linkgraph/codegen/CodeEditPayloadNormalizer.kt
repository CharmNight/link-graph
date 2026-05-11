package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.LlmJsonSupport

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
        val parsed = LlmJsonSupport.parseObjectOrNull(trimmed) ?: return payload
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
