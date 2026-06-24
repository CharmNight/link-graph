package com.charmnight.linkgraph.jvm.relation

/**
 * JVM 关系索引。
 *
 * 把一组 [JvmRelation] 按多种维度索引（按源/按目标/按种类），
 * 让关系查询可以在 O(1) 或 O(k) 时间内完成。
 * 构造时按"保留优先级"排序并截取，避免大规模项目把所有 CALL 关系都塞进索引。
 *
 * @param relations 原始关系列表
 * @param maxRelations 最多保留的关系数；超出按优先级裁剪
 */
class JvmRelationIndex(
    relations: List<JvmRelation> = emptyList(),
    maxRelations: Int = Int.MAX_VALUE,
) {
    /** 是否因超过上限被截断。 */
    val truncated: Boolean = relations.size > maxRelations

    /**
     * 排序并截取后的关系列表。
     * 排序键：保留优先级 → 置信度 → ID，保证结果稳定可复现。
     */
    val relations: List<JvmRelation> = relations
        .sortedWith(
            compareBy<JvmRelation> { relation -> relation.kind.retentionPriority() }
                .thenBy { relation -> relation.confidence.ordinal }
                .thenBy { relation -> relation.id },
        )
        .take(maxRelations)

    /** 按源符号 ID 索引。 */
    val relationsBySourceId: Map<String, List<JvmRelation>> = relations.groupBy { relation -> relation.fromSymbolId }
    /** 按目标符号 ID 索引。 */
    val relationsByTargetId: Map<String, List<JvmRelation>> = relations.groupBy { relation -> relation.toSymbolId }
    /** 按关系种类索引。 */
    val relationsByKind: Map<JvmRelationKind, List<JvmRelation>> = relations.groupBy { relation -> relation.kind }

    /** 取某符号的出边（以该符号为源的关系）。 */
    fun outgoing(symbolId: String): List<JvmRelation> = relationsBySourceId[symbolId].orEmpty()

    /** 取某符号的入边（以该符号为目标的关系）。 */
    fun incoming(symbolId: String): List<JvmRelation> = relationsByTargetId[symbolId].orEmpty()

    /** 取某种类的全部关系。 */
    fun byKind(kind: JvmRelationKind): List<JvmRelation> = relationsByKind[kind].orEmpty()

    /** 取某符号的反射目标（REFLECTS_TO 类型的出边）。 */
    fun findReflectiveTargets(symbolId: String): List<JvmRelation> =
        outgoing(symbolId).filter { relation -> relation.kind == JvmRelationKind.REFLECTS_TO }

    /** 取某符号通过 ServiceLoader 加载的目标。 */
    fun serviceLoaderLoads(symbolId: String): List<JvmRelation> =
        outgoing(symbolId).filter { relation -> relation.kind == JvmRelationKind.SERVICE_LOADER_LOADS }

    /** 取某符号的代理目标（USES_PROXY 类型的出边）。 */
    fun proxyTargets(symbolId: String): List<JvmRelation> =
        outgoing(symbolId).filter { relation -> relation.kind == JvmRelationKind.USES_PROXY }

    /**
     * 关系种类的保留优先级。
     * 0 = 最高（必须保留），3 = 最低（结构关系，量大但价值低）。
     * 当索引需要裁剪时，高优先级关系先保留。
     */
    private fun JvmRelationKind.retentionPriority(): Int =
        when (this) {
            // 框架与基础设施类关系：优先保留
            JvmRelationKind.EXTENDS,
            JvmRelationKind.IMPLEMENTS,
            JvmRelationKind.INJECTS,
            JvmRelationKind.SPI_PROVIDES,
            JvmRelationKind.SERVICE_LOADER_LOADS,
            JvmRelationKind.REFLECTS_TO,
            JvmRelationKind.TESTS,
            JvmRelationKind.USES_PROXY,
            JvmRelationKind.SPRING_EVENT_PUBLISHES,
            JvmRelationKind.SPRING_EVENT_LISTENS,
            JvmRelationKind.DUBBO_PROVIDES,
            JvmRelationKind.DUBBO_REFERENCES,
            JvmRelationKind.FEIGN_CLIENT_CALLS,
            JvmRelationKind.FEIGN_ROUTES_TO,
            JvmRelationKind.SPRING_ROUTES_TO,
            JvmRelationKind.MQ_PUBLISHES,
            JvmRelationKind.MQ_CONSUMES,
            -> 0
            // 注解与资源绑定：次优先
            JvmRelationKind.ANNOTATED_BY,
            JvmRelationKind.RESOURCE_BINDS,
            -> 1
            // 调用与类型使用：再次（量大）
            JvmRelationKind.CALLS,
            JvmRelationKind.USES_TYPE,
            -> 2
            // 结构包含关系：最低优先级（量大但信息密度低）
            JvmRelationKind.MODULE_CONTAINS_PACKAGE,
            JvmRelationKind.PACKAGE_CONTAINS_CLASS,
            -> 3
        }
}
