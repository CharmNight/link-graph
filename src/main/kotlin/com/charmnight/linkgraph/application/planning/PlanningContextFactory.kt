package com.charmnight.linkgraph.application.planning

import com.charmnight.linkgraph.application.model.PlanningInput
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.model.currentVisibleGraph
import com.charmnight.linkgraph.application.model.currentWorkingGraph
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.codegen.ProjectPathNormalizer
import com.charmnight.linkgraph.llm.GenerationContext
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GraphBeautificationContext
import com.charmnight.linkgraph.llm.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.llm.GraphGenerationService
import com.charmnight.linkgraph.llm.GraphPresentationContext
import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
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
    private val graphGenerationService: GraphGenerationService,
    /** 问答源码证据收集器。 */
    private val auditEvidenceCollector: AuditEvidenceCollector = AuditEvidenceCollector(),
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
        followUp: GraphBeautificationFollowUpContext?,
        granularity: StepGranularity,
    ): GraphBeautificationContext {
        val visibleGraph = currentVisibleGraph(snapshot)
        val workingGraph = currentWorkingGraph(snapshot)
        val fullGraph = if (snapshot.workingGraphDirty) {
            workingGraph
        } else {
            snapshot.semanticFactGraph.takeIf { graph -> graph.nodes.isNotEmpty() || graph.edges.isNotEmpty() } ?: workingGraph
        }
        val anchorNodeId = resolveBeautificationAnchorNodeId(snapshot, visibleGraph)
        val selectedNodeIds = snapshot.selectedNodeId?.let(::listOf).orEmpty()
        val (hiddenCurrentMethodNodeCount, hiddenCrossMethodNodeCount) = computeBeautificationHiddenCounts(
            snapshot = snapshot,
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            anchorNodeId = anchorNodeId,
        )
        return GraphBeautificationContext(
            presentationContext = GraphPresentationContext(
                graph = visibleGraph,
                fullGraph = fullGraph,
                anchorNodeId = anchorNodeId,
                selectedNodeIds = selectedNodeIds,
                hiddenCurrentMethodNodeCount = hiddenCurrentMethodNodeCount,
                hiddenCrossMethodNodeCount = hiddenCrossMethodNodeCount,
            ),
            sourceContext = buildSourceSnippetContexts(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                anchorNodeId = anchorNodeId,
                selectedNodeIds = selectedNodeIds,
            ),
            userGoal = goal,
            preferredStyle = preferredStyle,
            explanationFocus = explanationFocus,
            followUp = followUp,
            granularity = granularity,
        )
    }

    /**
     * 根据选区决定问答时使用的事实图和可编辑图。
     */
    fun buildAuditGraphs(
        snapshot: WorkflowEditorSnapshot,
        selectedNodeIds: List<String>,
        collectSourceEvidence: Boolean = true,
    ): AuditGraphs {
        val workingGraph = currentWorkingGraph(snapshot)
        val backgroundFactGraph = snapshot.semanticFactGraph
            .takeIf { graph -> graph.nodes.isNotEmpty() || graph.edges.isNotEmpty() }
            ?: workingGraph
        val evidenceCollection = if (collectSourceEvidence) {
            auditEvidenceCollector.collect(
                graph = mergeAuditEvidenceGraph(backgroundFactGraph, workingGraph),
                selectedNodeIds = selectedNodeIds,
            )
        } else {
            AuditEvidenceCollection()
        }
        return AuditGraphs(
            factGraph = backgroundFactGraph,
            editableGraph = workingGraph,
            sourceContext = evidenceCollection.sourceContext,
            evidenceTrace = evidenceCollection.evidenceTrace,
        )
    }

    private fun mergeAuditEvidenceGraph(
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
    ): String? {
        val currentSceneSelection = snapshot.selectedNodeId
        snapshot.selectedMethodSignature
            ?.let { GraphNode.stableId(NodeType.METHOD, it) }
            ?.takeIf { anchorId -> visibleGraph.nodes.any { it.id == anchorId } }
            ?.let { return it }
        return currentSceneSelection
            ?.takeIf { nodeId -> visibleGraph.nodes.any { it.id == nodeId } }
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
        val methodNodeIds = fullGraph.nodes
            .asSequence()
            .filter { node ->
                when {
                    anchorNodeId != null && node.id == anchorNodeId -> true
                    selectedMethodSignature.isNullOrBlank() -> false
                    node.signature == selectedMethodSignature -> true
                    node.metadata["flow.anchorMethod"] == selectedMethodSignature -> true
                    node.metadata["flow.ownerMethod"] == selectedMethodSignature -> true
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
    ): List<SourceSnippetContext> {
        val fullNodeById = fullGraph.nodes.associateBy { it.id }
        return visibleGraph.nodes
            .asSequence()
            .map { visibleNode -> fullNodeById[visibleNode.id] ?: visibleNode }
            .filter { node -> !node.metadata["source.filePath"].isNullOrBlank() }
            .sortedWith(
                compareByDescending<GraphNode> { it.id in selectedNodeIds }
                    .thenByDescending { it.id == anchorNodeId }
                    .thenBy { it.metadata["source.startOffset"]?.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.id },
            )
            .take(MAX_BEAUTIFICATION_SOURCE_SNIPPETS)
            .mapNotNull { node ->
                val filePath = node.metadata["source.filePath"] ?: return@mapNotNull null
                val startOffset = node.metadata["source.startOffset"]?.toIntOrNull()
                val endOffset = node.metadata["source.endOffset"]?.toIntOrNull()
                SourceSnippetContext(
                    nodeId = node.id,
                    filePath = filePath,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    startLine = node.metadata["source.startLine"]?.toIntOrNull(),
                    endLine = node.metadata["source.endLine"]?.toIntOrNull(),
                    snippet = readSourceSnippet(
                        filePath = filePath,
                        startOffset = startOffset,
                        endOffset = endOffset,
                        startLine = node.metadata["source.startLine"]?.toIntOrNull(),
                        endLine = node.metadata["source.endLine"]?.toIntOrNull(),
                    ),
                )
            }
            .toList()
    }

    /**
     * 从节点本身的 source metadata 读取源码片段。
     */
    private fun sourceSnippetFromNode(node: GraphNode?): SourceSnippetContext? {
        node ?: return null
        val filePath = node.metadata["source.filePath"] ?: return null
        val normalizedOffsets = normalizeSnippetOffsets(
            startOffset = node.metadata["source.startOffset"]?.toIntOrNull(),
            endOffset = node.metadata["source.endOffset"]?.toIntOrNull(),
        )
        val startOffset = normalizedOffsets.first
        val endOffset = normalizedOffsets.second
        val startLine = node.metadata["source.startLine"]?.toIntOrNull()
        val endLine = node.metadata["source.endLine"]?.toIntOrNull()
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
    }
}

internal data class AuditGraphs(
    val factGraph: GraphDocument,
    val editableGraph: GraphDocument,
    val sourceContext: List<SourceSnippetContext> = emptyList(),
    val evidenceTrace: List<com.charmnight.linkgraph.llm.EvidenceTraceEntry> = emptyList(),
)
