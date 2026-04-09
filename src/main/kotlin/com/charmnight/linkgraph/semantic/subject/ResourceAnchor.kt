package com.charmnight.linkgraph.semantic.subject

/**
 * 表示资源与代码方法之间的锚点信息。
 */
data class ResourceAnchor(
    /** 保存归属类型名。 */
    val ownerName: String,
    /** 保存方法名。 */
    val methodName: String,
    /** 保存参数类型名列表。 */
    val parameterTypeNames: List<String>? = null,
    /** 保存返回类型名。 */
    val returnTypeName: String? = null,
)
