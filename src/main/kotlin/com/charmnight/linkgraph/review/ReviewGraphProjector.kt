package com.charmnight.linkgraph.review

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
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.review.git.GitChangedFile

class ReviewGraphProjector(
    private val windowProjector: GraphWindowProjector = GraphWindowProjector(),
) {
    fun project(
        bundle: ReviewEvidenceBundle,
        index: ArchitectureGraphIndex? = null,
        request: IndexedGraphRequest = requestReviewGraphRequest(),
        cacheState: String = "UNKNOWN",
        freshness: IndexedGraphFreshness = IndexedGraphFreshness(),
    ): ReviewGraphViewDocument {
        val nodes = linkedMapOf<String, GraphNode>()
        val edges = linkedMapOf<String, GraphEdge>()
        val changedById = bundle.changedSymbols.associateBy(ChangedSymbol::symbolId)
        val changedHunks = reviewChangedHunks(bundle)

        bundle.blastRadius.changedSymbols.forEach { changed ->
            nodes[changed.symbolId] = changed.toGraphNode(request)
        }
        fun addSymbolNode(symbol: JvmSymbol, role: String) {
            nodes.putIfAbsent(symbol.id, symbol.toGraphNode(role, changedById[symbol.id], request))
        }
        bundle.blastRadius.upstream.forEach { addSymbolNode(it, "UPSTREAM") }
        bundle.blastRadius.downstream.forEach { addSymbolNode(it, "DOWNSTREAM") }
        bundle.blastRadius.relatedTests.forEach { addSymbolNode(it, "RELATED_TEST") }

        val relationEdges = (bundle.blastRadius.spiProviders +
            bundle.blastRadius.reflectionTargets +
            bundle.blastRadius.serviceLoaderLoads +
            bundle.blastRadius.proxyTargets +
            bundle.blastRadius.testRelations)
            .distinctBy(JvmRelation::id)
            .mapNotNull { relation -> relation.toGraphEdge(nodes) }
        relationEdges.forEach { edge -> edges.putIfAbsent(edge.id, edge) }

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
        val anchorNodeId = bundle.blastRadius.changedSymbols.firstOrNull { symbol ->
            visibleGraph.nodes.any { node -> node.id == symbol.symbolId }
        }?.symbolId ?: visibleGraph.nodes.firstOrNull()?.id
        return ReviewGraphViewDocument(
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

    private fun reviewHunkKey(hunk: ReviewGraphChangedHunk): String =
        listOf(hunk.filePath, hunk.oldFilePath.orEmpty(), hunk.newFilePath.orEmpty(), hunk.header, hunk.oldStartLine, hunk.newStartLine)
            .joinToString("|")

    private fun hunkKey(hunk: ChangedHunk): String =
        listOf(hunk.filePath, hunk.oldFilePath.orEmpty(), hunk.newFilePath.orEmpty(), hunk.header, hunk.oldStartLine, hunk.newStartLine)
            .joinToString("|")

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
                filePath?.let { put("source.filePath", it) }
                startLine?.let { put("source.startLine", it.toString()) }
                endLine?.let { put("source.endLine", it.toString()) }
                hunk?.header?.let { put("review.hunkHeader", it) }
                putAll(reviewChangedSymbolIndexedMetadata(request))
            },
        )

    private fun ChangedSymbol.displayTitle(): String {
        if (qualifiedName.contains('/') || qualifiedName.contains('\\')) {
            return qualifiedName.replace('\\', '/').substringAfterLast('/')
        }
        return qualifiedName.substringAfterLast('.')
    }

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
                put("source.origin", origin.name)
                source?.displayPath?.let { put("source.filePath", it) }
                source?.virtualFileUrl?.let { put("source.virtualFileUrl", it) }
                source?.startLine?.let { put("source.startLine", it.toString()) }
                source?.endLine?.let { put("source.endLine", it.toString()) }
                put("source.decompiled", (source?.decompiled ?: false).toString())
                putAll(indexedNodeMetadata(role = changed?.let { "CHANGED" } ?: role, request = request))
            },
        )

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

    private fun JvmRelationConfidence.indexedConfidence(): String =
        when (this) {
            JvmRelationConfidence.PROVEN -> "STATIC"
            JvmRelationConfidence.RULE_INFERRED -> "RULE_INFERRED"
            JvmRelationConfidence.RUNTIME_REQUIRED -> "RUNTIME_REQUIRED"
            JvmRelationConfidence.AMBIGUOUS -> "AMBIGUOUS"
        }

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

    private fun JvmRelationConfidence.toCertainty(): Certainty =
        when (this) {
            JvmRelationConfidence.PROVEN -> Certainty.PROVEN
            JvmRelationConfidence.RULE_INFERRED,
            JvmRelationConfidence.AMBIGUOUS,
            JvmRelationConfidence.RUNTIME_REQUIRED,
            -> Certainty.RULE_INFERRED
        }

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

    private fun reviewNodePriority(node: GraphNode): Int =
        when (node.metadata["review.role"]) {
            "CHANGED" -> 0
            "RELATED_TEST" -> 1
            "UPSTREAM" -> 2
            "DOWNSTREAM" -> 3
            else -> 4
        }

    private fun reviewEdgePriority(edge: GraphEdge): Int =
        when (edge.metadata["review.edgeRole"]) {
            "RELATED_TEST" -> 0
            "RELATION" -> 1
            "UPSTREAM" -> 2
            "DOWNSTREAM" -> 3
            else -> 4
        }

    private companion object {
        const val MAX_VISIBLE_NODES = 240
        const val MAX_VISIBLE_EDGES = 360
    }
}
