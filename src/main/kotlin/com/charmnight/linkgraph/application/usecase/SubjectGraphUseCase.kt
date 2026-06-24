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

/**
 * 主题图谱用例的执行结果密封接口，统一封装与"分析展示模式切换"、"节点应用"相关的回执。
 */
sealed interface SubjectGraphUseCaseResult {
    /**
     * 表示展示模式切换请求被接受，调用方可以按此模式渲染对应视图。
     */
    data class RequestedDisplayMode(val displayMode: AnalysisDisplayMode) : SubjectGraphUseCaseResult

    /**
     * 表示展示模式切换请求被拒绝，附带反馈级别和面向用户的提示文案。
     */
    data class DisplayModeRejected(
        val level: ApplicationFeedbackLevel,
        val message: String,
    ) : SubjectGraphUseCaseResult

    /**
     * 表示展示模式已就绪，可直接基于已缓存的语义分析结果复用既有数据。
     */
    data class DisplayModeReady(
        val displayMode: AnalysisDisplayMode,
        val source: String,
        val analysisResult: SemanticAnalysisResult,
    ) : SubjectGraphUseCaseResult

    /**
     * 表示当前方法节点已成功写入工作图，并附带更新后的图与状态信息。
     */
    data class CurrentMethodNodeApplied(
        val graph: GraphDocument,
        val selectedMethodSignature: String,
        val statusMessage: String,
    ) : SubjectGraphUseCaseResult

    /**
     * 表示资源类型节点已成功写入工作图，并附带更新后的图与状态信息。
     */
    data class ResourceNodeApplied(
        val graph: GraphDocument,
        val selectedNodeId: String,
        val statusMessage: String,
    ) : SubjectGraphUseCaseResult
}

/**
 * 主题图谱用例：围绕"代码主体/资源主体"统一处理展示模式协商、节点合并与画布布局等编排逻辑。
 */
class SubjectGraphUseCase {
    /**
     * 计算给定主体在请求展示模式下的"实际生效"展示模式。
     * 资源主体不支持流程图，会回落到资源关系视图；其他场景保持请求的模式不变。
     */
    fun effectiveAnalysisDisplayModeFor(
        subject: SubjectHandle,
        requestedDisplayMode: AnalysisDisplayMode,
    ): AnalysisDisplayMode {
        if (subject is ResourceSubjectHandle && requestedDisplayMode == AnalysisDisplayMode.FLOWCHART) {
            return AnalysisDisplayMode.RESOURCE_RELATION_VIEW
        }
        return requestedDisplayMode
    }

    /**
     * 根据主体类型返回对应的"来源标识"，用于追溯图谱数据的出处。
     */
    fun sourceForSubject(handle: SubjectHandle): String {
        return when (handle) {
            is CodeSubjectHandle -> CURRENT_METHOD_SOURCE
            is ResourceSubjectHandle -> CURRENT_CONTEXT_SOURCE
        }
    }

    /**
     * 处理用户切换展示模式的请求：
     * - 项目级视图需要先确认对应索引已加载；
     * - 工作图脏时直接进入请求模式；
     * - 命中缓存时返回可直接复用的就绪结果，否则按提示级别拒绝。
     */
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

    /**
     * 将"当前方法"节点写入工作图：若同 ID 已存在则合并元数据并刷新，否则按画布网格定位后追加。
     */
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

    /**
     * 将资源型节点写入工作图：若同 ID 已存在则进行字段合并，否则按画布网格定位后追加，
     * 并根据资源种类返回对应的中文状态提示。
     */
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

    /**
     * 为新加入画布的节点计算默认坐标：若节点元数据中已带坐标则保留，否则按行列网格排列。
     */
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

    /**
     * 合并既有节点与新增节点的字段：非空字段优先采用新值，空值则沿用旧值，元数据采用新值覆盖旧值的方式合并。
     */
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

    /**
     * 将资源种类枚举映射为面向用户的中文简称，用于生成节点写入后的状态文案。
     */
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
        // 标识图谱数据来源为"当前方法"
        const val CURRENT_METHOD_SOURCE: String = "currentMethod"
        // 标识图谱数据来源为"当前上下文"（通常是资源主体）
        const val CURRENT_CONTEXT_SOURCE: String = "currentContext"
        // 默认画布网格列数
        private const val DEFAULT_CANVAS_COLUMNS = 3
        // 默认画布起始坐标 X
        private const val DEFAULT_CANVAS_START_X = 120
        // 默认画布起始坐标 Y
        private const val DEFAULT_CANVAS_START_Y = 96
        // 画布相邻节点的水平间距
        private const val DEFAULT_CANVAS_GAP_X = 260
        // 画布相邻节点的垂直间距
        private const val DEFAULT_CANVAS_GAP_Y = 170
        // 需要项目级索引支撑的展示模式集合
        private val PROJECT_LEVEL_DISPLAY_MODES = setOf(
            AnalysisDisplayMode.ARCHITECTURE_GRAPH,
            AnalysisDisplayMode.CLASS_DIAGRAM,
            AnalysisDisplayMode.REVIEW_GRAPH,
        )
    }
}

/**
 * 生成项目级展示模式尚未加载时的中文提示信息，引导用户前往对应入口构建索引。
 */
private fun projectLevelDisplayModeMissingMessage(displayMode: AnalysisDisplayMode): String =
    when (displayMode) {
        AnalysisDisplayMode.ARCHITECTURE_GRAPH -> "架构图尚未加载，请通过架构图入口构建项目级索引。"
        AnalysisDisplayMode.CLASS_DIAGRAM -> "类图尚未加载，请通过类图入口构建项目级索引。"
        AnalysisDisplayMode.REVIEW_GRAPH -> "Review Graph 尚未加载，请通过 Review Graph 入口构建项目级索引。"
        else -> "目标视图尚未加载。"
    }

/**
 * 写入"当前方法"节点所需的输入：节点本体、方法签名以及面向用户展示的方法名称。
 */
data class CurrentMethodNodeInput(
    val node: GraphNode,
    val methodSignature: String,
    val methodDisplayName: String,
)
