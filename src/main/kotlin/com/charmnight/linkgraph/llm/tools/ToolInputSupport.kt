package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

/**
 * 从工具入参 Map 中读取必填字符串。
 * 值会做去空白处理；空白或缺失返回 null，让调用方走 missingRequired 路径。
 */
internal fun ToolInputPayload.requiredString(key: String): String? {
    return this[key]?.toString()?.trim()?.takeIf(String::isNotEmpty)
}

/**
 * 从工具入参 Map 中读取可选字符串。
 * 与 [requiredString] 行为一致，仅语义上表示"可有可无"。
 */
internal fun ToolInputPayload.optionalString(key: String): String? {
    return this[key]?.toString()?.trim()?.takeIf(String::isNotEmpty)
}

/** 读取可选整数；非数字类型时返回 null。 */
internal fun ToolInputPayload.optionalInt(key: String): Int? {
    return (this[key] as? Number)?.toInt()
}

/** 读取必填强类型值；类型不匹配或缺失时返回 null。 */
internal inline fun <reified T> ToolInputPayload.requiredValue(key: String): T? {
    return this[key] as? T
}

/** 读取可选列表；自动过滤掉类型不匹配的元素，保证列表元素都是 T。 */
internal inline fun <reified T> ToolInputPayload.optionalList(key: String): List<T> {
    return (this[key] as? List<*>).orEmpty().filterIsInstance<T>()
}

/**
 * 读取可选字符串列表。
 * 兼容两种输入：
 * - List<*>：过滤非字符串并去空白；
 * - String：按逗号或换行拆分，便于模型用单字符串表达多值。
 */
internal fun ToolInputPayload.optionalStringList(key: String): List<String> {
    return when (val value = this[key]) {
        is List<*> -> value.mapNotNull { item -> item?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        is String -> value.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
        else -> emptyList()
    }
}

/**
 * 工具方法：构造"必填字段缺失"的失败结果。
 * 让工具实现里只写一行 `return missingRequired("foo")`。
 */
internal fun AgentTool.missingRequired(key: String): ToolResult {
    return failure("$key 不能为空")
}

/**
 * 工具方法：构造通用的失败结果。
 * 同时附带 payload，让模型在失败时仍能拿到部分有用的上下文。
 */
internal fun AgentTool.failure(
    errorMessage: String,
    payload: ToolPayload = emptyMap(),
): ToolResult {
    return ToolResult(
        toolName = name,
        success = false,
        payload = payload,
        errorMessage = errorMessage,
    )
}
