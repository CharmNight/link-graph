package com.charmnight.linkgraph.application.command

import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.model.DraftPatchPreviewSource
import com.charmnight.linkgraph.application.model.GraphEditRequestParseResult
import com.charmnight.linkgraph.application.model.GraphLayoutPosition
import com.charmnight.linkgraph.diff.GraphDifferResult
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.subject.SubjectPreviewKind
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.workbench.AssistantComposerTarget
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.RiskResolutionStatus
import com.charmnight.linkgraph.workbench.StepGranularity

/**
 * 应用层命令总线的统一抽象。所有可被派发的请求（UI 交互、工作台动作、助理任务、补丁预览等）
 * 都被建模为该 sealed interface 的一个具体子类，并由对应的 CommandHandler 处理。
 *
 * 泛型 R 表示该命令的执行结果类型，供调用方按需同步等待或忽略结果。
 *
 * P4-1：command 层不再依赖 com.intellij.openapi.editor.Editor。
 * 主体（subject）/ 上下文（context）解析完全由 workflow 内部走 IntelliJ 平台当前焦点编辑器，
 * 不再暴露 editor 引用给上层——历史上 editor token 字段从未被读取，且 fbda452d 之后
 * SubjectGraphWorkflow 已移除 Editor 参数，token 是死字段，本类一并移除。
 */
internal sealed interface ApplicationCommand<out R> {
    /**
     * 解析当前活动编辑器所属的主体（类、资源、方法等）类型，用于在图谱视图等位置对相关节点做高亮或定位。
     * 平台当前焦点编辑器由 workflow 内部解析。
     */
    data object PreviewCurrentEditorSubjectKind : ApplicationCommand<SubjectPreviewKind?>

    /**
     * 把当前活动编辑器对应的上下文节点加入活动图谱，使后续图谱分析能纳入该上下文。
     * 返回是否成功添加。
     */
    data object AddCurrentEditorContextNode : ApplicationCommand<Boolean>

    /**
     * 基于当前活动编辑器加载上下文图谱（例如该文件相关的类图/调用关系）。
     * 平台当前焦点编辑器由 workflow 内部解析。
     */
    data object LoadCurrentEditorContextGraph : ApplicationCommand<Unit>

    /**
     * 请求展开被折叠聚合（overflow）的节点，让其中被合并的子节点重新可见。
     */
    data class RequestExpandOverflowNode(
        val nodeId: String,
    ) : ApplicationCommand<Unit>

    /**
     * 切换分析视图的展示模式（如类图、调用图等不同维度），驱动 UI 与投影器随之更新。
     */
    data class RequestAnalysisDisplayMode(
        val displayMode: AnalysisDisplayMode,
    ) : ApplicationCommand<Unit>

    /**
     * 根据结构化索引请求（按符号、范围、过滤条件等）构建并加载对应的索引图谱。
     */
    data class RequestIndexedGraph(
        val request: IndexedGraphRequest,
    ) : ApplicationCommand<Unit>

    /**
     * 直接加载一份完整的图谱文档到当前会话，[source] 记录来源（导入、同步、调试等），便于追溯。
     */
    data class LoadGraph(
        val graph: GraphDocument,
        val source: String,
    ) : ApplicationCommand<Unit>

    /**
     * 解析一段 Mermaid 文本并转换为内部图谱文档模型，用于外部 Mermaid 资源的导入流程。
     */
    data class ImportMermaid(
        val mermaid: String,
    ) : ApplicationCommand<GraphDocument>

    /**
     * 把当前活动图谱序列化为 Mermaid 文本，常用于复制、导出或与外部工具互通。
     */
    data object ExportMermaid : ApplicationCommand<String>

    /**
     * 计算并展示当前图谱与基准之间的差异视图；结果可能为空表示无可展示的差异。
     */
    data object ShowDiffMode : ApplicationCommand<GraphDifferResult?>

    /**
     * 应用一次图谱编辑请求（增删改节点/边等），由投影器把编辑结果落到文档与 UI。
     *
     * 携带解析结果而非裸 request：解析失败时 issues 非空，由 workflow 统一构造拒绝响应。
     */
    data class ApplyGraphEditRequest(
        val parseResult: GraphEditRequestParseResult,
    ) : ApplicationCommand<Unit>

    /**
     * 通知图谱布局发生变化，把当前各节点的坐标持久化或同步给相关订阅方。
     */
    data class LayoutChanged(
        val positions: Map<String, GraphLayoutPosition>,
    ) : ApplicationCommand<Unit>

    /**
     * 计算并返回图谱与外部代码/资源之间的同步预览项列表，供用户在确认前检阅待变更内容。
     */
    data object RequestSyncPreview : ApplicationCommand<List<SyncPreviewItem>>

    /**
     * 请求跳转到指定节点对应的源码位置，触发 IDE 原生的导航能力。
     */
    data class RequestSourceNavigation(
        val nodeId: String,
    ) : ApplicationCommand<Unit>

    /**
     * 展开某个调用/引用节点的内部细节（例如展开方法体的调用链），用于在图谱中查看更多上下文。
     */
    data class RequestExpandInvocation(
        val nodeId: String,
    ) : ApplicationCommand<Unit>

    /**
     * 移除之前为某次展开生成的临时扩展记录，使图谱视图恢复到展开前的状态。
     */
    data class RequestRemoveInvocationExpansion(
        val expansionId: String,
    ) : ApplicationCommand<Unit>

    /**
     * 打开插件设置面板，让用户调整图谱、索引、助理等模块的偏好。
     */
    data object OpenSettings : ApplicationCommand<Unit>

    /**
     * 向助理提交一个工作任务，携带动作类型、意图、提示词、选中范围、目标位置以及 QA 与解释粒度等参数。
     * 由 [AssistantWorkflowRouter] 据此编排具体工作流。
     */
    data class RequestAssistantTask(
        val actionId: AssistantActionId,
        val intent: AssistantIntent = actionId.toIntent(),
        val sceneId: String? = null,
        val prompt: String,
        val selectedNodeIds: List<String> = emptyList(),
        val selectedDiffItemIds: List<String> = emptyList(),
        val target: AssistantComposerTarget = AssistantComposerTarget.NewTask,
        val mode: QaMode = QaMode.AUTO,
        val explanationGranularity: StepGranularity = StepGranularity.BUSINESS,
    ) : ApplicationCommand<Unit>

    /**
     * 重新发起上一次 QA 请求，常用于在临时网络或上下文异常后快速重试。
     */
    data object RetryLastQaRequest : ApplicationCommand<Unit>

    /**
     * 结束一条风险评估/调查线程，并记录其最终处置状态与备注，用于工作台归档与后续审计。
     */
    data class ResolveInvestigationThread(
        val threadId: String,
        val status: RiskResolutionStatus,
        val note: String = "",
    ) : ApplicationCommand<Unit>

    /**
     * 在 QA 候选变更列表中确认某条变更，使其进入待应用的草稿集合；返回更新后的草稿条目（如适用）。
     */
    data class ConfirmQaCandidateChange(
        val changeId: String,
    ) : ApplicationCommand<DraftWorkbenchEntry?>

    /**
     * 撤销对某条 QA 候选变更的确认，将其移出待应用草稿集合；返回更新后的草稿条目（如适用）。
     */
    data class UnconfirmQaCandidateChange(
        val changeId: String,
    ) : ApplicationCommand<DraftWorkbenchEntry?>

    /**
     * 把草稿补丁预览应用到图谱。可指定部分操作 ID；为 null 时应用全部。返回应用后的图谱文档。
     */
    data class ApplyDraftPatchPreview(
        val operationIds: Set<String>? = null,
    ) : ApplicationCommand<GraphDocument?>

    /**
     * 清空当前草稿补丁预览，丢弃所有未应用的变更项。
     */
    data object ClearDraftPatchPreview : ApplicationCommand<Unit>

    /**
     * 从指定来源（如历史快照、外部输入）恢复草稿补丁预览，返回重建出的补丁内容。
     */
    data class RestoreDraftPatchPreview(
        val source: DraftPatchPreviewSource,
    ) : ApplicationCommand<GraphPatch?>

    /**
     * 回滚最后一次成功应用到图谱上的草稿补丁，返回回滚后的图谱文档。
     */
    data object UndoLastDraftPatchApply : ApplicationCommand<GraphDocument?>

    /**
     * 触发对当前草稿/图谱生成代码草稿（Code Drafts）的流程，为后续逐项应用做准备。
     */
    data object RequestCodeDrafts : ApplicationCommand<Unit>

    /**
     * 应用全部已生成的代码草稿到目标源文件，完成从图谱变更到代码落地的批量提交。
     */
    data object ApplyCodeDrafts : ApplicationCommand<Unit>

    /**
     * 只应用单条代码草稿，便于用户在审阅过程中按需逐项落地。
     */
    data class ApplySingleCodeDraft(
        val draftId: String,
    ) : ApplicationCommand<Unit>

    /**
     * 用 IDE 原生差异视图打开某条代码草稿，方便用户对照原文件审阅后再决定是否应用。
     */
    data class OpenCodeDraftNativeDiff(
        val draftId: String,
    ) : ApplicationCommand<Unit>

    /**
     * 跳转到指定路径对应的草稿/文件位置，常用于工作台中的“定位到代码”操作。
     */
    data class RequestDraftNavigation(
        val targetPath: String,
    ) : ApplicationCommand<Unit>

    /**
     * 针对调试场景预先切换分析展示模式，依据目标环境名准备好对应视图，以便调试期间即时查看。
     */
    data class PrepareDebugRequestedAnalysisDisplayMode(
        val envName: String,
    ) : ApplicationCommand<Unit>

    /**
     * 在调试上下文中按方法签名加载该方法的局部图谱（调用/数据流等），用于聚焦调试目标。
     */
    data class LoadDebugMethodGraphBySignature(
        val signature: String,
    ) : ApplicationCommand<Unit>

    /**
     * 在调试上下文中按模式（如断点、堆栈等）加载对应图谱，支撑调试期间的图谱联动。
     */
    data class LoadDebugGraph(
        val mode: String,
    ) : ApplicationCommand<Unit>
}
