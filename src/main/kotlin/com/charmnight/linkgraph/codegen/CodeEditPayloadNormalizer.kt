package com.charmnight.linkgraph.codegen

internal object CodeEditPayloadNormalizer {
    private val candidateKeys = listOf("with", "replacement", "body", "content", "code", "text", "newText", "after")

    fun normalize(payload: String): String {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return payload
        }
        val parsed = runCatching {
            RemoteCodeGenerationJsonParser(trimmed).parseValue() as? Map<*, *>
        }.getOrNull() ?: return payload
        return candidateKeys.firstNotNullOfOrNull { key ->
            (parsed[key] as? String)?.trim()?.takeIf(String::isNotEmpty)
        } ?: payload
    }
}
