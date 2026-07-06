package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.agent.model.GraphBeautificationResult
import com.charmnight.linkgraph.agent.model.GraphPatchResult
import com.charmnight.linkgraph.agent.model.GenerationPlan
import com.charmnight.linkgraph.agent.model.LlmResultSource
import com.charmnight.linkgraph.agent.runtime.AgentRunArtifactSummary
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.application.model.GraphEditRejected
import com.charmnight.linkgraph.application.model.GraphEditTransaction
import com.charmnight.linkgraph.application.model.toAnalysisDisplayMode as toApplicationAnalysisDisplayMode
import com.charmnight.linkgraph.application.model.toWorkspaceSceneId as toApplicationWorkspaceSceneId
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.review.ReviewGraphResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.semantic.outcome.FactGraphViewDocument
import com.charmnight.linkgraph.semantic.outcome.FlowchartViewDocument
import com.charmnight.linkgraph.semantic.outcome.ResourceRelationViewDocument
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession
import com.charmnight.linkgraph.workbench.QaRequestRecoveryState
import com.charmnight.linkgraph.workbench.StageEligibilityDecision
import com.charmnight.linkgraph.workbench.AssistantSessionState
import com.charmnight.linkgraph.workbench.AssistantResultStore

/**
 * 草稿补丁撤销所需的快照信息。
 *
 * 在用户点击「应用补丁」前，先把当前图谱完整文档缓存起来，并保留对应预览，
 * 这样后续若需要回滚到应用前的状态，可一次性恢复视图与数据。
 */
data class DraftPatchUndoState(
    // 应用补丁之前的图谱文档快照，用于撤销时整体还原
    val graphBeforeApply: GraphDocument,
    // 触发应用的补丁预览，便于撤销后重新展示给用户
    val patchPreview: GraphPatch? = null,
)

/** 复用应用层对图谱场景标识的定义，避免在 UI 层重复维护一份场景枚举。 */
typealias GraphSceneId = com.charmnight.linkgraph.application.model.GraphSceneId

/** 调用展开上下文过滤模式。当前默认只沿活动阅读链提供 full evidence。 */
enum class InvocationExpansionContextMode {
    ACTIVE_CHAIN,
}

/** 调用展开子状态快照，用于后续恢复父块重新打开后的子块状态。 */
data class ChildInvocationExpansionState(
    val activeExpansionId: String? = null,
    val activeExpansionPath: List<String> = emptyList(),
    val collapsedExpansionIds: Set<String> = emptySet(),
    val activeSiblingByParentContext: Map<String, String> = emptyMap(),
)

/** 流程图调用展开的 UI/session 状态；不属于语义图 metadata。 */
data class InvocationExpansionSceneState(
    val activeExpansionId: String? = null,
    val activeExpansionPath: List<String> = emptyList(),
    val collapsedExpansionIds: Set<String> = emptySet(),
    val activeSiblingByParentContext: Map<String, String> = emptyMap(),
    val blockPositions: Map<String, GraphLayoutPosition> = emptyMap(),
    val lastChildStateByExpansionId: Map<String, ChildInvocationExpansionState> = emptyMap(),
    val contextMode: InvocationExpansionContextMode = InvocationExpansionContextMode.ACTIVE_CHAIN,
)

/**
 * 单个图谱场景（如事实图谱、工作台等）的交互状态集合。
 *
 * 跟踪当前选中的节点、布局信息以及折叠状态，作为不同场景之间相互独立、
 * 切换时可恢复的临时态。
 */
data class GraphSceneState(
    // 当前场景下被选中的节点 ID，用于高亮及上下文操作
    val selectedNodeId: String? = null,
    // 视图锚点节点 ID，常用于添加节点时确定相对位置
    val anchorNodeId: String? = null,
    // 节点布局状态，包含位置、缩放等布局引擎需要的输入
    val layoutState: GraphLayoutState = GraphLayoutState(),
    // 布局版本号，每次布局变化递增，便于前端判断是否需要重排
    val layoutRevision: Long = 0,
    // 已折叠的节点 ID 集合，记录用户主动收起子结构的节点
    val collapsedNodeIds: Set<String> = emptySet(),
    // 调用展开折叠/激活状态，独立于普通节点折叠
    val invocationExpansionState: InvocationExpansionSceneState = InvocationExpansionSceneState(),
)

/** 为所有已知的图谱场景生成初始状态映射，确保新会话启动时每个场景都有可用的默认态。 */
internal fun defaultGraphSceneStates(): Map<GraphSceneId, GraphSceneState> = GraphSceneId.entries.associateWith { GraphSceneState() }

/** 为所有索引型图谱视图初始化异步请求状态映射，避免首次访问时出现空状态导致的请求丢失。 */
internal fun defaultIndexedGraphRequestStates(): Map<IndexedGraphView, AsyncRequestState> =
    IndexedGraphView.entries.associateWith { AsyncRequestState() }

/** 将分析展示模式映射为对应的工作台场景标识，用于在不同分析视角之间切换路由。 */
fun AnalysisDisplayMode.toWorkspaceSceneId(): GraphSceneId = toApplicationWorkspaceSceneId()

/** 反向映射：当工作台进入某个场景时，推导其对应的分析展示模式，方便 UI 同步状态。 */
fun GraphSceneId.toAnalysisDisplayMode(): AnalysisDisplayMode? = toApplicationAnalysisDisplayMode()

/**
 * 图谱编辑器整体状态的扁平快照。
 *
 * 集中维护工作台、事实图谱、代码生成、审阅等子领域所需的全部信息，
 * 供前端通过序列化方式同步，是 UI 与后端之间传递的根状态对象。
 */
data class GraphEditorStateSnapshot(
    // 来自语义分析的权威事实图谱，作为工作台改动的对比基线
    val semanticFactGraph: GraphDocument = GraphDocument(),
    // 工作台的基础图谱（不含未保存的草稿改动），用于计算 diff 与回退
    val workspaceBaseGraph: GraphDocument = GraphDocument(),
    // 工作台当前实际呈现的图谱，可能叠加了未确认的草稿修改
    val workspaceGraph: GraphDocument = GraphDocument(),
    // 设计基线图谱，用于和实际实现比对，呈现「设计 vs 实现」差异
    val designBaselineGraph: GraphDocument? = null,
    // 用户标记为可信的导航节点，用于在跳转和提示中优先选择
    val trustedNavigationNodes: Map<String, GraphNode> = emptyMap(),
    // 事实图谱的展示视图，包含布局与展示相关元信息
    val factGraphView: FactGraphViewDocument = FactGraphViewDocument(),
    // 流程图视图，描述系统调用或控制流的可视化结果
    val flowchartView: FlowchartViewDocument = FlowchartViewDocument(),
    // 资源关系视图，呈现模块或文件之间的依赖与引用关系
    val resourceRelationView: ResourceRelationViewDocument = ResourceRelationViewDocument(),
    // 架构图谱视图结果，描述系统组件层级的拓扑结构
    val architectureGraphView: ArchitectureGraphResult = ArchitectureGraphResult(),
    // 类图视图结果，呈现类与类之间的继承、组合等关系
    val classDiagramView: ClassDiagramResult = ClassDiagramResult(),
    // 审阅图谱视图结果，用于在评审阶段展示被审查的图谱结构
    val reviewGraphView: ReviewGraphResult = ReviewGraphResult(),
    // 各索引型视图的异步请求状态，用于跟踪加载/错误情况
    val indexedGraphRequestStates: Map<IndexedGraphView, AsyncRequestState> = defaultIndexedGraphRequestStates(),
    // 当前选择的分析展示模式，决定主视图展示哪类图谱
    val analysisDisplayMode: AnalysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
    // 当前激活的工作台场景标识
    val currentSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    // 进入当前场景之前所在的工作台场景，用于「返回」操作
    val previousWorkspaceSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    // 各场景独立的交互状态集合，确保切换场景时本地态得以保留
    val sceneStates: Map<GraphSceneId, GraphSceneState> = defaultGraphSceneStates(),
    // 草稿工作台状态，记录草稿补丁流转过程中的中间态
    val draftWorkbenchState: DraftWorkbenchState = DraftWorkbenchState(),
    // 当前待应用的草稿补丁预览，呈现给用户后再决定是否落库
    val draftPatchPreview: GraphPatch? = null,
    // 应用补丁前的撤销快照，用于一键回退
    val draftPatchUndoState: DraftPatchUndoState? = null,
    // 上一次应用补丁的结果，便于展示成功/失败提示
    val lastDraftPatchApplyResult: DraftPatchApplyResult? = null,
    // QA 模块的最新检查结果
    val qaResult: GraphPatchResult? = null,
    // QA 请求的异步状态
    val qaRequestState: AsyncRequestState = AsyncRequestState(),
    // QA 请求失败时的恢复态，记录重试或回退相关信息
    val qaRequestRecoveryState: QaRequestRecoveryState = QaRequestRecoveryState(),
    // 按会话分组保存的运行时产物摘要，用于在 UI 中回顾历史
    val runtimeArtifactSummaries: Map<String, List<RuntimeArtifactSummary>> = emptyMap(),
    // Diff 审阅阶段的最新结果
    val diffReviewResult: GraphPatchResult? = null,
    // Diff 审阅请求的异步状态
    val diffReviewRequestState: AsyncRequestState = AsyncRequestState(),
    // 图谱美化阶段的结果
    val graphBeautificationResult: GraphBeautificationResult? = null,
    // 图谱美化请求的异步状态
    val graphBeautificationRequestState: AsyncRequestState = AsyncRequestState(),
    // 当前 diff 结果，描述与基线之间的结构差异
    val diff: GraphDiff? = null,
    // 用于在 UI 上单独展示的 diff 图谱
    val diffGraph: GraphDocument? = null,
    // 上一次产生图谱数据的来源标记（如索引、生成、导入等）
    val lastGraphSource: String? = null,
    // 前端入口 URL，供外部工具调用或调试使用
    val frontendEntryUrl: String? = null,
    // 用户当前选中的方法签名，用于上下文相关的图谱构建
    val selectedMethodSignature: String? = null,
    // 最近一次导入的 Mermaid 文本，便于复用或校验
    val importedMermaid: String? = null,
    // 最近一次导出的 Mermaid 文本，用于再次分享或归档
    val exportedMermaid: String? = null,
    // Mermaid 解析阶段暴露的问题清单
    val mermaidIssues: List<MermaidIssue> = emptyList(),
    // 同步预览项列表，展示待同步到代码或其他系统的变更
    val syncPreviewItems: List<SyncPreviewItem> = emptyList(),
    // 草稿版本号，每次草稿变更递增以驱动 UI 刷新
    val draftVersion: Long = 0,
    // 当前生成的方案（Generation Plan），描述将要如何改写图谱
    val generationPlan: GenerationPlan? = null,
    // 方案草稿版本号，用于追踪方案演进
    val generationPlanDraftVersion: Long? = null,
    // 生成方案请求的异步状态
    val generationPlanRequestState: AsyncRequestState = AsyncRequestState(),
    // 草稿校验结果，记录可应用性等校验信息
    val draftValidationState: DraftValidationState? = null,
    // 方案讨论会话，记录用户与助手围绕方案的来回交互
    val generationPlanDiscussionSession: GenerationPlanDiscussionSession? = null,
    // 方案讨论请求的异步状态
    val generationPlanDiscussionRequestState: AsyncRequestState = AsyncRequestState(),
    // 已生成的代码草稿列表
    val generatedCodeDrafts: List<GeneratedCodeDraft> = emptyList(),
    // 代码草稿版本号
    val generatedCodeDraftVersion: Long? = null,
    // 代码草稿在生成阶段产生的告警信息
    val generatedCodeDraftWarnings: List<String> = emptyList(),
    // 代码草稿的来源标记（缓存命中或新调用）
    val generatedCodeDraftSource: LlmResultSource? = null,
    // 生成代码草稿所用的提示词预览，便于排查与回放
    val generatedCodeDraftPromptPreview: String? = null,
    // 代码草稿写入文件系统后的报告，包含写入路径与统计
    val generatedCodeDraftWriteReport: GeneratedCodeDraftWriteReport? = null,
    // 代码草稿请求的异步状态
    val codeDraftRequestState: AsyncRequestState = AsyncRequestState(),
    // 阶段准入判定，决定是否允许进入代码生成阶段
    val codeEligibilityDecision: StageEligibilityDecision? = null,
    // 源码导航状态，记录从图谱跳转到源码的过程
    val sourceNavigationState: SourceNavigationState = SourceNavigationState(),
    // 是否需要请求同步预览的标记
    val syncPreviewRequested: Boolean = false,
    // 是否需要打开工具窗口的标记
    val toolWindowOpenRequested: Boolean = false,
    // 工作台图谱是否存在未保存改动的脏标记
    val workingGraphDirty: Boolean = false,
    // 语义图谱版本号，反映事实图谱更新次数
    val semanticRevision: Long = 0,
    // 工作台图谱版本号，反映工作台改动的累计次数
    val workspaceRevision: Long = 0,
    // 快照版本号，用于前后端增量同步时的一致性校验
    val snapshotRevision: Long = 0,
    // 给用户的操作反馈，用于展示轻量的提示信息
    val operationFeedback: OperationFeedback? = null,
    // 最近一次图谱编辑事务的记录，便于追溯改动来源
    val lastGraphEditTransaction: GraphEditTransaction? = null,
    // 最近一次被拒绝的编辑请求，用于向用户解释未生效的原因
    val lastGraphEditRejection: GraphEditRejected? = null,
    // 助手会话状态，维护对话上下文与权限
    val assistantSessionState: AssistantSessionState = AssistantSessionState(sessionId = "assistant-session"),
    // 助手结果存储，沉淀历次产物以便查询
    val assistantResultStore: AssistantResultStore = AssistantResultStore(),
    // 最近一次消息类型，用于前端做样式与交互分发
    val lastMessageType: String? = null,
) {
    /** 获取指定场景的交互状态，若不存在则返回空状态，避免空指针。 */
    fun sceneState(sceneId: GraphSceneId): GraphSceneState = sceneStates[sceneId] ?: GraphSceneState()

    /** 获取当前激活场景的交互状态，便于 UI 渲染时直接读取。 */
    fun currentSceneState(): GraphSceneState = sceneState(currentSceneId)
}

/**
 * 工作台领域的局部状态。
 *
 * 抽取自总快照中与「工作台图谱、基线、diff、同步预览」相关的字段，
 * 便于在该子领域内独立传递与处理，减少全量快照耦合。
 */
data class WorkspaceState(
    // 来自语义分析的权威事实图谱
    val semanticFactGraph: GraphDocument = GraphDocument(),
    // 工作台基础图谱，作为 diff/回退基线
    val workspaceBaseGraph: GraphDocument = GraphDocument(),
    // 工作台当前展示的图谱（含未保存改动）
    val workspaceGraph: GraphDocument = GraphDocument(),
    // 设计基线图谱，可选
    val designBaselineGraph: GraphDocument? = null,
    // 用户标记为可信的导航节点
    val trustedNavigationNodes: Map<String, GraphNode> = emptyMap(),
    // 最近导入的 Mermaid 文本
    val importedMermaid: String? = null,
    // 最近导出的 Mermaid 文本
    val exportedMermaid: String? = null,
    // Mermaid 解析阶段暴露的问题
    val mermaidIssues: List<MermaidIssue> = emptyList(),
    // 工作台与基线之间的 diff 结果
    val diff: GraphDiff? = null,
    // 用于单独展示 diff 的图谱
    val diffGraph: GraphDocument? = null,
    // 同步预览项列表
    val syncPreviewItems: List<SyncPreviewItem> = emptyList(),
    // 当前选中的方法签名
    val selectedMethodSignature: String? = null,
    // 工作台脏标记
    val workingGraphDirty: Boolean = false,
    // 语义图谱版本号
    val semanticRevision: Long = 0,
    // 工作台版本号
    val workspaceRevision: Long = 0,
    // 最近一次图谱编辑事务
    val lastGraphEditTransaction: GraphEditTransaction? = null,
    // 最近一次被拒绝的编辑请求
    val lastGraphEditRejection: GraphEditRejected? = null,
)

/**
 * 图谱视图相关的局部状态。
 *
 * 汇总各种分析视图的产物及当前展示模式、场景切换等元信息，
 * 供视图层订阅、刷新。
 */
data class GraphViewsState(
    // 事实图谱视图
    val factGraphView: FactGraphViewDocument = FactGraphViewDocument(),
    // 流程图视图
    val flowchartView: FlowchartViewDocument = FlowchartViewDocument(),
    // 资源关系视图
    val resourceRelationView: ResourceRelationViewDocument = ResourceRelationViewDocument(),
    // 架构图谱视图
    val architectureGraphView: ArchitectureGraphResult = ArchitectureGraphResult(),
    // 类图视图
    val classDiagramView: ClassDiagramResult = ClassDiagramResult(),
    // 审阅图谱视图
    val reviewGraphView: ReviewGraphResult = ReviewGraphResult(),
    // 各索引型视图的异步请求状态
    val indexedGraphRequestStates: Map<IndexedGraphView, AsyncRequestState> = defaultIndexedGraphRequestStates(),
    // 当前分析展示模式
    val analysisDisplayMode: AnalysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
    // 当前激活场景
    val currentSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    // 上一个工作台场景，用于返回
    val previousWorkspaceSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    // 各场景独立的交互态
    val sceneStates: Map<GraphSceneId, GraphSceneState> = defaultGraphSceneStates(),
)

/**
 * 助手领域相关的局部状态。
 *
 * 维护助手会话上下文、结果缓存以及运行时产物摘要，
 * 让助手能力可以与编辑器其他部分解耦地演化。
 */
data class AssistantState(
    // 助手会话状态
    val sessionState: AssistantSessionState = AssistantSessionState(sessionId = "assistant-session"),
    // 助手结果存储
    val resultStore: AssistantResultStore = AssistantResultStore(),
    // 按会话分组的运行时产物摘要
    val runtimeArtifactSummaries: Map<String, List<RuntimeArtifactSummary>> = emptyMap(),
)

/**
 * 审阅相关流程的局部状态。
 *
 * 包含 QA、Diff 审阅、图谱美化等多个审阅阶段的结果与异步状态，
 * 用于在审阅工作流中协调 UI 反馈。
 */
data class ReviewState(
    // QA 检查结果
    val qaResult: GraphPatchResult? = null,
    // QA 请求异步状态
    val qaRequestState: AsyncRequestState = AsyncRequestState(),
    // QA 请求失败后的恢复态
    val qaRequestRecoveryState: QaRequestRecoveryState = QaRequestRecoveryState(),
    // Diff 审阅结果
    val diffReviewResult: GraphPatchResult? = null,
    // Diff 审阅异步状态
    val diffReviewRequestState: AsyncRequestState = AsyncRequestState(),
    // 图谱美化结果
    val graphBeautificationResult: GraphBeautificationResult? = null,
    // 图谱美化异步状态
    val graphBeautificationRequestState: AsyncRequestState = AsyncRequestState(),
)

/**
 * 代码/方案生成流程的局部状态。
 *
 * 涵盖草稿工作台、方案生成、方案讨论、代码草稿生成等阶段的全部中间态，
 * 用于驱动生成流水线的 UI 与流程。
 */
data class GenerationState(
    // 草稿工作台状态
    val draftWorkbenchState: DraftWorkbenchState = DraftWorkbenchState(),
    // 当前待应用补丁预览
    val draftPatchPreview: GraphPatch? = null,
    // 应用前撤销快照
    val draftPatchUndoState: DraftPatchUndoState? = null,
    // 最近一次补丁应用结果
    val lastDraftPatchApplyResult: DraftPatchApplyResult? = null,
    // 草稿版本号
    val draftVersion: Long = 0,
    // 当前生成方案
    val generationPlan: GenerationPlan? = null,
    // 方案草稿版本号
    val generationPlanDraftVersion: Long? = null,
    // 生成方案异步状态
    val generationPlanRequestState: AsyncRequestState = AsyncRequestState(),
    // 草稿校验状态
    val draftValidationState: DraftValidationState? = null,
    // 方案讨论会话
    val generationPlanDiscussionSession: GenerationPlanDiscussionSession? = null,
    // 方案讨论异步状态
    val generationPlanDiscussionRequestState: AsyncRequestState = AsyncRequestState(),
    // 已生成代码草稿列表
    val generatedCodeDrafts: List<GeneratedCodeDraft> = emptyList(),
    // 代码草稿版本号
    val generatedCodeDraftVersion: Long? = null,
    // 代码草稿告警
    val generatedCodeDraftWarnings: List<String> = emptyList(),
    // 代码草稿来源
    val generatedCodeDraftSource: LlmResultSource? = null,
    // 代码草稿提示词预览
    val generatedCodeDraftPromptPreview: String? = null,
    // 代码草稿写入报告
    val generatedCodeDraftWriteReport: GeneratedCodeDraftWriteReport? = null,
    // 代码草稿异步状态
    val codeDraftRequestState: AsyncRequestState = AsyncRequestState(),
    // 阶段准入判定
    val codeEligibilityDecision: StageEligibilityDecision? = null,
)

/**
 * 导航/外部跳转相关的局部状态。
 *
 * 集中维护源码跳转以及工具窗口/同步预览的请求标记，
 * 便于导航类操作按需触发而不污染图谱状态。
 */
data class NavigationState(
    // 源码导航过程状态
    val sourceNavigationState: SourceNavigationState = SourceNavigationState(),
    // 是否需要触发同步预览
    val syncPreviewRequested: Boolean = false,
    // 是否需要打开工具窗口
    val toolWindowOpenRequested: Boolean = false,
)

/**
 * 传输层的局部状态。
 *
 * 承载前后端通信过程中产生的版本号、用户反馈以及元信息，
 * 用于驱动 UI 提示与一致性校验。
 */
data class TransportState(
    // 快照版本号
    val snapshotRevision: Long = 0,
    // 用户操作反馈
    val operationFeedback: OperationFeedback? = null,
    // 最近一次消息类型
    val lastMessageType: String? = null,
    // 最近一次图谱来源
    val lastGraphSource: String? = null,
    // 前端入口 URL
    val frontendEntryUrl: String? = null,
)

/**
 * 图谱编辑器按领域拆分的聚合状态。
 *
 * 把扁平的快照拆成多个领域子状态，让关注点分离的模块各自订阅，
 * 避免任何一处改动都触发整体刷新。
 */
data class GraphEditorDomainStates(
    // 工作台领域
    val workspace: WorkspaceState = WorkspaceState(),
    // 图谱视图领域
    val graphViews: GraphViewsState = GraphViewsState(),
    // 助手领域
    val assistant: AssistantState = AssistantState(),
    // 审阅领域
    val review: ReviewState = ReviewState(),
    // 生成领域
    val generation: GenerationState = GenerationState(),
    // 导航领域
    val navigation: NavigationState = NavigationState(),
    // 传输领域
    val transport: TransportState = TransportState(),
)

/** 把扁平快照转换为按领域划分的结构化状态，方便领域模块按需消费。 */
fun GraphEditorStateSnapshot.domainStates(): GraphEditorDomainStates =
    GraphEditorDomainStates(
        workspace = WorkspaceState(
            semanticFactGraph = semanticFactGraph,
            workspaceBaseGraph = workspaceBaseGraph,
            workspaceGraph = workspaceGraph,
            designBaselineGraph = designBaselineGraph,
            trustedNavigationNodes = trustedNavigationNodes,
            importedMermaid = importedMermaid,
            exportedMermaid = exportedMermaid,
            mermaidIssues = mermaidIssues,
            diff = diff,
            diffGraph = diffGraph,
            syncPreviewItems = syncPreviewItems,
            selectedMethodSignature = selectedMethodSignature,
            workingGraphDirty = workingGraphDirty,
            semanticRevision = semanticRevision,
            workspaceRevision = workspaceRevision,
            lastGraphEditTransaction = lastGraphEditTransaction,
            lastGraphEditRejection = lastGraphEditRejection,
        ),
        graphViews = GraphViewsState(
            factGraphView = factGraphView,
            flowchartView = flowchartView,
            resourceRelationView = resourceRelationView,
            architectureGraphView = architectureGraphView,
            classDiagramView = classDiagramView,
            reviewGraphView = reviewGraphView,
            indexedGraphRequestStates = indexedGraphRequestStates,
            analysisDisplayMode = analysisDisplayMode,
            currentSceneId = currentSceneId,
            previousWorkspaceSceneId = previousWorkspaceSceneId,
            sceneStates = sceneStates,
        ),
        assistant = AssistantState(
            sessionState = assistantSessionState,
            resultStore = assistantResultStore,
            runtimeArtifactSummaries = runtimeArtifactSummaries,
        ),
        review = ReviewState(
            qaResult = qaResult,
            qaRequestState = qaRequestState,
            qaRequestRecoveryState = qaRequestRecoveryState,
            diffReviewResult = diffReviewResult,
            diffReviewRequestState = diffReviewRequestState,
            graphBeautificationResult = graphBeautificationResult,
            graphBeautificationRequestState = graphBeautificationRequestState,
        ),
        generation = GenerationState(
            draftWorkbenchState = draftWorkbenchState,
            draftPatchPreview = draftPatchPreview,
            draftPatchUndoState = draftPatchUndoState,
            lastDraftPatchApplyResult = lastDraftPatchApplyResult,
            draftVersion = draftVersion,
            generationPlan = generationPlan,
            generationPlanDraftVersion = generationPlanDraftVersion,
            generationPlanRequestState = generationPlanRequestState,
            draftValidationState = draftValidationState,
            generationPlanDiscussionSession = generationPlanDiscussionSession,
            generationPlanDiscussionRequestState = generationPlanDiscussionRequestState,
            generatedCodeDrafts = generatedCodeDrafts,
            generatedCodeDraftVersion = generatedCodeDraftVersion,
            generatedCodeDraftWarnings = generatedCodeDraftWarnings,
            generatedCodeDraftSource = generatedCodeDraftSource,
            generatedCodeDraftPromptPreview = generatedCodeDraftPromptPreview,
            generatedCodeDraftWriteReport = generatedCodeDraftWriteReport,
            codeDraftRequestState = codeDraftRequestState,
            codeEligibilityDecision = codeEligibilityDecision,
        ),
        navigation = NavigationState(
            sourceNavigationState = sourceNavigationState,
            syncPreviewRequested = syncPreviewRequested,
            toolWindowOpenRequested = toolWindowOpenRequested,
        ),
        transport = TransportState(
            snapshotRevision = snapshotRevision,
            operationFeedback = operationFeedback,
            lastMessageType = lastMessageType,
            lastGraphSource = lastGraphSource,
            frontendEntryUrl = frontendEntryUrl,
        ),
    )

/**
 * 运行时产物的可展示摘要。
 *
 * 把 Agent 运行过程中的产物精简成 UI 友好的字段，
 * 用于在历史列表、对话气泡等位置呈现给用户。
 */
data class RuntimeArtifactSummary(
    // 产物唯一标识，用于回查原始数据
    val artifactId: String,
    // 产物类型（如 patch、plan、code 等），驱动 UI 分发渲染
    val artifactType: String,
    // 给用户看的标题
    val title: String,
    // 可选的描述信息，呈现产物用途与要点
    val description: String? = null,
) {
    companion object {
        /** 把 Agent 运行层的产物摘要转换为 UI 层使用的可展示摘要。 */
        fun from(summary: AgentRunArtifactSummary): RuntimeArtifactSummary {
            return RuntimeArtifactSummary(
                artifactId = summary.artifactId,
                artifactType = summary.artifactType,
                title = summary.title,
                description = summary.description,
            )
        }
    }
}

/**
 * 源码导航过程的运行态。
 *
 * 记录从图谱节点跳转到源码位置所需的全部信息，
 * 包括目标节点、阶段进度以及最终结果，便于 UI 反馈进度与失败原因。
 */
data class SourceNavigationState(
    // 发起跳转的图谱节点 ID
    val nodeId: String? = null,
    // 当前导航所处的阶段
    val phase: SourceNavigationPhase = SourceNavigationPhase.IDLE,
    // 导航结果（仅成功时存在）
    val result: SourceNavigationResult? = null,
    // 目标文件路径
    val targetPath: String? = null,
    // 目标行号（1-based）
    val line: Int? = null,
    // 目标列号（1-based）
    val column: Int? = null,
    // 失败时的错误信息
    val errorMessage: String? = null,
)

/** 源码导航生命周期内可能出现的阶段，用于驱动 UI 状态切换。 */
enum class SourceNavigationPhase {
    // 空闲，没有进行中的导航
    IDLE,
    // 正在解析与定位目标
    RUNNING,
    // 已成功打开目标位置
    SUCCEEDED,
    // 在源码中找不到对应目标
    NOT_FOUND,
    // 出现异常或失败
    FAILED,
}

/** 源码导航结果分类，便于在日志/统计中区分不同成功路径。 */
enum class SourceNavigationResult {
    // 已成功打开目标位置
    OPENED,
}

/** 一次给用户的操作反馈，用于在工具栏或状态栏中以合适级别展示提示。 */
data class OperationFeedback(
    // 反馈级别（如 info、warning、error），决定展示样式
    val level: ApplicationFeedbackLevel,
    // 反馈文本内容
    val message: String,
)
