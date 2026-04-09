package com.charmnight.linkgraph.semantic.model

/** 匹配语义标识中的非字母数字字符。 */
private val SEMANTIC_ID_NON_ALNUM = Regex("[^a-z0-9]+")

/**
 * 负责生成稳定的语义单元标识。
 */
object SemanticIdFactory {
    /**
     * 为方法语义单元生成稳定标识。
     */
    fun methodUnitId(signature: String): String = compose("method", signature)

    /**
     * 为资源语义单元生成稳定标识。
     */
    fun resourceUnitId(
        resourceKind: String,
        subjectId: String,
    ): String = compose(resourceKind, subjectId)

    /**
     * 根据命名空间和原始键生成稳定标识。
     */
    fun compose(
        namespace: String,
        rawKey: String,
    ): String {
        // 命名空间和原始键分别规范化后再拼接，保证不同来源生成结果一致。
        val normalizedNamespace = normalize(namespace)
        val normalizedKey = normalize(rawKey)
        return "$normalizedNamespace:$normalizedKey"
    }

    /**
     * 把任意文本规范化为适合放入语义标识的片段。
     */
    private fun normalize(value: String): String {
        return value.trim()
            .lowercase()
            .replace(SEMANTIC_ID_NON_ALNUM, "-")
            .trim('-')
            .ifBlank { "unknown" }
    }
}
