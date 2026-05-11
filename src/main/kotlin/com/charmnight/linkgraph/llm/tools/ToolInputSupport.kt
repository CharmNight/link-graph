package com.charmnight.linkgraph.llm.tools

internal fun Map<String, Any?>.requiredString(key: String): String? {
    return this[key]?.toString()?.trim()?.takeIf(String::isNotEmpty)
}

internal fun Map<String, Any?>.optionalString(key: String): String? {
    return this[key]?.toString()?.trim()?.takeIf(String::isNotEmpty)
}

internal fun Map<String, Any?>.optionalInt(key: String): Int? {
    return (this[key] as? Number)?.toInt()
}

internal inline fun <reified T> Map<String, Any?>.requiredValue(key: String): T? {
    return this[key] as? T
}

internal inline fun <reified T> Map<String, Any?>.optionalList(key: String): List<T> {
    return (this[key] as? List<*>).orEmpty().filterIsInstance<T>()
}

internal fun AgentTool.missingRequired(key: String): ToolResult {
    return failure("$key 不能为空")
}

internal fun AgentTool.failure(
    errorMessage: String,
    payload: Map<String, Any?> = emptyMap(),
): ToolResult {
    return ToolResult(
        toolName = name,
        success = false,
        payload = payload,
        errorMessage = errorMessage,
    )
}
