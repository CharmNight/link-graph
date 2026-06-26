package com.charmnight.linkgraph.architecture.query

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ArchitectureNode
import com.charmnight.linkgraph.architecture.memory.ArchitectureIndexMemorySnapshot
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import java.util.ArrayDeque

/**
 * 架构图查询门户：将底层索引（符号、关系、模块层级）封装成一组面向上层（图谱视图、AI 助手、诊断面板）的查询接口，
 * 同时承担"符号定位、上下游遍历、最短路径计算、影响范围评估、索引摘要"等查询语义的统一编排。
 */
class ArchitectureGraphQueryService(
    /** 底层架构图索引，提供符号、关系、节点层级数据 */
    private val index: ArchitectureGraphIndex,
    /** 索引内存快照，记录缓存命中率和过期分片，用于摘要与诊断 */
    private val memorySnapshot: ArchitectureIndexMemorySnapshot = ArchitectureIndexMemorySnapshot(),
) {
    /** 按全限定名查找类符号，未命中返回 null */
    fun lookupClass(qualifiedName: String): JvmClassSymbol? =
        index.findClass(qualifiedName.trim())

    /** 返回某个节点的直接上游（依赖当前节点的架构节点） */
    fun upstreamOneHop(nodeId: String): List<ArchitectureNode> =
        index.upstreamOneHop(nodeId)

    /** 返回某个节点的直接下游（当前节点所依赖的架构节点） */
    fun downstreamOneHop(nodeId: String): List<ArchitectureNode> =
        index.downstreamOneHop(nodeId)

    /** 列出归属在某个架构节点作用域下的所有类符号 */
    fun classesInScope(scopeNodeId: String): List<JvmClassSymbol> =
        index.classesInScope(scopeNodeId)

    /**
     * 按用户输入查询符号：先按 ID/全限定名/方法签名/字段名做精确匹配，
     * 全部未命中再退化为模糊搜索，结果合并返回。
     */
    fun findSymbol(query: String): List<JvmSymbol> {
        // 规范化输入，空串直接返回空结果
        val normalized = query.trim()
        if (normalized.isBlank()) {
            return emptyList()
        }
        // 依次尝试按 ID、类全限定名、方法签名、字段名精确匹配
        index.findSymbol(normalized)?.let { return listOf(it) }
        index.findClass(normalized)?.let { return listOf(it) }
        index.findMethod(normalized)?.let { return listOf(it) }
        index.findField(normalized)?.let { return listOf(it) }
        // 全部未命中则使用带评分的模糊搜索
        return rankedFindSymbol(normalized).map(ArchitectureSymbolSearchResult::symbol)
    }

    /**
     * 带评分的符号检索：精确匹配给最高分，否则退化到倒排搜索并对结果按相关性打分排序，
     * 主要用于"问题→候选符号集合"的召回阶段。
     */
    fun rankedFindSymbol(query: String, limit: Int = 50): List<ArchitectureSymbolSearchResult> {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            return emptyList()
        }
        // 不同匹配维度给予不同档位的评分，便于上层做归并与排序
        index.findSymbol(normalized)?.let { return listOf(ArchitectureSymbolSearchResult(it, 1000, "ID_EXACT")) }
        index.findClass(normalized)?.let { return listOf(ArchitectureSymbolSearchResult(it, 950, "QUALIFIED_NAME_EXACT")) }
        index.findMethod(normalized)?.let { return listOf(ArchitectureSymbolSearchResult(it, 950, "QUALIFIED_NAME_EXACT")) }
        index.findField(normalized)?.let { return listOf(ArchitectureSymbolSearchResult(it, 950, "QUALIFIED_NAME_EXACT")) }
        return ArchitectureSymbolSearch(index.symbolIndex).search(normalized, limit)
    }

    /**
     * 查询与某个符号相关的关系集合，可按关系类型、方向（入/出/双向）做过滤，
     * 常用于"展示某个 API 的调用方/被调用方"等场景。
     */
    fun relationsForSymbol(
        symbolIdOrName: String,
        kind: JvmRelationKind? = null,
        direction: RelationDirection = RelationDirection.BOTH,
    ): List<JvmRelation> {
        // 输入可能是符号 ID 或名称，统一解析成符号 ID 集合
        val symbolIds = findSymbol(symbolIdOrName).mapTo(linkedSetOf()) { symbol -> symbol.id }
        if (symbolIds.isEmpty()) {
            return emptyList()
        }
        return symbolIds.flatMap { symbolId ->
            when (direction) {
                RelationDirection.OUTGOING -> index.relationIndex.outgoing(symbolId)
                RelationDirection.INCOMING -> index.relationIndex.incoming(symbolId)
                RelationDirection.BOTH -> index.relationIndex.outgoing(symbolId) + index.relationIndex.incoming(symbolId)
            }
        }
            // 按关系类型过滤并去重，最终按稳定顺序输出
            .filter { relation -> kind == null || relation.kind == kind }
            .distinctBy(JvmRelation::id)
            .sortedWith(compareBy({ it.kind.name }, { it.fromSymbolId }, { it.toSymbolId }))
    }

    /** 查找实现了某个 SPI 接口的全部 Provider，按提供方符号 ID 排序，便于定位扩展点实现 */
    fun serviceProviders(interfaceName: String): List<JvmRelation> {
        val interfaceSymbol = index.findClass(interfaceName.trim()) ?: return emptyList()
        return index.relationIndex.incoming(interfaceSymbol.id)
            .filter { relation -> relation.kind == JvmRelationKind.SPI_PROVIDES }
            .sortedBy(JvmRelation::fromSymbolId)
    }

    /** 列出某个符号通过反射访问的目标关系，便于发现隐式依赖 */
    fun reflectionTargets(symbolIdOrName: String): List<JvmRelation> =
        relationsForSymbol(symbolIdOrName, JvmRelationKind.REFLECTS_TO, RelationDirection.OUTGOING)

    /** 向上游（依赖当前符号的符号）遍历，深度限定在 1~5 层防止爆炸式扩散 */
    fun upstream(symbolIdOrName: String, depth: Int = 1): List<JvmSymbol> =
        traverse(symbolIdOrName, depth.coerceIn(1, 5), incoming = true)

    /** 向下游（当前符号依赖的符号）遍历，深度限定在 1~5 层防止爆炸式扩散 */
    fun downstream(symbolIdOrName: String, depth: Int = 1): List<JvmSymbol> =
        traverse(symbolIdOrName, depth.coerceIn(1, 5), incoming = false)

    /** 汇总当前索引的整体规模（模块/包/类/方法/字段/资源/各类关系数量），用于面板展示和健康度检查 */
    fun summary(): ArchitectureIndexSummary =
        ArchitectureIndexSummary(
            moduleCount = index.symbolIndex.modulesByName.size,
            packageCount = index.symbolIndex.packagesByName.size,
            classCount = index.symbolIndex.classesByQualifiedName.size,
            methodCount = index.symbolIndex.methodsBySignature.size,
            fieldCount = index.symbolIndex.fieldsByQualifiedName.size,
            resourceCount = index.symbolIndex.resourcesByPath.size,
            relationCount = index.relationIndex.relations.size,
            spiProviderCount = index.relationIndex.byKind(JvmRelationKind.SPI_PROVIDES).size,
            reflectionRelationCount = index.relationIndex.byKind(JvmRelationKind.REFLECTS_TO).size,
            serviceLoaderRelationCount = index.relationIndex.byKind(JvmRelationKind.SERVICE_LOADER_LOADS).size,
            proxyRelationCount = index.relationIndex.byKind(JvmRelationKind.USES_PROXY).size,
            testRelationCount = index.relationIndex.byKind(JvmRelationKind.TESTS).size,
            externalClassCount = index.symbolIndex.classesByQualifiedName.values.count { symbol -> symbol.external },
        )

    /**
     * 面向"自然语言/关键字问题"的图谱切片：先把问题映射到一组根符号，再按指定方向（上游/下游/邻域）展开，
     * 最终把命中范围内的符号与关系序列化成扁平 Map，供前端/AI 直接消费。
     */
    fun queryProjectGraph(question: String, budget: Int, mode: TraversalMode): ProjectGraphQueryResult {
        // 把问题作为查询词召回根符号集合，budget 同时约束召回数量与最终切片规模
        val roots = rankedFindSymbol(question, budget.coerceIn(1, 50)).map(ArchitectureSymbolSearchResult::symbol)
        val relations = roots
            .flatMap { symbol ->
                when (mode) {
                    TraversalMode.UPSTREAM -> index.relationIndex.incoming(symbol.id)
                    TraversalMode.DOWNSTREAM -> index.relationIndex.outgoing(symbol.id)
                    TraversalMode.NEIGHBORHOOD -> index.relationIndex.incoming(symbol.id) + index.relationIndex.outgoing(symbol.id)
                }
            }
            .distinctBy(JvmRelation::id)
            .take(budget.coerceAtLeast(1))
        // 收集关系涉及到的对端符号，与根符号合并后输出
        val relatedIds = relations.flatMap { relation -> listOf(relation.fromSymbolId, relation.toSymbolId) }.toSet()
        val symbols = (roots + relatedIds.mapNotNull(index::findSymbol))
            .distinctBy(JvmSymbol::id)
            .take(budget.coerceAtLeast(1))
            .map(::symbolPayload)
        return ProjectGraphQueryResult(symbols = symbols, relations = relations.map(::relationPayload))
    }

    /**
     * 使用广度优先搜索在符号关系图上寻找两个符号之间的最短路径，将符号与边依次返回。
     * 用于回答"X 是怎么被 Y 使用的"这类追溯性问题。
     */
    fun shortestPath(from: String, to: String, maxDepth: Int = 6): ProjectGraphPath? {
        // 解析起点/终点的符号 ID，无法定位时直接放弃
        val fromId = findSymbol(from).firstOrNull()?.id ?: return null
        val toId = findSymbol(to).firstOrNull()?.id ?: return null
        if (fromId == toId) {
            return ProjectGraphPath(symbolIds = listOf(fromId), relationIds = emptyList())
        }
        // BFS 队列，每个元素是一条候选路径
        val queue = ArrayDeque<ProjectGraphPath>()
        queue += ProjectGraphPath(symbolIds = listOf(fromId), relationIds = emptyList())
        // visited 集合避免环路带来的重复展开
        val visited = linkedSetOf(fromId)
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            // 超过深度上限则停止扩展
            if (path.symbolIds.size > maxDepth.coerceAtLeast(1) + 1) {
                continue
            }
            val last = path.symbolIds.last()
            // 当前末端的全部边（入+出）作为可走方向
            val edges = (index.relationIndex.outgoing(last) + index.relationIndex.incoming(last)).distinctBy(JvmRelation::id)
            edges.forEach { relation ->
                val next = if (relation.fromSymbolId == last) relation.toSymbolId else relation.fromSymbolId
                if (!visited.add(next)) {
                    return@forEach
                }
                val nextPath = ProjectGraphPath(path.symbolIds + next, path.relationIds + relation.id)
                // 命中终点立即返回，BFS 保证是最短路径
                if (next == toId) {
                    return nextPath
                }
                queue += nextPath
            }
        }
        return null
    }

    /** 解释某个符号：返回该符号本身以及入向/出向关系，便于查看其在架构中的位置 */
    fun explainNode(symbolOrNodeId: String): ProjectNodeExplanation? {
        val symbol = findSymbol(symbolOrNodeId).firstOrNull() ?: return null
        val incoming = index.relationIndex.incoming(symbol.id).map(::relationPayload)
        val outgoing = index.relationIndex.outgoing(symbol.id).map(::relationPayload)
        return ProjectNodeExplanation(
            symbol = symbolPayload(symbol),
            incomingRelations = incoming,
            outgoingRelations = outgoing,
        )
    }

    /** 计算某个符号的影响范围：同时给出上游依赖方与下游被依赖方，用于变更影响评估 */
    fun affectedNodes(symbolOrFile: String, depth: Int = 2): ProjectAffectedNodes {
        val downstream = downstream(symbolOrFile, depth).map(::symbolPayload)
        val upstream = upstream(symbolOrFile, depth).map(::symbolPayload)
        return ProjectAffectedNodes(upstream = upstream, downstream = downstream)
    }

    /** 生成某个社区/包范围内的索引摘要：核心节点、高度节点、过期分片、缓存命中率以及建议问题 */
    fun communityOrPackageDigest(scope: String): ProjectIndexDigest {
        val normalized = scope.trim()
        // 按命名匹配确定范围内的符号集合
        val scopedSymbols = index.symbolIndex.symbolsById.values
            .filter { symbol -> normalized.isBlank() || symbol.qualifiedName.contains(normalized, ignoreCase = true) }
            .sortedBy(JvmSymbol::qualifiedName)
        // 统计每个符号的度数（入+出关系总数），用于排序高度节点
        val degreeById = index.relationIndex.relations
            .flatMap { relation -> listOf(relation.fromSymbolId, relation.toSymbolId) }
            .groupingBy { it }
            .eachCount()
        return ProjectIndexDigest(
            coreNodes = scopedSymbols.take(20).map(::symbolPayload),
            highDegreeNodes = scopedSymbols.sortedByDescending { symbol -> degreeById[symbol.id] ?: 0 }.take(10).map(::symbolPayload),
            staleSlices = memorySnapshot.staleSliceIds,
            cacheHitRate = memorySnapshot.cacheHitRate,
            suggestedQuestions = listOf("哪些符号依赖 $scope?", "$scope 的入口点是什么?"),
        )
    }

    /**
     * 通用关系图遍历工具：以一组根符号为起点，按方向（入/出）逐层扩展到指定深度，
     * 返回所有可达的非根符号，供 upstream/downstream 等高层接口复用。
     */
    private fun traverse(
        symbolIdOrName: String,
        depth: Int,
        incoming: Boolean,
    ): List<JvmSymbol> {
        val roots = findSymbol(symbolIdOrName).mapTo(linkedSetOf()) { symbol -> symbol.id }
        val visited = roots.toMutableSet()
        val result = linkedSetOf<String>()
        var frontier = roots
        repeat(depth) {
            val next = linkedSetOf<String>()
            frontier.forEach { symbolId ->
                val relations = if (incoming) {
                    index.relationIndex.incoming(symbolId)
                } else {
                    index.relationIndex.outgoing(symbolId)
                }
                relations.forEach { relation ->
                    val candidate = if (incoming) relation.fromSymbolId else relation.toSymbolId
                    if (visited.add(candidate)) {
                        result += candidate
                        next += candidate
                    }
                }
            }
            frontier = next
            if (frontier.isEmpty()) {
                return@repeat
            }
        }
        return result.mapNotNull(index::findSymbol)
    }

    /** 将符号对象转换成 [SymbolPayloadDto]，供跨进程（前端/AI）消费。 */
    private fun symbolPayload(symbol: JvmSymbol): SymbolPayloadDto =
        SymbolPayloadDto(
            id = symbol.id,
            qualifiedName = symbol.qualifiedName,
            simpleName = symbol.simpleName,
            filePath = symbol.source?.displayPath,
            origin = symbol.origin.name,
        )

    /** 将关系对象转换成 [RelationPayloadDto]，与 [symbolPayload] 配套使用。 */
    private fun relationPayload(relation: JvmRelation): RelationPayloadDto =
        RelationPayloadDto(
            id = relation.id,
            kind = relation.kind.name,
            fromSymbolId = relation.fromSymbolId,
            toSymbolId = relation.toSymbolId,
            metadata = relation.metadata,
        )
}

/** 关系查询方向枚举：仅入向、仅出向、双向 */
enum class RelationDirection {
    INCOMING,
    OUTGOING,
    BOTH,
}

/** 遍历模式枚举：邻域、上游、下游，决定图谱切片的展开方向 */
enum class TraversalMode {
    NEIGHBORHOOD,
    UPSTREAM,
    DOWNSTREAM,
}

/** 一次图谱切片查询的结果：包含符号与关系两份数据 */
data class ProjectGraphQueryResult(
    /** 切片内涉及的符号载荷列表 */
    val symbols: List<SymbolPayloadDto>,
    /** 切片内涉及的关系载荷列表 */
    val relations: List<RelationPayloadDto>,
)

/** 符号间的最短路径：以符号 ID 序列与边 ID 序列表达 */
data class ProjectGraphPath(
    /** 路径上的符号 ID 顺序 */
    val symbolIds: List<String>,
    /** 路径上的关系 ID 顺序 */
    val relationIds: List<String>,
)

/** 单个符号的解释结果：符号本身加两端关系，用于"节点详情"面板 */
data class ProjectNodeExplanation(
    /** 被解释的符号载荷 */
    val symbol: SymbolPayloadDto,
    /** 指向该符号的关系载荷列表 */
    val incomingRelations: List<RelationPayloadDto>,
    /** 从该符号出发的关系载荷列表 */
    val outgoingRelations: List<RelationPayloadDto>,
)

/** 影响范围结果：上游与下游符号载荷列表，配合变更分析使用 */
data class ProjectAffectedNodes(
    /** 上游依赖方载荷列表（依赖当前符号的符号） */
    val upstream: List<SymbolPayloadDto>,
    /** 下游被依赖方载荷列表（当前符号依赖的符号） */
    val downstream: List<SymbolPayloadDto>,
)

/** 某个社区/包的索引摘要：核心节点、高度节点、过期信息与建议问题 */
data class ProjectIndexDigest(
    /** 命名/排序优先取出的核心节点载荷 */
    val coreNodes: List<SymbolPayloadDto>,
    /** 按度数（连接数）排序后的高度节点载荷 */
    val highDegreeNodes: List<SymbolPayloadDto>,
    /** 内存快照中标记为过期的分片 ID */
    val staleSlices: List<String>,
    /** 缓存命中率，可能为空 */
    val cacheHitRate: Double?,
    /** 给用户提示的可继续追问的问题 */
    val suggestedQuestions: List<String>,
)

/** 索引整体规模汇总，提供给面板/健康度检查消费 */
data class ArchitectureIndexSummary(
    val moduleCount: Int,
    val packageCount: Int,
    val classCount: Int,
    val methodCount: Int,
    val fieldCount: Int = 0,
    val resourceCount: Int,
    val relationCount: Int,
    val spiProviderCount: Int,
    val reflectionRelationCount: Int,
    val serviceLoaderRelationCount: Int = 0,
    val proxyRelationCount: Int = 0,
    val testRelationCount: Int = 0,
    val externalClassCount: Int,
)
