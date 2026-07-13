package com.charmnight.linkgraph.application.event

import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.model.ApplicationSnapshot
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.model.GraphEditTransaction
import com.charmnight.linkgraph.application.model.GraphEditRejected as GraphEditRejectedPayload
import com.charmnight.linkgraph.application.model.GraphLayoutPosition
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.result.BeautificationCompletedResult
import com.charmnight.linkgraph.application.result.BeautificationFailedResult
import com.charmnight.linkgraph.application.result.CodeDraftWriteResult
import com.charmnight.linkgraph.application.result.DiffReviewCompletedResult
import com.charmnight.linkgraph.application.result.DiffReviewFailedResult
import com.charmnight.linkgraph.application.result.GeneratedCodeDraftsResult
import com.charmnight.linkgraph.application.result.GenerationDiscussionResult
import com.charmnight.linkgraph.application.result.GenerationPlanResult
import com.charmnight.linkgraph.application.result.GenerationRequestFailureResult
import com.charmnight.linkgraph.application.result.GenerationRequestScene
import com.charmnight.linkgraph.application.result.GenerationRequestStartedResult
import com.charmnight.linkgraph.application.result.QaCompletedResult
import com.charmnight.linkgraph.application.result.QaFailedResult
import com.charmnight.linkgraph.application.result.ReviewRequestScene
import com.charmnight.linkgraph.application.result.ReviewRequestStartedResult
import com.charmnight.linkgraph.application.usecase.ApplyDraftPatchUseCaseResult
import com.charmnight.linkgraph.application.usecase.ClearDraftPatchPreviewUseCaseResult
import com.charmnight.linkgraph.application.usecase.ConfirmDraftChangeUseCaseResult
import com.charmnight.linkgraph.application.usecase.PreviewDraftPatchUseCaseResult
import com.charmnight.linkgraph.application.usecase.RestoreDraftPatchPreviewUseCaseResult
import com.charmnight.linkgraph.application.usecase.UndoDraftPatchApplyUseCaseResult
import com.charmnight.linkgraph.application.usecase.UnconfirmDraftChangeUseCaseResult
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.review.ReviewGraphResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.StageEligibilityDecision

/**
 * 图谱编辑器在应用层广播的领域事件集合。所有事件以密封接口的形式聚合，
 * 供 UI、状态机、状态变更上下文等订阅者统一消费。
 */
sealed interface GraphEditorApplicationEvent {
    /** 工作区图谱首次加载完成，携带图谱内容与加载来源 */
    data class WorkspaceGraphLoaded(val graph: GraphDocument, val source: String) : GraphEditorApplicationEvent
    /** 工作区图谱发生变化，记录选中方法签名、是否保留草稿撤销栈等关键状态 */
    data class WorkspaceGraphChanged(
        val graph: GraphDocument,
        val selectedMethodSignature: String?,
        val preserveDraftPatchUndo: Boolean,
        val workingGraphDirty: Boolean,
        val graphEditTransaction: GraphEditTransaction? = null,
    ) : GraphEditorApplicationEvent
    /** 一次图谱编辑请求被拒绝，携带拒绝原因详情 */
    data class GraphEditRejected(val rejection: GraphEditRejectedPayload) : GraphEditorApplicationEvent
    /** 工作区节点的布局位置发生变更 */
    data class WorkspaceLayoutChanged(val positions: Map<String, GraphLayoutPosition>) : GraphEditorApplicationEvent
    /** Mermaid 文本导入完成，包含原始文本、转换后的图谱以及解析期间发现的问题 */
    data class MermaidImported(val mermaid: String, val graph: GraphDocument, val issues: List<MermaidIssue>) : GraphEditorApplicationEvent
    /** Mermaid 文本导出完成，包含导出内容和是否已复制到剪贴板的标志 */
    data class MermaidExported(val exported: String, val copiedToClipboard: Boolean) : GraphEditorApplicationEvent
    /** 进入 diff 模式，携带基础图谱与差异结构 */
    data class DiffModeShown(val graph: GraphDocument, val diff: GraphDiff) : GraphEditorApplicationEvent
    /** 同步预览准备就绪，包含待同步条目列表 */
    data class SyncPreviewReady(val items: List<SyncPreviewItem>) : GraphEditorApplicationEvent

    /** 用于在状态栏向用户展示的反馈消息，可控制是否保留先前状态类型 */
    data class Feedback(
        val level: ApplicationFeedbackLevel,
        val message: String,
        val preservePreviousStatusKind: Boolean = false,
    ) : GraphEditorApplicationEvent
    /** 分析结果的展示模式（如普通视图/diff 视图）发生变化 */
    data class AnalysisDisplayModeChanged(val displayMode: AnalysisDisplayMode) : GraphEditorApplicationEvent
    /** 已有调用展开被重新打开，仅改变当前场景的展开可见状态。 */
    data class InvocationExpansionOpened(val expansionId: String) : GraphEditorApplicationEvent
    /** 当前选中的方法签名变化 */
    data class SelectedMethodChanged(val signature: String) : GraphEditorApplicationEvent
    /** 分析结果加载完成，携带分析产物与加载来源 */
    data class AnalysisOutcomeLoaded(val outcome: AnalysisOutcome, val source: String) : GraphEditorApplicationEvent
    /** 索引图谱查询请求开始，包含视图标识、请求状态以及状态栏消息 */
    data class IndexedGraphRequestStarted(
        val view: IndexedGraphView,
        val requestState: AsyncRequestState,
        val statusMessage: String,
    ) : GraphEditorApplicationEvent
    /** 索引图谱查询请求失败 */
    data class IndexedGraphRequestFailed(
        val view: IndexedGraphView,
        val requestState: AsyncRequestState,
        val statusMessage: String,
    ) : GraphEditorApplicationEvent
    /** 架构图谱加载完成 */
    data class ArchitectureGraphLoaded(
        val view: ArchitectureGraphResult,
        val requestState: AsyncRequestState,
        val statusMessage: String,
    ) : GraphEditorApplicationEvent
    /** 类图加载完成 */
    data class ClassDiagramLoaded(
        val view: ClassDiagramResult,
        val requestState: AsyncRequestState,
        val statusMessage: String,
    ) : GraphEditorApplicationEvent
    /** 复核视图加载完成 */
    data class ReviewGraphLoaded(
        val view: ReviewGraphResult,
        val requestState: AsyncRequestState,
        val statusMessage: String,
    ) : GraphEditorApplicationEvent
    /** 调试图谱加载完成，附带被选中方法的签名以及一段总结说明 */
    data class DebugGraphLoaded(
        val graph: GraphDocument,
        val source: String,
        val selectedMethodSignature: String,
        val summary: String,
    ) : GraphEditorApplicationEvent
    /** 向图谱中新增了资源节点，记录被选中的节点 ID 与状态消息 */
    data class ResourceNodeAdded(val selectedNodeId: String, val statusMessage: String) : GraphEditorApplicationEvent

    /** 收到跳转请求，携带目标节点 ID */
    data class NavigationRequested(val nodeId: String) : GraphEditorApplicationEvent
    /** 跳转动作即将开始，携带页面标题 */
    data class NavigationStarting(val title: String) : GraphEditorApplicationEvent
    /** 跳转已成功打开，包含目标节点、文件路径、行列号等信息 */
    data class NavigationOpened(
        val nodeId: String,
        val targetPath: String,
        val line: Int?,
        val column: Int?,
        val title: String,
    ) : GraphEditorApplicationEvent
    /** 跳转目标节点未找到，携带节点 ID 与显示标签 */
    data class NavigationNotFound(val nodeId: String, val label: String) : GraphEditorApplicationEvent
    /** 跳转动作失败，携带错误信息、状态栏消息及反馈级别 */
    data class NavigationFailed(
        val nodeId: String,
        val message: String,
        val statusMessage: String = "打开源码失败：$message",
        val level: ApplicationFeedbackLevel = ApplicationFeedbackLevel.ERROR,
    ) : GraphEditorApplicationEvent
    /** 设置面板已打开（无载荷事件） */
    data object SettingsOpened : GraphEditorApplicationEvent
    /** 打开设置面板失败，携带失败原因 */
    data class SettingsOpenFailed(val message: String) : GraphEditorApplicationEvent

    /** 草稿补丁预览准备就绪 */
    data class DraftPatchPreviewReady(val result: PreviewDraftPatchUseCaseResult) : GraphEditorApplicationEvent
    /** 草稿补丁已应用 */
    data class DraftPatchApplied(val result: ApplyDraftPatchUseCaseResult) : GraphEditorApplicationEvent
    /** 草稿补丁预览已清除 */
    data class DraftPatchCleared(val result: ClearDraftPatchPreviewUseCaseResult) : GraphEditorApplicationEvent
    /** 草稿补丁预览已恢复 */
    data class DraftPatchRestored(val result: RestoreDraftPatchPreviewUseCaseResult) : GraphEditorApplicationEvent
    /** 草稿补丁应用已撤销 */
    data class DraftPatchUndone(val result: UndoDraftPatchApplyUseCaseResult) : GraphEditorApplicationEvent
    /** 草稿变更项已确认，附带确认时的应用快照 */
    data class DraftChangeConfirmed(
        val result: ConfirmDraftChangeUseCaseResult,
        val baseSnapshot: ApplicationSnapshot,
    ) : GraphEditorApplicationEvent
    /** 草稿变更项确认已撤销 */
    data class DraftChangeUnconfirmed(
        val result: UnconfirmDraftChangeUseCaseResult,
        val baseSnapshot: ApplicationSnapshot,
    ) : GraphEditorApplicationEvent

    /** 生成阶段计划已就绪 */
    data class GenerationPlanReady(val result: GenerationPlanResult) : GraphEditorApplicationEvent
    /** 草稿与代码生成阶段可用性已重新计算 */
    data class DraftAndCodeEligibilityUpdated(
        val draftValidationState: DraftValidationState?,
        val codeEligibilityDecision: StageEligibilityDecision?,
    ) : GraphEditorApplicationEvent
    /** 一次代码生成请求已开始 */
    data class GenerationRequestStarted(val result: GenerationRequestStartedResult) : GraphEditorApplicationEvent
    /** 流式生成过程中的中间预览事件，携带场景、请求 ID 与当前预览文本 */
    data class GenerationStreamingPreview(
        val scene: GenerationRequestScene,
        val requestId: Long,
        val previewText: String,
        val finalizingStructuredResult: Boolean,
    ) : GraphEditorApplicationEvent
    /** 生成计划请求失败 */
    data class GenerationPlanRequestFailed(val result: GenerationRequestFailureResult) : GraphEditorApplicationEvent
    /** 生成讨论结果已就绪 */
    data class GenerationDiscussionReady(val result: GenerationDiscussionResult) : GraphEditorApplicationEvent
    /** 生成讨论请求失败 */
    data class GenerationDiscussionFailed(val result: GenerationRequestFailureResult) : GraphEditorApplicationEvent
    /** 代码草稿落盘写入结果上报 */
    data class CodeDraftWriteReported(val result: CodeDraftWriteResult) : GraphEditorApplicationEvent
    /** 生成阶段向用户展示的反馈消息 */
    data class GenerationFeedback(
        val level: ApplicationFeedbackLevel,
        val message: String,
        val preservePreviousStatusKind: Boolean = false,
    ) : GraphEditorApplicationEvent
    /** 合并写入产物上报，携带写入报告、反馈级别与展示消息 */
    data class MergeWriteReported(
        val report: GeneratedCodeDraftWriteReport,
        val level: ApplicationFeedbackLevel,
        val message: String,
    ) : GraphEditorApplicationEvent
    /** 已生成的代码草稿列表准备就绪 */
    data class GeneratedCodeDraftsReady(val result: GeneratedCodeDraftsResult) : GraphEditorApplicationEvent
    /** 代码草稿请求失败 */
    data class CodeDraftRequestFailed(val result: GenerationRequestFailureResult) : GraphEditorApplicationEvent

    /** 问答流程完成 */
    data class QaCompleted(val result: QaCompletedResult) : GraphEditorApplicationEvent
    /** 复核请求已开始 */
    data class ReviewRequestStarted(val result: ReviewRequestStartedResult) : GraphEditorApplicationEvent
    /** 复核流程中的流式预览事件 */
    data class ReviewStreamingPreview(
        val scene: ReviewRequestScene,
        val requestId: Long,
        val previewText: String,
        val finalizingStructuredResult: Boolean,
    ) : GraphEditorApplicationEvent
    /** 问答流程失败 */
    data class QaFailed(val result: QaFailedResult) : GraphEditorApplicationEvent
    /** 差异复核完成 */
    data class DiffReviewCompleted(val result: DiffReviewCompletedResult) : GraphEditorApplicationEvent
    /** 差异复核失败 */
    data class DiffReviewFailed(val result: DiffReviewFailedResult) : GraphEditorApplicationEvent
    /** 美化处理完成 */
    data class BeautificationCompleted(val result: BeautificationCompletedResult) : GraphEditorApplicationEvent
    /** 美化处理失败 */
    data class BeautificationFailed(val result: BeautificationFailedResult) : GraphEditorApplicationEvent
}

/**
 * 应用事件的统一发送出口，所有事件都通过该接口向订阅者广播。
 * 使用函数式接口便于以 lambda 形式注入。
 */
fun interface GraphEditorApplicationEventSink {
    /**
     * 发送一个事件给所有订阅者。
     */
    fun emit(event: GraphEditorApplicationEvent)
}
