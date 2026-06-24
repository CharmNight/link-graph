package com.charmnight.linkgraph.semantic.subject

/**
 * 表示资源与代码方法之间的锚点信息。
 *
 * 资源（SQL、HTTP、MQ 等）往往通过方法上的注解或配置与代码绑定，
 * 本结构记录绑定的具体方法（归属类 + 方法名 + 参数 + 返回类型），
 * 用于把资源节点关联到方法节点。
 */
data class ResourceAnchor(
    /** 保存归属类型名。 */
    val ownerName: String,
    /** 保存方法名。 */
    val methodName: String,
    /** 保存参数类型名列表；null 表示未指定（不区分重载）。 */
    val parameterTypeNames: List<String>? = null,
    /** 保存返回类型名；null 表示未指定。 */
    val returnTypeName: String? = null,
)
