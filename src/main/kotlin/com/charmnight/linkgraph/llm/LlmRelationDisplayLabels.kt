package com.charmnight.linkgraph.llm

/**
 * 把类图关系原始标签转换为面向用户的中文展示标签。
 *
 * 原始标签可能是 "extends Foo"、"call bar"、"field baz" 等格式，
 * 本函数把前缀翻译为中文，保留目标名原样。
 *
 * @param label 原始标签
 * @return 中文展示标签
 */
internal fun llmClassDiagramRelationDisplayLabel(label: String): String {
    val trimmed = label.trim()
    // 先尝试整体匹配（例如直接是 "EXTENDS"）
    llmRelationKindDisplayLabelOrNull(trimmed)?.let { return it }
    // 否则按 "前缀 目标" 形式拆分，翻译前缀
    val parts = Regex("^(extends|implements|field|ctor|call|param|local|return|throws)\\s+(.+)$")
        .matchEntire(trimmed)
        ?: return trimmed
    val prefix = llmRelationKindDisplayLabelOrNull(parts.groupValues[1]) ?: parts.groupValues[1]
    return "$prefix ${parts.groupValues[2].trim()}"
}

/** 把单种关系种类名翻译为中文；无法识别时原样返回。 */
internal fun llmRelationKindDisplayLabel(value: String): String =
    llmRelationKindDisplayLabelOrNull(value.trim()) ?: value.trim()

/**
 * 关系种类 → 中文标签的字典。
 * 同时接受大小写与多别名（例如 EXTENDS / extends / GENERALIZATION 都映射为"继承"）。
 */
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
