package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession
import com.charmnight.linkgraph.workbench.QaRequestRecoveryState

/**
 * 工作流编辑器快照：把 UI 与命令处理层需要的全部状态聚合为不可变数据。
 *
 * 与 [ApplicationSnapshot] 区别：本快照面向工作流编排层（包含视图、QA、草稿等所有运行时状态）；
 * ApplicationSnapshot 面向持久化层（只含可序列化的核心字段）。
 */
data class WorkflowEditorSnapshot(
    /** 语义事实图。 */
    val semanticFactGraph: GraphDocument = GraphDocument(),
    /** 工作台基线图（不含草稿）。 */
    val workspaceBaseGraph: GraphDocument = GraphDocument(),
    /** 当前工作台图（基线 + 已应用草稿）。 */
    val workspaceGraph: GraphDocument = GraphDocument(),
    /** 设计基线图（来自 Mermaid 导入等）。 */
    val designBaselineGraph: GraphDocument? = null,
    /** 可信导航节点索引：ID → 节点。 */
    val trustedNavigationNodes: Map<String, GraphNode> = emptyMap(),
    /** 事实图视图。 */
    val factGraphView: ApplicationGraphView = ApplicationGraphView(),
    /** 流程图视图。 */
    val flowchartView: ApplicationGraphView = ApplicationGraphView(),
    /** 资源关系图视图。 */
    val resourceRelationView: ApplicationGraphView = ApplicationGraphView(),
    /** 架构图视图。 */
    val architectureGraphView: ApplicationGraphView = ApplicationGraphView(),
    /** 类图视图。 */
    val classDiagramView: ApplicationGraphView = ApplicationGraphView(),
    /** 审查图视图。 */
    val reviewGraphView: ApplicationGraphView = ApplicationGraphView(),
    /** 当前展示模式。 */
    val analysisDisplayMode: AnalysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
    /** 当前场景 ID。 */
    val currentSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    /** 当前选中节点 ID。 */
    val selectedNodeId: String? = null,
    /** 当前选中的方法签名。 */
    val selectedMethodSignature: String? = null,
    /** 工作图是否有未保存修改。 */
    val workingGraphDirty: Boolean = false,
    /** 最近一次载图的来源标记。 */
    val lastGraphSource: String? = null,
    /** 工作台图版本号。 */
    val workspaceRevision: Long = 0,
    /** 整体快照版本号。 */
    val snapshotRevision: Long = 0,
    /** 同步预览项列表。 */
    val syncPreviewItems: List<SyncPreviewItem> = emptyList(),
    /** Mermaid 解析问题列表。 */
    val mermaidIssues: List<MermaidIssue> = emptyList(),
    /** 差异信息。 */
    val diff: GraphDiff? = null,
    /** 差异图（用于差异视图）。 */
    val diffGraph: GraphDocument? = null,
    /** 最近一次 QA 结果。 */
    val qaResult: GraphPatchResult? = null,
    /** 最近一次差异审查结果。 */
    val diffReviewResult: GraphPatchResult? = null,
    /** QA 请求状态。 */
    val qaRequestState: AsyncRequestState = AsyncRequestState(),
    /** QA 请求恢复状态（用于重试）。 */
    val qaRequestRecoveryState: QaRequestRecoveryState = QaRequestRecoveryState(),
    /** 草稿工作台状态。 */
    val draftWorkbenchState: DraftWorkbenchState = DraftWorkbenchState(),
    /** 草稿补丁预览。 */
    val draftPatchPreview: GraphPatch? = null,
    /** 草稿补丁撤销栈。 */
    val draftPatchUndo: DraftPatchUndo? = null,
    /** 实现计划。 */
    val generationPlan: GenerationPlan? = null,
    /** 实现计划讨论会话。 */
    val generationPlanDiscussionSession: GenerationPlanDiscussionSession? = null,
    /** 已生成的代码草稿列表。 */
    val generatedCodeDrafts: List<GeneratedCodeDraft> = emptyList(),
    /** 代码草稿写入报告。 */
    val generatedCodeDraftWriteReport: GeneratedCodeDraftWriteReport? = null,
) {
    /**
     * 把工作流快照投影为应用快照。
     * 只保留持久化相关字段，丢弃运行时状态。
     */
    fun toApplicationSnapshot(): ApplicationSnapshot {
        return ApplicationSnapshot(
            workspaceBaseGraph = workspaceBaseGraph,
            workspaceGraph = workspaceGraph,
            selectedMethodSignature = selectedMethodSignature,
            draftWorkbenchState = draftWorkbenchState,
            draftPatchPreview = draftPatchPreview,
            draftPatchUndo = draftPatchUndo,
            qaResult = qaResult,
            diffReviewResult = diffReviewResult,
        )
    }
}
