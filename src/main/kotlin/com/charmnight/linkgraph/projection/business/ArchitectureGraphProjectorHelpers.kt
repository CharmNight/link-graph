package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.architecture.ArchitectureNodeKind
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

/**
 * ArchitectureGraphProjector 的纯展示 / 名称计算 helper（P2-1 拆分）。
 *
 * 这些函数无状态、无 IntelliJ 依赖，与 ArchitectureGraphProjector 的索引查询 /
 * 节点投影主流程解耦后便于复用与单独测试。
 */

/**
 * 计算多段名称（每段是按分隔符切好的部分列表）共享的前缀长度。
 *
 * 用于"最短唯一后缀"算法：当多个节点共享前缀时，需要从共享前缀之后开始展示
 * 才能让每个节点名称在上下文中唯一。
 *
 * 例：`[[com, foo, Order], [com, foo, Payment]]` 共享 `[com, foo]`，返回 2。
 */
internal fun commonRootSize(names: List<List<String>>): Int {
    if (names.isEmpty()) {
        return 0
    }
    val first = names.first()
    var rootSize = 0
    for (index in first.indices) {
        val part = first[index]
        if (names.all { name -> name.getOrNull(index) == part }) {
            rootSize += 1
        } else {
            break
        }
    }
    return rootSize
}

/**
 * 把隐藏桶的 ID 转换为中文展示标签。
 *
 * 桶 ID 形如 "DATA_ACCESS" / "CONTROLLER" 等，对应 [ArchitectureDisplayLayer.laneId]；
 * 未匹配时原样返回。
 */
internal fun architectureBucketLabel(bucket: String): String =
    ArchitectureDisplayLayer.entries.firstOrNull { layer -> layer.laneId == bucket }?.label ?: bucket

/** 把 [ArchitectureDisplayLayer] 转换为前端展示用的 presentation.* metadata 字段。 */
internal fun ArchitectureDisplayLayer.presentationMetadata(): Map<String, String> =
    mapOf(
        "presentation.role" to role,
        "presentation.laneId" to laneId,
        "presentation.priority" to order.toString(),
        "presentation.compact" to "true",
    )

/** 把架构节点种类映射到图模型节点类型。LIBRARY / JDK 统一为 LIBRARY。 */
internal fun ArchitectureNodeKind.toNodeType(): NodeType = when (this) {
    ArchitectureNodeKind.MODULE -> NodeType.MODULE
    ArchitectureNodeKind.PACKAGE -> NodeType.PACKAGE
    ArchitectureNodeKind.COMPONENT -> NodeType.COMPONENT
    ArchitectureNodeKind.CLASS -> NodeType.CLASS
    ArchitectureNodeKind.INTERFACE -> NodeType.INTERFACE
    ArchitectureNodeKind.ENUM -> NodeType.ENUM
    ArchitectureNodeKind.ANNOTATION -> NodeType.ANNOTATION
    ArchitectureNodeKind.RECORD -> NodeType.RECORD
    ArchitectureNodeKind.OBJECT -> NodeType.OBJECT
    ArchitectureNodeKind.SERVICE -> NodeType.SERVICE
    ArchitectureNodeKind.RESOURCE -> NodeType.RESOURCE
    ArchitectureNodeKind.LAYER -> NodeType.LAYER
    ArchitectureNodeKind.LIBRARY,
    ArchitectureNodeKind.JDK,
    -> NodeType.LIBRARY
}

/** 判定节点种类是否属于"类型节点"（可使用全限定名作为签名）。 */
internal fun ArchitectureNodeKind.isTypeLike(): Boolean =
    this in setOf(
        ArchitectureNodeKind.CLASS,
        ArchitectureNodeKind.INTERFACE,
        ArchitectureNodeKind.ENUM,
        ArchitectureNodeKind.ANNOTATION,
        ArchitectureNodeKind.RECORD,
        ArchitectureNodeKind.OBJECT,
    )

/**
 * 节点优先级：数字越小越优先保留。
 *
 * 优先使用预计算的 `architecture.structureRank`，缺失时按节点类型回退。
 */
internal fun architectureNodePriority(node: GraphNode): Int =
    node.metadata["architecture.structureRank"]?.toIntOrNull()
        ?: when (node.type) {
            NodeType.MODULE -> 0
            NodeType.LAYER -> 1
            NodeType.SERVICE -> 2
            NodeType.COMPONENT -> 3
            NodeType.PACKAGE -> 4
            NodeType.RESOURCE -> 4
            NodeType.LIBRARY -> 5
            else -> 6
        }

/**
 * 边优先级：数字越小越优先保留。
 *
 * 结构边最优先，其次按聚合层级，再按 JVM 关系种类排序。
 */
internal fun architectureEdgePriority(edge: GraphEdge): Int = when {
    edge.metadata["architecture.graph.kind"] == "STRUCTURE" -> 0
    else -> when (edge.metadata["architecture.aggregate"]) {
        "LAYER" -> 1
        "SERVICE" -> 2
        "COMPONENT" -> 3
        "RESOURCE" -> 4
        "PACKAGE" -> 5
        else -> when (edge.metadata["jvm.relation.kind"]) {
            JvmRelationKind.MODULE_CONTAINS_PACKAGE.name -> 6
            JvmRelationKind.SPI_PROVIDES.name -> 7
            else -> 6
        }
    }
}

