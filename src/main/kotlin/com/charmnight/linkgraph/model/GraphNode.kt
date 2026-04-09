package com.charmnight.linkgraph.model

/** 匹配非字母数字字符，用于稳定标识规范化。 */
private val NON_ALNUM = Regex("[^a-z0-9]+")

/**
 * 将任意字符串规范化为稳定的标识片段。
 */
internal fun normalizeStableComponent(value: String): String {
    // 先统一转成小写并替换掉不安全字符，保证生成结果适合拼入标识。
    val normalized = value.trim().lowercase().replace(NON_ALNUM, "-").trim('-')
    // 规范化后如果为空，则回退到固定占位值，避免生成非法标识。
    return normalized.ifEmpty { "unknown" }
}

/**
 * 将类型名称规范化为稳定标识片段。
 */
internal fun normalizeStableType(typeName: String): String = normalizeStableComponent(typeName)

/**
 * 表示链路图中的节点信息。
 */
data class GraphNode(
    /** 保存节点的唯一标识。 */
    val id: String,
    /** 保存节点的语义类型。 */
    val type: NodeType,
    /** 保存节点的显示标题。 */
    val title: String,
    /** 保存节点对应的源码位置。 */
    val location: String? = null,
    /** 保存节点对应的方法或资源签名。 */
    val signature: String? = null,
    /** 保存节点输入参数列表。 */
    val inputs: List<String> = emptyList(),
    /** 保存节点输出结果列表。 */
    val outputs: List<String> = emptyList(),
    /** 保存节点相关的文档说明。 */
    val doc: String? = null,
    /** 保存节点来源种类。 */
    val sourceKind: String? = null,
    /** 保存用于界面展示的状态。 */
    val status: String? = null,
    /** 保存节点与外部对象的绑定状态。 */
    val bindingStatus: BindingStatus = BindingStatus.BOUND,
    /** 保存当前节点结论的可信程度。 */
    val certainty: Certainty = Certainty.PROVEN,
    /** 保存节点的差异信息。 */
    val diff: GraphDiff = GraphDiff(),
    /** 保存支撑节点结论的证据列表。 */
    val evidence: List<GraphEvidence> = emptyList(),
    /** 保存节点的不确定性说明。 */
    val uncertainty: GraphUncertainty? = null,
    /** 保存节点的扩展元数据。 */
    val metadata: Map<String, String> = emptyMap(),
    /** 保存节点的数据来源标签。 */
    val sourceTag: GraphSourceTag = GraphSourceTag.FACT,
) {
    /**
     * 提供稳定节点标识的构建逻辑。
     */
    companion object {
        /**
         * 根据节点类型、原始键和可选归属上下文生成稳定标识。
         */
        fun stableId(type: NodeType, rawKey: String, ownerContext: String? = null): String {
            // 先规范化节点类型，确保不同来源对同一类型的标识保持一致。
            val typePart = normalizeStableType(type.name)
            // 仅在存在归属上下文时补充层级信息，避免标识无谓膨胀。
            val ownerPart = ownerContext?.let { normalizeStableComponent(it) }?.takeIf { it.isNotBlank() }
            // 对原始业务键做规范化，生成适合持久化和对比的稳定片段。
            val keyPart = normalizeStableComponent(rawKey)
            // 有归属上下文时以分层路径形式组织标识，否则直接使用类型加键值。
            return if (ownerPart == null) {
                "$typePart:$keyPart"
            } else {
                "$typePart:$ownerPart/$keyPart"
            }
        }
    }
}
