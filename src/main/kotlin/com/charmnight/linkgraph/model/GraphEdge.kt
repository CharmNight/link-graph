package com.charmnight.linkgraph.model

/**
 * 表示链路图中的一条关系边。
 *
 * 边连接两个节点，描述它们之间的某种语义关系（调用、继承、注入等）。
 * 与节点类似，边也携带可信度、绑定状态、证据、差异等元信息。
 */
data class GraphEdge(
    /** 保存边的唯一标识。 */
    val id: String,
    /** 保存边的语义类型。 */
    val type: EdgeType,
    /** 保存起始节点标识。 */
    val fromNodeId: String,
    /** 保存目标节点标识。 */
    val toNodeId: String,
    /** 保存边的展示标签。 */
    val label: String? = null,
    /** 保存当前边结论的可信程度。 */
    val certainty: Certainty = Certainty.PROVEN,
    /** 保存当前边的绑定状态。 */
    val bindingStatus: BindingStatus = BindingStatus.BOUND,
    /** 保存用于界面展示的业务状态。 */
    val status: String? = null,
    /** 保存当前边的差异信息。 */
    val diff: GraphDiff = GraphDiff(),
    /** 保存支撑当前边的证据列表。 */
    val evidence: List<GraphEvidence> = emptyList(),
    /** 保存当前边的不确定性说明。 */
    val uncertainty: GraphUncertainty? = null,
    /** 保存边的扩展元数据。 */
    val metadata: Map<String, String> = emptyMap(),
    /** 保存边来源于事实、设计稿还是草稿。 */
    val sourceTag: GraphSourceTag = GraphSourceTag.FACT,
) {
    /**
     * 提供稳定边标识的构建逻辑。
     */
    companion object {
        /**
         * 根据边类型、起止节点和可选归属上下文生成稳定标识。
         *
         * 稳定标识意味着同一逻辑边在不同会话中生成的 ID 相同，
         * 这是差分比对、缓存等机制正常工作的前提。
         *
         * @param type 边类型
         * @param fromNodeId 起点节点 ID
         * @param toNodeId 终点节点 ID
         * @param ownerContext 归属上下文（例如视图名）；可空
         * @return 形如 "TYPE:owner/from->to" 或 "TYPE:from->to" 的稳定 ID
         */
        fun stableId(
            type: EdgeType,
            fromNodeId: String,
            toNodeId: String,
            ownerContext: String? = null,
        ): String {
            // 将边类型转换为可序列化且稳定的片段，便于跨会话复用。
            val typePart = normalizeStableType(type.name)
            // 仅在存在归属上下文时追加归属片段，避免无意义的层级前缀。
            val ownerPart = ownerContext?.let { normalizeStableComponent(it) }?.takeIf { it.isNotBlank() }
            // 对起点节点标识做统一规范化，保证生成结果只包含安全字符。
            val fromPart = normalizeStableComponent(fromNodeId)
            // 对终点节点标识做统一规范化，保证生成结果只包含安全字符。
            val toPart = normalizeStableComponent(toNodeId)
            // 无归属上下文时直接拼出基础边标识，否则补上归属层级。
            return if (ownerPart == null) {
                "$typePart:$fromPart->$toPart"
            } else {
                "$typePart:$ownerPart/$fromPart->$toPart"
            }
        }
    }
}
