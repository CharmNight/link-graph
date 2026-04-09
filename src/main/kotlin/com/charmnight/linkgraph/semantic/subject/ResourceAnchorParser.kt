package com.charmnight.linkgraph.semantic.subject

/**
 * 负责从文本中解析资源锚点描述。
 */
class ResourceAnchorParser {
    /** 匹配完整方法签名引用，例如 `a.b.C.m(java.lang.String):void`。 */
    private val methodSignatureReferenceRegex = Regex(
        """\b([A-Za-z_][A-Za-z0-9_$]*(?:\.[A-Za-z_][A-Za-z0-9_$]*)+)\.([A-Za-z_][A-Za-z0-9_]*)\(([^)]*)\)(?::([A-Za-z0-9_$.<>\[\], ?]+))?""",
    )
    /** 匹配普通方法调用引用，例如 `a.b.C.m(`。 */
    private val methodCallReferenceRegex = Regex(
        """\b([A-Za-z_][A-Za-z0-9_$]*(?:\.[A-Za-z_][A-Za-z0-9_$]*)+)\.([A-Za-z_][A-Za-z0-9_]*)\s*\(""",
    )
    /** 匹配 `类名#方法名` 形式的引用。 */
    private val methodReferenceRegex = Regex("""([A-Za-z_][A-Za-z0-9_$.]*)#([A-Za-z_][A-Za-z0-9_]*)""")
    /** 匹配点号形式的类方法引用。 */
    private val dottedMethodReferenceRegex = Regex(
        """\b((?:[A-Za-z_][A-Za-z0-9_$]*\.)+[A-Z][A-Za-z0-9_$]*)\.([A-Za-z_][A-Za-z0-9_]*)\b(?=$|[^A-Za-z0-9_])""",
    )

    /**
     * 从输入文本中提取第一个可识别的资源锚点。
     */
    fun parse(source: String): ResourceAnchor? {
        // 优先解析完整方法签名，保留最丰富的参数与返回值信息。
        methodSignatureReferenceRegex.find(source)?.let { match ->
            return ResourceAnchor(
                ownerName = match.groupValues[1],
                methodName = match.groupValues[2],
                parameterTypeNames = parseMethodParameterTypes(match.groupValues[3]),
                returnTypeName = match.groupValues[4].trim().ifBlank { null },
            )
        }
        // 再退化到普通方法调用形式。
        methodCallReferenceRegex.find(source)?.let { match ->
            return ResourceAnchor(
                ownerName = match.groupValues[1],
                methodName = match.groupValues[2],
            )
        }
        // 兼容 `Owner#method` 形式的引用。
        methodReferenceRegex.find(source)?.let { match ->
            return ResourceAnchor(
                ownerName = match.groupValues[1],
                methodName = match.groupValues[2],
            )
        }
        // 最后兼容点号形式的类方法引用。
        dottedMethodReferenceRegex.find(source)?.let { match ->
            return ResourceAnchor(
                ownerName = match.groupValues[1],
                methodName = match.groupValues[2],
            )
        }
        return null
    }

    /**
     * 把方法参数文本拆解为参数类型列表。
     */
    private fun parseMethodParameterTypes(parametersText: String): List<String>? {
        // 空参数列表显式返回空集合，和“解析失败”区分开。
        val normalized = parametersText.trim()
        if (normalized.isEmpty()) {
            return emptyList()
        }
        // 去掉多余空白后按逗号拆分参数类型。
        return normalized
            .split(',')
            .map { token -> token.trim() }
            .filter(String::isNotBlank)
    }
}
