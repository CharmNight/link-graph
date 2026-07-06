package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.model.GraphEditRequestParseResult
import com.charmnight.linkgraph.agent.model.GraphBeautificationResult as GraphBeautificationPayload
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.workbench.RiskResolutionStatus
import com.charmnight.linkgraph.workbench.AssistantComposerTarget
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.StepGranularity

/**
 * 前后端之间约定的编辑器消息协议。
 * 一期只保留最小消息集合，保证导入、导出、diff、单节点编辑、代码草案写入都能串起来。
 */
sealed interface GraphEditorMessage {
    /**
     * 标识草稿补丁预览的来源。
     */
    enum class DraftPatchPreviewSource {
        /** 表示预览来自问答结果。 */
        QA,
        /** 表示预览来自差异评审。 */
        DIFF_REVIEW,
        /** 表示预览来自最近一次应用结果。 */
        LAST_APPLIED,
    }

    /**
     * 加载整张图到前端。
     */
    data class LoadGraph(
        /** 保存要加载的图。 */
        val graph: GraphDocument,
        /** 保存图来源描述。 */
        val source: String,
    ) : GraphEditorMessage

    /**
     * 请求导入 Mermaid 文本。
     */
    data class ImportMermaid(
        /** 保存 Mermaid 原文。 */
        val mermaid: String,
    ) : GraphEditorMessage

    /** 请求导出当前图为 Mermaid。 */
    data object ExportMermaid : GraphEditorMessage

    /** 请求切换到差异模式。 */
    data object ShowDiffMode : GraphEditorMessage

    /**
     * 通知前端当前选中的节点。
     */
    data class NodeSelected(
        /** 保存选中节点标识。 */
        val nodeId: String,
    ) : GraphEditorMessage

    /**
     * 请求把当前场景的结构编辑请求应用到权威工作区图。
     */
    data class ApplyGraphEditRequest(
        /** 解析结果：成功时 request 非空，失败时仅 issues；workflow 会根据 issues 是否为空决定走 apply 还是 reject。 */
        val parseResult: GraphEditRequestParseResult,
    ) : GraphEditorMessage

    /**
     * 通知前端布局已变更。
     */
    data class LayoutChanged(
        /** 保存节点布局位置映射。 */
        val positions: Map<String, GraphLayoutPosition>,
    ) : GraphEditorMessage

    /**
     * 通知后端前端桥接层已就绪。
     */
    data class FrontendReady(
        /** 保存前端最近一次已应用的修订号。 */
        val lastAppliedRevision: Long? = null,
    ) : GraphEditorMessage

    /**
     * 通知后端某个快照已被前端确认。
     */
    data class SnapshotAck(
        /** 保存已确认的修订号。 */
        val revision: Long,
    ) : GraphEditorMessage

    /**
     * 请求执行源码跳转。
     */
    data class RequestSourceNavigation(
        /** 保存目标节点标识。 */
        val nodeId: String,
    ) : GraphEditorMessage

    /**
     * 请求展开溢出摘要节点。
     */
    data class RequestExpandOverflowNode(
        /** 保存待展开节点标识。 */
        val nodeId: String,
    ) : GraphEditorMessage

    /**
     * 请求展开调用节点对应的目标方法。
     */
    data class RequestExpandInvocation(
        /** 保存调用节点标识。 */
        val nodeId: String,
        /** 保存前端发出请求时的 epoch millis，用于测量点击到后端处理的桥接耗时。 */
        val frontendRequestedAtMs: Long? = null,
    ) : GraphEditorMessage

    /**
     * 请求移除某次调用方法展开批次。
     */
    data class RequestRemoveInvocationExpansion(
        /** 保存展开批次标识。 */
        val expansionId: String,
    ) : GraphEditorMessage

    /** 请求折叠某个调用展开块；仅更新 UI/session 状态。 */
    data class CollapseInvocationExpansion(
        val expansionId: String,
    ) : GraphEditorMessage

    /** 请求打开某个折叠调用展开块；仅更新 UI/session 状态。 */
    data class OpenInvocationExpansion(
        val expansionId: String,
    ) : GraphEditorMessage

    /** 请求激活某个调用展开阅读路径；仅更新 UI/session 状态。 */
    data class ActivateInvocationExpansion(
        val expansionId: String,
    ) : GraphEditorMessage

    /** 请求计算同步预览。 */
    data object RequestSyncPreview : GraphEditorMessage

    /**
     * 请求执行统一 AI 工作台任务。
     */
    data class RequestAssistantTask(
        /** 保存用户触发的具体工作台动作。 */
        val actionId: AssistantActionId,
        /** 保存由具体动作派生的业务意图。 */
        val intent: AssistantIntent = actionId.toIntent(),
        /** 保存提交发生的视图场景。 */
        val sceneId: String? = null,
        /** 保存用户输入。 */
        val prompt: String,
        /** 保存选中的节点标识列表。 */
        val selectedNodeIds: List<String> = emptyList(),
        /** 保存选中的差异条目标识列表。 */
        val selectedDiffItemIds: List<String> = emptyList(),
        /** 保存统一输入框提交目标。 */
        val target: AssistantComposerTarget = AssistantComposerTarget.NewTask,
        /** 保存普通问答的显式模式选择。 */
        val mode: QaMode = QaMode.AUTO,
        /** 保存讲解粒度。 */
        val explanationGranularity: StepGranularity = StepGranularity.BUSINESS,
    ) : GraphEditorMessage

    /** 请求直接重试最近一次失败的问答。 */
    data object RetryLastQaRequest : GraphEditorMessage

    /**
     * 确认一条问答候选变更。
     */
    data class ConfirmQaCandidateChange(
        /** 保存待确认的候选变更标识。 */
        val changeId: String,
    ) : GraphEditorMessage

    /**
     * 取消一条已经确认的问答候选变更。
     */
    data class UnconfirmQaCandidateChange(
        /** 保存待取消确认的候选变更标识。 */
        val changeId: String,
    ) : GraphEditorMessage

    /**
     * 请求为风险线程写入人工决策。
     */
    data class ResolveInvestigationThread(
        /** 保存线程标识。 */
        val threadId: String,
        /** 保存人工决策状态。 */
        val resolutionStatus: RiskResolutionStatus,
        /** 保存可选备注。 */
        val note: String = "",
    ) : GraphEditorMessage

    /**
     * 返回图讲解结果。
     */
    data class GraphBeautificationResult(
        /** 保存讲解结果载荷。 */
        val result: GraphBeautificationPayload,
    ) : GraphEditorMessage

    /**
     * 请求应用草稿补丁预览。
     */
    data class ApplyDraftPatchPreview(
        /** 保存待应用的操作标识集合。 */
        val operationIds: Set<String>? = null,
    ) : GraphEditorMessage

    /** 请求清空草稿补丁预览。 */
    data object ClearDraftPatchPreview : GraphEditorMessage

    /**
     * 请求恢复指定来源的草稿补丁预览。
     */
    data class RestoreDraftPatchPreview(
        /** 保存预览来源。 */
        val source: DraftPatchPreviewSource,
    ) : GraphEditorMessage

    /** 请求撤销最近一次草稿补丁应用。 */
    data object UndoLastDraftPatchApply : GraphEditorMessage

    /** 请求生成代码草稿。 */
    data object RequestCodeDrafts : GraphEditorMessage

    /** 请求加载当前编辑器上下文图。 */
    data object RequestCurrentEditorContextGraph : GraphEditorMessage

    /**
     * 请求切换分析展示模式。
     */
    data class RequestAnalysisDisplayMode(
        /** 保存目标展示模式。 */
        val displayMode: AnalysisDisplayMode,
    ) : GraphEditorMessage

    /**
     * 请求加载 indexed 图视图。
     */
    data class RequestIndexedGraph(
        /** 保存完整 indexed 图请求。 */
        val request: IndexedGraphRequest,
    ) : GraphEditorMessage

    /** 请求打开设置页。 */
    data object OpenSettings : GraphEditorMessage

    /** 请求批量应用代码草稿。 */
    data object ApplyCodeDrafts : GraphEditorMessage

    /**
     * 请求应用单个代码草稿。
     */
    data class ApplySingleCodeDraft(
        /** 保存草稿标识。 */
        val draftId: String,
    ) : GraphEditorMessage

    /**
     * 请求打开指定代码草稿的原生 IDE diff。
     */
    data class OpenCodeDraftNativeDiff(
        /** 保存草稿标识。 */
        val draftId: String,
    ) : GraphEditorMessage

    /**
     * 请求跳转到草稿文件。
     */
    data class RequestDraftNavigation(
        /** 保存目标文件路径。 */
        val targetPath: String,
    ) : GraphEditorMessage
}
