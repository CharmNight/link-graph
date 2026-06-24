package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.application.indexed.IndexedGraphFreshness
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.indexed.IndexedGraphLayerKind
import com.charmnight.linkgraph.application.indexed.IndexedGraphNodeRole
import com.charmnight.linkgraph.application.indexed.IndexedGraphRelationLayer
import com.charmnight.linkgraph.application.indexed.IndexedGraphSourceKind
import com.charmnight.linkgraph.application.indexed.indexedLayerKind
import com.charmnight.linkgraph.application.indexed.indexedNodeRole
import com.charmnight.linkgraph.application.indexed.indexedSourceKind
import com.charmnight.linkgraph.application.indexed.relationLayerTo
import com.charmnight.linkgraph.application.indexed.requestReviewGraphRequest
import com.charmnight.linkgraph.application.indexed.reviewSelectedDiffItemIds
import com.charmnight.linkgraph.application.indexed.scopeKind
import com.charmnight.linkgraph.application.indexed.toSummary
import com.charmnight.linkgraph.application.model.GraphEditCommandKind
import com.charmnight.linkgraph.application.model.GraphProjectionEdgeMapping
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.application.model.GraphProjectionMappingKind
import com.charmnight.linkgraph.application.model.GraphProjectionNodeMapping
import com.charmnight.linkgraph.projection.GraphProjectionMetadata
import com.charmnight.linkgraph.projection.GraphWindowPolicy
import com.charmnight.linkgraph.projection.GraphWindowProjector
import com.charmnight.linkgraph.projection.GraphWindowRoleQuota
import com.charmnight.linkgraph.projection.graphProjectionHiddenCounts
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.charmnight.linkgraph.jvm.index.JvmFieldSymbol
import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.index.JvmResourceSymbol
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationConfidence
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceLocation
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.putSourceLocation
import com.charmnight.linkgraph.review.ChangedHunk
import com.charmnight.linkgraph.review.ChangedSymbol
import com.charmnight.linkgraph.review.ReviewEvidenceBundle
import com.charmnight.linkgraph.review.ReviewGraphChangedFile
import com.charmnight.linkgraph.review.ReviewGraphChangedHunk
import com.charmnight.linkgraph.review.ReviewGraphChangedSymbolDetail
import com.charmnight.linkgraph.review.ReviewGraphEvidenceSnippet
import com.charmnight.linkgraph.review.ReviewGraphRelatedTestDetail
import com.charmnight.linkgraph.review.ReviewGraphSummary
import com.charmnight.linkgraph.review.ReviewGraphResult
import com.charmnight.linkgraph.review.git.GitChangedFile

/**
 * 代码评审图投影器。
 *
 * 把评审证据包（变更符号 + 影响半径 + 关联测试 + 关系集合）投影为
 * 一个以"变更符号"为中心的评审图，包含：
 * - 变更符号节点（CHANGED）；
 * - 上游、下游影响节点（UPSTREAM / DOWNSTREAM）；
 * - 相关测试节点（RELATED_TEST）；
 * - 关系边（运行时调用/SPI/反射/代理/测试关系）；
 * - 上游/下游/测试影响边；
 * - 变更文件、变更代码块、未匹配代码块、相关测试详情、证据片段等。
 *
 * 同时通过窗口投影器裁剪规模，输出完整的 [ReviewGraphResult]。
 *
 * @param windowProjector 窗口投影器，用于裁剪可见规模
 */
class ReviewGraphProjector(
    /**
     * 窗口投影器，负责按配额与优先级裁剪评审图，把规模控制在可视范围内。
     */
    private val windowProjector: GraphWindowProjector = GraphWindowProjector(),
) : GraphProjector {
    /**
     * 把评审证据包投影为评审图结果。
     *
     * @param bundle 评审证据包，包含变更符号与影响半径
     * @param index 可选架构索引，用于生成索引摘要；为空时摘要中不携带索引信息
     * @param request 索引请求，控制可见规模、配额与选中 diff 项
     * @param cacheState 当前缓存状态描述
     * @param freshness 索引新鲜度信息
     * @return 评审图投影结果
     */
    fun project(
        bundle: ReviewEvidenceBundle,
        index: ArchitectureGraphIndex? = null,
        request: IndexedGraphRequest = requestReviewGraphRequest(),
        cacheState: String = "UNKNOWN",
        freshness: IndexedGraphFreshness = IndexedGraphFreshness(),
    ): ReviewGraphResult {
        // 节点集合：保持插入顺序，键为节点 ID。
        val nodes = linkedMapOf<String, GraphNode>()
        // 边集合：保持插入顺序，键为边 ID。
        val edges = linkedMapOf<String, GraphEdge>()
        // 变更符号按 ID 索引，便于后续为影响半径节点附加变更身份。
        val changedById = bundle.changedSymbols.associateBy(ChangedSymbol::symbolId)
        // 提前计算代码变更块（含匹配上的符号），用于结果中暴露未匹配块。
        val changedHunks = reviewChangedHunks(bundle)

        // 先把所有变更符号加入图。
        bundle.blastRadius.changedSymbols.forEach { changed ->
            nodes[changed.symbolId] = changed.toGraphNode(request)
        }
        /**
         * 把一个 JVM 符号作为影响半径节点加入图。
         * 如果该符号同时也是变更符号，则使用变更身份覆盖默认角色。
         */
        fun addSymbolNode(symbol: JvmSymbol, role: String) {
            nodes.putIfAbsent(symbol.id, symbol.toGraphNode(role, changedById[symbol.id], request))
        }
        bundle.blastRadius.upstream.forEach { addSymbolNode(it, "UPSTREAM") }
        bundle.blastRadius.downstream.forEach { addSymbolNode(it, "DOWNSTREAM") }
        bundle.blastRadius.relatedTests.forEach { addSymbolNode(it, "RELATED_TEST") }

        // 把影响半径中收集到的多种关系去重后转为图边。
        val relationEdges = (bundle.blastRadius.spiProviders +
            bundle.blastRadius.reflectionTargets +
            bundle.blastRadius.serviceLoaderLoads +
            bundle.blastRadius.proxyTargets +
            bundle.blastRadius.testRelations)
            .distinctBy(JvmRelation::id)
            .mapNotNull { relation -> relation.toGraphEdge(nodes) }
        relationEdges.forEach { edge -> edges.putIfAbsent(edge.id, edge) }

        // 为每个变更符号构造"上游影响"边。
        bundle.blastRadius.changedSymbols.forEach { changed ->
            bundle.blastRadius.upstreamByChangedSymbolId[changed.symbolId].orEmpty().forEach { symbol ->
                edges.putIfAbsent(
                    "review:upstream:${symbol.id}->${changed.symbolId}",
                    reviewEdge(
                        id = "review:upstream:${symbol.id}->${changed.symbolId}",
                        from = symbol.id,
                        to = changed.symbolId,
                        label = "上游影响",
                        role = "UPSTREAM",
                        nodes = nodes,
                    ),
                )
            }
        }
        // 为每个变更符号构造"下游影响"边。
        bundle.blastRadius.changedSymbols.forEach { changed ->
            bundle.blastRadius.downstreamByChangedSymbolId[changed.symbolId].orEmpty().forEach { symbol ->
                edges.putIfAbsent(
                    "review:downstream:${changed.symbolId}->${symbol.id}",
                    reviewEdge(
                        id = "review:downstream:${changed.symbolId}->${symbol.id}",
                        from = changed.symbolId,
                        to = symbol.id,
                        label = "下游影响",
                        role = "DOWNSTREAM",
                        nodes = nodes,
                    ),
                )
            }
        }
        // 为每个变更符号构造"相关测试"边，携带原因元数据。
        bundle.blastRadius.changedSymbols.forEach { changed ->
            val reasons = bundle.blastRadius.relatedTestReasonsByChangedSymbolId[changed.symbolId].orEmpty()
            bundle.blastRadius.relatedTestsByChangedSymbolId[changed.symbolId].orEmpty().forEach { symbol ->
                val reason = reasons[symbol.id] ?: bundle.blastRadius.relatedTestReasons[symbol.id] ?: "CALL_PATH"
                edges.putIfAbsent(
                    "review:test:${changed.symbolId}->${symbol.id}",
                    reviewEdge(
                        id = "review:test:${changed.symbolId}->${symbol.id}",
                        from = changed.symbolId,
                        to = symbol.id,
                        label = "相关测试",
                        role = "RELATED_TEST",
                        nodes = nodes,
                        metadata = mapOf("review.relatedTest.reason" to reason),
                    ),
                )
            }
        }

        val graph = GraphDocument(
            nodes = nodes.values.sortedBy(GraphNode::id),
            edges = edges.values
                .filter { edge -> nodes.containsKey(edge.fromNodeId) && nodes.containsKey(edge.toNodeId) }
                .sortedBy(GraphEdge::id),
        )
        val visibleGraph = graph.toReviewVisibleGraph(request)
        val hiddenCounts = graphProjectionHiddenCounts(visibleGraph = visibleGraph, fullGraph = graph)
        val hiddenNodeCount = hiddenCounts.hiddenNodeCount
        val hiddenEdgeCount = hiddenCounts.hiddenEdgeCount
        // 锚点优先选可见图中的第一个变更符号，缺失时回退到首个节点。
        val anchorNodeId = bundle.blastRadius.changedSymbols.firstOrNull { symbol ->
            visibleGraph.nodes.any { node -> node.id == symbol.symbolId }
        }?.symbolId ?: visibleGraph.nodes.firstOrNull()?.id
        return ReviewGraphResult(
            visibleGraph = visibleGraph,
            fullGraph = graph,
            anchorNodeId = anchorNodeId,
            summary = ReviewGraphSummary(
                changedSymbolCount = bundle.blastRadius.changedSymbols.size,
                upstreamCount = bundle.blastRadius.upstream.size,
                downstreamCount = bundle.blastRadius.downstream.size,
                relatedTestCount = bundle.blastRadius.relatedTests.size,
                affectedPackageCount = bundle.blastRadius.affectedPackages.size,
                affectedModuleCount = bundle.blastRadius.affectedModules.size,
                evidenceRefCount = bundle.evidenceRefs.size,
                truncated = hiddenNodeCount > 0 || hiddenEdgeCount > 0,
                hiddenNodeCount = hiddenNodeCount,
                hiddenEdgeCount = hiddenEdgeCount,
                selectedDiffItemIds = request.reviewSelectedDiffItemIds(),
                maxChangedNodes = request.review.maxChangedNodes,
                maxUpstreamNodes = request.review.maxUpstreamNodes,
                maxDownstreamNodes = request.review.maxDownstreamNodes,
                maxRelatedTestNodes = request.review.maxRelatedTestNodes,
                indexed = index?.let { architectureIndex ->
                    request.toSummary(
                        index = architectureIndex,
                        visibleGraph = visibleGraph,
                        fullGraph = graph,
                        anchorNodeId = anchorNodeId,
                        scopedNodeCount = graph.nodes.size,
                        candidateNodeCount = graph.nodes.size,
                        candidateEdgeCount = graph.edges.size,
                        hiddenNodeCount = hiddenNodeCount,
                        hiddenEdgeCount = hiddenEdgeCount,
                        truncated = hiddenNodeCount > 0 || hiddenEdgeCount > 0,
                        cacheState = cacheState,
                        freshness = freshness,
                    )
                },
            ),
            projectionIndex = readonlyProjectionIndex(visibleGraph),
            changedFiles = reviewChangedFiles(bundle),
            changedHunks = changedHunks,
            unmatchedHunks = changedHunks.filter { hunk -> hunk.matchedSymbolIds.isEmpty() },
            baselineOnlySymbols = bundle.changedSymbols
                .filter(ChangedSymbol::baselineOnly)
                .map { symbol -> symbol.toChangedSymbolDetail() },
            relatedTests = bundle.blastRadius.relatedTests.map { symbol ->
                ReviewGraphRelatedTestDetail(
                    symbolId = symbol.id,
                    qualifiedName = symbol.qualifiedName,
                    reason = bundle.blastRadius.relatedTestReasons[symbol.id] ?: "CALL_PATH",
                    filePath = symbol.source?.displayPath,
                    startLine = symbol.source?.startLine,
                )
            },
            affectedPackages = bundle.blastRadius.affectedPackages,
            affectedModules = bundle.blastRadius.affectedModules,
            evidenceSnippets = evidenceSnippets(bundle),
        )
    }

    /**
     * 把证据包中的变更文件清单整理为评审图变更文件列表。
     *
     * 当证据包携带 Git 变更文件信息时直接使用；否则按变更符号路径分组构造。
     */
    private fun reviewChangedFiles(bundle: ReviewEvidenceBundle): List<ReviewGraphChangedFile> {
        if (bundle.gitChangedFiles.isNotEmpty()) {
            return bundle.gitChangedFiles.map { file ->
                ReviewGraphChangedFile(
                    oldPath = file.oldPath,
                    newPath = file.newPath,
                    changeKind = file.changeKind.name,
                    hunkCount = file.hunks.size,
                    similarity = file.similarity,
                )
            }
        }
        return bundle.changedSymbols
            .groupBy { symbol -> symbol.filePath ?: symbol.hunk?.filePath ?: symbol.qualifiedName }
            .map { (path, symbols) ->
                ReviewGraphChangedFile(
                    oldPath = symbols.firstOrNull()?.hunk?.oldFilePath,
                    newPath = symbols.firstOrNull()?.hunk?.newFilePath ?: path,
                    changeKind = symbols.firstOrNull()?.changeKind ?: "MODIFIED",
                    hunkCount = symbols.mapNotNull(ChangedSymbol::hunk).distinctBy(::hunkKey).size,
                )
            }
            .sortedBy { file -> file.newPath ?: file.oldPath.orEmpty() }
    }

    /**
     * 把证据包中的变更代码块整理为评审图变更块列表。
     *
     * - 优先使用 Git 变更文件中的代码块；
     * - 否则使用变更符号携带的代码块信息。
     *
     * 每个代码块都会尝试匹配变更符号，未匹配上的块会带上原因元数据，
     * 最终结果按路径与起始行排序并去重。
     */
    private fun reviewChangedHunks(bundle: ReviewEvidenceBundle): List<ReviewGraphChangedHunk> {
        val rawHunks = if (bundle.gitChangedFiles.isNotEmpty()) {
            bundle.gitChangedFiles.flatMap { file -> file.toReviewHunks() }
        } else {
            bundle.changedSymbols
                .mapNotNull(ChangedSymbol::hunk)
                .distinctBy(::hunkKey)
                .map { hunk -> hunk.toReviewHunk(emptyList()) }
        }
        return rawHunks
            .map { hunk ->
                // 与当前块 key 相同的变更符号列表，视为该块的匹配符号。
                val matchedSymbolIds = bundle.changedSymbols
                    .filter { symbol -> symbol.hunk?.let { symbolHunk -> hunkKey(symbolHunk) == reviewHunkKey(hunk) } == true }
                    .map(ChangedSymbol::symbolId)
                    .distinct()
                hunk.copy(
                    matchedSymbolIds = matchedSymbolIds,
                    reason = if (matchedSymbolIds.isEmpty()) "UNMATCHED_HUNK_NO_SYMBOL_RANGE" else null,
                )
            }
            .distinctBy(::reviewHunkKey)
            .sortedWith(compareBy<ReviewGraphChangedHunk> { it.newFilePath ?: it.oldFilePath ?: it.filePath }.thenBy { it.newStartLine ?: it.oldStartLine ?: 0 })
    }

    /**
     * 把 Git 变更文件中的所有代码块转换为评审图变更块。
     *
     * 若该文件没有任何块（极端情况），构造一个最小占位块用于占位。
     */
    private fun GitChangedFile.toReviewHunks(): List<ReviewGraphChangedHunk> =
        hunks.map { hunk ->
            ReviewGraphChangedHunk(
                filePath = newPath ?: oldPath.orEmpty(),
                oldFilePath = oldPath,
                newFilePath = newPath,
                changeKind = "HUNK_${changeKind.name}",
                header = hunk.header,
                oldStartLine = hunk.oldStart,
                oldLineCount = hunk.oldLineCount,
                newStartLine = hunk.newStart,
                newLineCount = hunk.newLineCount,
            )
        }.ifEmpty {
            listOf(
                ReviewGraphChangedHunk(
                    filePath = newPath ?: oldPath.orEmpty(),
                    oldFilePath = oldPath,
                    newFilePath = newPath,
                    changeKind = "HUNK_${changeKind.name}",
                    header = changeKind.name,
                ),
            )
        }

    /**
     * 把变更块对象转换为评审图变更块，附带匹配上的符号列表。
     */
    private fun ChangedHunk.toReviewHunk(matchedSymbolIds: List<String>): ReviewGraphChangedHunk =
        ReviewGraphChangedHunk(
            filePath = filePath,
            oldFilePath = oldFilePath,
            newFilePath = newFilePath,
            changeKind = changeKind,
            header = header,
            oldStartLine = oldStartLine,
            oldLineCount = oldLineCount,
            newStartLine = newStartLine,
            newLineCount = newLineCount,
            matchedSymbolIds = matchedSymbolIds,
        )

    /**
     * 把变更符号转换为评审图变更符号详情。
     */
    private fun ChangedSymbol.toChangedSymbolDetail(): ReviewGraphChangedSymbolDetail =
        ReviewGraphChangedSymbolDetail(
            symbolId = symbolId,
            qualifiedName = qualifiedName,
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            changeKind = changeKind,
            blastRadiusIncomplete = blastRadiusIncomplete,
            unavailableReason = unavailableReason,
        )

    /**
     * 从证据引用集合中提取前若干条证据片段，用于评审 UI 展示。
     *
     * 同一条证据需要至少包含片段文本或不可用原因，才会被纳入结果。
     */
    private fun evidenceSnippets(bundle: ReviewEvidenceBundle): List<ReviewGraphEvidenceSnippet> =
        bundle.evidenceRefs
            .mapNotNull { ref ->
                val snippet = ref["snippet"] as? String
                val unavailableReason = (ref["snippetUnavailableReason"] ?: ref["unavailableReason"]) as? String
                if (snippet.isNullOrBlank() && unavailableReason.isNullOrBlank()) {
                    return@mapNotNull null
                }
                ReviewGraphEvidenceSnippet(
                    title = (ref["qualifiedName"] ?: ref["kind"] ?: ref["relationId"] ?: ref["symbolId"] ?: "evidence").toString(),
                    kind = (ref["kind"] ?: ref["changeKind"] ?: "SYMBOL").toString(),
                    filePath = ref["filePath"] as? String,
                    startLine = ref["snippetStartLine"] as? Int ?: ref["startLine"] as? Int,
                    endLine = ref["snippetEndLine"] as? Int ?: ref["endLine"] as? Int,
                    snippet = snippet,
                    unavailableReason = unavailableReason,
                )
            }
            .take(30)

    /**
     * 构造评审图变更块的稳定 key，用于去重和匹配。
     */
    private fun reviewHunkKey(hunk: ReviewGraphChangedHunk): String =
        listOf(hunk.filePath, hunk.oldFilePath.orEmpty(), hunk.newFilePath.orEmpty(), hunk.header, hunk.oldStartLine, hunk.newStartLine)
            .joinToString("|")

    /**
     * 构造原始变更块的稳定 key，用于去重。
     */
    private fun hunkKey(hunk: ChangedHunk): String =
        listOf(hunk.filePath, hunk.oldFilePath.orEmpty(), hunk.newFilePath.orEmpty(), hunk.header, hunk.oldStartLine, hunk.newStartLine)
            .joinToString("|")

    /**
     * 把变更符号转换为图节点，携带变更专用的元数据（角色、变更种类、原因等）。
     */
    private fun ChangedSymbol.toGraphNode(request: IndexedGraphRequest): GraphNode =
        GraphNode(
            id = symbolId,
            type = NodeType.CLASS,
            title = displayTitle(),
            location = filePath?.let { path -> startLine?.let { line -> "$path:$line" } ?: path },
            signature = qualifiedName,
            bindingStatus = BindingStatus.BOUND,
            certainty = Certainty.PROVEN,
            metadata = buildMap {
                put("review.role", "CHANGED")
                put("review.changeKind", changeKind)
                put("review.changed.reason", reason)
                put("review.qualifiedName", qualifiedName)
                put("review.baselineOnly", baselineOnly.toString())
                put("review.blastRadiusIncomplete", blastRadiusIncomplete.toString())
                unavailableReason?.let { put("review.unavailableReason", it) }
                putSourceLocation(
                    GraphSourceLocation(
                        filePath = filePath,
                        startLine = startLine,
                        endLine = endLine,
                    ),
                )
                hunk?.header?.let { put("review.hunkHeader", it) }
                putAll(reviewChangedSymbolIndexedMetadata(request))
            },
        )

    /**
     * 生成变更符号的展示标题：路径类符号取文件名，其余取最后一段类名。
     */
    private fun ChangedSymbol.displayTitle(): String {
        if (qualifiedName.contains('/') || qualifiedName.contains('\\')) {
            return qualifiedName.replace('\\', '/').substringAfterLast('/')
        }
        return qualifiedName.substringAfterLast('.')
    }

    /**
     * 把 JVM 符号转换为图节点。
     *
     * 如果该符号同时也是变更符号（[changed] 非空），则其角色会被标记为 CHANGED。
     */
    private fun JvmSymbol.toGraphNode(
        role: String,
        changed: ChangedSymbol?,
        request: IndexedGraphRequest,
    ): GraphNode =
        GraphNode(
            id = id,
            type = nodeType(),
            title = simpleName.ifBlank { qualifiedName.substringAfterLast('.') },
            location = source?.displayPath?.let { path -> source?.startLine?.let { line -> "$path:$line" } ?: path },
            signature = qualifiedName,
            inputs = (this as? JvmMethodSymbol)?.parameterTypes.orEmpty(),
            outputs = (this as? JvmMethodSymbol)?.returnType?.let(::listOf).orEmpty(),
            bindingStatus = BindingStatus.BOUND,
            certainty = Certainty.PROVEN,
            metadata = buildMap {
                put("review.role", changed?.let { "CHANGED" } ?: role)
                put("review.qualifiedName", qualifiedName)
                putSourceLocation(
                    GraphSourceLocation(
                        filePath = source?.displayPath,
                        virtualFileUrl = source?.virtualFileUrl,
                        startLine = source?.startLine,
                        endLine = source?.endLine,
                        origin = origin.name,
                        decompiled = source?.decompiled ?: false,
                    ),
                )
                putAll(indexedNodeMetadata(role = changed?.let { "CHANGED" } ?: role, request = request))
            },
        )

    /**
     * 把 JVM 符号种类映射到通用节点类型（类/接口/枚举/方法/资源等）。
     */
    private fun JvmSymbol.nodeType(): NodeType =
        when (this) {
            is JvmClassSymbol -> when (kind) {
                com.charmnight.linkgraph.jvm.index.JvmClassKind.INTERFACE -> NodeType.INTERFACE
                com.charmnight.linkgraph.jvm.index.JvmClassKind.ENUM -> NodeType.ENUM
                com.charmnight.linkgraph.jvm.index.JvmClassKind.ANNOTATION -> NodeType.ANNOTATION
                com.charmnight.linkgraph.jvm.index.JvmClassKind.RECORD -> NodeType.RECORD
                com.charmnight.linkgraph.jvm.index.JvmClassKind.OBJECT -> NodeType.OBJECT
                com.charmnight.linkgraph.jvm.index.JvmClassKind.CLASS -> NodeType.CLASS
            }
            is JvmMethodSymbol -> NodeType.METHOD
            is JvmFieldSymbol -> NodeType.CONFIG_ITEM
            is JvmResourceSymbol -> when (kind) {
                com.charmnight.linkgraph.jvm.index.JvmResourceKind.MQ_TOPIC -> NodeType.MQ_TOPIC
                com.charmnight.linkgraph.jvm.index.JvmResourceKind.SPI_SERVICE_FILE -> NodeType.RESOURCE
                else -> NodeType.RESOURCE
            }
            else -> NodeType.RESOURCE
        }

    /**
     * 把 JVM 关系转换为评审图边。
     *
     * 当关系的两端节点不都在节点集合中时返回空，避免出现悬空边。
     */
    private fun JvmRelation.toGraphEdge(nodes: Map<String, GraphNode>): GraphEdge? {
        if (fromSymbolId !in nodes || toSymbolId !in nodes) {
            return null
        }
        return GraphEdge(
            id = id,
            type = kind.toReviewEdgeType(),
            fromNodeId = fromSymbolId,
            toNodeId = toSymbolId,
            label = kind.name,
            certainty = confidence.toCertainty(),
            bindingStatus = BindingStatus.BOUND,
            metadata = metadata + mapOf(
                "review.edgeRole" to "RELATION",
                "jvm.relation.kind" to kind.name,
                "jvm.relation.confidence" to confidence.name,
                "jvm.relation.source" to source.name,
            ) + indexedEdgeMetadata(nodes[fromSymbolId], nodes[toSymbolId]),
        )
    }

    /**
     * 构造一条评审图专用的影响边（上游/下游/相关测试等）。
     *
     * 这些边默认按规则推断的确定性、单条来源计数。
     */
    private fun reviewEdge(
        id: String,
        from: String,
        to: String,
        label: String,
        role: String,
        nodes: Map<String, GraphNode>,
        metadata: Map<String, String> = emptyMap(),
    ): GraphEdge =
        GraphEdge(
            id = id,
            type = EdgeType.USES_TYPE,
            fromNodeId = from,
            toNodeId = to,
            label = label,
            certainty = Certainty.RULE_INFERRED,
            bindingStatus = BindingStatus.BOUND,
            metadata = mapOf(
                "review.edgeRole" to role,
                "indexed.relationKind" to role,
                "indexed.relationLayer" to reviewRelationLayer(nodes[from], nodes[to]).name,
                "indexed.sourceCount" to "1",
                "indexed.sampleCount" to "0",
                "indexed.sourceRelationIds" to "",
                "indexed.aggregate" to "false",
                "indexed.confidence" to "RULE_INFERRED",
            ) + metadata,
        )

    /**
     * 变更符号专用的索引元数据：固定为项目源、未知角色、不可展开。
     */
    private fun ChangedSymbol.reviewChangedSymbolIndexedMetadata(request: IndexedGraphRequest): Map<String, String> =
        mapOf(
            "indexed.layerKind" to IndexedGraphLayerKind.PROJECT_SOURCE.name,
            "indexed.nodeRole" to IndexedGraphNodeRole.UNKNOWN.name,
            "indexed.scopeKind" to request.scopeKind(),
            "indexed.sourceKind" to IndexedGraphSourceKind.SOURCE_CLASS.name,
            "indexed.memberClassCount" to "1",
            "indexed.memberResourceCount" to "0",
            GraphProjectionMetadata.Indexed.COLLAPSED_COUNT to "0",
            "indexed.expandable" to "false",
        )

    /**
     * JVM 符号的索引元数据：根据角色、层类、源种类推导；
     * 关联测试角色的节点会被强制标记为 TEST 角色。
     */
    private fun JvmSymbol.indexedNodeMetadata(
        role: String,
        request: IndexedGraphRequest,
    ): Map<String, String> {
        val layerKind = indexedLayerKind()
        val sourceKind = indexedSourceKind()
        val nodeRole = when (role) {
            "RELATED_TEST" -> IndexedGraphNodeRole.TEST
            else -> indexedNodeRole()
        }
        return mapOf(
            "indexed.layerKind" to layerKind.name,
            "indexed.nodeRole" to nodeRole.name,
            "indexed.scopeKind" to request.scopeKind(),
            "indexed.sourceKind" to sourceKind.name,
            "indexed.memberClassCount" to if (this is JvmClassSymbol) "1" else "0",
            "indexed.memberResourceCount" to if (this is JvmResourceSymbol) "1" else "0",
            GraphProjectionMetadata.Indexed.COLLAPSED_COUNT to "0",
            "indexed.expandable" to "false",
        )
    }

    /**
     * JVM 关系的索引元数据：关系种类、关系层级、来源计数、样本数等。
     */
    private fun JvmRelation.indexedEdgeMetadata(
        fromNode: GraphNode?,
        toNode: GraphNode?,
    ): Map<String, String> =
        mapOf(
            "indexed.relationKind" to kind.name,
            "indexed.relationLayer" to reviewRelationLayer(fromNode, toNode).name,
            "indexed.sourceCount" to count.toString(),
            "indexed.sampleCount" to samples.size.toString(),
            "indexed.sourceRelationIds" to id,
            "indexed.aggregate" to (count > 1).toString(),
            "indexed.confidence" to confidence.indexedConfidence(),
        )

    /**
     * 根据两端节点的层类推导关系层级（项目内部、项目到外部等）。
     */
    private fun reviewRelationLayer(
        fromNode: GraphNode?,
        toNode: GraphNode?,
    ): IndexedGraphRelationLayer {
        val fromLayer = fromNode?.metadata?.get("indexed.layerKind")
            ?.let { raw -> IndexedGraphLayerKind.entries.firstOrNull { it.name == raw } }
            ?: IndexedGraphLayerKind.PROJECT_SOURCE
        val toLayer = toNode?.metadata?.get("indexed.layerKind")
            ?.let { raw -> IndexedGraphLayerKind.entries.firstOrNull { it.name == raw } }
            ?: IndexedGraphLayerKind.PROJECT_SOURCE
        return fromLayer.relationLayerTo(toLayer)
    }

    /**
     * 把 JVM 关系置信度映射到索引摘要中使用的字符串。
     */
    private fun JvmRelationConfidence.indexedConfidence(): String =
        when (this) {
            JvmRelationConfidence.PROVEN -> "STATIC"
            JvmRelationConfidence.RULE_INFERRED -> "RULE_INFERRED"
            JvmRelationConfidence.RUNTIME_REQUIRED -> "RUNTIME_REQUIRED"
            JvmRelationConfidence.AMBIGUOUS -> "AMBIGUOUS"
        }

    /**
     * 把 JVM 关系种类映射到评审图通用边类型。
     */
    private fun JvmRelationKind.toReviewEdgeType(): EdgeType =
        when (this) {
            JvmRelationKind.CALLS -> EdgeType.CALL
            JvmRelationKind.TESTS -> EdgeType.TESTS
            JvmRelationKind.INJECTS -> EdgeType.INJECT
            JvmRelationKind.SPI_PROVIDES,
            JvmRelationKind.SERVICE_LOADER_LOADS,
            -> EdgeType.SPI_RESOLVES_TO
            JvmRelationKind.REFLECTS_TO -> EdgeType.REFLECTS_TO
            JvmRelationKind.USES_PROXY,
            JvmRelationKind.DUBBO_REFERENCES,
            JvmRelationKind.FEIGN_CLIENT_CALLS,
            -> EdgeType.USES_PROXY
            JvmRelationKind.FEIGN_ROUTES_TO -> EdgeType.ROUTES_TO
            JvmRelationKind.MQ_PUBLISHES,
            JvmRelationKind.SPRING_EVENT_PUBLISHES,
            JvmRelationKind.SPRING_EVENT_LISTENS,
            -> EdgeType.PUBLISHES_TO
            JvmRelationKind.MQ_CONSUMES -> EdgeType.CONSUMES_FROM
            else -> EdgeType.USES_TYPE
        }

    /**
     * 把 JVM 关系置信度映射到通用确定性枚举。
     */
    private fun JvmRelationConfidence.toCertainty(): Certainty =
        when (this) {
            JvmRelationConfidence.PROVEN -> Certainty.PROVEN
            JvmRelationConfidence.RULE_INFERRED,
            JvmRelationConfidence.AMBIGUOUS,
            JvmRelationConfidence.RUNTIME_REQUIRED,
            -> Certainty.RULE_INFERRED
        }

    /**
     * 为评审图生成只读投影索引，每个节点和边都映射到自身。
     */
    private fun readonlyProjectionIndex(graph: GraphDocument): GraphProjectionIndex =
        GraphProjectionIndex(
            nodeMappings = graph.nodes.associate { node ->
                node.id to GraphProjectionNodeMapping(
                    projectedNodeId = node.id,
                    mappingKind = GraphProjectionMappingKind.INDEXED_READONLY,
                    canonicalNodeIds = listOf(node.id),
                    editableCommandKinds = emptySet<GraphEditCommandKind>(),
                )
            },
            edgeMappings = graph.edges.associate { edge ->
                edge.id to GraphProjectionEdgeMapping(
                    projectedEdgeId = edge.id,
                    mappingKind = GraphProjectionMappingKind.INDEXED_READONLY,
                    canonicalEdgeIds = listOf(edge.id),
                    editableCommandKinds = emptySet<GraphEditCommandKind>(),
                )
            },
        )

    /**
     * 把完整评审图按角色配额裁剪为可见评审图。
     *
     * 启用"填充孤立节点"，避免因为关系紧密程度差异而漏掉变更符号；
     * 通过角色配额分别限制变更、上游、下游、相关测试的可见规模。
     */
    private fun GraphDocument.toReviewVisibleGraph(request: IndexedGraphRequest): GraphDocument =
        windowProjector.project(
            graph = this,
            policy = GraphWindowPolicy(
                maxVisibleNodes = request.viewport.maxVisibleNodes ?: MAX_VISIBLE_NODES,
                maxVisibleEdges = request.viewport.maxVisibleEdges ?: MAX_VISIBLE_EDGES,
                enableOverflowSummary = false,
                fillDisconnectedNodes = true,
            ),
            roleMetadataKey = "review.role",
            roleQuotas = listOf(
                GraphWindowRoleQuota("CHANGED", request.review.maxChangedNodes),
                GraphWindowRoleQuota("RELATED_TEST", request.review.maxRelatedTestNodes),
                GraphWindowRoleQuota("UPSTREAM", request.review.maxUpstreamNodes),
                GraphWindowRoleQuota("DOWNSTREAM", request.review.maxDownstreamNodes),
            ),
            nodePriority = ::reviewNodePriority,
            edgePriority = ::reviewEdgePriority,
            overflowOwnerContext = "review-graph",
        ).graph

    /**
     * 评审图节点优先级：变更 > 相关测试 > 上游 > 下游 > 其他。
     */
    private fun reviewNodePriority(node: GraphNode): Int =
        when (node.metadata["review.role"]) {
            "CHANGED" -> 0
            "RELATED_TEST" -> 1
            "UPSTREAM" -> 2
            "DOWNSTREAM" -> 3
            else -> 4
        }

    /**
     * 评审图边优先级：相关测试 > 关系 > 上游 > 下游 > 其他。
     */
    private fun reviewEdgePriority(edge: GraphEdge): Int =
        when (edge.metadata["review.edgeRole"]) {
            "RELATED_TEST" -> 0
            "RELATION" -> 1
            "UPSTREAM" -> 2
            "DOWNSTREAM" -> 3
            else -> 4
        }

    private companion object {
        /** 评审图默认最大可见节点数。 */
        const val MAX_VISIBLE_NODES = 240
        /** 评审图默认最大可见边数。 */
        const val MAX_VISIBLE_EDGES = 360
    }
}
