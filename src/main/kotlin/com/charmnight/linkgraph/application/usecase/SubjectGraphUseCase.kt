package com.charmnight.linkgraph.application.usecase

import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.model.currentVisibleGraph
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphMetadataKeys
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind
import com.charmnight.linkgraph.semantic.subject.SubjectHandle

sealed interface SubjectGraphUseCaseResult {
    data class RequestedDisplayMode(val displayMode: AnalysisDisplayMode) : SubjectGraphUseCaseResult
    data class DisplayModeRejected(
        val level: ApplicationFeedbackLevel,
        val message: String,
    ) : SubjectGraphUseCaseResult
    data class DisplayModeReady(
        val displayMode: AnalysisDisplayMode,
        val source: String,
        val analysisResult: SemanticAnalysisResult,
    ) : SubjectGraphUseCaseResult
    data class CurrentMethodNodeApplied(
        val graph: GraphDocument,
        val selectedMethodSignature: String,
        val statusMessage: String,
    ) : SubjectGraphUseCaseResult
    data class ResourceNodeApplied(
        val graph: GraphDocument,
        val selectedNodeId: String,
        val statusMessage: String,
    ) : SubjectGraphUseCaseResult
}

class SubjectGraphUseCase {
    fun effectiveAnalysisDisplayModeFor(
        subject: SubjectHandle,
        requestedDisplayMode: AnalysisDisplayMode,
    ): AnalysisDisplayMode {
        if (subject is ResourceSubjectHandle && requestedDisplayMode == AnalysisDisplayMode.FLOWCHART) {
            return AnalysisDisplayMode.RESOURCE_RELATION_VIEW
        }
        return requestedDisplayMode
    }

    fun sourceForSubject(handle: SubjectHandle): String {
        return when (handle) {
            is CodeSubjectHandle -> CURRENT_METHOD_SOURCE
            is ResourceSubjectHandle -> CURRENT_CONTEXT_SOURCE
        }
    }

    fun requestAnalysisDisplayMode(
        snapshot: WorkflowEditorSnapshot,
        displayMode: AnalysisDisplayMode,
        cachedResult: SemanticAnalysisResult?,
        lastGraphSource: String?,
    ): SubjectGraphUseCaseResult {
        if (displayMode in PROJECT_LEVEL_DISPLAY_MODES) {
            val existingView = when (displayMode) {
                AnalysisDisplayMode.ARCHITECTURE_GRAPH -> snapshot.architectureGraphView.visibleGraph
                AnalysisDisplayMode.CLASS_DIAGRAM -> snapshot.classDiagramView.visibleGraph
                AnalysisDisplayMode.REVIEW_GRAPH -> snapshot.reviewGraphView.visibleGraph
                else -> GraphDocument()
            }
            return if (existingView.nodes.isNotEmpty() || existingView.edges.isNotEmpty()) {
                SubjectGraphUseCaseResult.RequestedDisplayMode(displayMode)
            } else {
                SubjectGraphUseCaseResult.DisplayModeRejected(
                    level = ApplicationFeedbackLevel.INFO,
                    message = projectLevelDisplayModeMissingMessage(displayMode),
                )
            }
        }
        if (snapshot.workingGraphDirty) {
            return SubjectGraphUseCaseResult.RequestedDisplayMode(displayMode)
        }
        if (cachedResult == null) {
            return SubjectGraphUseCaseResult.DisplayModeRejected(
                level = ApplicationFeedbackLevel.WARNING,
                message = "当前结果不是基于统一语义分析生成，无法直接切换展示模式，请重新分析当前主体。",
            )
        }
        val effectiveDisplayMode = effectiveAnalysisDisplayModeFor(cachedResult.subject, displayMode)
        return SubjectGraphUseCaseResult.DisplayModeReady(
            displayMode = effectiveDisplayMode,
            source = lastGraphSource ?: sourceForSubject(cachedResult.subject),
            analysisResult = cachedResult,
        )
    }

    fun applyCurrentMethodNode(
        snapshot: WorkflowEditorSnapshot,
        currentMethodNode: CurrentMethodNodeInput,
    ): SubjectGraphUseCaseResult.CurrentMethodNodeApplied {
        val currentGraph = currentVisibleGraph(snapshot)
        val existingNode = currentGraph.nodes.firstOrNull { it.id == currentMethodNode.node.id }
        val mergedNode = existingNode?.let { node ->
            currentMethodNode.node.copy(metadata = node.metadata + currentMethodNode.node.metadata)
        } ?: positionNodeForCanvas(currentMethodNode.node, currentGraph.nodes.size)
        val nextNodes = if (existingNode == null) {
            currentGraph.nodes + mergedNode
        } else {
            currentGraph.nodes.map { node -> if (node.id == mergedNode.id) mergedNode else node }
        }
        return SubjectGraphUseCaseResult.CurrentMethodNodeApplied(
            graph = currentGraph.copy(nodes = nextNodes),
            selectedMethodSignature = currentMethodNode.methodSignature,
            statusMessage = if (existingNode == null) {
                "已追加当前方法节点：${currentMethodNode.methodDisplayName}"
            } else {
                "已刷新当前方法节点：${currentMethodNode.methodDisplayName}"
            },
        )
    }

    fun applyResourceNode(
        snapshot: WorkflowEditorSnapshot,
        handle: ResourceSubjectHandle,
        node: GraphNode,
    ): SubjectGraphUseCaseResult.ResourceNodeApplied {
        val currentGraph = currentVisibleGraph(snapshot)
        val existingNode = currentGraph.nodes.firstOrNull { it.id == node.id }
        val mergedNode = existingNode?.let { mergeGraphNode(it, node) }
            ?: positionNodeForCanvas(node, currentGraph.nodes.size)
        val nextNodes = if (existingNode == null) {
            currentGraph.nodes + mergedNode
        } else {
            currentGraph.nodes.map { current -> if (current.id == mergedNode.id) mergedNode else current }
        }
        val kindLabel = resourceKindLabel(handle.kind)
        return SubjectGraphUseCaseResult.ResourceNodeApplied(
            graph = currentGraph.copy(nodes = nextNodes),
            selectedNodeId = mergedNode.id,
            statusMessage = if (existingNode == null) {
                "已追加当前${kindLabel}节点：${node.title}"
            } else {
                "已刷新当前${kindLabel}节点：${node.title}"
            },
        )
    }

    private fun positionNodeForCanvas(
        node: GraphNode,
        existingNodeCount: Int,
    ): GraphNode {
        if (node.metadata.containsKey(GraphMetadataKeys.Ui.X) && node.metadata.containsKey(GraphMetadataKeys.Ui.Y)) {
            return node
        }
        val column = existingNodeCount % DEFAULT_CANVAS_COLUMNS
        val row = existingNodeCount / DEFAULT_CANVAS_COLUMNS
        val x = DEFAULT_CANVAS_START_X + column * DEFAULT_CANVAS_GAP_X
        val y = DEFAULT_CANVAS_START_Y + row * DEFAULT_CANVAS_GAP_Y
        return node.copy(
            metadata = node.metadata + mapOf(
                GraphMetadataKeys.Ui.X to x.toString(),
                GraphMetadataKeys.Ui.Y to y.toString(),
            ),
        )
    }

    private fun mergeGraphNode(existing: GraphNode, next: GraphNode): GraphNode {
        return existing.copy(
            title = next.title,
            location = next.location ?: existing.location,
            signature = next.signature ?: existing.signature,
            inputs = next.inputs.ifEmpty { existing.inputs },
            outputs = next.outputs.ifEmpty { existing.outputs },
            doc = next.doc ?: existing.doc,
            sourceKind = next.sourceKind ?: existing.sourceKind,
            metadata = existing.metadata + next.metadata,
        )
    }

    private fun resourceKindLabel(kind: ResourceSubjectKind): String {
        return when (kind) {
            ResourceSubjectKind.CONFIG_ITEM -> "配置"
            ResourceSubjectKind.MYBATIS_STATEMENT -> "MyBatis"
            ResourceSubjectKind.XML_RESOURCE -> "XML"
            ResourceSubjectKind.MARKDOWN_PAGE -> "文档"
            ResourceSubjectKind.SQL_FILE -> "SQL"
        }
    }

    companion object {
        const val CURRENT_METHOD_SOURCE: String = "currentMethod"
        const val CURRENT_CONTEXT_SOURCE: String = "currentContext"
        private const val DEFAULT_CANVAS_COLUMNS = 3
        private const val DEFAULT_CANVAS_START_X = 120
        private const val DEFAULT_CANVAS_START_Y = 96
        private const val DEFAULT_CANVAS_GAP_X = 260
        private const val DEFAULT_CANVAS_GAP_Y = 170
        private val PROJECT_LEVEL_DISPLAY_MODES = setOf(
            AnalysisDisplayMode.ARCHITECTURE_GRAPH,
            AnalysisDisplayMode.CLASS_DIAGRAM,
            AnalysisDisplayMode.REVIEW_GRAPH,
        )
    }
}

private fun projectLevelDisplayModeMissingMessage(displayMode: AnalysisDisplayMode): String =
    when (displayMode) {
        AnalysisDisplayMode.ARCHITECTURE_GRAPH -> "架构图尚未加载，请通过架构图入口构建项目级索引。"
        AnalysisDisplayMode.CLASS_DIAGRAM -> "类图尚未加载，请通过类图入口构建项目级索引。"
        AnalysisDisplayMode.REVIEW_GRAPH -> "Review Graph 尚未加载，请通过 Review Graph 入口构建项目级索引。"
        else -> "目标视图尚未加载。"
    }

data class CurrentMethodNodeInput(
    val node: GraphNode,
    val methodSignature: String,
    val methodDisplayName: String,
)
