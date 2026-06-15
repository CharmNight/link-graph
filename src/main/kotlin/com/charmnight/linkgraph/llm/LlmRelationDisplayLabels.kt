package com.charmnight.linkgraph.llm

internal fun llmClassDiagramRelationDisplayLabel(label: String): String {
    val trimmed = label.trim()
    llmRelationKindDisplayLabelOrNull(trimmed)?.let { return it }
    val parts = Regex("^(extends|implements|field|ctor|call|param|local|return|throws)\\s+(.+)$")
        .matchEntire(trimmed)
        ?: return trimmed
    val prefix = llmRelationKindDisplayLabelOrNull(parts.groupValues[1]) ?: parts.groupValues[1]
    return "$prefix ${parts.groupValues[2].trim()}"
}

internal fun llmRelationKindDisplayLabel(value: String): String =
    llmRelationKindDisplayLabelOrNull(value.trim()) ?: value.trim()

private fun llmRelationKindDisplayLabelOrNull(value: String): String? =
    when (value) {
        "EXTENDS",
        "GENERALIZATION",
        "extends",
        -> "继承"
        "IMPLEMENTS",
        "REALIZATION",
        "implements",
        -> "实现"
        "FIELD",
        "field",
        -> "字段"
        "CONSTRUCTOR_PARAMETER",
        "ctor",
        -> "构造参数"
        "CALL",
        "CALLS",
        "METHOD_CALL",
        "call",
        -> "调用"
        "METHOD_PARAMETER",
        "param",
        -> "参数"
        "LOCAL_TYPE",
        "local",
        -> "局部类型"
        "METHOD_RETURN",
        "return",
        -> "返回"
        "THROWS",
        "throws",
        -> "抛出"
        "COMPOSITION",
        "composition",
        -> "组合"
        "AGGREGATION",
        "aggregation",
        -> "聚合"
        "ASSOCIATION",
        "association",
        -> "关联"
        "DEPENDENCY",
        "dependency",
        -> "依赖"
        "USES_TYPE",
        -> "类型依赖"
        "INJECT",
        "INJECTS",
        -> "注入"
        else -> null
    }
