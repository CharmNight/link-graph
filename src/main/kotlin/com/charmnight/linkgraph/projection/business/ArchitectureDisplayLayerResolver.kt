package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.application.indexed.IndexedGraphLayerKind
import com.charmnight.linkgraph.application.indexed.IndexedGraphNodeRole
import com.charmnight.linkgraph.application.indexed.indexedLayerKind
import com.charmnight.linkgraph.application.indexed.indexedNodeRole
import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureNode
import com.charmnight.linkgraph.architecture.ArchitectureNodeKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmStereotype
import com.charmnight.linkgraph.model.GraphNode

/**
 * 架构图展示分层枚举。
 *
 * 把架构节点按职责映射到稳定的展示泳道，供前端按层渲染和排序。
 * 每一项包含 [laneId]（前端使用的稳定标识）、[label]（中文展示名称）、
 * [role]（业务角色标识）与 [order]（默认排序权重，数字越小越靠前）。
 */
enum class ArchitectureDisplayLayer(
    val laneId: String,
    val label: String,
    val role: String,
    val order: Int,
) {
    /** 入口层：对外暴露的控制器、API 等接入点。 */
    ENTRY("entry", "入口层", "ENTRY", 10),

    /** 应用层：服务编排、组件、应用逻辑。 */
    APPLICATION("application", "应用层", "APPLICATION", 20),

    /** 领域层：领域模型、领域对象。 */
    DOMAIN("domain", "领域层", "DOMAIN", 30),

    /** 基础设施：仓储、配置、DAO/Mapper 等数据访问设施。 */
    DATA("data", "基础设施", "DATA", 40),

    /** 资源：外部资源节点（消息、配置文件、HTTP 资源等）。 */
    RESOURCE("resource", "资源", "RESOURCE", 50),

    /** 外部依赖：第三方库与 JDK 类。 */
    EXTERNAL("external", "外部依赖", "EXTERNAL", 60),
}

/**
 * 架构展示分层解析器。
 *
 * 根据架构节点的类型、Stereotype、索引角色或命名约定，
 * 推断该节点应当归属的 [ArchitectureDisplayLayer]，作为前端泳道渲染与排版的依据。
 */
class ArchitectureDisplayLayerResolver {
    /**
     * 解析架构节点对应的展示分层。
     *
     * 优先使用确定性的类型判定（资源、外部库、JDK、层级节点），
     * 其次查找直接对应的 JVM 类符号；当节点是聚合（如服务、组件）时，
     * 通过其成员类的多数派分层决定整体归属，最终回退到元数据或命名约定。
     *
     * @param node 当前要判定的架构节点
     * @param index 架构图索引，可用于解析成员类符号
     * @return 推断得到的展示分层，永远有非空回退值
     */
    fun resolve(
        node: ArchitectureNode,
        index: ArchitectureGraphIndex,
    ): ArchitectureDisplayLayer {
        if (node.kind == ArchitectureNodeKind.RESOURCE) {
            return ArchitectureDisplayLayer.RESOURCE
        }
        if (node.kind in setOf(ArchitectureNodeKind.LIBRARY, ArchitectureNodeKind.JDK)) {
            return ArchitectureDisplayLayer.EXTERNAL
        }
        if (node.kind == ArchitectureNodeKind.LAYER) {
            return layerFromText(node.qualifiedName) ?: ArchitectureDisplayLayer.APPLICATION
        }
        val directClass = index.findSymbol(node.id) as? JvmClassSymbol
        if (directClass != null) {
            return resolveClass(directClass)
        }
        val memberClassLayers = node.memberClassIds
            .mapNotNull { memberId -> index.findSymbol(memberId) as? JvmClassSymbol }
            .map(::resolveClass)
        return memberClassLayers.dominantLayer()
            ?: layerFromNodeMetadata(node)
            ?: when (node.kind) {
                ArchitectureNodeKind.SERVICE -> ArchitectureDisplayLayer.APPLICATION
                ArchitectureNodeKind.COMPONENT,
                ArchitectureNodeKind.PACKAGE,
                ArchitectureNodeKind.MODULE,
                -> ArchitectureDisplayLayer.APPLICATION
                else -> ArchitectureDisplayLayer.APPLICATION
            }
    }

    /**
     * 解析已投影的图节点对应的展示分层。
     *
     * 由于该入口下节点已经携带元数据，因此按以下顺序确定分层：
     * 1) 节点已有的 `presentation.laneId`；
     * 2) 索引层类别（`indexed.layerKind`）；
     * 3) 索引节点角色（`indexed.nodeRole`）；
     * 4) 通过节点全限定名进行文本启发式判定；
     * 全部失败时回退到应用层。
     *
     * @param node 已投影的图节点
     * @return 推断得到的展示分层
     */
    fun resolve(node: GraphNode): ArchitectureDisplayLayer =
        node.metadata["presentation.laneId"]?.let(::layerFromLaneId)
            ?: node.metadata["indexed.layerKind"]?.let(::layerFromIndexedLayer)
            ?: node.metadata["indexed.nodeRole"]?.let(::layerFromIndexedRole)
            ?: layerFromText(node.metadata["architecture.qualifiedName"].orEmpty())
            ?: ArchitectureDisplayLayer.APPLICATION

    /**
     * 根据类的索引层类与角色判定其分层。
     *
     * 外部库与 JDK 优先归到外部依赖；其次按节点角色映射到对应分层；
     * 当索引信息缺失时回退到全限定名的命名约定推断。
     *
     * @param cls 待判定的类符号
     * @return 该类应当归属的展示分层
     */
    private fun resolveClass(cls: JvmClassSymbol): ArchitectureDisplayLayer =
        when {
            cls.indexedLayerKind() == IndexedGraphLayerKind.JDK ||
                cls.indexedLayerKind() == IndexedGraphLayerKind.EXTERNAL_LIBRARY -> ArchitectureDisplayLayer.EXTERNAL
            cls.indexedNodeRole() == IndexedGraphNodeRole.API ||
                cls.indexedNodeRole() == IndexedGraphNodeRole.ENTRY -> ArchitectureDisplayLayer.ENTRY
            cls.indexedNodeRole() == IndexedGraphNodeRole.SERVICE -> ArchitectureDisplayLayer.APPLICATION
            cls.indexedNodeRole() == IndexedGraphNodeRole.DATA ||
                cls.indexedNodeRole() == IndexedGraphNodeRole.CONFIG -> ArchitectureDisplayLayer.DATA
            cls.indexedNodeRole() == IndexedGraphNodeRole.RESOURCE -> ArchitectureDisplayLayer.RESOURCE
            else -> layerFromText("${cls.packageName}.${cls.simpleName}") ?: ArchitectureDisplayLayer.APPLICATION
        }

    /**
     * 当无法通过直接符号判定时，依据节点元数据推断分层。
     *
     * 依次尝试：节点 stereotype、元数据中的类 stereotype、
     * 索引节点角色、索引层类别，最终回退到文本命名启发式。
     *
     * @param node 当前架构节点
     * @return 推断得到的展示分层，若全部失败则返回空
     */
    private fun layerFromNodeMetadata(node: ArchitectureNode): ArchitectureDisplayLayer? {
        node.stereotype?.let { stereotype -> layerFromStereotype(stereotype)?.let { return it } }
        node.metadata["class.stereotype"]
            ?.let { raw -> JvmStereotype.entries.firstOrNull { it.name == raw } }
            ?.let(::layerFromStereotype)
            ?.let { return it }
        node.metadata["indexed.nodeRole"]?.let(::layerFromIndexedRole)?.let { return it }
        node.metadata["indexed.layerKind"]?.let(::layerFromIndexedLayer)?.let { return it }
        return layerFromText("${node.packageName.orEmpty()}.${node.qualifiedName}.${node.title}")
    }

    /**
     * 根据 JVM 类的 Stereotype 推断分层。
     *
     * @param stereotype 类的语义标签
     * @return 对应的展示分层；UNKNOWN 时返回空表示需要继续回退
     */
    private fun layerFromStereotype(stereotype: JvmStereotype): ArchitectureDisplayLayer? =
        when (stereotype) {
            JvmStereotype.CONTROLLER -> ArchitectureDisplayLayer.ENTRY
            JvmStereotype.SERVICE,
            JvmStereotype.COMPONENT,
            -> ArchitectureDisplayLayer.APPLICATION
            JvmStereotype.REPOSITORY,
            JvmStereotype.CONFIGURATION,
            -> ArchitectureDisplayLayer.DATA
            JvmStereotype.RESOURCE -> ArchitectureDisplayLayer.RESOURCE
            JvmStereotype.UNKNOWN -> null
        }

    /**
     * 根据索引节点角色名称推断分层。
     *
     * @param raw 索引角色名称字符串
     * @return 对应的展示分层；未知角色返回空
     */
    private fun layerFromIndexedRole(raw: String): ArchitectureDisplayLayer? =
        when (raw) {
            IndexedGraphNodeRole.ENTRY.name,
            IndexedGraphNodeRole.API.name,
            -> ArchitectureDisplayLayer.ENTRY
            IndexedGraphNodeRole.SERVICE.name -> ArchitectureDisplayLayer.APPLICATION
            IndexedGraphNodeRole.DATA.name,
            IndexedGraphNodeRole.CONFIG.name,
            -> ArchitectureDisplayLayer.DATA
            IndexedGraphNodeRole.RESOURCE.name -> ArchitectureDisplayLayer.RESOURCE
            IndexedGraphNodeRole.EXTERNAL.name -> ArchitectureDisplayLayer.EXTERNAL
            else -> null
        }

    /**
     * 根据索引层类名称推断分层。
     *
     * @param raw 索引层类名称字符串
     * @return 对应的展示分层；其它内部层类返回空
     */
    private fun layerFromIndexedLayer(raw: String): ArchitectureDisplayLayer? =
        when (raw) {
            IndexedGraphLayerKind.RESOURCE.name -> ArchitectureDisplayLayer.RESOURCE
            IndexedGraphLayerKind.EXTERNAL_LIBRARY.name,
            IndexedGraphLayerKind.JDK.name,
            -> ArchitectureDisplayLayer.EXTERNAL
            else -> null
        }

    /**
     * 通过全限定名的命名约定推断分层。
     *
     * 按包路径或名称片段中是否包含约定关键字（如 controller、service、dao、domain 等）
     * 进行启发式判定。
     *
     * @param raw 节点的全限定名或可读文本
     * @return 命中关键字时返回对应分层，否则返回空
     */
    private fun layerFromText(raw: String): ArchitectureDisplayLayer? {
        val normalized = raw.lowercase()
        return when {
            containsSegment(normalized, "controller") ||
                containsSegment(normalized, "web") ||
                containsSegment(normalized, "api") -> ArchitectureDisplayLayer.ENTRY
            containsSegment(normalized, "service") -> ArchitectureDisplayLayer.APPLICATION
            containsSegment(normalized, "domain") ||
                containsSegment(normalized, "model") -> ArchitectureDisplayLayer.DOMAIN
            containsSegment(normalized, "repository") ||
                containsSegment(normalized, "dao") ||
                containsSegment(normalized, "mapper") ||
                containsSegment(normalized, "config") ||
                containsSegment(normalized, "infra") ||
                containsSegment(normalized, "infrastructure") -> ArchitectureDisplayLayer.DATA
            else -> null
        }
    }

    /**
     * 把泳道 ID 反向映射到展示分层枚举。
     *
     * @param laneId 节点已有的泳道标识
     * @return 对应的展示分层；未匹配时返回空
     */
    private fun layerFromLaneId(laneId: String): ArchitectureDisplayLayer? =
        ArchitectureDisplayLayer.entries.firstOrNull { layer -> layer.laneId == laneId }

    /**
     * 从一组分层中选出出现次数最多的"主流"分层。
     *
     * 当次数相同时，按 [ArchitectureDisplayLayer.order] 更小者优先，
     * 保证聚合节点能够映射到职责上更靠前的分层。
     */
    private fun List<ArchitectureDisplayLayer>.dominantLayer(): ArchitectureDisplayLayer? =
        groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<ArchitectureDisplayLayer, Int>> { it.value }.thenBy { it.key.order })
            .firstOrNull()
            ?.key

    /**
     * 判定归一化后的字符串是否以包路径段的形式包含某个关键字。
     *
     * 兼容完全相等、以 `segment.` 开头、以 `.segment` 结尾、中间包含 `.segment.`，
     * 以及以该关键字结尾的多种命名形式。
     *
     * @param text 已归一化（小写）的待判定字符串
     * @param segment 期望匹配的路径段关键字
     * @return 是否命中
     */
    private fun containsSegment(
        text: String,
        segment: String,
    ): Boolean =
        text == segment ||
            text.startsWith("$segment.") ||
            text.endsWith(".$segment") ||
            ".$segment." in text ||
            text.endsWith(segment)
}
