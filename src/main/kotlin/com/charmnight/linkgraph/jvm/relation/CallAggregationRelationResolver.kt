package com.charmnight.linkgraph.jvm.relation

/**
 * 把"方法调用"按聚合粒度解析为 JVM 关系的解析器。
 *
 * 实际的提取逻辑复用 [ClassDiagramRelationExtractor] 中的方法调用提取，
 * 本类只作为 [JvmRelationResolver] 的一个注册项，让关系解析管线可以按 id 派发。
 * 这样做的好处是：类图与一般 JVM 关系视图共用同一份调用关系抽取实现，避免重复。
 */
class CallAggregationRelationResolver : JvmRelationResolver {
    /** 解析器唯一标识，用于关系解析管线按 id 路由。 */
    override val id: String = "jvm.call-aggregation"

    /**
     * 在给定 JVM 解析上下文中提取方法调用关系。
     * @param context 当前 PSI 上下文，提供类、方法等元素访问入口
     * @return 提取到的 JVM 关系列表
     */
    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        return ClassDiagramRelationExtractor.extractMethodCallRelations(context)
    }
}
