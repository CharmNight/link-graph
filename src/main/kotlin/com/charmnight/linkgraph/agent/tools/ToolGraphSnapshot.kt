package com.charmnight.linkgraph.agent.tools

import com.charmnight.linkgraph.agent.model.InvocationExpansionSceneState
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.DraftWorkbenchState

/** 标识工作台当前可能切换的图视图场景，工具会按此选择对应的可见图与完整图。 */
enum class ToolGraphSceneId {
    /** 事实图视图。 */
    WORKSPACE_FACT,
    /** 流程图视图。 */
    WORKSPACE_FLOWCHART,
    /** 资源关系视图。 */
    WORKSPACE_RESOURCE_RELATION,
    /** 架构图视图。 */
    WORKSPACE_ARCHITECTURE_GRAPH,
    /** 类图视图。 */
    WORKSPACE_CLASS_DIAGRAM,
    /** Review Graph 视图。 */
    WORKSPACE_REVIEW_GRAPH,
    /** 差异视图。 */
    DIFF,
}

/** 单个场景下的运行时状态，例如当前选中节点。 */
data class ToolGraphSceneState(
    /** 当前场景中用户选中的节点 ID，缺失表示未选中。 */
    val selectedNodeId: String? = null,
    /** 流程图调用展开 UI/session 状态，用于 LLM 工具过滤上下文。 */
    val invocationExpansionState: InvocationExpansionSceneState = InvocationExpansionSceneState(),
)

/** 单个图视图的展示快照，包含当前可见子图、完整图与节点投影索引。 */
data class ToolGraphView(
    /** 当前视图中实际展示给用户的子图。 */
    val visibleGraph: GraphDocument = GraphDocument(),
    /** 当前视图对应的完整图，作为邻域展开与证据查找的背景。 */
    val fullGraph: GraphDocument = GraphDocument(),
    /** 投影节点到真实源码节点的映射索引，缺失时使用空索引。 */
    val projectionIndex: ToolGraphProjectionIndex = ToolGraphProjectionIndex.EMPTY,
)

/**
 * 工具执行时可访问的完整图快照。
 * 包含工作台图、各场景视图、差异图、选区状态和草稿工作台状态等，工具实现按需读取所需字段。
 */
data class ToolGraphSnapshot(
    /** 工作台主工作图。 */
    val workspaceGraph: GraphDocument = GraphDocument(),
    /** 工作台主工作图的版本号，用于检测快照是否已过期。 */
    val workspaceRevision: Long = 0,
    /** 语义事实图，作为更高层级的背景知识。 */
    val semanticFactGraph: GraphDocument = GraphDocument(),
    /** 事实图视图的展示快照。 */
    val factGraphView: ToolGraphView = ToolGraphView(),
    /** 流程图视图的展示快照。 */
    val flowchartView: ToolGraphView = ToolGraphView(),
    /** 资源关系视图的展示快照。 */
    val resourceRelationView: ToolGraphView = ToolGraphView(),
    /** 架构图视图的展示快照。 */
    val architectureGraphView: ToolGraphView = ToolGraphView(),
    /** 类图视图的展示快照。 */
    val classDiagramView: ToolGraphView = ToolGraphView(),
    /** Review Graph 视图的展示快照。 */
    val reviewGraphView: ToolGraphView = ToolGraphView(),
    /** 当前差异视图使用的差异图，未进入差异视图时为 null。 */
    val diffGraph: GraphDocument? = null,
    /** 当前差异结构对象。 */
    val diff: GraphDiff? = null,
    /** 当前激活的图视图场景 ID。 */
    val currentSceneId: ToolGraphSceneId = ToolGraphSceneId.WORKSPACE_FACT,
    /** 各场景对应的运行时状态映射。 */
    val sceneStates: Map<ToolGraphSceneId, ToolGraphSceneState> = ToolGraphSceneId.entries.associateWith {
        ToolGraphSceneState()
    },
    /** 当前用户在工作台中选中的方法签名，便于按签名定位源码。 */
    val selectedMethodSignature: String? = null,
    /** 信任的导航节点集合，常用于绕过折叠视图直接定位真实节点。 */
    val trustedNavigationNodes: Map<String, GraphNode> = emptyMap(),
    /** 草稿工作台状态快照。 */
    val draftWorkbenchState: DraftWorkbenchState = DraftWorkbenchState(),
    /**
     * 待确认候选草稿变更列表（P4-2：替代之前的 qaResult 字段）。
     *
     * 之前 ToolGraphSnapshot 直接持有 `qaResult: GraphPatchResult?`（llm 包类型），
     * 导致 ToolGraphSnapshot 反向依赖 llm 层。这里改为只暴露中性的候选变更列表
     * （CandidateDraftChange 来自 workbench 包），由 ToolGraphSnapshotAdapter 从 qaResult
     * 派生此字段，ToolGraphSnapshot 自身不再依赖 llm.GraphPatchResult。
     */
    val pendingCandidateChanges: List<CandidateDraftChange> = emptyList(),
) {
    /** 返回当前激活场景对应的运行时状态，缺失时返回空状态。 */
    fun currentSceneState(): ToolGraphSceneState = sceneStates[currentSceneId] ?: ToolGraphSceneState()
}

/** 返回当前快照的工作图，作为大部分工具默认的图来源。 */
fun currentWorkingGraph(snapshot: ToolGraphSnapshot): GraphDocument = snapshot.workspaceGraph

/** 返回当前工作图的来源标签，用于日志和调试展示。 */
fun currentWorkingGraphSource(snapshot: ToolGraphSnapshot): String {
    return if (snapshot.workspaceGraph.hasGraphContent()) "workspaceGraph" else "emptyGraph"
}

/** 判断图是否包含任何节点、边或 patch 内容。 */
private fun GraphDocument.hasGraphContent(): Boolean = nodes.isNotEmpty() || edges.isNotEmpty() || patch != null

/**
 * 工具图快照提供者（P4-2 从 application/port 移到 llm/tools）。
 *
 * 此接口属于 LLM 工具契约（专供 LLM 工具上下文使用），返回值是 [ToolGraphSnapshot]
 * 也定义在本包；之前放在 application/port 造成 application 层反向依赖 llm/tools。
 *
 * 移动后 application/port 不再 import 本包类型；调用方（ReviewWorkflow / GenerationWorkflowDependencies）
 * 直接从本包取用即可。
 */
fun interface ToolGraphSnapshotProvider {
    /** 取当前工具图快照。 */
    fun snapshot(): ToolGraphSnapshot
}
