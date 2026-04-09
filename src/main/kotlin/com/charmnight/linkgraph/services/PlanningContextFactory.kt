package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.llm.GenerationContext
import com.charmnight.linkgraph.llm.GraphBeautificationContext
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
import com.charmnight.linkgraph.ui.GraphEditorStateService
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * 为计划生成、审计、讲解等流程统一构造上下文载荷。
 */
internal class PlanningContextFactory(
    /** 图 diff 比较器。 */
    private val graphDiffer: GraphDiffer,
    /** 同步预览规划器。 */
    private val syncPreviewPlanner: SyncPreviewPlanner,
    /** 图生成服务。 */
    private val graphGenerationService: GraphGenerationService,
    /** 当前真正生效的生成设置。 */
    private val settingsProvider: () -> LinkGraphSettingsState,
) {
    /**
     * 计算实现计划和代码草稿共用的规划载荷。
     */
    fun computePlanningPayload(snapshot: GraphEditorStateService.Snapshot): PlanningPayload {
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
        return PlanningPayload(
            planningGraph = planningGraph,
            diff = diff,
            previewItems = previewItems,
            snapshot = snapshot,
        )
    }

    /**
     * 基于规划上下文即时构造计划快照。
     */
    fun buildPlanSnapshot(
        planningGraph: GraphDocument,
        diff: GraphDiff,
        previewItems: List<SyncPreviewItem>,
        snapshot: GraphEditorStateService.Snapshot,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ) = graphGenerationService.generatePlan(
        context = GenerationContext(
            graph = planningGraph,
            mermaidIssues = snapshot.mermaidIssues,
            diff = diff,
            syncPreviewItems = previewItems,
        ),
        settings = settingsProvider(),
        onPreview = onPreview,
    )

    /**
     * 构造链路讲解所需的展示和源码上下文。
     */
    fun buildGraphBeautificationContext(
        snapshot: GraphEditorStateService.Snapshot,
        goal: String,
        preferredStyle: String?,
        explanationFocus: String?,
    ): GraphBeautificationContext {
        val visibleGraph = currentVisibleGraph(snapshot)
        val workingGraph = currentWorkingGraph(snapshot)
        val fullGraph = if (snapshot.workingGraphDirty) {
            workingGraph
        } else {
            snapshot.referenceFactGraph ?: workingGraph
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
        )
    }

    /**
     * 根据选区决定审计时使用的事实图和草稿图。
     */
    fun buildAuditGraphs(
        snapshot: GraphEditorStateService.Snapshot,
        selectedNodeIds: List<String>,
    ): AuditGraphs {
        val workingGraph = currentWorkingGraph(snapshot)
        val backgroundFactGraph = if (selectedNodeIds.isEmpty()) {
            workingGraph
        } else {
            snapshot.referenceFactGraph ?: workingGraph
        }
        return AuditGraphs(
            factGraph = backgroundFactGraph,
            draftGraph = workingGraph,
        )
    }

    /**
     * 为链路讲解选择最合适的锚点节点。
     */
    private fun resolveBeautificationAnchorNodeId(
        snapshot: GraphEditorStateService.Snapshot,
        visibleGraph: GraphDocument,
    ): String? {
        snapshot.selectedMethodSignature
            ?.let { GraphNode.stableId(NodeType.METHOD, it) }
            ?.takeIf { anchorId -> visibleGraph.nodes.any { it.id == anchorId } }
            ?.let { return it }
        return snapshot.selectedNodeId
            ?.takeIf { nodeId -> visibleGraph.nodes.any { it.id == nodeId } }
            ?: visibleGraph.nodes.firstOrNull { it.type == NodeType.METHOD }?.id
            ?: visibleGraph.nodes.firstOrNull()?.id
    }

    /**
     * 统计当前方法内和跨方法被折叠隐藏的节点数量。
     */
    private fun computeBeautificationHiddenCounts(
        snapshot: GraphEditorStateService.Snapshot,
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
                    snippet = readSourceSnippet(filePath, startOffset, endOffset),
                )
            }
            .toList()
    }

    /**
     * 按偏移读取源码片段，供提示词使用。
     */
    private fun readSourceSnippet(
        filePath: String,
        startOffset: Int?,
        endOffset: Int?,
    ): String? {
        if (filePath.isBlank()) {
            return null
        }
        return runCatching {
            val path = Path.of(filePath)
            if (!Files.isRegularFile(path)) {
                return null
            }
            val content = Files.readString(path)
            if (content.isBlank()) {
                return null
            }
            val snippet = if (startOffset != null && endOffset != null) {
                val start = startOffset.coerceIn(0, content.length)
                val end = endOffset.coerceIn(start, content.length)
                content.substring(start, end)
            } else {
                content
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

internal data class PlanningPayload(
    val planningGraph: GraphDocument,
    val diff: GraphDiff,
    val previewItems: List<SyncPreviewItem>,
    val snapshot: GraphEditorStateService.Snapshot,
)

internal data class AuditGraphs(
    val factGraph: GraphDocument,
    val draftGraph: GraphDocument,
)
