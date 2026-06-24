package com.charmnight.linkgraph.application.indexed

import com.charmnight.linkgraph.architecture.ArchitectureEdge
import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureNode
import com.charmnight.linkgraph.architecture.ArchitectureNodeKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.charmnight.linkgraph.jvm.index.JvmStereotype
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.projection.GraphProjectionMetadata
import com.charmnight.linkgraph.source.SourceOrigin

/**
 * 计算架构节点所属的分层类别。
 *
 * 优先回退到符号索引中的真实分类；找不到时按节点类别推导，
 * 例如资源节点固定为资源层，库/JDK 节点固定为对应外部层，
 * 模块/包/层等聚合节点采用成员中最显著的分层。
 */
fun ArchitectureNode.indexedLayerKind(index: ArchitectureGraphIndex): IndexedGraphLayerKind =
    index.findSymbol(id)?.indexedLayerKind()
        ?: when (kind) {
            ArchitectureNodeKind.RESOURCE -> IndexedGraphLayerKind.RESOURCE
            ArchitectureNodeKind.CLASS,
            ArchitectureNodeKind.INTERFACE,
            ArchitectureNodeKind.ENUM,
            ArchitectureNodeKind.ANNOTATION,
            ArchitectureNodeKind.RECORD,
            ArchitectureNodeKind.OBJECT,
            -> IndexedGraphLayerKind.PROJECT_SOURCE
            ArchitectureNodeKind.MODULE,
            ArchitectureNodeKind.PACKAGE,
            ArchitectureNodeKind.COMPONENT,
            ArchitectureNodeKind.SERVICE,
            ArchitectureNodeKind.LAYER,
            -> memberLayerCounts(index).dominantLayerKind() ?: IndexedGraphLayerKind.AGGREGATE
            ArchitectureNodeKind.LIBRARY -> IndexedGraphLayerKind.EXTERNAL_LIBRARY
            ArchitectureNodeKind.JDK -> IndexedGraphLayerKind.JDK
        }

/**
 * 计算架构节点的来源类别，用于在前端区分源码、库、JDK、资源等显示形态。
 * 聚合节点会被归类为合成的聚合来源。
 */
fun ArchitectureNode.indexedSourceKind(index: ArchitectureGraphIndex): IndexedGraphSourceKind =
    index.findSymbol(id)?.indexedSourceKind()
        ?: when (kind) {
            ArchitectureNodeKind.RESOURCE -> IndexedGraphSourceKind.RESOURCE_FILE
            ArchitectureNodeKind.CLASS,
            ArchitectureNodeKind.INTERFACE,
            ArchitectureNodeKind.ENUM,
            ArchitectureNodeKind.ANNOTATION,
            ArchitectureNodeKind.RECORD,
            ArchitectureNodeKind.OBJECT,
            -> IndexedGraphSourceKind.SOURCE_CLASS
            ArchitectureNodeKind.MODULE,
            ArchitectureNodeKind.PACKAGE,
            ArchitectureNodeKind.COMPONENT,
            ArchitectureNodeKind.SERVICE,
            ArchitectureNodeKind.LAYER,
            ArchitectureNodeKind.LIBRARY,
            ArchitectureNodeKind.JDK,
            -> IndexedGraphSourceKind.SYNTHETIC_AGGREGATE
        }

/** 计算架构节点在分层图中的角色（入口、服务、数据等），用于配色与图标。 */
fun ArchitectureNode.indexedNodeRole(index: ArchitectureGraphIndex): IndexedGraphNodeRole {
    val symbol = index.findSymbol(id)
    if (symbol != null) {
        return symbol.indexedNodeRole()
    }
    return when (kind) {
        ArchitectureNodeKind.RESOURCE -> IndexedGraphNodeRole.RESOURCE
        ArchitectureNodeKind.COMPONENT -> IndexedGraphNodeRole.UNKNOWN
        ArchitectureNodeKind.SERVICE -> IndexedGraphNodeRole.SERVICE
        ArchitectureNodeKind.LAYER -> layerRole()
        ArchitectureNodeKind.LIBRARY,
        ArchitectureNodeKind.JDK,
        -> IndexedGraphNodeRole.EXTERNAL
        else -> IndexedGraphNodeRole.UNKNOWN
    }
}

/**
 * 汇总架构节点的索引元数据，作为前端展示和窗口统计使用。
 *
 * 输出包含分层、角色、来源类别、成员数量、是否可下钻等。
 * 同时初始化折叠桶数量为 0（后续由投影器更新）。
 */
fun ArchitectureNode.indexedNodeMetadata(
    index: ArchitectureGraphIndex,
    scopeKind: String,
): Map<String, String> {
    val layerCounts = memberLayerCounts(index)
    val memberClassCount = memberClassIds.size
    val memberResourceCount = memberResourceIds.size
    val expandable = memberClassCount > 0 || memberResourceCount > 0 || metadata["architecture.drillDownClassScope"] != null
    return buildMap {
        put("indexed.layerKind", indexedLayerKind(index).name)
        put("indexed.nodeRole", indexedNodeRole(index).name)
        put("indexed.scopeKind", scopeKind)
        put("indexed.sourceKind", indexedSourceKind(index).name)
        put("indexed.memberClassCount", memberClassCount.toString())
        put("indexed.memberResourceCount", memberResourceCount.toString())
        put(GraphProjectionMetadata.Indexed.COLLAPSED_COUNT, "0")
        put("indexed.expandable", expandable.toString())
        put("indexed.member.projectSource", layerCounts.projectSource.toString())
        put("indexed.member.externalLibrary", layerCounts.externalLibrary.toString())
        put("indexed.member.jdk", layerCounts.jdk.toString())
        put("indexed.member.resource", layerCounts.resource.toString())
        put("indexed.member.aggregate", layerCounts.aggregate.toString())
        put(GraphProjectionMetadata.Indexed.Collapsed.PROJECT_SOURCE, "0")
        put(GraphProjectionMetadata.Indexed.Collapsed.EXTERNAL_LIBRARY, "0")
        put(GraphProjectionMetadata.Indexed.Collapsed.JDK, "0")
        put(GraphProjectionMetadata.Indexed.Collapsed.RESOURCE, "0")
        put(GraphProjectionMetadata.Indexed.Collapsed.AGGREGATE, "0")
    }
}

/** 汇总架构边的索引元数据，包括关系种类、分层来源、采样数、置信度等。 */
fun ArchitectureEdge.indexedEdgeMetadata(index: ArchitectureGraphIndex): Map<String, String> =
    buildMap {
        put("indexed.relationKind", kind.name)
        put("indexed.relationLayer", indexedRelationLayer(index).name)
        put("indexed.sourceCount", count.coerceAtLeast(sourceRelationIds.size).toString())
        put("indexed.sampleCount", sourceRelationIds.size.toString())
        put("indexed.sourceRelationIds", sourceRelationIds.joinToString(","))
        put("indexed.aggregate", (metadata["architecture.aggregate"] != null || sourceRelationIds.size > 1 || count > 1).toString())
        put("indexed.confidence", confidence.indexedConfidence())
    }

/** 计算符号表中的符号所属的分层类别。 */
fun JvmSymbol.indexedLayerKind(): IndexedGraphLayerKind =
    when (this) {
        is JvmResourceSymbol -> IndexedGraphLayerKind.RESOURCE
        is JvmClassSymbol -> when {
            jdk || origin in setOf(SourceOrigin.JDK_SOURCE, SourceOrigin.JDK_CLASS) -> IndexedGraphLayerKind.JDK
            library || external || origin in externalLibraryOrigins -> IndexedGraphLayerKind.EXTERNAL_LIBRARY
            else -> IndexedGraphLayerKind.PROJECT_SOURCE
        }
        is JvmMethodSymbol -> origin.indexedLayerKind()
        is JvmFieldSymbol -> origin.indexedLayerKind()
        else -> origin.indexedLayerKind()
    }

/** 计算符号的来源类别，区分源码类、库类、JDK 类、资源文件等。 */
fun JvmSymbol.indexedSourceKind(): IndexedGraphSourceKind =
    when (this) {
        is JvmResourceSymbol -> IndexedGraphSourceKind.RESOURCE_FILE
        is JvmClassSymbol -> when (indexedLayerKind()) {
            IndexedGraphLayerKind.JDK -> IndexedGraphSourceKind.JDK_CLASS
            IndexedGraphLayerKind.EXTERNAL_LIBRARY -> IndexedGraphSourceKind.LIBRARY_CLASS
            IndexedGraphLayerKind.PROJECT_SOURCE -> IndexedGraphSourceKind.SOURCE_CLASS
            IndexedGraphLayerKind.RESOURCE -> IndexedGraphSourceKind.RESOURCE_FILE
            IndexedGraphLayerKind.AGGREGATE -> IndexedGraphSourceKind.SYNTHETIC_AGGREGATE
        }
        else -> when (indexedLayerKind()) {
            IndexedGraphLayerKind.JDK -> IndexedGraphSourceKind.JDK_CLASS
            IndexedGraphLayerKind.EXTERNAL_LIBRARY -> IndexedGraphSourceKind.LIBRARY_CLASS
            IndexedGraphLayerKind.RESOURCE -> IndexedGraphSourceKind.RESOURCE_FILE
            IndexedGraphLayerKind.PROJECT_SOURCE,
            IndexedGraphLayerKind.AGGREGATE,
            -> IndexedGraphSourceKind.SOURCE_CLASS
        }
    }

/** 计算符号的节点角色；测试源、控制器、服务、仓储等都会被识别出来。 */
fun JvmSymbol.indexedNodeRole(): IndexedGraphNodeRole =
    when (this) {
        is JvmResourceSymbol -> IndexedGraphNodeRole.RESOURCE
        is JvmClassSymbol -> when {
            indexedLayerKind() in setOf(IndexedGraphLayerKind.EXTERNAL_LIBRARY, IndexedGraphLayerKind.JDK) -> IndexedGraphNodeRole.EXTERNAL
            testSource -> IndexedGraphNodeRole.TEST
            stereotype == JvmStereotype.CONTROLLER -> IndexedGraphNodeRole.API
            stereotype == JvmStereotype.SERVICE -> IndexedGraphNodeRole.SERVICE
            stereotype == JvmStereotype.REPOSITORY -> IndexedGraphNodeRole.DATA
            stereotype == JvmStereotype.CONFIGURATION -> IndexedGraphNodeRole.CONFIG
            else -> IndexedGraphNodeRole.UNKNOWN
        }
        else -> when (indexedLayerKind()) {
            IndexedGraphLayerKind.EXTERNAL_LIBRARY,
            IndexedGraphLayerKind.JDK,
            -> IndexedGraphNodeRole.EXTERNAL
            else -> IndexedGraphNodeRole.UNKNOWN
        }
    }

/** 从图节点元数据读取分层类别；缺失时返回 null。 */
fun GraphNode.indexedLayerKindOrNull(): IndexedGraphLayerKind? =
    metadata["indexed.layerKind"]?.let { raw -> IndexedGraphLayerKind.entries.firstOrNull { it.name == raw } }

/** 根据起点和终点的分层推断关系属于哪一类跨层关系（项目内部、项目到 JDK 等）。 */
fun IndexedGraphLayerKind.relationLayerTo(target: IndexedGraphLayerKind): IndexedGraphRelationLayer =
    when {
        this == IndexedGraphLayerKind.AGGREGATE || target == IndexedGraphLayerKind.AGGREGATE -> IndexedGraphRelationLayer.AGGREGATE
        this == IndexedGraphLayerKind.RESOURCE || target == IndexedGraphLayerKind.RESOURCE -> IndexedGraphRelationLayer.PROJECT_TO_RESOURCE
        this == IndexedGraphLayerKind.JDK || target == IndexedGraphLayerKind.JDK -> IndexedGraphRelationLayer.PROJECT_TO_JDK
        this == IndexedGraphLayerKind.EXTERNAL_LIBRARY || target == IndexedGraphLayerKind.EXTERNAL_LIBRARY -> IndexedGraphRelationLayer.PROJECT_TO_EXTERNAL
        else -> IndexedGraphRelationLayer.PROJECT_INTERNAL
    }

/** 计算架构边两端节点所属的跨层关系类别。聚合关系直接归类为聚合层。 */
private fun ArchitectureEdge.indexedRelationLayer(index: ArchitectureGraphIndex): IndexedGraphRelationLayer {
    if (metadata["architecture.aggregate"] != null || kind in aggregateRelationKinds) {
        return IndexedGraphRelationLayer.AGGREGATE
    }
    val fromLayer = index.node(fromNodeId)?.indexedLayerKind(index)
        ?: index.findSymbol(fromNodeId)?.indexedLayerKind()
        ?: IndexedGraphLayerKind.PROJECT_SOURCE
    val toLayer = index.node(toNodeId)?.indexedLayerKind(index)
        ?: index.findSymbol(toNodeId)?.indexedLayerKind()
        ?: IndexedGraphLayerKind.PROJECT_SOURCE
    return fromLayer.relationLayerTo(toLayer)
}

/** 把来源类型映射为分层类别。 */
private fun SourceOrigin.indexedLayerKind(): IndexedGraphLayerKind =
    when (this) {
        SourceOrigin.JDK_SOURCE,
        SourceOrigin.JDK_CLASS,
        -> IndexedGraphLayerKind.JDK
        SourceOrigin.LIBRARY_SOURCE_JAR,
        SourceOrigin.LIBRARY_CLASS_JAR,
        SourceOrigin.USER_ATTACHED_SOURCE_JAR,
        SourceOrigin.USER_ATTACHED_CLASS_JAR,
        SourceOrigin.DECOMPILED,
        -> IndexedGraphLayerKind.EXTERNAL_LIBRARY
        SourceOrigin.CONTENT_ROOT -> IndexedGraphLayerKind.RESOURCE
        SourceOrigin.PROJECT_SOURCE,
        SourceOrigin.LOCAL_FILE,
        -> IndexedGraphLayerKind.PROJECT_SOURCE
    }

/** 统计聚合节点的成员在每层中的数量，用于判断主导分层。 */
private fun ArchitectureNode.memberLayerCounts(index: ArchitectureGraphIndex): IndexedGraphLayerCounts =
    (memberClassIds + memberResourceIds)
        .fold(IndexedGraphLayerCounts()) { counts, memberId ->
            val layer = index.findSymbol(memberId)?.indexedLayerKind()
                ?: index.node(memberId)?.indexedLayerKind(index)
                ?: IndexedGraphLayerKind.PROJECT_SOURCE
            counts + IndexedGraphLayerCounts.single(layer)
        }

/** 找出唯一非零的分层；用于聚合节点主导分层判断。多种分层同时存在时返回 null。 */
private fun IndexedGraphLayerCounts.dominantLayerKind(): IndexedGraphLayerKind? {
    val nonZeroLayers = listOf(
        IndexedGraphLayerKind.PROJECT_SOURCE to projectSource,
        IndexedGraphLayerKind.EXTERNAL_LIBRARY to externalLibrary,
        IndexedGraphLayerKind.JDK to jdk,
        IndexedGraphLayerKind.RESOURCE to resource,
        IndexedGraphLayerKind.AGGREGATE to aggregate,
    ).filter { (_, count) -> count > 0 }
    return nonZeroLayers.singleOrNull()?.first
}

/** 根据聚合节点的限定名（如 API、SERVICE 等）猜测角色。 */
private fun ArchitectureNode.layerRole(): IndexedGraphNodeRole =
    when (qualifiedName.uppercase()) {
        "API" -> IndexedGraphNodeRole.API
        "SERVICE" -> IndexedGraphNodeRole.SERVICE
        "DATA" -> IndexedGraphNodeRole.DATA
        "CONFIG" -> IndexedGraphNodeRole.CONFIG
        else -> IndexedGraphNodeRole.UNKNOWN
    }

/** 把关系置信度转换为对外可读的稳定字符串。 */
private fun JvmRelationConfidence.indexedConfidence(): String =
    when (this) {
        JvmRelationConfidence.PROVEN -> "STATIC"
        JvmRelationConfidence.RULE_INFERRED -> "RULE_INFERRED"
        JvmRelationConfidence.RUNTIME_REQUIRED -> "RUNTIME_REQUIRED"
        JvmRelationConfidence.AMBIGUOUS -> "AMBIGUOUS"
    }

/** 视为聚合结构的关系种类集合。 */
private val aggregateRelationKinds = setOf(
    JvmRelationKind.MODULE_CONTAINS_PACKAGE,
    JvmRelationKind.PACKAGE_CONTAINS_CLASS,
)

/** 表示符号来源于外部库的来源种类集合。 */
private val externalLibraryOrigins = setOf(
    SourceOrigin.LIBRARY_SOURCE_JAR,
    SourceOrigin.LIBRARY_CLASS_JAR,
    SourceOrigin.USER_ATTACHED_SOURCE_JAR,
    SourceOrigin.USER_ATTACHED_CLASS_JAR,
    SourceOrigin.DECOMPILED,
)
