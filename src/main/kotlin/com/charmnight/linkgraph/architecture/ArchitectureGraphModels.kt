package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmResourceKind
import com.charmnight.linkgraph.jvm.index.JvmSourceRef
import com.charmnight.linkgraph.jvm.index.JvmStereotype
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind

/**
 * 架构图节点种类。
 *
 * 架构图把项目按模块/包/类/服务/层等维度组织，
 * 每个维度对应一种节点种类。
 */
enum class ArchitectureNodeKind {
    /** 模块节点。 */
    MODULE,
    /** 包节点。 */
    PACKAGE,
    /** 类节点。 */
    CLASS,
    /** 接口节点。 */
    INTERFACE,
    /** 枚举节点。 */
    ENUM,
    /** 注解节点。 */
    ANNOTATION,
    /** record 节点。 */
    RECORD,
    /** Kotlin object 节点。 */
    OBJECT,
    /** 服务节点（多个类的逻辑聚合）。 */
    SERVICE,
    /** 资源节点。 */
    RESOURCE,
    /** 架构层节点。 */
    LAYER,
    /** 依赖库节点。 */
    LIBRARY,
    /** JDK 节点。 */
    JDK,
    /** 组件节点（项目内逻辑分组）。 */
    COMPONENT,
}

/** 架构图聚合粒度。 */
enum class ArchitectureAggregationLevel {
    /** 概览级（按模块/服务聚合）。 */
    OVERVIEW,
    /** 包级（更细粒度）。 */
    PACKAGE,
}

/**
 * 架构图节点。
 *
 * 把符号索引中的类/资源等抽象为架构图节点，
 * 携带种类、全限定名、成员集合与可选的源码位置。
 */
data class ArchitectureNode(
    /** 节点 ID。 */
    val id: String,
    /** 节点种类。 */
    val kind: ArchitectureNodeKind,
    /** 全限定名。 */
    val qualifiedName: String,
    /** 标题。 */
    val title: String,
    /** 所属模块名。 */
    val moduleName: String? = null,
    /** 所属包名。 */
    val packageName: String? = null,
    /** 类的细分种类（仅类节点有效）。 */
    val classKind: JvmClassKind? = null,
    /** 刻度（例如 SPRING_SERVICE / EJB 等）。 */
    val stereotype: JvmStereotype? = null,
    /** 资源种类（仅资源节点有效）。 */
    val resourceKind: JvmResourceKind? = null,
    /** 成员类 ID 集合。 */
    val memberClassIds: Set<String> = emptySet(),
    /** 成员资源 ID 集合。 */
    val memberResourceIds: Set<String> = emptySet(),
    /** 源码位置。 */
    val source: JvmSourceRef? = null,
    /** 扩展元数据。 */
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * 架构图边。
 *
 * 与底层 JVM 关系一一对应，但属于"架构视图"层。
 */
data class ArchitectureEdge(
    /** 边 ID。 */
    val id: String,
    /** 边种类。 */
    val kind: JvmRelationKind,
    /** 起点节点 ID。 */
    val fromNodeId: String,
    /** 终点节点 ID。 */
    val toNodeId: String,
    /** 置信度。 */
    val confidence: JvmRelationConfidence,
    /** 出现次数。 */
    val count: Int = 1,
    /** 来源关系 ID 集合（一条架构边可能合并多条底层关系）。 */
    val sourceRelationIds: Set<String> = emptySet(),
    /** 扩展元数据。 */
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * 完整架构图。
 *
 * 携带节点、边、根节点列表与索引版本号。
 * 版本号不匹配时需要重建。
 */
data class ArchitectureGraph(
    /** 当前架构图包含的全部节点。 */
    val nodes: List<ArchitectureNode> = emptyList(),
    /** 当前架构图包含的全部边。 */
    val edges: List<ArchitectureEdge> = emptyList(),
    /** 标记为根节点的 ID 列表（用于 UI 默认展开或导航）。 */
    val rootNodeIds: List<String> = emptyList(),
    /** 索引版本号，与底层索引不匹配时需要重建。 */
    val indexVersion: String = "architecture-graph-v1",
    /** 标记是否因为数据量过大而被截断。 */
    val truncated: Boolean = false,
    /** 扩展元数据。 */
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * 架构图快照：把符号索引、关系索引与图本身打包为不可变快照。
 *
 * 让消费方一次性拿到生成架构图所需的全部底层信息。
 */
data class ArchitectureGraphSnapshot(
    /** 快照包含的符号索引，描述项目里所有被索引的类/资源符号。 */
    val symbolIndex: com.charmnight.linkgraph.jvm.index.JvmSymbolIndex,
    /** 快照包含的关系索引，描述符号之间的关系。 */
    val relationIndex: com.charmnight.linkgraph.jvm.relation.JvmRelationIndex,
    /** 基于上述索引构建出的架构图本身。 */
    val graph: ArchitectureGraph,
)
