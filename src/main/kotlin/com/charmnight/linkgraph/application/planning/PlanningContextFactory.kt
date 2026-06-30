package com.charmnight.linkgraph.application.planning

import com.charmnight.linkgraph.application.model.PlanningInput
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.model.currentVisibleGraph
import com.charmnight.linkgraph.application.model.currentWorkingGraph
import com.charmnight.linkgraph.application.model.toAnalysisDisplayMode
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.codegen.ProjectPathNormalizer
import com.charmnight.linkgraph.agent.model.GenerationContext
import com.charmnight.linkgraph.agent.model.GenerationPlan
import com.charmnight.linkgraph.agent.model.GraphBeautificationContext
import com.charmnight.linkgraph.agent.model.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.application.port.GraphGenerationPort
import com.charmnight.linkgraph.agent.model.GraphPresentationContext
import com.charmnight.linkgraph.agent.model.SourceSnippetContext
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.model.sourceLocation
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.StepGranularity
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * 为计划生成、问答、讲解等流程统一构造上下文载荷。
 */
internal class PlanningContextFactory(
    /** 图 diff 比较器。 */
    private val graphDiffer: GraphDiffer,
    /** 同步预览规划器。 */
    private val syncPreviewPlanner: SyncPreviewPlanner,
    /** 图生成服务。 */
    private val graphGenerationService: GraphGenerationPort,
    /** 问答源码证据收集器。 */
    private val qaEvidenceCollector: QaEvidenceCollector = QaEvidenceCollector(),
    /** 当前真正生效的生成设置。 */
    private val settingsProvider: () -> LinkGraphSettingsState,
    /** 当前项目根路径提供器。 */
    private val projectBasePathProvider: () -> String? = { null },
) {
    /**
     * 计算实现计划和代码草稿共用的规划载荷。
     */
    fun computePlanningPayload(
        snapshot: WorkflowEditorSnapshot,
        generationPlanOverride: GenerationPlan? = snapshot.generationPlan,
        userGoal: String = "",
    ): PlanningInput {
        val workingGraph = currentWorkingGraph(snapshot)
        val diffResult = when {
            snapshot.designBaselineGraph != null -> graphDiffer.diff(workingGraph, snapshot.designBaselineGraph)
            else -> null
        }
        val planningGraph = diffResult?.graph ?: workingGraph
        val diff = snapshot.diff ?: diffResult?.diff ?: GraphDiff()
        val previewItems = snapshot.syncPreviewItems.ifEmpty {
            if (planningGraph.nodes.isEmpty()) {
                emptyList()
            } else {
                syncPreviewPlanner.plan(planningGraph, diff)
            }
        }
        return PlanningInput(
            planningGraph = planningGraph,
            diff = diff,
            previewItems = previewItems,
            confirmedChanges = snapshot.draftWorkbenchState.draftChanges,
            mermaidIssues = snapshot.mermaidIssues,
            sourceContext = emptyList(),
            userGoal = userGoal,
        )
    }

    /**
     * 基于规划上下文即时构造计划快照。
     */
    fun buildPlanSnapshot(
        planningGraph: GraphDocument,
        diff: GraphDiff,
        previewItems: List<SyncPreviewItem>,
        mermaidIssues: List<com.charmnight.linkgraph.mermaid.MermaidIssue>,
        confirmedChanges: List<com.charmnight.linkgraph.workbench.DraftWorkbenchEntry>,
        sourceContext: List<SourceSnippetContext>,
        userGoal: String = "",
        onPreview: ((String, Boolean) -> Unit)? = null,
        settingsOverride: LinkGraphSettingsState? = null,
        ) = graphGenerationService.generatePlan(
        context = GenerationContext(
            graph = planningGraph,
            mermaidIssues = mermaidIssues,
            diff = diff,
            syncPreviewItems = previewItems,
            confirmedChanges = confirmedChanges,
            sourceContext = sourceContext,
            userGoal = userGoal,
        ),
        settings = settingsOverride ?: settingsProvider(),
        onPreview = onPreview,
    )

    /**
     * 构造链路讲解所需的展示和源码上下文。
     */
    fun buildGraphBeautificationContext(
        snapshot: WorkflowEditorSnapshot,
        goal: String,
        preferredStyle: String?,
        explanationFocus: String?,
        focusNodeId: String? = null,
        followUp: GraphBeautificationFollowUpContext?,
        granularity: StepGranularity,
        assistantActionId: AssistantActionId? = null,
    ): GraphBeautificationContext {
        val requestedFocusNodeId = focusNodeId?.trim()?.takeIf(String::isNotBlank)
        val graphContext = buildInteractiveGraphContext(
            snapshot = snapshot,
            focusNodeIds = requestedFocusNodeId?.let(::listOf).orEmpty(),
        )
        val anchorNodeId = resolveBeautificationAnchorNodeId(
            snapshot = snapshot,
            visibleGraph = graphContext.presentationGraph,
            requestedFocusNodeId = requestedFocusNodeId,
        )
        val selectedNodeIds = when {
            requestedFocusNodeId != null && graphContext.presentationGraph.nodes.any { node -> node.id == requestedFocusNodeId } ->
                listOf(requestedFocusNodeId)
            else -> snapshot.selectedNodeId?.let(::listOf).orEmpty()
        }
        val (hiddenCurrentMethodNodeCount, hiddenCrossMethodNodeCount) = computeBeautificationHiddenCounts(
            snapshot = snapshot,
            visibleGraph = graphContext.presentationGraph,
            fullGraph = graphContext.fullGraph,
            anchorNodeId = anchorNodeId,
        )
        return GraphBeautificationContext(
            presentationContext = GraphPresentationContext(
                graph = graphContext.presentationGraph,
                fullGraph = graphContext.fullGraph,
                anchorNodeId = anchorNodeId,
                selectedNodeIds = selectedNodeIds,
                hiddenCurrentMethodNodeCount = hiddenCurrentMethodNodeCount,
                hiddenCrossMethodNodeCount = hiddenCrossMethodNodeCount,
            ),
            sourceContext = buildSourceSnippetContexts(
                visibleGraph = graphContext.presentationGraph,
                fullGraph = graphContext.fullGraph,
                anchorNodeId = anchorNodeId,
                selectedNodeIds = selectedNodeIds,
                limit = MAX_BEAUTIFICATION_SOURCE_SNIPPETS,
            ),
            stepSourceContext = buildSourceSnippetContexts(
                visibleGraph = graphContext.presentationGraph,
                fullGraph = graphContext.fullGraph,
                anchorNodeId = anchorNodeId,
                selectedNodeIds = selectedNodeIds,
                limit = null,
            ),
            userGoal = goal,
            preferredStyle = preferredStyle,
            explanationFocus = explanationFocus,
            followUp = followUp,
            granularity = granularity,
            assistantActionId = assistantActionId,
        )
    }

    private fun buildInteractiveGraphContext(
        snapshot: WorkflowEditorSnapshot,
        focusNodeIds: List<String> = emptyList(),
    ): InteractiveGraphContext {
        val workingGraph = currentWorkingGraph(snapshot)
        val currentSceneFullGraph = currentSceneFullGraph(snapshot)
        val fullGraph = if (snapshot.workingGraphDirty) {
            workingGraph
        } else {
            currentSceneFullGraph
                ?: snapshot.semanticFactGraph.takeIf(GraphDocument::hasGraphContent)
                ?: workingGraph
        }
        val normalizedFocusNodeIds = focusNodeIds
            .mapNotNull { nodeId -> nodeId.trim().takeIf(String::isNotBlank) }
            .distinct()
        val visibleGraph = currentVisibleGraph(snapshot)
            .takeIf(GraphDocument::hasGraphContent)
            ?: workingGraph
        val presentationGraph = includeVisibleInvocationExpansions(
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
        ).includeRequestedFocusNodes(
            fullGraph = fullGraph,
            focusNodeIds = normalizedFocusNodeIds,
        )
        return InteractiveGraphContext(
            presentationGraph = presentationGraph,
            fullGraph = fullGraph,
            workingGraph = workingGraph,
        )
    }

    /**
     * 前端流程图会把当前方法的调用展开节点叠加到锚点作用域里；讲解请求没有前端本地作用域，
     * 因此这里用展开批次元数据把同一批节点和边补回 prompt 图。
     */
    private fun includeVisibleInvocationExpansions(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
    ): GraphDocument {
        if (visibleGraph.nodes.isEmpty() || fullGraph.nodes.isEmpty()) {
            return visibleGraph
        }
        val visibleCanonicalNodeIds = visibleGraph.nodes
            .flatMapTo(linkedSetOf()) { node -> listOf(node.id) + projectedAliasNodeIds(node) }
        val expansionIds = linkedSetOf<String>()
        val expandedNodeIds = linkedSetOf<String>()
        fullGraph.nodes.forEach { node ->
            if (isExpansionFromVisibleInvocation(node.metadata, visibleCanonicalNodeIds)) {
                expandedNodeIds += node.id
                node.metadata[INVOCATION_EXPANSION_ID_KEY]
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(expansionIds::add)
            }
        }
        fullGraph.edges.forEach { edge ->
            if (isExpansionFromVisibleInvocation(edge.metadata, visibleCanonicalNodeIds)) {
                expandedNodeIds += edge.fromNodeId
                expandedNodeIds += edge.toNodeId
                edge.metadata[INVOCATION_EXPANSION_ID_KEY]
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(expansionIds::add)
            }
        }
        if (expansionIds.isNotEmpty()) {
            fullGraph.nodes
                .filter { node -> node.metadata[INVOCATION_EXPANSION_ID_KEY]?.trim() in expansionIds }
                .mapTo(expandedNodeIds) { node -> node.id }
            fullGraph.edges
                .filter { edge -> edge.metadata[INVOCATION_EXPANSION_ID_KEY]?.trim() in expansionIds }
                .forEach { edge ->
                    expandedNodeIds += edge.fromNodeId
                    expandedNodeIds += edge.toNodeId
                }
        }
        expandedNodeIds.removeAll(visibleGraph.nodes.mapTo(linkedSetOf()) { it.id })
        if (expandedNodeIds.isEmpty()) {
            return visibleGraph
        }
        val visibleNodeIds = visibleGraph.nodes.mapTo(linkedSetOf()) { it.id }
        val presentationNodeIds = linkedSetOf<String>().apply {
            addAll(visibleNodeIds)
            addAll(expandedNodeIds)
        }
        val visibleEdgeIds = visibleGraph.edges.mapTo(linkedSetOf()) { it.id }
        val extraNodes = fullGraph.nodes.filter { node -> node.id in expandedNodeIds }
        val extraEdges = fullGraph.edges.filter { edge ->
            edge.id !in visibleEdgeIds &&
                edge.fromNodeId in presentationNodeIds &&
                edge.toNodeId in presentationNodeIds &&
                (
                    edge.fromNodeId in expandedNodeIds ||
                        edge.toNodeId in expandedNodeIds ||
                        edge.metadata[INVOCATION_EXPANSION_ID_KEY]?.trim() in expansionIds ||
                        isExpansionFromVisibleInvocation(edge.metadata, visibleCanonicalNodeIds)
                    )
        }
        return visibleGraph.copy(
            nodes = visibleGraph.nodes + extraNodes,
            edges = visibleGraph.edges + extraEdges,
        )
    }

    private fun GraphDocument.includeRequestedFocusNodes(
        fullGraph: GraphDocument,
        focusNodeIds: List<String>,
    ): GraphDocument {
        if (focusNodeIds.isEmpty()) {
            return this
        }
        val visibleNodeIds = nodes.mapTo(linkedSetOf()) { node -> node.id }
        val missingFocusNodes = focusNodeIds
            .filterNot { nodeId -> nodeId in visibleNodeIds }
            .mapNotNull { nodeId -> fullGraph.nodes.firstOrNull { node -> node.id == nodeId } }
        if (missingFocusNodes.isEmpty()) {
            return this
        }
        val expansionIds = missingFocusNodes
            .mapNotNull { node -> node.metadata[INVOCATION_EXPANSION_ID_KEY]?.trim()?.takeIf(String::isNotBlank) }
            .toCollection(linkedSetOf())
        val extraNodeIds = missingFocusNodes.mapTo(linkedSetOf()) { node -> node.id }
        if (expansionIds.isNotEmpty()) {
            fullGraph.nodes
                .filter { node -> node.metadata[INVOCATION_EXPANSION_ID_KEY]?.trim() in expansionIds }
                .mapTo(extraNodeIds) { node -> node.id }
            fullGraph.edges
                .filter { edge -> edge.metadata[INVOCATION_EXPANSION_ID_KEY]?.trim() in expansionIds }
                .forEach { edge ->
                    extraNodeIds += edge.fromNodeId
                    extraNodeIds += edge.toNodeId
                }
        }
        extraNodeIds.removeAll(visibleNodeIds)
        if (extraNodeIds.isEmpty()) {
            return this
        }
        val presentationNodeIds = linkedSetOf<String>().apply {
            addAll(visibleNodeIds)
            addAll(extraNodeIds)
        }
        val visibleEdgeIds = edges.mapTo(linkedSetOf()) { edge -> edge.id }
        val extraNodes = fullGraph.nodes.filter { node -> node.id in extraNodeIds }
        val extraEdges = fullGraph.edges.filter { edge ->
            edge.id !in visibleEdgeIds &&
                edge.fromNodeId in presentationNodeIds &&
                edge.toNodeId in presentationNodeIds &&
                (
                    edge.fromNodeId in extraNodeIds ||
                        edge.toNodeId in extraNodeIds ||
                        edge.metadata[INVOCATION_EXPANSION_ID_KEY]?.trim() in expansionIds
                    )
        }
        return copy(
            nodes = nodes + extraNodes,
            edges = edges + extraEdges,
        )
    }

    private fun isExpansionFromVisibleInvocation(
        metadata: Map<String, String>,
        visibleCanonicalNodeIds: Set<String>,
    ): Boolean {
        val sourceInvocationNodeId = metadata[INVOCATION_EXPANSION_SOURCE_NODE_ID_KEY]
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return false
        return sourceInvocationNodeId in visibleCanonicalNodeIds
    }

    private fun projectedAliasNodeIds(node: GraphNode): List<String> {
        return node.metadata[FLOWCHART_ALIAS_NODE_IDS_KEY]
            ?.split(',')
            ?.mapNotNull { nodeId -> nodeId.trim().takeIf(String::isNotBlank) }
            .orEmpty()
    }

    /**
     * 根据选区决定问答时使用的事实图和可编辑图。
     */
    fun buildQaGraphs(
        snapshot: WorkflowEditorSnapshot,
        selectedNodeIds: List<String>,
        collectSourceEvidence: Boolean = true,
    ): QaGraphs {
        val graphContext = buildInteractiveGraphContext(
            snapshot = snapshot,
            focusNodeIds = selectedNodeIds,
        )
        val factGraph = qaFactGraph(snapshot, graphContext)
        val editableGraph = qaEditableGraph(snapshot, graphContext)
        val evidenceCollection = if (collectSourceEvidence) {
            qaEvidenceCollector.collect(
                graph = mergeQaEvidenceGraph(graphContext.presentationGraph, editableGraph),
                selectedNodeIds = selectedNodeIds,
            )
        } else {
            QaEvidenceCollection()
        }
        return QaGraphs(
            factGraph = factGraph,
            editableGraph = editableGraph,
            sourceContext = evidenceCollection.sourceContext,
            evidenceTrace = evidenceCollection.evidenceTrace,
        )
    }

    private fun currentSceneFullGraph(snapshot: WorkflowEditorSnapshot): GraphDocument? {
        val graph = when (snapshot.currentSceneId.toAnalysisDisplayMode()) {
            AnalysisDisplayMode.FACT_GRAPH -> snapshot.factGraphView.fullGraph
            AnalysisDisplayMode.FLOWCHART -> snapshot.flowchartView.fullGraph
            AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> snapshot.resourceRelationView.fullGraph
            AnalysisDisplayMode.ARCHITECTURE_GRAPH -> snapshot.architectureGraphView.fullGraph
            AnalysisDisplayMode.CLASS_DIAGRAM -> snapshot.classDiagramView.fullGraph
            AnalysisDisplayMode.REVIEW_GRAPH -> snapshot.reviewGraphView.fullGraph
            null -> snapshot.diffGraph ?: GraphDocument()
        }
        return graph.takeIf(GraphDocument::hasGraphContent)
    }

    private fun usesCurrentSceneGraphForReview(snapshot: WorkflowEditorSnapshot): Boolean =
        when (snapshot.currentSceneId.toAnalysisDisplayMode()) {
            AnalysisDisplayMode.ARCHITECTURE_GRAPH,
            AnalysisDisplayMode.CLASS_DIAGRAM,
            AnalysisDisplayMode.REVIEW_GRAPH,
            -> true
            else -> false
        }

    private fun qaEditableGraph(
        snapshot: WorkflowEditorSnapshot,
        graphContext: InteractiveGraphContext,
    ): GraphDocument {
        return if (usesCurrentSceneGraphForReview(snapshot)) {
            graphContext.presentationGraph.takeIf(GraphDocument::hasGraphContent)
                ?: graphContext.fullGraph
        } else {
            graphContext.workingGraph
        }
    }

    private fun qaFactGraph(
        snapshot: WorkflowEditorSnapshot,
        graphContext: InteractiveGraphContext,
    ): GraphDocument {
        if (usesCurrentSceneGraphForReview(snapshot)) {
            return graphContext.fullGraph.takeIf(GraphDocument::hasGraphContent)
                ?: graphContext.presentationGraph
        }
        return snapshot.semanticFactGraph
            .takeIf(GraphDocument::hasGraphContent)
            ?: snapshot.factGraphView.fullGraph
                .takeIf(GraphDocument::hasGraphContent)
            ?: graphContext.presentationGraph
    }

    private fun mergeQaEvidenceGraph(
        factGraph: GraphDocument,
        editableGraph: GraphDocument,
    ): GraphDocument {
        return GraphDocument(
            nodes = (editableGraph.nodes + factGraph.nodes).distinctBy(GraphNode::id),
            edges = (editableGraph.edges + factGraph.edges).distinctBy { edge -> edge.id },
        )
    }

    /**
     * 为链路讲解选择最合适的锚点节点。
     */
    private fun resolveBeautificationAnchorNodeId(
        snapshot: WorkflowEditorSnapshot,
        visibleGraph: GraphDocument,
        requestedFocusNodeId: String?,
    ): String? {
        val currentSceneSelection = snapshot.selectedNodeId
        requestedFocusNodeId
            ?.takeIf { nodeId -> visibleGraph.nodes.any { it.id == nodeId } }
            ?.let { return it }
        currentSceneSelection
            ?.takeIf { nodeId -> visibleGraph.nodes.any { it.id == nodeId } }
            ?.let { return it }
        return snapshot.selectedMethodSignature
            ?.let { GraphNode.stableId(NodeType.METHOD, it) }
            ?.takeIf { anchorId -> visibleGraph.nodes.any { it.id == anchorId } }
            ?: visibleGraph.nodes.firstOrNull { it.type == NodeType.METHOD }?.id
            ?: visibleGraph.nodes.firstOrNull()?.id
    }

    /**
     * 统计当前方法内和跨方法被折叠隐藏的节点数量。
     */
    private fun computeBeautificationHiddenCounts(
        snapshot: WorkflowEditorSnapshot,
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        anchorNodeId: String?,
    ): Pair<Int, Int> {
        val visibleNodeIds = visibleGraph.nodes.mapTo(linkedSetOf()) { it.id }
        val fullNodeIds = fullGraph.nodes.mapTo(linkedSetOf()) { it.id }
        val currentMethodNodeIds = collectBeautificationCurrentMethodNodeIds(
            fullGraph = fullGraph,
            selectedMethodSignature = snapshot.selectedMethodSignature,
            anchorNodeId = anchorNodeId,
        )
        val hiddenCurrentMethodNodeCount = (currentMethodNodeIds - visibleNodeIds).size
        val hiddenCrossMethodNodeCount = (fullNodeIds - currentMethodNodeIds - visibleNodeIds).size
        return hiddenCurrentMethodNodeCount to hiddenCrossMethodNodeCount
    }

    /**
     * 收集属于当前方法语境的节点 ID。
     */
    private fun collectBeautificationCurrentMethodNodeIds(
        fullGraph: GraphDocument,
        selectedMethodSignature: String?,
        anchorNodeId: String?,
    ): Set<String> {
        val anchorSignature = anchorNodeId
            ?.let { nodeId -> fullGraph.nodes.firstOrNull { it.id == nodeId } }
            ?.signature
            ?.takeIf(String::isNotBlank)
        val currentMethodSignature = anchorSignature ?: selectedMethodSignature
        val methodNodeIds = fullGraph.nodes
            .asSequence()
            .filter { node ->
                when {
                    anchorNodeId != null && node.id == anchorNodeId -> true
                    currentMethodSignature.isNullOrBlank() -> false
                    node.signature == currentMethodSignature -> true
                    node.metadata["flow.anchorMethod"] == currentMethodSignature -> true
                    node.metadata["flow.ownerMethod"] == currentMethodSignature -> true
                    else -> false
                }
            }
            .mapTo(linkedSetOf()) { it.id }
        if (methodNodeIds.isEmpty() && anchorNodeId != null && fullGraph.nodes.any { it.id == anchorNodeId }) {
            methodNodeIds += anchorNodeId
        }
        return methodNodeIds
    }

    /**
     * 为链路讲解构造源码片段上下文。
     */
    private fun buildSourceSnippetContexts(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        anchorNodeId: String?,
        selectedNodeIds: List<String>,
        limit: Int?,
    ): List<SourceSnippetContext> {
        val fullNodeById = fullGraph.nodes.associateBy { it.id }
        val sequence = visibleGraph.nodes
            .asSequence()
            .map { visibleNode -> fullNodeById[visibleNode.id] ?: visibleNode }
            .sortedWith(
                compareByDescending<GraphNode> { it.id in selectedNodeIds }
                    .thenByDescending { it.id == anchorNodeId }
                    .thenBy { it.sourceLocation().startOffset ?: Int.MAX_VALUE }
                    .thenBy { it.id },
            )
        val snippets = sequence
            .flatMap { node ->
                sequence {
                    sourceSnippetFromNode(node)?.let { yield(it) }
                    architectureSourceSampleSnippets(node).forEach { yield(it) }
                }
            }
            .distinctBy(::snippetKey)
        return (limit?.let { maxItems -> snippets.take(maxItems) } ?: snippets)
            .toList()
    }

    /**
     * 从节点本身的 source metadata 读取源码片段。
     */
    private fun sourceSnippetFromNode(node: GraphNode?): SourceSnippetContext? {
        node ?: return null
        val sourceLocation = node.sourceLocation()
        val filePath = sourceLocation.filePath ?: return null
        val normalizedOffsets = normalizeSnippetOffsets(
            startOffset = sourceLocation.startOffset,
            endOffset = sourceLocation.endOffset,
        )
        val startOffset = normalizedOffsets.first
        val endOffset = normalizedOffsets.second
        val startLine = sourceLocation.startLine
        val endLine = sourceLocation.endLine
        return SourceSnippetContext(
            nodeId = node.id,
            filePath = filePath,
            startOffset = startOffset,
            endOffset = endOffset,
            startLine = startLine,
            endLine = endLine,
            snippet = readSourceSnippet(
                filePath = filePath,
                startOffset = startOffset,
                endOffset = endOffset,
                startLine = startLine,
                endLine = endLine,
            ),
        )
    }

    private fun architectureSourceSampleSnippets(node: GraphNode): List<SourceSnippetContext> {
        val sampleCount = node.metadata["architecture.sourceSample.count"]?.toIntOrNull()?.coerceAtLeast(0) ?: return emptyList()
        return (0 until sampleCount).mapNotNull { sampleIndex ->
            val prefix = "architecture.sourceSample.$sampleIndex"
            val filePath = node.metadata["$prefix.filePath"]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val startLine = node.metadata["$prefix.startLine"]?.toIntOrNull()
            val endLine = node.metadata["$prefix.endLine"]?.toIntOrNull()
            SourceSnippetContext(
                nodeId = node.metadata["$prefix.nodeId"]?.takeIf(String::isNotBlank) ?: node.id,
                filePath = filePath,
                startLine = startLine,
                endLine = endLine,
                snippet = readSourceSnippet(
                    filePath = filePath,
                    startOffset = null,
                    endOffset = null,
                    startLine = startLine,
                    endLine = endLine,
                ),
                origin = node.metadata["$prefix.reason"],
                decompiled = node.metadata["$prefix.decompiled"]?.toBooleanStrictOrNull() ?: false,
                virtualFileUrl = node.metadata["$prefix.virtualFileUrl"],
            )
        }
    }

    /**
     * 为去重生成稳定 key。
     */
    private fun snippetKey(snippet: SourceSnippetContext): String {
        return listOf(
            snippet.nodeId,
            snippet.filePath,
            snippet.startOffset?.toString().orEmpty(),
            snippet.endOffset?.toString().orEmpty(),
            snippet.startLine?.toString().orEmpty(),
            snippet.endLine?.toString().orEmpty(),
        ).joinToString("|")
    }

    /**
     * 过滤非法偏移，避免把坏 offset 误当成文件头片段。
     */
    private fun normalizeSnippetOffsets(
        startOffset: Int?,
        endOffset: Int?,
    ): Pair<Int?, Int?> {
        if (startOffset == null || endOffset == null) {
            return null to null
        }
        if (startOffset < 0 || endOffset < startOffset) {
            return null to null
        }
        return startOffset to endOffset
    }

    /**
     * 按偏移读取源码片段，供提示词使用。
     */
    private fun readSourceSnippet(
        filePath: String,
        startOffset: Int?,
        endOffset: Int?,
        startLine: Int? = null,
        endLine: Int? = null,
    ): String? {
        if (filePath.isBlank()) {
            return null
        }
        return runCatching {
            val path = ProjectPathNormalizer.resolvePath(filePath, projectBasePathProvider()) ?: return null
            if (!Files.isRegularFile(path)) {
                return null
            }
            val content = Files.readString(path)
            if (content.isBlank()) {
                return null
            }
            val snippet = when {
                startOffset != null && endOffset != null -> {
                    val start = startOffset.coerceIn(0, content.length)
                    val end = endOffset.coerceIn(start, content.length)
                    content.substring(start, end)
                }

                startLine != null && endLine != null -> {
                    val lines = content.replace("\r\n", "\n").lines()
                    if (lines.isEmpty()) {
                        return null
                    }
                    val start = (startLine - 1).coerceIn(0, lines.lastIndex)
                    val end = (endLine - 1).coerceIn(start, lines.lastIndex)
                    lines.subList(start, end + 1).joinToString("\n")
                }

                else -> content
            }
            normalizeSourceSnippetForPrompt(snippet)
        }.recoverCatching { error ->
            if (error is InvalidPathException) {
                null
            } else {
                throw error
            }
        }.getOrNull()
    }

    /**
     * 规范化源码片段长度与换行，避免提示词过长。
     */
    private fun normalizeSourceSnippetForPrompt(snippet: String): String? {
        val normalized = snippet
            .replace("\r\n", "\n")
            .trim()
            .ifBlank { return null }
        if (normalized.length <= MAX_BEAUTIFICATION_SOURCE_SNIPPET_LENGTH) {
            return normalized
        }
        val lines = normalized.lines()
        val collectedLines = mutableListOf<String>()
        var currentLength = 0
        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.isBlank()) {
                return@forEach
            }
            val addition = if (collectedLines.isEmpty()) line.length else line.length + 1
            if (currentLength + addition <= PREFERRED_BEAUTIFICATION_SOURCE_SNIPPET_LENGTH) {
                collectedLines += line
                currentLength += addition
                return@forEach
            }
            if (collectedLines.isEmpty() && line.length <= MAX_BEAUTIFICATION_SOURCE_SNIPPET_LENGTH) {
                collectedLines += line
            }
            return@forEach
        }
        if (collectedLines.isNotEmpty()) {
            return collectedLines.joinToString("\n")
        }
        return clipSnippetAtBoundary(normalized)
    }

    /**
     * 在自然边界处裁剪超长源码片段。
     */
    private fun clipSnippetAtBoundary(text: String): String {
        if (text.length <= MAX_BEAUTIFICATION_SOURCE_SNIPPET_LENGTH) {
            return text
        }
        val preferredIndex = PREFERRED_BEAUTIFICATION_SOURCE_SNIPPET_LENGTH.coerceAtMost(text.length - 1)
        val maxIndex = MAX_BEAUTIFICATION_SOURCE_SNIPPET_LENGTH.coerceAtMost(text.length)
        val boundaryIndex = ((preferredIndex + 1)..maxIndex).firstOrNull { index ->
            text[index - 1].isWhitespace() || text[index - 1] in setOf(')', '}', ';', ',', ']')
        }
        return text.substring(0, boundaryIndex ?: maxIndex).trimEnd()
    }

    companion object {
        private const val MAX_BEAUTIFICATION_SOURCE_SNIPPETS = 12
        private const val PREFERRED_BEAUTIFICATION_SOURCE_SNIPPET_LENGTH = 240
        private const val MAX_BEAUTIFICATION_SOURCE_SNIPPET_LENGTH = 800
        private const val FLOWCHART_ALIAS_NODE_IDS_KEY = "flowchart.projectedFromNodeIds"
        private const val INVOCATION_EXPANSION_ID_KEY = "linkGraph.expansion.id"
        private const val INVOCATION_EXPANSION_SOURCE_NODE_ID_KEY = "linkGraph.expansion.sourceInvocationNodeId"
    }
}

private fun GraphDocument.hasGraphContent(): Boolean = nodes.isNotEmpty() || edges.isNotEmpty() || patch != null

internal data class QaGraphs(
    val factGraph: GraphDocument,
    val editableGraph: GraphDocument,
    val sourceContext: List<SourceSnippetContext> = emptyList(),
    val evidenceTrace: List<com.charmnight.linkgraph.agent.model.EvidenceTraceEntry> = emptyList(),
)

private data class InteractiveGraphContext(
    val presentationGraph: GraphDocument,
    val fullGraph: GraphDocument,
    val workingGraph: GraphDocument,
)
