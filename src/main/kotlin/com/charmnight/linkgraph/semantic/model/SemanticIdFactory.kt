package com.charmnight.linkgraph.semantic.model

/** 匹配语义标识中的非字母数字字符；这些字符会被规范化为 "-"。 */
private val SEMANTIC_ID_NON_ALNUM = Regex("[^a-z0-9]+")

/**
 * 负责生成稳定的语义单元标识。
 *
 * 语义 ID 必须在不同运行、不同会话之间保持稳定，否则缓存与差分比对都会失效。
 * 本对象把各种来源（方法签名、资源种类等）映射为统一格式的 ID：
 *   "<namespace>:<normalized-key>"
 * 其中所有字符都做小写化和非字母数字替换，避免大小写或特殊字符造成 ID 漂移。
 */
object SemanticIdFactory {
    /**
     * 为方法语义单元生成稳定标识。
     * @param signature 方法签名（通常包含全限定名与参数类型）
     */
    fun methodUnitId(signature: String): String = compose("method", signature)

    /**
     * 为资源语义单元生成稳定标识。
     *
     * @param resourceKind 资源种类字符串
     * @param subjectId 资源主题 ID
     */
    fun resourceUnitId(
        resourceKind: String,
        subjectId: String,
    ): String = compose(resourceKind, subjectId)

    /**
     * 根据命名空间和原始键生成稳定标识。
     * 内部使用：先分别规范化，再用 ":" 拼接，保证可读性与稳定性。
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
     * 步骤：去首尾空白 → 转小写 → 非字母数字替换为 "-" → 去首尾 "-"；
     * 全空时回退为 "unknown" 保证总有非空结果。
     */
    private fun normalize(value: String): String {
        return value.trim()
            .lowercase()
            .replace(SEMANTIC_ID_NON_ALNUM, "-")
            .trim('-')
            .ifBlank { "unknown" }
    }
}
